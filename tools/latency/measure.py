"""Glass-to-glass latency from the latency test pattern's two logs.

The phone's debug LatencyTestActivity logs each black/white flip (and the
click on each white flip) with its wall-clock time; the receiver run with
ZFC_LATENCY_PROBE=1 logs when each flip is decoded and each click arrives.
This script aligns the two clocks over adb and pairs the marks.

Clock alignment: the phone prints its clock over `adb shell` every 50 ms;
the smallest (Mac arrival − phone time) is the offset plus the fastest
one-way trip, so phone marks land a few ms LATE on the Mac clock and the
latencies below read a few ms LOW (the "sync floor" printed).

Usage (docs/development/latency-measurement.md):
  python3 -I tools/latency/measure.py --logcat PHONE.log --receiver RX.log
"""
import argparse
import re
import statistics
import subprocess
import time

DEFAULT_PHONE_HZ = 60  # the flip shows one phone vsync after its frame time
MAC_VSYNC_MS = 1000 / 120  # a paced frame shows within one display refresh
MATCH_WINDOW_MS = 900  # a receiver mark must follow its phone mark within this


def clock_offset(seconds):
    """Mac wall ms − phone wall ms (+ the fastest one-way trip)."""
    proc = subprocess.Popen(
        ["adb", "shell", "while true; do date +%s%N; sleep 0.05; done"],
        stdout=subprocess.PIPE, text=True, bufsize=1,
    )
    samples = []
    end = time.time() + seconds
    try:
        for line in proc.stdout:
            arrived = time.time() * 1000
            line = line.strip()
            if line.isdigit():
                samples.append(arrived - int(line) / 1e6)
            if time.time() > end:
                break
    finally:
        proc.kill()
    if not samples:
        raise SystemExit("no clock samples from adb — is the phone connected?")
    best = min(samples)
    return best, statistics.median(samples) - best, len(samples)


def phone_marks(path):
    flips, beeps = [], []
    for line in open(path, errors="replace"):
        m = re.search(r"flip (white|black) frame_ms=([\d.]+)(?: present_ms=([\d.]+))?(?: refresh_hz=(\d+))?", line)
        if m:
            hz = float(m.group(4)) if m.group(4) and float(m.group(4)) > 0 else DEFAULT_PHONE_HZ
            frame_ms = float(m.group(2))
            # On the phone's screen: Android's expected presentation when the
            # pattern logged it (Android 13+), else one vsync after the frame.
            shown = float(m.group(3)) if m.group(3) else frame_ms + 1000 / hz
            flips.append((m.group(1), frame_ms, hz, shown))
        m = re.search(r"click written_ms=([\d.]+)", line)
        if m:
            beeps.append(float(m.group(1)))
    return flips, beeps


def receiver_marks(path):
    video, audio = [], []
    for line in open(path, errors="replace"):
        m = re.search(r"video (white|black) decoded_ms=([\d.]+) hold_ms=([\d.]+)", line)
        if m:
            video.append((m.group(1), float(m.group(2)), float(m.group(3))))
        m = re.search(r"audio beep arrived_ms=([\d.]+) ring_ms=(\d+)", line)
        if m:
            audio.append((float(m.group(1)), float(m.group(2))))
    return video, audio


def first_after(t, items, key):
    for item in items:
        if 0 <= key(item) - t <= MATCH_WINDOW_MS:
            return item
    return None


def summary(name, values):
    if not values:
        print(f"{name}: no matched marks")
        return
    q = sorted(values)
    print(f"{name}: n={len(q)}  median {statistics.median(q):.0f} ms  "
          f"min {q[0]:.0f}  p90 {q[int(len(q) * 0.9) - 1 if len(q) > 1 else 0]:.0f}  max {q[-1]:.0f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--logcat", required=True)
    ap.add_argument("--receiver", required=True)
    ap.add_argument("--sync-seconds", type=float, default=8)
    ap.add_argument("--phone-screen-lag", type=float, default=None,
                    help="SurfaceFlinger's actual − desired present for the pattern layer (ms): "
                         "how much later the phone's own screen showed frames than Android expected")
    args = ap.parse_args()

    offset, jitter, n = clock_offset(args.sync_seconds)
    print(f"clock offset {offset:.1f} ms from {n} samples (median trip {jitter:.1f} ms above the floor)")
    flips, beeps = phone_marks(args.logcat)
    video, audio = receiver_marks(args.receiver)
    print(f"phone: {len(flips)} flips, {len(beeps)} beeps | receiver: {len(video)} video marks, {len(audio)} beeps")

    to_decode, glass = [], []
    rates = sorted({hz for _, _, hz, _ in flips})
    print(f"phone display refresh during the run: {', '.join(f'{r:.0f} Hz' for r in rates)}")
    ahead = [shown - frame_ms for _, frame_ms, _, shown in flips]
    if ahead:
        print(f"phone pipeline (frame drawn → on the phone's screen): median {statistics.median(ahead):.1f} ms")
    for colour, frame_ms, hz, shown in flips:
        shown_on_phone = shown + offset
        hit = first_after(shown_on_phone, [v for v in video if v[0] == colour], key=lambda v: v[1])
        if hit:
            to_decode.append(hit[1] - shown_on_phone)
            glass.append(hit[1] - shown_on_phone + hit[2] + MAC_VSYNC_MS)
    summary("video  phone screen → Mac decoded       ", to_decode)
    summary("video  phone screen → Mac screen (est.) ", glass)
    if args.phone_screen_lag is not None:
        summary(f"video  vs the phone's ACTUAL screen (−{args.phone_screen_lag:.0f} ms)",
                [g - args.phone_screen_lag for g in glass])

    ear = []
    for beep_ms in beeps:
        hit = first_after(beep_ms + offset, audio, key=lambda a: a[0])
        if hit:
            ear.append(hit[0] + hit[1] - (beep_ms + offset))
    summary("audio  click into phone track → Mac playout", ear)

    track = []
    for line in open(args.logcat, errors="replace"):
        m = re.search(r"click written_ms=[\d.]+ track_ms=(-?[\d.]+)", line)
        if m:
            track.append(float(m.group(1)))
    summary("audio    of which: inside the click's own track", track)

    captured = []
    for line in open(args.logcat, errors="replace"):
        m = re.search(r"click captured_ms=(\d+)", line)
        if m:
            captured.append(float(m.group(1)))
    split_phone, split_wire = [], []
    for beep_ms in beeps:
        cap = next((c for c in captured if 0 <= c - beep_ms <= MATCH_WINDOW_MS), None)
        hit = first_after(beep_ms + offset, audio, key=lambda a: a[0])
        if cap is not None:
            split_phone.append(cap - beep_ms)  # same clock: no offset
            if hit:
                split_wire.append(hit[0] - (cap + offset))
    summary("audio    of which: phone track → captured ", split_phone)
    summary("audio    of which: captured → Mac arrival ", split_wire)
    print("notes: Mac screen adds the pacer hold + one 120 Hz refresh; audio excludes the Mac's "
          "output device buffer; all figures read low by the sync floor (a few ms).")


if __name__ == "__main__":
    main()
