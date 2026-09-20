import type { DesktopApi } from './index'

declare global {
  interface Window {
    desktopApi: DesktopApi
  }
}

export {}
