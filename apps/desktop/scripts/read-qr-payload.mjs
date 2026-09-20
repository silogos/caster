// Manual-verification helper: connects to the running Electron app over CDP,
// reads the pairing QR from the renderer, and prints the decoded payload v1
// (the same JSON the Android app receives when it scans the QR with its camera).
//
// Usage: node scripts/read-qr-payload.mjs [debugPort=9222]
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

function evaluate(expression) {
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
    ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true } }))
  })
}

const qrDataUrl = await evaluate("document.getElementById('qr').src")
if (typeof qrDataUrl !== 'string' || !qrDataUrl.startsWith('data:image/png;base64,')) {
  throw new Error('renderer has no QR yet (is a session active?)')
}
const png = PNG.sync.read(Buffer.from(qrDataUrl.slice('data:image/png;base64,'.length), 'base64'))
const decoded = jsQR(new Uint8ClampedArray(png.data), png.width, png.height)
if (decoded === null) throw new Error('QR did not decode')

const payload = JSON.parse(decoded.data)
console.log(decoded.data)

// Sanity: desktop status text alongside the QR.
const status = await evaluate("document.getElementById('status').textContent")
console.error(`desktop status: ${status}`)

ws.close()
