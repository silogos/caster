package com.zerofriction.localcast.config

import kotlinx.serialization.Serializable

/**
 * Everything the settings screen persists (Phase10): the chosen preset plus
 * the effective send targets and audio defaults, serialized by
 * [CastSettingsCodec] and stored by [CastSettingsStore]. [toConfig] is the
 * single conversion into what a cast actually consumes — [CastConfig] stays
 * the one thing the rest of the app sees (AGENTS.md: one home for cast
 * settings).
 *
 * `bitrateAuto` is UI state, not a send target: true = the window rides the
 * chosen preset (and profile picks restore it), false = the window is the
 * user's manual ceiling with a derived floor.
 */
@Serializable
data class CastSettings(
    /** The preset the user last picked — the source for [profileLabelFor]'s label. */
    val profile: QualityProfile,
    /** Capture target: the long edge of the physical screen scaled down to this. */
    val longEdgePx: Int,
    /** Frames per second sent to the encoder. */
    val fps: Int,
    /** True = preset bitrate window; false = [bitrateMinBps]/[bitrateMaxBps] are manual. */
    val bitrateAuto: Boolean,
    val bitrateMinBps: Int,
    val bitrateMaxBps: Int,
    /** Cast the device's game audio (AudioPlaybackCapture) with the screen. */
    val gameAudio: Boolean,
    /** Start the cast with the microphone on (audio.md: off by default). */
    val mic: Boolean,
    /** Stats/thermal-driven auto quality (Phase12, thermal.md) — on by default. */
    val autoQuality: Boolean,
    // ---- Advanced (designs/mobile-app.html; defaults reproduce today's cast) ----
    val encoderImpl: EncoderImplementation = EncoderImplementation.HARDWARE,
    val h264HighProfile: Boolean = true,
    val preferredCodec: PreferredVideoCodec = PreferredVideoCodec.H264,
    val degradationStrategy: DegradationStrategy = DegradationStrategy.BALANCED,
    val encoderFpsLimit: Int? = null,
    val bitrateMode: EncoderBitrateMode = EncoderBitrateMode.CBR,
) {
    fun toConfig(): CastConfig = CastConfig(
        profile = profileLabelFor(longEdgePx, fps, bitrateMinBps, bitrateMaxBps),
        longEdgePx = longEdgePx,
        fps = fps,
        bitrateMinBps = bitrateMinBps,
        bitrateMaxBps = bitrateMaxBps,
        degradationPreference = when (degradationStrategy) {
            DegradationStrategy.BALANCED -> CastConfig.DEGRADATION_BALANCED
            DegradationStrategy.MAINTAIN_FRAMERATE -> CastConfig.DEGRADATION_FRAMERATE
            DegradationStrategy.MAINTAIN_RESOLUTION -> CastConfig.DEGRADATION_RESOLUTION
        },
        gameAudio = gameAudio,
        mic = mic,
        autoQuality = autoQuality,
        encoderImpl = encoderImpl,
        h264HighProfile = h264HighProfile,
        preferredCodec = preferredCodec,
        encoderFpsLimit = encoderFpsLimit,
        bitrateMode = bitrateMode,
    )

    companion object {
        /** Preset defaults + the Phase7/8 audio defaults (game audio on, mic off). */
        fun default(): CastSettings {
            val preset = QualityProfile.BALANCED
            return CastSettings(
                profile = preset,
                longEdgePx = preset.longEdgePx,
                fps = preset.fps,
                bitrateAuto = true,
                bitrateMinBps = preset.bitrateMinBps,
                bitrateMaxBps = preset.bitrateMaxBps,
                gameAudio = CastConfig.DEFAULT_GAME_AUDIO,
                mic = CastConfig.DEFAULT_MIC,
                autoQuality = CastConfig.DEFAULT_AUTO_QUALITY,
            )
        }
    }
}
