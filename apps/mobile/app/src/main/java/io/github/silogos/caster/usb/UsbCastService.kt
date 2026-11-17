package io.github.silogos.caster.usb

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
import io.github.silogos.caster.audio.GameAudioState
import io.github.silogos.caster.audio.MicState
import io.github.silogos.caster.config.MicDeviceSource
import io.github.silogos.caster.service.CastForegroundTypes
import io.github.silogos.caster.capture.CaptureSize
import io.github.silogos.caster.capture.DisplaySize
import io.github.silogos.caster.config.CastDropWindow
import io.github.silogos.caster.config.CastSettingsStore
import io.github.silogos.caster.R
import io.github.silogos.caster.service.CastLink
import io.github.silogos.caster.service.CastNotifications
import io.github.silogos.caster.service.CastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * the projection revoked, the encoder failing, or the cable staying out for
 * the whole drop window. ADR-036: the cast outlives its link. When the cable
 * goes, the media stops but the projection and its [UsbScreen] stay; when the
 * same desktop is back within [CastDropWindow], a new encoder goes onto the
 * same screen, with no new consent.
 */
class UsbCastService : Service() {

    companion object {
        private const val TAG = "UsbCast"
        private const val ACTION_STOP = "io.github.silogos.caster.usb.STOP"
        private const val ACTION_TOGGLE_GAME_AUDIO = "io.github.silogos.caster.usb.TOGGLE_GAME_AUDIO"
        private const val ACTION_TOGGLE_MIC = "io.github.silogos.caster.usb.TOGGLE_MIC"
        private const val EXTRA_CONSENT = "consent"
        private const val STATS_INTERVAL_MS = 5_000L

        private val _gameAudioState = MutableStateFlow<GameAudioState>(GameAudioState.Off)
        val gameAudioState: StateFlow<GameAudioState> = _gameAudioState.asStateFlow()

        /** The cable cast as Home shows it: Idle, Casting, Interrupted (cable out) or Failed (ADR-036). */
        private val _castState = MutableStateFlow<CastState>(CastState.Idle)
        val castState: StateFlow<CastState> = _castState.asStateFlow()

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
    private var screen: UsbScreen? = null
    private var media: CableMedia? = null
    private var drop: UsbCastDrop? = null
    private var linkWatch: CoroutineScope? = null
    private var startedAtMillis = 0L
    /** The user's mic choice for this cast; it survives the wait (ADR-036). */
    private var micWanted = false
    /** Game audio muted by the user; it survives the wait. */
    private var gameAudioMuted = false
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
                val capture = audio
                if (capture != null) {
                    capture.setMuted(capture.state != GameAudioState.Muted)
                } else if (waiting()) {
                    gameAudioMuted = !gameAudioMuted
                    _gameAudioState.value = if (gameAudioMuted) GameAudioState.Muted else GameAudioState.Active
                }
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MIC -> {
                if (waiting()) {
                    // Applied when the cable is back; turning it off frees the mic type now.
                    micWanted = !micWanted
                    _micState.value = if (micWanted) MicState.Active else MicState.Off
                    if (!micWanted) updateForegroundTypes(micOn = false)
                } else if (casting) {
                    setMic(on = mic == null)
                }
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
        UsbLink.onLinkClosed = { main.post { onLinkLost() } }

        // "Changes take effect on the next cast": the store, read fresh. Over
        // the cable the profile's values hold for the whole cast (ADR-034),
        // across a drop too.
        val config = CastSettingsStore(this).load().toCableConfig()
        val (physicalW, physicalH) = DisplaySize.physicalPx(this)
        val (w, h) = CaptureSize.scaleTo(config.longEdgePx, physicalW, physicalH)
        // The profile's encoder fps limit applies over the cable too: the lower of the two.
        val fps = config.encoderFpsLimit?.let { minOf(it, config.fps) } ?: config.fps
        val desktopId = UsbLink.trustedDesktopId
        if (desktopId == null) {
            endCast("the cable link is not trusted")
            return
        }
        val castScreen = try {
            UsbScreen(mp, w, h, resources.displayMetrics.densityDpi)
        } catch (e: RuntimeException) {
            Log.e(TAG, "virtual display refused: ${e.message}")
            endCast("no virtual display")
            return
        }
        screen = castScreen
        drop = UsbCastDrop(desktopId)
        media = CableMedia(
            width = w,
            height = h,
            fps = fps,
            bitrateBps = config.bitrateMaxBps,
            software = config.encoderImpl == io.github.silogos.caster.config.EncoderImplementation.SOFTWARE,
            highProfile = config.h264HighProfile,
            gameAudio = config.gameAudio,
            profile = config.profile.toString(),
        )
        micDevice = config.micDevice
        micWanted = config.mic
        gameAudioMuted = false
        if (!startMedia()) return
        casting = true
        startedAtMillis = System.currentTimeMillis()
        _castState.value = CastState.Casting(desktopName, startedAtMillis)
        // A drop's wait ends when the link is Ready again (same desktop) — ADR-036.
        linkWatch = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope ->
            scope.launch { UsbLink.state.collect { if (it is UsbLink.State.Ready) onLinkReady() } }
        }
        lastBytes = UsbLink.bytesSent()
        lastStatsAt = System.currentTimeMillis()
        main.postDelayed(::logStats, STATS_INTERVAL_MS)
    }

