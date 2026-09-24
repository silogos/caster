// getStats logging (webrtc.md: each side polls ~1 Hz from Phase 5 onward):
// extract the receive-side samples that feed the log line — inbound-rtp video
// plus the nominated candidate-pair's RTT (Phase5), and the Phase16 additions:
// the decode/render fields (reported fps, frames received, freezes, per-frame
// jitter-buffer residence, keyframes, RTCP repair requests, frame size) and the
// audio inbound sample (game audio rides the media pc, the mic its own —
// reception was invisible at the receiver until now). Pure so it is
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
  /** Reported inbound fps — the decode/render liveness signal; null until known. */
  framesPerSecond: number | null
  /** Cumulative frames that reached the receiver; null while not reported. */
  framesReceived: number | null
  /** Cumulative render freezes detected by the receiver; null while not reported. */
  freezeCount: number | null
  /** Cumulative seconds spent frozen; null while not reported. */
  totalFreezesDuration: number | null
  /** Cumulative seconds frames spent in the jitter buffer before decode; null while not reported. */
  jitterBufferDelay: number | null
  /** Frames emitted from the jitter buffer — the divisor of the delay; null while not reported. */
  jitterBufferEmittedCount: number | null
  /** Cumulative decoded keyframes — the recovery-cadence signal; null while not reported. */
  keyFramesDecoded: number | null
  /** Cumulative NACKs sent (receiver asking for retransmits); null while not reported. */
  nackCount: number | null
  /** Cumulative PLIs sent (receiver asking for a keyframe); null while not reported. */
  pliCount: number | null
  /** Frame size last reported on the inbound track; null until known. */
  frameWidth: number | null
  /** Frame size last reported on the inbound track; null until known. */
  frameHeight: number | null
}

export interface AudioReceiveSample {
  bytesReceived: number
  packetsLost: number
  jitterMs: number
  /** Cumulative samples synthesized by packet-loss concealment; null while not reported. */
  concealedSamples: number | null
  /** Cumulative decoded samples — the denominator of the concealment rate; null while not reported. */
  totalSamplesReceived: number | null
}

function asNumber(value: unknown): number {
  return typeof value === 'number' ? value : 0
}

function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

/** Present-or-null: Phase16 fields distinguish "not reported" from a real zero. */
function asOptionalNumber(value: unknown): number | null {
  return typeof value === 'number' ? value : null
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
    decoderImplementation: null,
    framesPerSecond: null,
    framesReceived: null,
    freezeCount: null,
    totalFreezesDuration: null,
    jitterBufferDelay: null,
    jitterBufferEmittedCount: null,
    keyFramesDecoded: null,
    nackCount: null,
    pliCount: null,
    frameWidth: null,
    frameHeight: null
  }
  for (const entry of stats) {
    if (entry['type'] === 'inbound-rtp' && entry['kind'] === 'video') {
      sample.bytesReceived = asNumber(entry['bytesReceived'])
      sample.packetsLost = asNumber(entry['packetsLost'])
      sample.jitterMs = asNumber(entry['jitter']) * 1000
      sample.framesDecoded = asNumber(entry['framesDecoded'])
      sample.framesDropped = asNumber(entry['framesDropped'])
      sample.decoderImplementation = asString(entry['decoderImplementation'])
      sample.framesPerSecond = asOptionalNumber(entry['framesPerSecond'])
      sample.framesReceived = asOptionalNumber(entry['framesReceived'])
      sample.freezeCount = asOptionalNumber(entry['freezeCount'])
      sample.totalFreezesDuration = asOptionalNumber(entry['totalFreezesDuration'])
      sample.jitterBufferDelay = asOptionalNumber(entry['jitterBufferDelay'])
      sample.jitterBufferEmittedCount = asOptionalNumber(entry['jitterBufferEmittedCount'])
      sample.keyFramesDecoded = asOptionalNumber(entry['keyFramesDecoded'])
      sample.nackCount = asOptionalNumber(entry['nackCount'])
      sample.pliCount = asOptionalNumber(entry['pliCount'])
      sample.frameWidth = asOptionalNumber(entry['frameWidth'])
      sample.frameHeight = asOptionalNumber(entry['frameHeight'])
    } else if (entry['type'] === 'candidate-pair' && entry['nominated'] === true && entry['state'] === 'succeeded') {
      const rtt = entry['currentRoundTripTime']
      sample.rttMs = typeof rtt === 'number' ? rtt * 1000 : null
    }
  }
  return sample
}

/** One audio sample from an RTCStatsReport (game audio on the media pc, mic on its own). */
export function sampleAudioReceive(stats: Array<Record<string, unknown>>): AudioReceiveSample | null {
  let audio: Record<string, unknown> | null = null
  for (const entry of stats) {
    if (entry['type'] === 'inbound-rtp' && entry['kind'] === 'audio') {
      audio = entry
    }
  }
  if (audio === null) return null
  return {
    bytesReceived: asNumber(audio['bytesReceived']),
    packetsLost: asNumber(audio['packetsLost']),
    jitterMs: asNumber(audio['jitter']) * 1000,
    concealedSamples: asOptionalNumber(audio['concealedSamples']),
    totalSamplesReceived: asOptionalNumber(audio['totalSamplesReceived'])
  }
}

/** Bitrate from two consecutive samples over `intervalMs` — receive-side, one direction. */
export function bitrateBps(bytesNow: number, bytesBefore: number, intervalMs: number): number {
  if (intervalMs <= 0) return 0
  return Math.max(0, (bytesNow - bytesBefore) * 8000 / intervalMs)
}

/**
 * Mean jitter-buffer residence per frame (ms) between two cumulative samples —
 * the receiver-side latency contribution that decode/render timing rides on.
 * Without a `before` (first tick) it is the total-so-far mean; a counter reset
 * (pc rebuild) yields null rather than a bogus negative window.
 */
export function jitterBufferMs(sample: VideoReceiveSample, before: VideoReceiveSample | null): number | null {
  const { jitterBufferDelay: delay, jitterBufferEmittedCount: count } = sample
  if (delay === null || count === null) return null
  if (before !== null && before.jitterBufferDelay !== null && before.jitterBufferEmittedCount !== null) {
    const deltaCount = count - before.jitterBufferEmittedCount
    const deltaDelay = delay - before.jitterBufferDelay
    if (deltaCount > 0 && deltaDelay >= 0) return (deltaDelay / deltaCount) * 1000
    if (deltaDelay < 0 || before.jitterBufferEmittedCount > count) return null
  }
  return count > 0 ? (delay / count) * 1000 : null
}

/**
 * Fraction of decoded samples synthesized by packet-loss concealment over the
 * interval between two cumulative audio samples (0..1) — the audio-quality
 * signal (a struggling link conceals). Without a `before` it is the
 * total-so-far fraction; missing counters yield null.
 */
export function concealmentRate(sample: AudioReceiveSample, before: AudioReceiveSample | null): number | null {
  const { concealedSamples: concealed, totalSamplesReceived: total } = sample
  if (concealed === null || total === null) return null
  if (before !== null && before.concealedSamples !== null && before.totalSamplesReceived !== null) {
    const deltaConcealed = concealed - before.concealedSamples
    const deltaTotal = total - before.totalSamplesReceived
    if (deltaTotal > 0 && deltaConcealed >= 0) return deltaConcealed / deltaTotal
    if (deltaConcealed < 0 || before.totalSamplesReceived > total) return null
  }
  return total > 0 ? concealed / total : null
}
