//! Releases frames to an output at their due time (ADR-017's pacer), on
//! the output's own thread: the Syphon server (ADR-021) and the OBS
//! Virtual Camera (ADR-039) both publish with the window's even cadence,
//! not the network's bursts.

use std::sync::mpsc::{sync_channel, Receiver, RecvTimeoutError, SyncSender};
use std::time::{Duration, Instant};

/// No frame waits longer than this for its slot, whatever its due time says.
const MAX_WAIT: Duration = Duration::from_millis(100);

/// Runs `sink` on its own thread for each submitted item, not before the
/// item's due time (seconds on `now`'s clock). `tick` runs on the same
/// thread about every `tick_every`, frames or not.
pub struct Scheduler<T> {
    queue: SyncSender<(T, f64)>,
}

impl<T: Send + 'static> Scheduler<T> {
    pub fn spawn(
        thread_name: &str,
        capacity: usize,
        now: fn() -> f64,
        sink: impl FnMut(T) + Send + 'static,
        tick: impl FnMut() + Send + 'static,
        tick_every: Duration,
    ) -> Self {
        let (queue, items) = sync_channel(capacity);
        std::thread::Builder::new()
            .name(thread_name.into())
            .spawn(move || run(items, now, sink, tick, tick_every))
            .expect("spawn an output thread");
        Self { queue }
    }

    /// False when the queue is full: the item is dropped, the sink is behind.
    pub fn submit(&self, item: T, due: f64) -> bool {
        self.queue.try_send((item, due)).is_ok()
    }
}

/// Ends when the [`Scheduler`] is dropped.
fn run<T>(items: Receiver<(T, f64)>, now: fn() -> f64, mut sink: impl FnMut(T), mut tick: impl FnMut(), tick_every: Duration) {
    let mut last_tick: Option<Instant> = None;
    loop {
        if last_tick.is_none_or(|t| t.elapsed() >= tick_every) {
            last_tick = Some(Instant::now());
            tick();
        }
        match items.recv_timeout(tick_every) {
            Ok((item, due)) => {
                let wait = due - now();
                if wait > 0.0 {
                    std::thread::sleep(Duration::from_secs_f64(wait).min(MAX_WAIT));
                }
                sink(item);
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => return,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc::channel;

    fn clock() -> f64 {
        static START: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
        START.get_or_init(Instant::now).elapsed().as_secs_f64()
    }

    #[test]
    fn frames_are_published_in_order_and_not_before_they_are_due() {
        let (seen, results) = channel();
        let scheduler = Scheduler::spawn("test-out", 8, clock, move |item: u32| seen.send((item, clock())).unwrap(), || {}, Duration::from_millis(50));
        let base = clock();
        let dues = [base + 0.030, base + 0.060, base + 0.090];
        for (i, due) in dues.iter().enumerate() {
            assert!(scheduler.submit(i as u32, *due));
        }
        for (i, due) in dues.iter().enumerate() {
            let (item, at) = results.recv_timeout(Duration::from_secs(2)).expect("published");
            assert_eq!(item, i as u32, "in the order they were handed over");
            assert!(at >= *due, "frame {i} published {:.1} ms early", (due - at) * 1000.0);
        }
    }

    #[test]
    fn a_frame_already_due_is_published_at_once() {
        let (seen, results) = channel();
        let scheduler = Scheduler::spawn("test-out", 8, clock, move |item: u32| seen.send(item).unwrap(), || {}, Duration::from_millis(50));
        let started = Instant::now();
        assert!(scheduler.submit(7, clock() - 1.0));
        assert_eq!(results.recv_timeout(Duration::from_secs(2)), Ok(7));
        assert!(started.elapsed() < Duration::from_millis(50), "no wait for a late frame");
    }

    #[test]
    fn a_full_queue_drops_the_newest_frame() {
        let (release, gate) = channel::<()>();
        let (seen, results) = channel();
        // The sink blocks on the first frame, so the queue fills behind it.
        let scheduler = Scheduler::spawn("test-out", 
            2,
            clock,
            move |item: u32| {
                let _ = gate.recv();
                seen.send(item).unwrap();
            },
            || {},
            Duration::from_millis(50),
        );
        assert!(scheduler.submit(0, 0.0));
        std::thread::sleep(Duration::from_millis(50)); // the sink now holds frame 0
        assert!(scheduler.submit(1, 0.0));
        assert!(scheduler.submit(2, 0.0));
        assert!(!scheduler.submit(3, 0.0), "the queue holds two; the third is dropped");
        for _ in 0..3 {
            release.send(()).unwrap();
        }
        let got: Vec<u32> = (0..3).map(|_| results.recv_timeout(Duration::from_secs(2)).unwrap()).collect();
        assert_eq!(got, [0, 1, 2]);
    }

    #[test]
    fn the_tick_runs_without_any_frames() {
        let (ticked, ticks) = channel();
        let _scheduler = Scheduler::spawn("test-out", 2, clock, |_: u32| {}, move || ticked.send(()).unwrap(), Duration::from_millis(20));
        for _ in 0..3 {
            ticks.recv_timeout(Duration::from_secs(2)).expect("a tick");
        }
    }
}