    /** What every encoder of this cast is built from: the profile, read once at the start. */
    private data class CableMedia(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateBps: Int,
        val software: Boolean,
        val highProfile: Boolean,
        val gameAudio: Boolean,
        val profile: String,
    )

    private fun waiting(): Boolean = drop?.waitingSinceMs != null

    /**
     * Starts this link's media: an encoder on the cast's screen, `cast-start`,
     * game audio and the mic as the user left them. Called at the start and
     * on every resume. False (and the cast ended) when the encoder refuses.
     */
    private fun startMedia(): Boolean {
        val m = media ?: return false
        val castScreen = screen ?: return false
        val enc = UsbVideoEncoder(
            screen = castScreen,
            width = m.width,
            height = m.height,
            fps = m.fps,
            bitrateBps = m.bitrateBps,
            software = m.software,
            highProfile = m.highProfile,
            onFrame = UsbLink::sendFramed,
            onStopped = { reason -> main.post { onEncoderStopped(reason) } },
        )
        UsbLink.sendControl(
            JSONObject()
                .put("type", "cast-start")
                .put("w", m.width)
                .put("h", m.height)
                .put("fps", m.fps)
                .put("audio", if (m.gameAudio) "pcm16-48k-stereo" else JSONObject.NULL)
                .put("mic", micWanted && micAllowed()),
        )
        try {
            enc.start()
        } catch (e: Exception) {
            Log.e(TAG, "video encoder failed to start: ${e.message}")
            endCast("video encoder failed")
            return false
        }
        encoder = enc
        UsbLink.markCasting(true)
        if (m.gameAudio) {
            val capture = UsbGameAudioCapture(
                context = this,
                projection = projection ?: return false,
                onBlock = { pts, pcm -> UsbLink.sendMedia(UsbFrame.KIND_GAME_AUDIO, 0, pts, pcm) },
                onState = { _gameAudioState.value = it },
            )
            _gameAudioState.value = if (capture.start()) GameAudioState.Active else GameAudioState.Failed
            if (gameAudioMuted) capture.setMuted(true)
            audio = capture
        } else {
            _gameAudioState.value = GameAudioState.Off
        }
        if (micWanted) setMic(on = true, announce = false)
        Log.i(TAG, "casting over USB to the desktop (profile ${m.profile}): ${m.width}x${m.height} @${m.fps} fps, ${m.bitrateBps / 1000} kbps, game audio ${m.gameAudio}")
        return true
    }

