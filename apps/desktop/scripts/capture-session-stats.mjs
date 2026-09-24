// Phase15 manual-verification helper: samples the receiver's live getStats
// reports over CDP (~1 Hz) and writes one JSON line per tick — the recorded
// receive-side trail for the long matrices (OBS session, thermal protocol,
// mixer drift). The receiver exposes itself via the `window.__castReceiver`
// debug hook (renderer main.ts); the reports are read-only evidence, never
// a signaling or media path. Phase16: the hook grew a `measureRender` method
// (requestVideoFrameCallback probe) — this capture script stays stats-only.
//
// Usage: node scripts/capture-session-stats.mjs [port=9222] [durationSec=∞]
//   stdout: one JSON line per tick — {"t":"…","pcs":{"media":[…],"mic":[…]}}
//   stderr: start/end and per-minute heartbeats
// Stop with Ctrl-C (or the duration); redirect stdout to a file — the Phase16
// analyzer (scripts/analyze-session-stats.mjs) turns the JSONL into a summary.
const port = process.argv[2] ?? '9222'
const durationSec = process.argv[3] !== undefined ? Number(process.argv[3]) : null
const TICK_MS = 1_000

const targets = await fetch(`http://127.0.0.1:${port}/json`).then((r) => r.json())
const page = targets.find((t) => t.type === 'page')
if (page === undefined) throw new Error('no renderer page target found')

const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  ws.onopen = resolve
  ws.onerror = reject
})

function evaluate(expression, awaitPromise = false) {
  return new Promise((resolve, reject) => {
    const id = Math.floor(Math.random() * 1e9)
    const onMessage = (event) => {
      const message = JSON.parse(event.data)
      if (message.id !== id) return
      ws.removeEventListener('message', onMessage)
      if (message.error !== undefined) reject(new Error(message.error.message))
      else resolve(message.result.result.value)
    }
    ws.addEventListener('message', onMessage)
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise } }))
  })
}

const hasHook = await evaluate("window.__castReceiver !== undefined")
if (!hasHook) {
  ws.close()
  throw new Error("window.__castReceiver is not exposed — the receiver page is not the Phase15 build")
}

const startedAt = Date.now()
console.error(`capturing session stats every ${TICK_MS} ms${durationSec !== null ? ` for ${durationSec} s` : ' (Ctrl-C to stop)'}`)

let lastHeartbeat = 0
for (;;) {
  const elapsedSec = (Date.now() - startedAt) /1_000
  if (durationSec !== null && elapsedSec >= durationSec) break

  const snapshot = await evaluate('window.__castReceiver.receiver.statsSnapshot()', true)
  process.stdout.write(`${JSON.stringify({ t: new Date().toISOString(), pcs: snapshot })}\n`)

  if (elapsedSec - lastHeartbeat >= 60) {
    const pcIds = Object.keys(snapshot ?? {})
    console.error(`[${Math.round(elapsedSec)}s] active pcs: ${pcIds.length > 0 ? pcIds.join(', ') : 'none'}`)
    lastHeartbeat = elapsedSec
  }

  await new Promise((r) => setTimeout(r, TICK_MS))
}

console.error(`done — ${Math.round((Date.now() - startedAt) / 1_000)} s captured`)
ws.close()
