package com.zerofriction.localcast.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log

/**
 * Feasibility probe for the tap scrcpy uses (found live 2026-11-03, after the
 * public playback-capture tap proved one-channel on this ROM): the hidden
 * `AudioPolicy`/`AudioMix` loopback sink (`createAudioRecordSink`, scrcpy
 * issue #4380) — the same route the system screen recorder taps. Mirrors
 * scrcpy's reflection chain verbatim, read-only diagnostics: read ~2 s, log
 * the channel aggregates, release. scrcpy's server runs as the *shell* user
 * (adb), which holds the signature-level `MODIFY_AUDIO_ROUTING` this API
 * chain needs — a normal app is expected to be refused; the probe turns that
 * expectation into logged evidence either way. Never wired into the cast.
 */
internal object AudioPolicySinkProbe {
    private const val TAG = "PlaybackCaptureAudio"
    private const val SAMPLE_RATE = 48000

    fun run(context: Context) {
        Thread({
            var record: AudioRecord? = null
            try {
                // android.media.audiopolicy.* is @hide — scrcpy's exact chain.
                val mixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
                val mixingRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
                val mixingRuleBuilder = mixingRuleBuilderClass.getConstructor().newInstance()
                mixingRuleBuilderClass
                    .getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
                    .invoke(mixingRuleBuilder, mixingRuleClass.getField("MIX_ROLE_PLAYERS").getInt(null))
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                mixingRuleBuilderClass
                    .getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
                    .invoke(mixingRuleBuilder, mixingRuleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null), attributes)
                val mixingRule = mixingRuleBuilderClass.getMethod("build").invoke(mixingRuleBuilder)

                val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
                val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
                val mixBuilder = mixBuilderClass.getConstructor(mixingRuleClass).newInstance(mixingRule)
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
                mixBuilder.javaClass
                    .getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                    .invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
                val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

                val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
                val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
                val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
                policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
                val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

                val register = AudioManager::class.java
                    .getDeclaredMethod("registerAudioPolicyStatic", policyClass)
                    .apply { isAccessible = true }
                val result = register.invoke(null, policy) as Int
                if (result != 0) {
                    Log.w(TAG, "audiopolicy sink check: registration refused (result=$result) — shell-only API, not castable")
                    return@Thread
                }
                record = policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix) as AudioRecord
                record.startRecording()
                val probe = StereoContentProbe(framesPerReport = SAMPLE_RATE)
                val buffer = ByteArray(1024 * 2 * 2)
                var reports = 0
                while (reports < 2) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    probe.onSamples(buffer.copyOf(read))?.let { report ->
                        Log.i(TAG, "audiopolicy sink check: ${report.summarize()}")
                        reports++
                    }
                }
                Log.i(TAG, "audiopolicy sink check: readable=$reports reports (this tap IS accessible to a normal app)")
            } catch (t: Throwable) {
                Log.w(TAG, "audiopolicy sink check failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                record?.release()
            }
        }, "audiopolicy-sink-probe").start()
    }
}