    /** Stops this link's media; the projection, the screen and the mic's service type stay (ADR-036). */
    private fun stopMedia() {
        audio?.let { gameAudioMuted = it.state == GameAudioState.Muted }
        audio?.stop()
        audio = null
        mic?.stop()
        mic = null
        encoder?.stop()
        encoder = null
    }

    /** The encoder ended on its own: a write to a gone link is a drop; anything else ends the cast. */
    private fun onEncoderStopped(reason: String) {
        if (reason == UsbVideoEncoder.REASON_LINK_GONE) onLinkLost() else endCast("video encoder stopped: $reason")
    }

    private fun onLinkLost() {
        if (!casting) return
        val decision = drop?.onLinkLost(System.currentTimeMillis()) as? UsbCastDrop.Decision.Wait ?: return
        Log.i(TAG, "cable gone mid-cast — waiting up to ${CastDropWindow.MS / 1000} s for it")
        stopMedia()
        // The mic's state shows the user's choice while nothing records.
        _micState.value = if (micWanted) MicState.Active else MicState.Off
        _castState.value = CastState.Interrupted(desktopName, CastLink.CABLE, decision.sinceMs, decision.endsAtMs, startedAtMillis)
        notifications.notify(
            notifications.build(
                desktopName,
                textOverride = getString(R.string.cast_waiting_cable_text),
                stop = stopIntent(this),
                titleOverride = getString(R.string.cast_waiting_cable_title),
                countdownTo = decision.endsAtMs,
            ),
        )
        main.postDelayed(windowCheck, decision.endsAtMs - decision.sinceMs)
    }

    private fun onLinkReady() {
        if (!casting) return
        when (val decision = drop?.onLinkReady(UsbLink.trustedDesktopId, System.currentTimeMillis()) ?: return) {
            UsbCastDrop.Decision.Resume -> {
                Log.i(TAG, "cable back — the cast carries on")
                main.removeCallbacks(windowCheck)
                if (!startMedia()) return
                _castState.value = CastState.Casting(desktopName, startedAtMillis)
                notifications.notify(notifications.build(desktopName, stop = stopIntent(this)))
            }
            is UsbCastDrop.Decision.End -> endCast(decision.reason, failure = getString(R.string.usb_cast_timed_out))
            else -> Unit
        }
    }

    private val windowCheck = Runnable { checkWindow() }

    private fun checkWindow() {
        val decision = drop?.onTick(System.currentTimeMillis()) as? UsbCastDrop.Decision.End ?: return
        endCast(decision.reason, failure = getString(R.string.usb_cast_timed_out))
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
                micWanted = false
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
                micWanted = false
                updateForegroundTypes(micOn = false)
                return
            }
            mic = capture
            micWanted = true
        } else {
            micWanted = false
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
        if (waiting()) {
            main.postDelayed(::logStats, STATS_INTERVAL_MS)
            return
        }
        val now = System.currentTimeMillis()
        val bytes = UsbLink.bytesSent()
        val kbps = (bytes - lastBytes) * 8 / (now - lastStatsAt).coerceAtLeast(1)
        Log.i(TAG, "usb link: $kbps kbps over the last ${(now - lastStatsAt) / 1000} s; game audio ${_gameAudioState.value}")
        lastBytes = bytes
        lastStatsAt = now
        main.postDelayed(::logStats, STATS_INTERVAL_MS)
    }

    /** [failure] is the user-facing message when the cast ended without the user (Home shows it). */
    private fun endCast(reason: String, failure: String? = null) {
        if (!casting && projection == null && encoder == null) {
            stopService(reason)
            return
        }
        Log.i(TAG, "cast over USB ends: $reason")
        casting = false
        main.removeCallbacksAndMessages(null)
        linkWatch?.cancel()
        linkWatch = null
        UsbLink.onLinkClosed = null
        stopMedia()
        _micState.value = MicState.Off
        screen?.release()
        screen = null
        drop = null
        media = null
        projection?.let { p -> runCatching { p.stop() } }
        projection = null
        _castState.value = if (failure != null) CastState.Failed(failure) else CastState.Idle
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
