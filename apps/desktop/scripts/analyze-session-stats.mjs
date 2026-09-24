// Phase16 before/after workbench: summarize one capture-session-stats.mjs
// JSONL run, or compare two runs side by side. The summaries come from
// lib/sessionStatsSummary.mjs (pure, vitest-covered); this file is only the
// printer. Steady-state metrics exclude the session-start ramp (the lib's
// START_RAMP_EXCLUDE_SEC — every cast ramps its capture format in the first
// ~12 s; measuring it would grade start-up, not the run).
//
// Usage:
//   node scripts/analyze-session-stats.mjs run.jsonl
//   node scripts/analyze-session-stats.mjs before.jsonl after.jsonl
import { readFile } from 'node:fs/promises'
import { summarizeRun } from './lib/sessionStatsSummary.mjs'

const files = process.argv.slice(2)
if (files.length < 1 || files.length > 2) {
  console.error('usage: node scripts/analyze-session-stats.mjs <run.jsonl> [<run-after.jsonl>]')
  process.exit(1)
}

const runs = []
for (const file of files) {
  const text = await readFile(file, 'utf8')
  runs.push({ file, summary: summarizeRun(text) })
}

for (const { file, summary } of runs) {
  console.log(`\n=== ${file} — ${summary.ticks} ticks, ${summary.durationSec} s ===`)
  printRun(summary)
}

if (runs.length === 2) {
  console.log('\n=== before → after ===')
  printComparison(runs[0].summary, runs[1].summary)
}

function fmt(value, unit = '') {
  if (value === null || value === undefined) return '—'
  return `${Math.round(value).toLocaleString('en-US')}${unit}`
}

function fmtPct(p) {
  return p === null || p === undefined ? '—' : `${p}%`
}

function printRun(summary) {
  for (const pc of [summary.media, summary.mic]) {
    if (pc.video === null && pc.audio === null) {
      console.log(`  [${pc.pcId}] no inbound tracks in the analyzed window`)
      continue
    }
    console.log(`  [${pc.pcId}] ${pc.analyzedTicks} analyzed ticks`)
    if (pc.video !== null) {
      const v = pc.video
      console.log(`    video      ${v.frameSize ?? '?'}  ${fmt(v.bitrateBps.p50 / 1000)} kbps median (p10 ${fmt(v.bitrateBps.p10 / 1000)}, p90 ${fmt(v.bitrateBps.p90 / 1000)})`)
      if (v.fps !== null) {
        console.log(`    fps        ${v.fps.p50} median (p10 ${v.fps.p10}, p90 ${v.fps.p90})`)
      } else {
        console.log(`    decodeRate ${v.decodeRateAvg} fps (no instantaneous member)`)
      }
      console.log(`    frames     ${v.decodedFrames} decoded, ${v.droppedFrames} dropped (${fmtPct(v.dropRatePct)}), ${v.keyFramesDecoded} keyframes`)
      console.log(`    net        rtt ${fmt(v.rttMs)} ms median, loss ${fmtPct(v.lossPct)} (${v.packetsLost} packets), ${v.nackCount} nacks, ${v.pliCount} plis`)
      console.log(`    render     jitter buffer ${fmt(v.jitterBufferMs)} ms median/frame, ${v.freezeCount} freezes (${v.freezesSec} s)`)
    }
    if (pc.audio !== null) {
      const a = pc.audio
      console.log(`    audio      ${fmt(a.bitrateBps / 1000)} kbps, loss ${fmtPct(a.lossPct)} (${a.packetsLost} packets), concealment ${fmtPct(a.concealmentPct)}`)
    }
  }
}

/** Metric rows for the before/after table: [path, label, format]. */
const COMPARISON_ROWS = [
  ['media.video.bitrateBps.p50', 'video bitrate p50 (kbps)', (v) => fmt(v / 1000)],
  ['media.video.bitrateBps.p10', 'video bitrate p10 (kbps)', (v) => fmt(v / 1000)],
  ['media.video.fps.p50', 'fps p50', (v) => (v === null ? '—' : String(v))],
  ['media.video.decodeRateAvg', 'decode rate (fps)', (v) => String(v)],
  ['media.video.dropRatePct', 'drop rate (%)', (v) => fmtPct(v)],
  ['media.video.lossPct', 'packet loss (%)', (v) => fmtPct(v)],
  ['media.video.rttMs', 'rtt p50 (ms)', (v) => fmt(v)],
  ['media.video.jitterBufferMs', 'jitter buffer (ms/frame)', (v) => fmt(v)],
  ['media.video.freezeCount', 'freezes', (v) => fmt(v, '')],
  ['media.audio.concealmentPct', 'game-audio concealment (%)', (v) => fmtPct(v)],
  ['mic.audio.concealmentPct', 'mic concealment (%)', (v) => fmtPct(v)],
  ['durationSec', 'duration (s)', (v) => String(v)]
]

function readPath(object, path) {
  return path.split('.').reduce((acc, key) => (acc === null || acc === undefined ? undefined : acc[key]), object)
}

function printComparison(before, after) {
  const width = Math.max(...COMPARISON_ROWS.map((row) => row[1].length))
  for (const [path, label, format] of COMPARISON_ROWS) {
    const b = readPath(before, path)
    const a = readPath(after, path)
    const bText = b === null || b === undefined ? '—' : format(b)
    const aText = a === null || a === undefined ? '—' : format(a)
    console.log(`  ${label.padEnd(width)}  ${bText.padStart(12)}  →  ${aText.padStart(12)}`)
  }
}
