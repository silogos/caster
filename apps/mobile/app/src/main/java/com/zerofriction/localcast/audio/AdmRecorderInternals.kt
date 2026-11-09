package com.zerofriction.localcast.audio

import android.util.Log
import java.lang.reflect.Field
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Reflective handles on the prebuilt ADM's recorder internals
 * (`JavaAudioDeviceModule.audioInput` → its `audioRecord` and `byteBuffer`),
 * shared by both record substitutions: game-audio playback capture
 * ([PlaybackCaptureAudioSource]) and the mic source swap ([MicRecordSubstituter]).
 *
 * Lazy lookups, never class-init failures: a library update that renames the
 * internals yields null fields, and each caller degrades on its own terms
 * (video-only game audio, stock mic record) instead of crashing the session.
 * Visibility differs between pinned artifact versions (1.3.8: private,
 * 1.3.10: public for `audioInput`) — `getDeclaredField` covers both.
 */
internal object AdmRecorderInternals {
    private const val TAG = "AdmRecorderInternals"

    val AUDIO_INPUT_FIELD: Field? = lookupField(JavaAudioDeviceModule::class.java, "audioInput")
    val RECORD_FIELD: Field? = AUDIO_INPUT_FIELD?.let { lookupField(it.type, "audioRecord") }
    val BYTE_BUFFER_FIELD: Field? = AUDIO_INPUT_FIELD?.let { lookupField(it.type, "byteBuffer") }

    private fun lookupField(container: Class<*>, name: String): Field? = try {
        container.getDeclaredField(name).apply { isAccessible = true }
    } catch (t: Throwable) {
        Log.w(TAG, "field $name not found on ${container.name} — ADM record substitution unavailable")
        null
    }
}
