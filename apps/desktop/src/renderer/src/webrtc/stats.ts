// getStats logging (webrtc.md: each side polls ~1 Hz from Phase 5 onward):
// extract the receive-side video sample that feeds the log line — inbound-rtp
// (kind=video) plus the nominated candidate-pair's RTT. Pure so it is
// unit-testable; nothing here touches RTCPeerConnection.

export interface VideoReceiveSample {
  bytesReceived: number
  packetsLost: number
  jitterMs: number
  framesDecoded: number
  framesDropped: number
  /** Round-trip of the nominated candidate pair; null until known. */
  rttMs: number | null
  /** e.g. 'ffmpeg' vs hardware decoder name — input for later phases. */
  decoderImplementation: string | null
}

function asNumber(value: unknown): number {
  return typeof value === 'number' ? value : 0
}

function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

/**
 * One sample from an RTCStatsReport (already spread to plain records).
 * Missing entries yield zeros/nulls — a sample is logged even while the
 * stream is ramping up, so gaps must not throw.
 */
export function sampleVideoReceive(stats: Array<Record<string, unknown>>): VideoReceiveSample {
  const sample: VideoReceiveSample = {
    bytesReceived: 0,
    packetsLost: 0,
    jitterMs: 0,
    framesDecoded: 0,
    framesDropped: 0,
    rttMs: null,
    decoderImplementation: null
  }
  for (const entry of stats) {
    if (entry['type'] === 'inbound-rtp' && entry['kind'] === 'video') {
      sample.bytesReceived = asNumber(entry['bytesReceived'])
      sample.packetsLost = asNumber(entry['packetsLost'])
      sample.jitterMs = asNumber(entry['jitter']) * 1000
      sample.framesDecoded = asNumber(entry['framesDecoded'])
      sample.framesDropped = asNumber(entry['framesDropped'])
      sample.decoderImplementation = asString(entry['decoderImplementation'])
    } else if (entry['type'] === 'candidate-pair' && entry['nominated'] === true && entry['state'] === 'succeeded') {
      const rtt = entry['currentRoundTripTime']
      sample.rttMs = typeof rtt === 'number' ? rtt * 1000 : null
    }
  }
  return sample
}

/** Bitrate from two consecutive samples over `intervalMs` — receive-side, one direction. */
export function bitrateBps(bytesNow: number, bytesBefore: number, intervalMs: number): number {
  if (intervalMs <= 0) return 0
  return Math.max(0, (bytesNow - bytesBefore) * 8000 / intervalMs)
}
