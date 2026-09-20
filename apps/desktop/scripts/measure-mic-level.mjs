// Manual-verification helper (Phase8): connects to the running Electron app
// over CDP and measures the peak level of the mic stream reaching the
// receiver — the end-to-end audibility check that doesn't need ears: the
// phone's mic → Opus → LAN → decoder → mic-audio element → Web Audio graph.
import { PNG } from 'pngjs'
import jsQR from 'jsqr'

const port = process.argv[2] ?? '9222'

const targets = await fetch(`http://127.0.0.1:${port}/json`).then((r) => r.json())
const page = targets.find((t) => t.type === 'page')
if (page === undefined) throw new Error('no renderer page target found')

const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  ws.onopen = resolve
  ws.onerror = reject
})

function evaluate(expression, awaitPromise =false) {
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

const hasStream = await evaluate("document.getElementById('mic-audio').srcObject !== null")
console.log(`mic-audio element has a stream: ${hasStream}`)
if (!hasStream) {
  ws.close()
  throw new Error('no mic stream at the receiver')
}

// Peak absolute sample over ~2 s at the receiver's output graph.
const peak = await evaluate(`(async () => {
  const el = document.getElementById('mic-audio')
  const ctx = new AudioContext()
  const src = ctx.createMediaStreamSource(el.srcObject)
  const an = ctx.createAnalyser()
  an.fftSize = 2048
  src.connect(an)
  let peak = 0
  const buf = new Float32Array(an.fftSize)
  for (let i = 0; i < 20; i++) {
    an.getFloatTimeDomainData(buf)
    for (const v of buf) peak = Math.max(peak, Math.abs(v))
    await new Promise((r) => setTimeout(r, 100))
  }
  ctx.close()
  return peak
})()`, true)
console.log(`mic stream peak level over ~2 s at the receiver: ${peak}`)

// Game-audio (media) sanity alongside: same measurement on the video element.
const mediaPeak = await evaluate(`(async () => {
  const el = document.getElementById('video')
  if (el.srcObject === null) return 'no stream'
  const ctx = new AudioContext()
  const src = ctx.createMediaStreamSource(el.srcObject)
  const an = ctx.createAnalyser()
  an.fftSize = 2048
  src.connect(an)
  let peak = 0
  const buf = new Float32Array(an.fftSize)
  for (let i = 0; i < 10; i++) {
    an.getFloatTimeDomainData(buf)
    for (const v of buf) peak = Math.max(peak, Math.abs(v))
    await new Promise((r) => setTimeout(r, 100))
  }
  ctx.close()
  return peak
})()`, true)
console.log(`media (video element) peak level over ~1 s: ${mediaPeak}`)

ws.close()
