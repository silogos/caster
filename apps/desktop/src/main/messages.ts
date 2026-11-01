import { isPcId, type PcId } from './signaling/envelope'

/**
 * Boundary validation for IPC input from the renderer (extracted from
 * index.ts): everything arriving over ipcMain is untrusted `unknown` — these
 * guards shape it or reject it before it reaches the socket, the camera, or
 * the window. Pure, so each rule is unit-testable without Electron.
 */

/** One packed virtual-camera frame: bytes × dims must agree with the pixel format. */
export function asVcFrame(
  raw: unknown,
  bytesPerPixel: number
): { data: Uint8Array; width: number; height: number } | null {
  if (typeof raw !== 'object' || raw === null) return null
  const { data, width, height } = raw as Record<string, unknown>
  if (!(data instanceof Uint8Array)) return null
  if (!Number.isInteger(width) || !Number.isInteger(height)) return null
  if ((width as number) <= 0 || (height as number) <= 0) return null
  if (data.byteLength !== (width as number) * (height as number) * bytesPerPixel) return null
  return { data, width: width as number, height: height as number }
}

/** An SDP answer relay: a known pc id + a non-empty sdp string. */
export function asSdpMessage(raw: unknown): { pc: PcId; sdp: string } | null {
  if (typeof raw !== 'object' || raw === null) return null
  const { pc, sdp } = raw as Record<string, unknown>
  if (!isPcId(pc) || typeof sdp !== 'string' || sdp.length === 0) return null
  return { pc, sdp }
}

/** A trickled ICE candidate: a known pc id + any candidate value (null = end-of-gathering). */
export function asIceMessage(raw: unknown): { pc: PcId; candidate: unknown } | null {
  if (typeof raw !== 'object' || raw === null) return null
  const { pc, candidate } = raw as Record<string, unknown>
  if (!isPcId(pc) || !('candidate' in (raw as Record<string, unknown>))) return null
  return { pc, candidate }
}

/** A stream size report: positive integer dims. */
export function asStreamSize(raw: unknown): { width: number; height: number } | null {
  if (typeof raw !== 'object' || raw === null) return null
  const { width, height } = raw as Record<string, unknown>
  if (!Number.isInteger(width) || !Number.isInteger(height)) return null
  if ((width as number) <= 0 || (height as number) <= 0) return null
  return { width: width as number, height: height as number }
}
