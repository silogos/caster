import { resolve } from 'node:path'
import { defineConfig } from 'electron-vite'

export default defineConfig({
  main: {
    build: {
      rollupOptions: {
        input: { index: resolve(__dirname, 'src/main/index.ts') }
      }
    }
  },
  preload: {
    build: {
      rollupOptions: {
        input: { index: resolve(__dirname, 'src/preload/index.ts') }
      }
    }
  },
  renderer: {
    root: 'src/renderer',
    build: {
      // Never inline assets: the game-audio worklet (ADR-012) must be a real
      // file next to the bundle — worklets loaded from inlined data:/blob:
      // URLs fail in file:// renderers (found live: AbortError on addModule).
      assetsInlineLimit: 0,
      rollupOptions: {
        // Two pages: the receiver and the off-cast session-info window
        // (Phase14) — window capture targets one window, so the info line
        // lives outside the receiver page.
        input: {
          index: resolve(__dirname, 'src/renderer/index.html'),
          overlay: resolve(__dirname, 'src/renderer/overlay.html')
        }
      }
    }
  }
})
