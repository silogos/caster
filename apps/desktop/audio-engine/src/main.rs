//! caster-audio-engine — Feature 2026-11e, Fase A of the native receiver
//! roadmap (ADR-014). The Electron receiver hands raw PCM16LE interleaved
//! frames to this process over stdin; this side owns the real-time output:
//! a lock-free ring (ring.rs) feeding a CPAL stream straight to the default
//! output device — bypassing Chromium's ~28 ms output buffer entirely.
//!
//! Contract:
//! - stdin, first line:  `zfaudio1 rate=48000 ch=2 fmt=pcm16le`
//!   (anything else → stderr + exit 1; only this fixed format is supported —
//!   the wire is the mobile's pinned capture format)
//! - stdin after: interleaved PCM16LE sample bytes, forever, no framing
//! - stdout: `depth_ms=<n> underruns=<n>` once per second (numbers only —
//!   AGENTS.md logging rules; never sample content)
//! - stderr: errors
//! - exit 0 when stdin closes (the cast ended), non-zero on a hard error

mod ring;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use ring::{AudioRing, RING_CAPACITY_FRAMES};
use std::io::{BufRead, Read, Write};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

const SAMPLE_RATE_HZ: u32 = 48_000;
const CHANNELS: usize = 2;
const HEADER: &str = "zfaudio1 rate=48000 ch=2 fmt=pcm16le";

fn main() {
    // 1. The one header line — the format contract.
    let stdin = std::io::stdin();
    let mut header = String::new();
    if stdin.lock().read_line(&mut header).is_err() {
        eprintln!("stdin read failed");
        std::process::exit(1);
    }
    if header.trim() != HEADER {
        eprintln!("unexpected header: expected \"{HEADER}\", got \"{}\"", header.trim());
        std::process::exit(1);
    }

    // 2. The ring + counters shared with the real-time callback.
    let ring = Arc::new(AudioRing::new(RING_CAPACITY_FRAMES, CHANNELS));
    let underruns = Arc::new(AtomicUsize::new(0));

    // 3. The stdin reader thread — the writer side. Never blocks the
    //    callback: a full ring drops the newest frames (ring.rs).
    let reader_ring = Arc::clone(&ring);
    let reader = thread::spawn(move || {
        let mut stdin = stdin.lock();
        let mut buf = vec![0i16; 4_800 * CHANNELS]; // 100 ms worth per read
        let mut bytes = vec![0u8; buf.len() * 2];
        loop {
            match stdin.read(&mut bytes) {
                Ok(0) => break, // stdin closed — the cast ended
                Ok(n) => {
                    if n < 2 {
                        break;
                    }
                    let usable = n - (n % 2);
                    for i in 0..usable / 2 {
                        buf[i] = i16::from_le_bytes([bytes[i * 2], bytes[i * 2 + 1]]);
                    }
                    reader_ring.push(&buf[..usable / 2]);
                }
                Err(_) => break,
            }
        }
    });

    // 4. The CPAL output stream — the real-time side.
    let host = cpal::default_host();
    let device = match host.default_output_device() {
        Some(device) => device,
        None => {
            eprintln!("no default output device");
            std::process::exit(2);
        }
    };
    let supported = match device.default_output_config() {
        Ok(config) => config,
        Err(error) => {
            eprintln!("default output config failed: {error}");
            std::process::exit(2);
        }
    };
    eprintln!(
        "output device: {} ({} Hz, {} ch)",
        device.name().unwrap_or_default(),
        supported.sample_rate().0,
        supported.channels()
    );
    let stream_config = cpal::StreamConfig {
        channels: supported.channels(),
        sample_rate: supported.sample_rate(),
        buffer_size: cpal::BufferSize::Default,
    };
    let callback_ring = Arc::clone(&ring);
    let callback_underruns = Arc::clone(&underruns);
    let stream = match device.build_output_stream(
        &stream_config,
        move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
            let expected_frames = data.len() / stream_config.channels as usize;
            let drained = callback_ring.drain_f32(data);
            if drained < expected_frames {
                // Silence-fill the underrun tail — never stall the device.
                for sample in &mut data[drained * stream_config.channels as usize..] {
                    *sample = 0.0;
                }
                callback_underruns.fetch_add(1, Ordering::Relaxed);
            }
        },
        move |error| eprintln!("audio stream error: {error}"),
        None,
    ) {
        Ok(stream) => stream,
        Err(error) => {
            eprintln!("output stream build failed: {error}");
            std::process::exit(2);
        }
    };
    if let Err(error) = stream.play() {
        eprintln!("output stream play failed: {error}");
        std::process::exit(2);
    }

    // 5. The depth reporter — stdout, once per second.
    let report_ring = Arc::clone(&ring);
    let report_underruns = Arc::clone(&underruns);
    let reporter = thread::spawn(move || {
        let stdout = std::io::stdout();
        let mut out = stdout.lock();
        let mut last = Instant::now();
        loop {
            thread::sleep(Duration::from_millis(200));
            if last.elapsed() >= Duration::from_secs(1) {
                last = Instant::now();
                let line = format!(
                    "depth_ms={} underruns={}\n",
                    report_ring.depth_frames() * 1000 / SAMPLE_RATE_HZ as usize,
                    report_underruns.load(Ordering::Relaxed)
                );
                if out.write_all(line.as_bytes()).is_err() {
                    break; // parent closed stdout — exit the reporter
                }
                let _ = out.flush();
            }
        }
    });

    let _ = reader.join();
    let _ = reporter.join();
    // stdin closed (cast ended) — exit cleanly so the parent's wait() returns.
    std::process::exit(0);
}
