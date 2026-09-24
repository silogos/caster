#!/usr/bin/env node
// Phase16 sender-side analyzer: turns a saved logcat capture of the mobile's
// ~1 Hz sender stats line into the same summary shape the receiver's
// analyze-session-stats.mjs prints — so a thermal-protocol run (sender
// logcat + receiver JSONL) gets analyzed the same way on both ends.
//
// The line (MediaCastSession, Phase15 wording + the Phase16 encode tail):
//   09-24 02:00:00.123 I MediaCastSession: stats: 10016 kbps, 866 encoded,
//     0 dropped, 55.0 fps, 1280x800, rtt 9 ms, encoder c2.qti.avc.encoder,
//     encode8.2 ms/f
// The `encode … ms/f` tail and a `rtt null ms` are both tolerated — the
// pinned prebuilt may not report totalEncodeTime, and RTT is null until the
// pair is nominated.
//
// Usage:
//   adb logcat -v time -s MediaCastSession:* … > run-balanced.log
//   node tools/analyze-sender-stats.mjs run-balanced.log
import { readFile } from 'node:fs/promises'

// Whitespace-flexible on purpose: every field separator is `\s*`, so the
// regex tolerates whatever spacing logcat hands over.
const STATS_LINE =
  /stats:\s+(\d+) kbps,\s*(\d+) encoded,\s*(\d+) dropped,\s*([\d.]+) fps,\s*(\d+)x(\d+),\s*rtt\s*(null|\d+) ms,\s*encoder\s+([^\s,]+)(?:,\s*encode\s*([\d.]+) ms\/f)?\s*$/
const LOGCAT_TIME = /^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})/

const file = process.argv[2]
if (file === undefined) {
  console.error('usage: node tools/analyze-sender-stats.mjs <sender-logcat.txt>')
  process.exit(1)
}

function percentile(sorted, p) {
  if (sorted.length === 0) return null
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.round(p * (sorted.length -1))))
  return sorted[idx]
}

function percentiles(values) {
  const sorted = [...values].sort((a, b) => a - b)
  return { p10: percentile(sorted, 0.1), p50: percentile(sorted, 0.5), p90: percentile(sorted, 0.9) }
}

function round(value, digits) {
  if (value === null || !Number.isFinite(value)) return null
  const factor = 10 ** digits
  return Math.round(value * factor) / factor
}

const text = await readFile(file, 'utf8')
const ticks = []
for (const line of text.split('\n')) {
  const match = STATS_LINE.exec(line)
  if (match === null) continue
  const time = LOGCAT_TIME.exec(line)
  ticks.push({
    sec: time !== null ? Number(time[3]) * 3600 + Number(time[4]) * 60 + Number(time[5]) + Number(time[6]) / 1000 : null,
    kbps: Number(match[1]),
    encoded: Number(match[2]),
    dropped: Number(match[3]),
    fps: Number(match[4]),
    size: `${match[5]}x${match[6]}`,
    rttMs: match[7] === 'null' ? null : Number(match[7]),
    encoder: match[8],
    encodeMsPerFrame: match[9] === undefined ? null : Number(match[9])
  })
}

if (ticks.length === 0) {
  console.error('no stats lines found — capture with: adb logcat -v time -s MediaCastSession:*')
  process.exit(1)
}

const kbps = percentiles(ticks.map((t) => t.kbps))
const fps = percentiles(ticks.map((t) => t.fps))
const rtt = percentiles(ticks.filter((t) => t.rttMs !== null).map((t) => t.rttMs))
const encode = percentiles(ticks.filter((t) => t.encodeMsPerFrame !== null).map((t) => t.encodeMsPerFrame))
const first = ticks[0]
const last = ticks[ticks.length -1]
const decoded = last.encoded - first.encoded
const dropped = last.dropped - first.dropped
const encoders = [...new Set(ticks.map((t) => t.encoder))]
const sizes = [...new Set(ticks.map((t) => t.size))]
const spanSec =
  first.sec !== null && last.sec !== null ? round(last.sec - first.sec, 1) : null

console.log(`sender stats: ${ticks.length} lines, ${spanSec === null ? 'unknown' : `${spanSec}s`} span`)
console.log(`  bitrate      ${round(kbps.p50, 0)} kbps median (p10 ${round(kbps.p10, 0)}, p90 ${round(kbps.p90,0)})`)
console.log(`  fps          ${round(fps.p50, 1)} median (p10 ${round(fps.p10,1)}, p90 ${round(fps.p90,1)})`)
console.log(`  frames       ${decoded} encoded, ${dropped} dropped (${round((dropped / (decoded + dropped)) * 100, 2)}%)`)
console.log(`  rtt          ${round(rtt.p50, 0)} ms median (p10 ${round(rtt.p10, 0)}, p90 ${round(rtt.p90, 0)})`)
console.log(
  encode.p50 === null
    ? '  encode       — ms/frame (prebuilt reports no totalEncodeTime)'
    : `  encode       ${round(encode.p50, 1)} ms/frame median (p10 ${round(encode.p10,1)}, p90 ${round(encode.p90,1)})`
)
console.log(`  encoder      ${encoders.join(', ')}`)
console.log(`  frame size   ${sizes.join(', ')}`)
