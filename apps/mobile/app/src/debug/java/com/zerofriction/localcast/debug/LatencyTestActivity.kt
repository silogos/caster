package com.zerofriction.localcast.debug

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Debug-only latency test pattern (docs/development/latency-measurement.md).
 *
 * The screen flips black ↔ white every [FLIP_PERIOD_MS] and clicks on each
 * white flip. Each flip is logged with the wall-clock time of the vsync it
 * was drawn for and (Android 13+) the time Android expects to put it on the
 * phone's own screen — apps queue 2–4 frames ahead of the display, a delay
 * the phone's screen has too and which is not the cast's (measured with
 * atrace, 2026-11-10). The receiver's matching marks
 * (`ZFC_LATENCY_PROBE=1`) give the cast's latency once the two clocks are
 * aligned (`tools/latency/measure.py`). Cast this screen like any other.
 *
 * Like a game, it keeps the stream busy: a strip along the top edge changes
 * every frame (Android sends no frames for a still screen, which would test
 * an idle encoder and bypass the receiver's pacer). The receiver samples the
 * centre only. The click comes from a low-latency [AudioTrack] that is
 * already streaming silence, so the mark is when the click enters the
 * track — not a tone generator's start-up delay.
 *
 * It asks for the display's highest refresh rate, like a game does (the
 * capture path runs on the display's vsync, so a 60 Hz panel measures
 * slower than a 120 Hz one); `--ef refresh 60` asks for another rate. The
 * rate actually running is logged with every flip.
 *
 * Open it with:
 * `adb shell am start -n com.zerofriction.localcast/.debug.LatencyTestActivity`
 */
class LatencyTestActivity : Activity() {

    private lateinit var view: View
    private lateinit var strip: View
    private var white = false
    private var lastFlipNanos = 0L
    private var frameCount = 0
    @Volatile private var clickPending = false
    @Volatile private var running = false
    private var audioThread: Thread? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            onFrame(frameTimeNanos, expectedPresentNanos = null)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Android 13+: the frame data carries when this frame is expected on screen. */
    private val vsyncCallback: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        object : Choreographer.VsyncCallback {
            override fun onVsync(data: Choreographer.FrameData) {
                onFrame(data.frameTimeNanos, data.preferredFrameTimeline.expectedPresentationTimeNanos)
                Choreographer.getInstance().postVsyncCallback(this)
            }
        }
    } else {
        null
    }

    private fun onFrame(frameTimeNanos: Long, expectedPresentNanos: Long?) {
        frameCount += 1
        strip.setBackgroundColor(if (frameCount % 2 == 0) Color.RED else Color.BLUE)
        if (frameTimeNanos - lastFlipNanos >= FLIP_PERIOD_MS * 1_000_000L) {
            lastFlipNanos = frameTimeNanos
            flip(frameTimeNanos, expectedPresentNanos)
        }
    }

    private fun startFrames() {
        val callback = vsyncCallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && callback is Choreographer.VsyncCallback) {
            Choreographer.getInstance().postVsyncCallback(callback)
        } else {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun stopFrames() {
        val callback = vsyncCallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && callback is Choreographer.VsyncCallback) {
            Choreographer.getInstance().removeVsyncCallback(callback)
        }
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val highest = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: DEFAULT_REFRESH_HZ
        val wanted = intent.getFloatExtra(EXTRA_REFRESH, highest)
        window.attributes = window.attributes.apply { preferredRefreshRate = wanted }
        Log.i(TAG, "pattern asks for $wanted Hz (display supports up to $highest Hz)")
        view = View(this).apply { setBackgroundColor(Color.BLACK) }
        strip = View(this)
        setContentView(FrameLayout(this).apply {
            addView(view)
            addView(strip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, STRIP_HEIGHT_PX, Gravity.TOP))
        })
    }

    override fun onResume() {
        super.onResume()
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        startFrames()
        startAudio()
    }

    override fun onPause() {
        stopFrames()
        running = false
        audioThread?.join(AUDIO_JOIN_MS)
        audioThread = null
        super.onPause()
    }

    /** Streams silence on a low-latency track; a pending click replaces one buffer. */
    private fun startAudio() {
        running = true
        audioThread = Thread({
            val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(minBytes)
                .build()
            val chunkFrames = SAMPLE_RATE * CHUNK_MS / 1000
            val silence = ShortArray(chunkFrames * 2)
            val click = ShortArray(chunkFrames * 2) { i ->
                // A 1 kHz square burst at near full scale.
                if ((i / 2 * 1000 * 2 / SAMPLE_RATE) % 2 == 0) CLICK_LEVEL else (-CLICK_LEVEL).toShort()
            }
            track.play()
            var clickLeft = 0
            var framesWritten = 0L
            val stamp = android.media.AudioTimestamp()
            while (running) {
                if (clickPending) {
                    clickPending = false
                    clickLeft = CLICK_CHUNKS
                    // How long until the frame about to be written leaves this
                    // track for the mixer: queued frames ahead of it, by the
                    // track's own presentation timestamp. Part of the click's
                    // path a game's audio has too; reported separately.
                    val ahead = if (track.getTimestamp(stamp)) {
                        val queuedMs = (framesWritten - stamp.framePosition) * 1000.0 / SAMPLE_RATE
                        val sinceStampMs = (System.nanoTime() - stamp.nanoTime) / 1_000_000.0
                        " track_ms=${"%.1f".format(queuedMs - sinceStampMs)}"
                    } else {
                        ""
                    }
                    Log.i(TAG, "click written_ms=${"%.1f".format(System.currentTimeMillis().toDouble())}$ahead")
                }
                track.write(if (clickLeft > 0) click else silence, 0, silence.size)
                framesWritten += chunkFrames
                if (clickLeft > 0) clickLeft -= 1
            }
            track.stop()
            track.release()
        }, "latency-click").apply { start() }
    }

    private fun flip(frameTimeNanos: Long, expectedPresentNanos: Long?) {
        white = !white
        view.setBackgroundColor(if (white) Color.WHITE else Color.BLACK)
        // Monotonic → wall clock: the vsync this frame was drawn for, and when
        // Android expects it on the phone's screen.
        val wallOffsetMs = System.currentTimeMillis() - System.nanoTime() / 1_000_000.0
        val frameMs = wallOffsetMs + frameTimeNanos / 1_000_000.0
        val present = expectedPresentNanos?.let { " present_ms=${"%.1f".format(wallOffsetMs + it / 1_000_000.0)}" } ?: ""
        if (white) clickPending = true
        Log.i(TAG, "flip ${if (white) "white" else "black"} frame_ms=${"%.1f".format(frameMs)}$present refresh_hz=${refreshHz()}")
    }

    private fun refreshHz(): String = "%.0f".format(display?.refreshRate ?: 0f)

    private companion object {
        const val EXTRA_REFRESH = "refresh"
        const val DEFAULT_REFRESH_HZ = 60f
        const val TAG = "LatencyTest"
        const val FLIP_PERIOD_MS = 1_000L
        const val STRIP_HEIGHT_PX = 48
        const val SAMPLE_RATE = 48_000
        const val CHUNK_MS = 5
        const val CLICK_CHUNKS = 12 // 60 ms
        const val CLICK_LEVEL: Short = 24_000
        const val AUDIO_JOIN_MS = 500L
    }
}
