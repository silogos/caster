package com.zerofriction.localcast.usb

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.service.CastForegroundTypes
import com.zerofriction.localcast.capture.CaptureSize
import com.zerofriction.localcast.capture.DisplaySize
import com.zerofriction.localcast.config.CastSettingsStore
import com.zerofriction.localcast.service.CastNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The foreground service (type `mediaProjection`) owning one cast over the
 * USB cable (ADR-034): the projection, [UsbVideoEncoder] and
 * [UsbGameAudioCapture], writing into [UsbLink]. Its own small module, not a
 * mode of CastService: the Wi-Fi cast's WebRTC session, adaptive quality and
 * mic do not exist on this path (AGENTS.md: no CastManager).
 *
 * Every end converges on [endCast]: the user's Stop (app or notification),
 * the projection revoked, the encoder dying, or the cable going.
 */
class UsbCastService : Service() {

    companion object {
        private const val TAG = "UsbCast"
        private const val ACTION_STOP = "com.zerofriction.localcast.usb.STOP"
        private const val ACTION_TOGGLE_GAME_AUDIO = "com.zerofriction.localcast.usb.TOGGLE_GAME_AUDIO"
        private const val ACTION_TOGGLE_MIC = "com.zerofriction.localcast.usb.TOGGLE_MIC"
        private const val EXTRA_CONSENT = "consent"
        private const val STATS_INTERVAL_MS = 5_000L

        private val _gameAudioState = MutableStateFlow<GameAudioState>(GameAudioState.Off)
        val gameAudioState: StateFlow<GameAudioState> = _gameAudioState.asStateFlow()

        /** The mic of the cable cast — its own state, never gated by game audio (ADR-003). */
        private val _micState = MutableStateFlow<MicState>(MicState.Off)
        val micState: StateFlow<MicState> = _micState.asStateFlow()

        /** Starts the cable cast with a fresh capture consent. False when there is no ready link. */
        fun start(context: Context, consent: Intent): Boolean {
            if (UsbLink.readyDesktopName() == null) {
                Log.w(TAG, "refusing to start: the USB link is not ready")
                return false
            }
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, UsbCastService::class.java).putExtra(EXTRA_CONSENT, consent),
                )
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "cast service start refused by the OS: ${e.message}")
                false
            }
        }

        fun stopIntent(context: Context): Intent = Intent(context, UsbCastService::class.java).setAction(ACTION_STOP)

        fun requestStop(context: Context) {
            context.startService(stopIntent(context))
        }

        fun requestToggleGameAudio(context: Context) {
            context.startService(Intent(context, UsbCastService::class.java).setAction(ACTION_TOGGLE_GAME_AUDIO))
        }

        /** Mic on/off for the running cable cast (no-op without one). */
        fun requestToggleMic(context: Context) {
            context.startService(Intent(context, UsbCastService::class.java).setAction(ACTION_TOGGLE_MIC))
        }
    }

    private val notifications by lazy { CastNotifications(this) }
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var encoder: UsbVideoEncoder? = null
    private var audio: UsbGameAudioCapture? = null
    private var mic: UsbMicCapture? = null
    private var micDevice: MicDeviceSource = MicDeviceSource.BUILTIN
    private var casting = false
    private var desktopName = ""
    private var lastBytes = 0L
    private var lastStatsAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                endCast("stopped by the user")
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_GAME_AUDIO -> {
                audio?.let { it.setMuted(it.state != GameAudioState.Muted) }
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MIC -> {
                if (casting) setMic(on = mic == null)
                return START_NOT_STICKY
            }
        }
        val name = UsbLink.readyDesktopName()
        notifications.ensureChannel()
        // Android 14+: foreground before the projection exists.
        ServiceCompat.startForeground(
            this,
            CastNotifications.NOTIFICATION_ID,
            notifications.build(name ?: "", stop = stopIntent(this)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        val consent = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_CONSENT, Intent::class.java) }
        if (casting || name == null || consent == null) {
            if (!casting) stopService("no ready link or no consent")
            return START_NOT_STICKY
        }
        startCast(name, consent)
        return START_NOT_STICKY
    }

    private fun startCast(name: String, consent: Intent) {
        desktopName = name
        val mp = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(android.app.Activity.RESULT_OK, consent)
        if (mp == null) {
            stopService("no projection from the consent")
            return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { endCast("the screen cast was stopped") }
            }
        }, main)
        UsbLink.onLinkClosed = { main.post { endCast("the cable link closed") } }

        // "Changes take effect on the next cast": the store, read fresh. Over
        // the cable the profile's values hold for the whole cast (ADR-034).
        val config = CastSettingsStore(this).load().toCableConfig()
        val (physicalW, physicalH) = DisplaySize.physicalPx(this)
        val (w, h) = CaptureSize.scaleTo(config.longEdgePx, physicalW, physicalH)
        // The profile's encoder fps limit applies over the cable too: the lower of the two.
        val fps = config.encoderFpsLimit?.let { minOf(it, config.fps) } ?: config.fps
        val enc = UsbVideoEncoder(
            projection = mp,
            width = w,
            height = h,
            fps = fps,
            bitrateBps = config.bitrateMaxBps,
            software = config.encoderImpl == com.zerofriction.localcast.config.EncoderImplementation.SOFTWARE,
            highProfile = config.h264HighProfile,
            densityDpi = resources.displayMetrics.densityDpi,
            onAccessUnit = { f, pts, data -> UsbLink.sendMedia(UsbFrame.KIND_VIDEO, f, pts, data) },
            onStopped = { reason -> main.post { endCast("video encoder stopped: $reason") } },
        )
        micDevice = config.micDevice
        UsbLink.sendControl(
            JSONObject()
                .put("type", "cast-start")
                .put("w", w)
                .put("h", h)
                .put("fps", fps)
                .put("audio", if (config.gameAudio) "pcm16-48k-stereo" else JSONObject.NULL)
                .put("mic", config.mic && micAllowed()),
        )
        try {
            enc.start()
        } catch (e: Exception) {
            Log.e(TAG, "video encoder failed to start: ${e.message}")
            endCast("video encoder failed")
            return
        }
        encoder = enc
        casting = true
        UsbLink.markCasting(true)
        if (config.gameAudio) {
            val capture = UsbGameAudioCapture(
                context = this,
                projection = mp,
                onBlock = { pts, pcm -> UsbLink.sendMedia(UsbFrame.KIND_GAME_AUDIO, 0, pts, pcm) },
                onState = { _gameAudioState.value = it },
            )
            _gameAudioState.value = if (capture.start()) GameAudioState.Active else GameAudioState.Failed
            audio = capture
        } else {
            _gameAudioState.value = GameAudioState.Off
        }
        if (config.mic) setMic(on = true, announce = false)
        Log.i(TAG, "casting over USB to the desktop (profile ${config.profile}): ${w}x$h @$fps fps, ${config.bitrateMaxBps / 1000} kbps, game audio ${config.gameAudio}")
        lastBytes = UsbLink.bytesSent()
        lastStatsAt = System.currentTimeMillis()
        main.postDelayed(::logStats, STATS_INTERVAL_MS)
    }

    private fun micAllowed(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * The mic on or off mid-cast. Android 11+ silences a backgrounded app's
     * mic unless the service carries the `microphone` type, so the type is
     * added before the record starts and removed after it stops.
     */
    private fun setMic(on: Boolean, announce: Boolean = true) {
        if (on == (mic != null)) return
        if (on) {
            if (!micAllowed()) {
                _micState.value = MicState.NeedsPermission
                return
            }
            updateForegroundTypes(micOn = true)
            val capture = UsbMicCapture(
                context = this,
                source = micDevice,
                onBlock = { pts, pcm -> UsbLink.sendMedia(UsbFrame.KIND_MIC_AUDIO, 0, pts, pcm) },
                onState = { _micState.value = it },
            )
            if (!capture.start()) {
                updateForegroundTypes(micOn = false)
                return
            }
            mic = capture
        } else {
            mic?.stop()
            mic = null
            updateForegroundTypes(micOn = false)
        }
        if (announce) UsbLink.sendControl(JSONObject().put("type", "mic").put("on", on))
    }

    private fun updateForegroundTypes(micOn: Boolean) {
        try {
            ServiceCompat.startForeground(
                this,
                CastNotifications.NOTIFICATION_ID,
                notifications.build(desktopName, stop = stopIntent(this)),
                CastForegroundTypes.types(micOn, Build.VERSION.SDK_INT),
            )
        } catch (e: RuntimeException) {
            // A mic type the OS refuses (e.g. started from the background) must not end the cast.
            Log.w(TAG, "foreground type update refused: ${e.message}")
        }
    }

    private fun logStats() {
        if (!casting) return
        val now = System.currentTimeMillis()
        val bytes = UsbLink.bytesSent()
        val kbps = (bytes - lastBytes) * 8 / (now - lastStatsAt).coerceAtLeast(1)
        Log.i(TAG, "usb link: $kbps kbps over the last ${(now - lastStatsAt) / 1000} s; game audio ${_gameAudioState.value}")
        lastBytes = bytes
        lastStatsAt = now
        main.postDelayed(::logStats, STATS_INTERVAL_MS)
    }

    private fun endCast(reason: String) {
        if (!casting && projection == null && encoder == null) {
            stopService(reason)
            return
        }
        Log.i(TAG, "cast over USB ends: $reason")
        casting = false
        main.removeCallbacksAndMessages(null)
        UsbLink.onLinkClosed = null
        audio?.stop()
        audio = null
        mic?.stop()
        mic = null
        _micState.value = MicState.Off
        encoder?.stop()
        encoder = null
        projection?.let { p -> runCatching { p.stop() } }
        projection = null
        UsbLink.sendControl(JSONObject().put("type", "cast-stop"))
        UsbLink.markCasting(false)
        _gameAudioState.value = GameAudioState.Off
        stopService(reason)
    }

    private fun stopService(reason: String) {
        Log.i(TAG, "service stops: $reason")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (casting) endCast("service destroyed")
        super.onDestroy()
    }
}
