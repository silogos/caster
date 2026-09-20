package com.zerofriction.localcast.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.MainActivity
import com.zerofriction.localcast.R
import com.zerofriction.localcast.adaptive.AdaptiveQualityController
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.capture.CaptureSize
import com.zerofriction.localcast.config.CastConfig
import com.zerofriction.localcast.config.PROFILE_CUSTOM
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.matchingProfile
import com.zerofriction.localcast.signaling.SignalingClient
import com.zerofriction.localcast.thermal.ThermalMonitor
import com.zerofriction.localcast.thermal.ThermalSource
import com.zerofriction.localcast.thermal.ThermalState
import com.zerofriction.localcast.webrtc.MediaCastSession
import com.zerofriction.localcast.webrtc.MicCastSession
import com.zerofriction.localcast.webrtc.SenderSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The cast pipeline's user-facing state, owned by [CastService] — the home and
 * scan screens render it 1:1. Errors carry the simple user-facing message only
 * (AGENTS.md); technical detail lives in the service/session logs. The
 * desktop's name rides along so any screen (and the notification) can say who
 * is being cast to without asking the pairing machine, which handed its
 * connection over when the cast started.
 */
sealed interface CastState {
    data object Idle : CastState
    data class Starting(val desktopName: String) : CastState
    data class Casting(val desktopName: String) : CastState
    data class Failed(val message: String) : CastState
}

/**
 * The auto-quality half of the cast's state (Phase12, thermal.md): what the
 * running cast's quality is right now, what it may go back up to (the user's
 * settings at cast start — the ceiling), and the last transition (the
 * "what happened" line the home/scan screens render). Blank state = no cast,
 * or the user's switch turned auto quality off.
 */
data class AdaptiveUiState(
    val current: QualityLevel? = null,
    val ceiling: QualityLevel? = null,
    val lastChange: AdaptiveQualityController.QualityChange? = null,
)

/**
 * Phase6 foreground service (type `mediaProjection`) owning the **whole cast
 * session** — the MediaProjection, the `media` peer connection and the pairing
 * signaling connection handed over at start (docs/architecture/mobile.md).
 * The Activity is only UI: a cast started from the scan screen survives
 * leaving the screen, the app being backgrounded for a game, and the task
 * being removed; it ends through the stop button (app or notification), the
 * user revoking the projection, the desktop disappearing, or death of the
 * owning process.
 *
 * Lifecycle (every edge converges on [endCast]):
 *  - consent result + signaling handover → [start] → foreground (Android14+
 *    ordering: foreground *before* the projection starts, consent *before* the
 *    service starts) → `MediaCastSession`
 *  - `onStop` from the projection (status-bar chip / system revoke) → session
 *    fatal → Failed("The screen cast was stopped.")
 *  - `bye`/terminal signaling failure/ICE failure → session fatal → Failed
 *  - user stop (app button or notification action) → clean stop, `bye` to the
 *    desktop (fresh QR there)
 *  - system kill of the service or process → `onDestroy` tears the session
 *    down; the cast does not auto-restart (`START_NOT_STICKY`) — projection
 *    consent cannot be reused after process death, so the user starts a fresh
 *    cast (new scan + new consent).
 */
class CastService : Service() {

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.zerofriction.localcast.service.STOP"
        private const val ACTION_TOGGLE_GAME_AUDIO = "com.zerofriction.localcast.service.TOGGLE_GAME_AUDIO"
        private const val ACTION_TOGGLE_MIC = "com.zerofriction.localcast.service.TOGGLE_MIC"
        private const val ACTION_RESTORE_QUALITY = "com.zerofriction.localcast.service.RESTORE_QUALITY"

        /** Start args travel via a holder — a Service Intent can only carry parceled values, not the live signaling client. */
        private var pendingStart: StartArgs? = null

        private val _state = MutableStateFlow<CastState>(CastState.Idle)
        val state: StateFlow<CastState> = _state.asStateFlow()

        /**
         * The game-audio half of the cast's state (Phase7, audio.md) —
         * rendered by the home screen next to [state]. Reset to [GameAudioState.Off]
         * whenever no cast is running; the session republishes on every change.
         */
        private val _gameAudioState = MutableStateFlow<GameAudioState>(GameAudioState.Off)
        val gameAudioState: StateFlow<GameAudioState> = _gameAudioState.asStateFlow()

        /**
         * The mic half (Phase8, audio.md) — same shape as [gameAudioState].
         * Off = not in this cast; the session republishes on every change.
         */
        private val _micState = MutableStateFlow<MicState>(MicState.Off)
        val micState: StateFlow<MicState> = _micState.asStateFlow()

        /**
         * The thermal half (Phase11, thermal.md) — read-only diagnostics for
         * the "This cast" section; resets to NONE between casts. Phase12's
         * auto quality reads it, but it changes no cast parameters itself.
         */
        private val _thermalState = MutableStateFlow(ThermalState())
        val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

        /**
         * The auto-quality half (Phase12, thermal.md): what the running cast's
         * quality is right now, what it may go back up to (the user's settings
         * at cast start), and the last transition (the "what happened" line).
         * Blank state = no cast or the user's switch turned auto quality off.
         */
        private val _adaptiveState = MutableStateFlow(AdaptiveUiState())
        val adaptiveState: StateFlow<AdaptiveUiState> = _adaptiveState.asStateFlow()

        fun start(context: Context, args: StartArgs) {
            if (pendingStart !== null) return // one cast at a time (v1 non-goal: multi-desktop)
            // The handover must carry a *live* pairing (found live, 2026-09-20:
            // a cast started with an already-closed client hangs in Starting
            // forever — no Authorized/Failed event ever arrives on a dead
            // socket, so nothing would end it). AUTHORIZED is the normal case;
            // RECONNECTING is allowed — the session negotiates when re-auth
            // succeeds, and expiry surfaces as a Failed event (→ ConnectionLost).
            when (args.signaling.state.value) {
                SignalingClient.State.AUTHORIZED,
                SignalingClient.State.RECONNECTING,
                -> Unit
                else -> {
                    Log.w(TAG, "refusing to start: signaling is ${args.signaling.state.value}")
                    _state.value = CastState.Failed(context.getString(R.string.cast_failed_generic))
                    return
                }
            }
            pendingStart = args
            _state.value = CastState.Starting(args.desktopName)
            ContextCompat.startForegroundService(context, Intent(context, CastService::class.java))
        }

        fun requestStop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }

        /** Mute/unmute the game-audio track of the running cast (no-op without one). */
        fun requestToggleGameAudio(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_TOGGLE_GAME_AUDIO))
        }

        /** Turn the mic of the running cast on/off (no-op without one). */
        fun requestToggleMic(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_TOGGLE_MIC))
        }

        /** The user puts the cast back at their own settings (auto quality is reversible). */
        fun requestRestoreQuality(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_RESTORE_QUALITY))
        }
    }

    /**
     * Everything the service needs from the screen that started the cast. The
     * signaling client was released by the pairing machine to this service —
     * from here on its lifecycle is the cast's lifecycle ([endCast] sends
     * `bye` and closes it).
     */
    data class StartArgs(
        val signaling: SignalingClient,
        val config: CastConfig,
        /** The consent result — single use; a new cast always re-prompts (mobile.md). */
        val projectionIntent: Intent,
        val physicalWidth: Int,
        val physicalHeight: Int,
        val desktopName: String,
    )

    private var session: MediaCastSession? = null
    private var signaling: SignalingClient? = null

    /** The mic half (Phase8) — non-null while the mic is on for this cast. */
    private var micSession: MicCastSession? = null

    /** Thermal monitoring (Phase11) — runs for exactly the cast's lifetime. */
    private var thermalSource: ThermalSource? = null

    /** Auto quality (Phase12, thermal.md) — null when off (the user's switch) or without a cast. */
    private var adaptive: AdaptiveQualityController? = null

    /** The ceiling auto quality may climb back to — the user's settings at cast start. */
    private var adaptiveCeiling: QualityLevel? = null

    /** Kept from [StartArgs] for the `session-info` resends on mic toggles. */
    private var config: CastConfig? = null
    private var physicalWidth: Int = 0
    private var physicalHeight: Int = 0

    /** Kept from [StartArgs] for the notification the FGS type updates rebuild. */
    private var desktopName: String = ""

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                endCast()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_GAME_AUDIO -> {
                val currentSession = session
                if (currentSession !== null) {
                    // Muted ⇄ everything else; the session owns the actual sender-side muting.
                    currentSession.setGameAudioMuted(currentSession.gameAudioState != GameAudioState.Muted)
                }
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MIC -> {
                toggleMic()
                return START_NOT_STICKY
            }
            ACTION_RESTORE_QUALITY -> {
                adaptive?.restore(System.currentTimeMillis())
                return START_NOT_STICKY
            }
        }
        val args = pendingStart ?: run {
            // Stopped by the system with nothing pending — nothing to resume.
            endCast()
            return START_NOT_STICKY
        }
        pendingStart = null
        signaling = args.signaling
        config = args.config
        desktopName = args.desktopName
        physicalWidth = args.physicalWidth
        physicalHeight = args.physicalHeight

        // Android14+ ordering (mobile.md): foreground *before* the projection
        // starts; the consent was collected *before* the service started. The
        // mic bit joins the type set only when the mic session starts — the
        // mic decision (config + permission) is settled below, and Android14+
        // refuses a microphone-typed start without RECORD_AUDIO.
        createChannel()
        startCastForeground(micOn = false)

        val newSession = MediaCastSession(
            context = applicationContext,
            signaling = args.signaling,
            config = args.config,
            projectionPermissionIntent = args.projectionIntent,
            physicalWidth = args.physicalWidth,
            physicalHeight = args.physicalHeight,
            onState = { sessionState ->
                if (sessionState == MediaCastSession.State.STREAMING) {
                    _state.value = CastState.Casting(args.desktopName)
                    // The media session's own summary rides its build; this
                    // one is the authoritative last word — by streaming time
                    // the mic decision (config + permission) is settled, so
                    // the flag can't be stale.
                    sendSessionInfo(mic = micSession !== null)
                }
            },
            onGameAudioState = { gameAudioState ->
                _gameAudioState.value = gameAudioState
            },
            onVideoStats = { sample, _ -> feedAutoQuality(sample) },
        )
        session = newSession
        newSession.start(onFatal = { failure -> onFatal(failure) })
        startThermalMonitoring()
        // Auto quality rides the cast only when the user left it on (config
        // module: the switch is a cast setting like any other — next-cast
        // semantics, same as resolution/fps/bitrate).
        if (args.config.autoQuality) startAutoQuality(args.config)
        // The mic rides along only when the config says so AND the runtime
        // permission is there (audio.md: off by default; denial is never
        // cast-fatal, the UI states the fact).
        if (args.config.mic) {
            if (hasRecordAudioPermission()) startMicSession()
            else _micState.value = MicState.NeedsPermission
        }
        Log.i(TAG, "cast service started")
        return START_NOT_STICKY
    }

    /**
     * The mic's live on/off toggle (Phase8): on = build the `mic` pc on demand
     * (factory B, independent of the `media` pc — no renegotiation of the
     * cast); off = tear it down. The desktop status line follows via a fresh
     * `session-info`, which is display-only (webrtc.md).
     */
    private fun toggleMic() {
        if (session === null) return // no cast — nothing to toggle
        val currentMic = micSession
        if (currentMic !== null) {
            currentMic.stop()
            micSession = null
            startCastForeground(micOn = false)
            // The session's onState(Off) republishes; the summary needs the
            // flag flipped after it.
            sendSessionInfo(mic = false)
            return
        }
        if (!hasRecordAudioPermission()) {
            _micState.value = MicState.NeedsPermission
            Log.w(TAG, "mic toggle without RECORD_AUDIO — mic stays off")
            return
        }
        startMicSession()
    }

    private fun startMicSession() {
        val currentSignaling = signaling
        if (currentSignaling === null) return
        val newMic = MicCastSession(
            context = applicationContext,
            signaling = currentSignaling,
            onState = { micState -> _micState.value = micState },
        )
        micSession = newMic
        newMic.start()
        // Before the ADM's first mic read: the mic bit must be on the FGS
        // before the app can be backgrounded, or Android11+ mutes the mic.
        startCastForeground(micOn = true)
        sendSessionInfo(mic = true)
        Log.i(TAG, "mic session started")
    }

    /**
     * Re-send the display-only summary (webrtc.md) — the media session sends
     * it at build; this is the follow-up whenever the mic toggles mid-cast.
     * `gameAudio` mirrors the media session's semantics: "part of this cast".
     */
    private fun sendSessionInfo(mic: Boolean) {
        val currentSignaling = signaling ?: return
        val currentConfig = config ?: return
        val (width, height) = CaptureSize.scaleTo(currentConfig.longEdgePx, physicalWidth, physicalHeight)
        currentSignaling.sendSessionInfo(
            profile = currentConfig.profile,
            width = width,
            height = height,
            fps = currentConfig.fps,
            gameAudio = _gameAudioState.value != GameAudioState.Off,
            mic = mic,
        )
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Thermal monitoring for the running cast (Phase11, thermal.md):
     * the ladder is displayed on the home screen and logged (headroom +
     * battery temperature at [ThermalSource.SAMPLE_INTERVAL_MS]); Phase12's
     * auto quality reads the ladder through [_thermalState] each stats tick.
     */
    private fun startThermalMonitoring() {
        val monitor = ThermalMonitor(
            onState = { state -> _thermalState.value = state },
            onTransition = { old, new ->
                val snapshot = _thermalState.value
                Log.i(
                    TAG,
                    "thermal: $old → $new" +
                        ", headroom ${snapshot.headroom ?: "n/a"}" +
                        ", battery ${snapshot.batteryTempC?.let { "$it°C" } ?: "n/a"}",
                )
            },
        )
        thermalSource = ThermalSource(applicationContext, monitor).also { it.start() }
    }

    private fun stopThermalMonitoring() {
        thermalSource?.stop()
        thermalSource = null
        _thermalState.value = ThermalState()
    }

    /**
     * Auto quality (Phase12, thermal.md): the pure policy machine gets every
     * ~1 Hz sender sample (session thread) plus the latest thermal reading,
     * and announces changes — each one applied live on the media session,
     * mirrored into the desktop's display-only `session-info`, announced on
     * the cast notification, and published for the "This cast" line.
     */
    private fun startAutoQuality(config: CastConfig) {
        // The user's settings at cast start are the ceiling: auto quality
        // never goes above what they chose (thermal.md principle 2).
        val ceiling = QualityLevel(config.longEdgePx, config.fps, config.bitrateMinBps, config.bitrateMaxBps)
        adaptiveCeiling = ceiling
        adaptive = AdaptiveQualityController(ceiling = ceiling, onLevelChanged = { onQualityAdapted(it) })
        _adaptiveState.value = AdaptiveUiState(current = ceiling, ceiling = ceiling)
    }

    /** The policy's stats input — called from the media session thread. */
    private fun feedAutoQuality(sample: SenderSample) {
        val controller = adaptive ?: return
        controller.onTick(
            System.currentTimeMillis(),
            AdaptiveQualityController.StreamSample(
                framesEncoded = sample.framesEncoded,
                framesDropped = sample.framesDropped,
                fractionLost = sample.fractionLost,
                rttMs = sample.rttMs,
            ),
            _thermalState.value.status,
        )
    }

    private fun onQualityAdapted(change: AdaptiveQualityController.QualityChange) {
        Log.i(
            TAG,
            "adaptive quality: ${change.direction} (${change.reason}) — " +
                "${describe(change.from)} → ${describe(change.to)}",
        )
        session?.changeQuality(change.to.longEdgePx, change.to.fps, change.to.bitrateMinBps, change.to.bitrateMaxBps)
        // The summary follows the *live* targets: keep the per-cast config in
        // step so sendSessionInfo describes what is actually being sent. The
        // user's persisted settings are untouched — changes there are next-cast.
        config = config?.copy(
            profile = matchingProfile(change.to.longEdgePx, change.to.fps, change.to.bitrateMinBps, change.to.bitrateMaxBps)
                ?.label ?: PROFILE_CUSTOM,
            longEdgePx = change.to.longEdgePx,
            fps = change.to.fps,
            bitrateMinBps = change.to.bitrateMinBps,
            bitrateMaxBps = change.to.bitrateMaxBps,
        )
        sendSessionInfo(mic = micSession !== null)
        _adaptiveState.value = AdaptiveUiState(
            current = change.to,
            ceiling = adaptiveCeiling,
            lastChange = change,
        )
        announceQualityChange(change)
    }

    /** One INFO line per change is the policy's announcement duty (thermal.md). */
    private fun describe(level: QualityLevel): String =
        matchingProfile(level.longEdgePx, level.fps, level.bitrateMinBps, level.bitrateMaxBps)?.label
            ?: "custom ${level.longEdgePx}px/${level.fps}fps"

    /**
     * The user-visible notification of a transition (acceptance: transitions
     * are logged *and* user-visible): the ongoing cast notification updates
     * its text — IMPORTANCE_LOW, so it never buzzes, it just tells.
     */
    private fun announceQualityChange(change: AdaptiveQualityController.QualityChange) {
        val name = levelName(change.to)
        val text = when {
            change.direction == AdaptiveQualityController.Direction.DOWN &&
                change.reason == AdaptiveQualityController.Reason.THERMAL ->
                getString(R.string.adaptive_notification_lowered_thermal, name)

            change.direction == AdaptiveQualityController.Direction.DOWN ->
                getString(R.string.adaptive_notification_lowered_stream, name)

            change.reason == AdaptiveQualityController.Reason.USER ->
                getString(R.string.adaptive_notification_restored)

            else -> getString(R.string.adaptive_notification_raised, name)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(desktopName, text))
    }

    /** Localized profile name for a level — "your settings" for a tweaked top rung. */
    private fun levelName(level: QualityLevel): String = when (
        matchingProfile(level.longEdgePx, level.fps, level.bitrateMinBps, level.bitrateMaxBps)
    ) {
        QualityProfile.COOL -> getString(R.string.profile_cool)
        QualityProfile.BALANCED -> getString(R.string.profile_balanced)
        QualityProfile.PERFORMANCE -> getString(R.string.profile_performance)
        QualityProfile.SHARP -> getString(R.string.profile_sharp)
        null -> getString(R.string.adaptive_your_settings)
    }

    private fun stopAutoQuality() {
        adaptive = null
        adaptiveCeiling = null
        _adaptiveState.value = AdaptiveUiState()
    }

    /**
     * (Re-)declare the service's foreground type set. The `microphone` bit
     * rides along exactly while the mic session is on (CastForegroundTypes):
     * Android11+ silences a backgrounded app's mic unless its FGS carries the
     * type — found live on Android16 (mic went silent the moment the app was
     * backgrounded while screen + game audio kept streaming). Re-calling
     * startForeground with the same id rebuilds the same notification with the
     * new types; callers add the mic bit only with RECORD_AUDIO granted.
     */
    private fun startCastForeground(micOn: Boolean) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(desktopName),
            CastForegroundTypes.types(micOn, Build.VERSION.SDK_INT),
        )
    }

    /** First-class stop paths (mobile.md): simple user-facing message, details stay in logs. */
    private fun onFatal(failure: MediaCastSession.Failure) {
        val message = when (failure) {
            MediaCastSession.Failure.ProjectionRevoked -> getString(R.string.cast_stopped_projection_revoked)
            MediaCastSession.Failure.DesktopEnded -> getString(R.string.session_ended)
            MediaCastSession.Failure.ConnectionLost -> getString(R.string.cast_failed_connection_lost)
            is MediaCastSession.Failure.Error -> getString(R.string.cast_failed_generic)
        }
        Log.w(TAG, "cast ended: ${failure.logDetail()}")
        // Debug builds only: this ROM's logcat suppresses app logs (features/
        // game-audio.md), so the detail rides the message to stay diagnosable.
        val shown = if (BuildConfig.DEBUG && failure is MediaCastSession.Failure.Error) {
            "$message [debug: ${failure.detail}]"
        } else {
            message
        }
        _state.value = CastState.Failed(shown)
        endCast()
    }

    /**
     * The single exit door. Idempotent: every lifecycle edge funnels here, and
     * any of them may fire twice (e.g. `bye` racing a user stop). Nothing is
     * left behind — session, signaling socket, foreground notification.
     */
    private fun endCast() {
        session?.stop()
        session = null
        micSession?.stop()
        micSession = null
        stopThermalMonitoring()
        stopAutoQuality()
        // The service owns the pairing connection now (Phase6): ending the
        // cast ends the pairing session too — `bye` invalidates it on the
        // desktop (fresh QR there), and the mobile must scan again to cast.
        signaling?.disconnect()
        signaling = null
        config = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        _gameAudioState.value = GameAudioState.Off
        _micState.value = MicState.Off
        if (_state.value !is CastState.Failed) {
            _state.value = CastState.Idle
        }
        Log.i(TAG, "cast service stopped")
    }

    override fun onDestroy() {
        // The system can kill the service without another onStartCommand —
        // never leak the projection, the pcs, or the socket.
        session?.stop()
        session = null
        micSession?.stop()
        micSession = null
        stopThermalMonitoring()
        stopAutoQuality()
        signaling?.disconnect()
        signaling = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.cast_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(desktopName: String, textOverride: String? = null): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopCast = PendingIntent.getService(
            this,
            1,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.casting_to, desktopName))
            .setContentText(textOverride ?: getString(R.string.cast_notification_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.cast_notification_stop), stopCast)
            .build()
    }
}

/** Technical detail for logs only — never the user-facing message (AGENTS.md). */
private fun MediaCastSession.Failure.logDetail(): String = when (this) {
    MediaCastSession.Failure.ProjectionRevoked -> "projection revoked"
    MediaCastSession.Failure.DesktopEnded -> "desktop ended the session (bye)"
    MediaCastSession.Failure.ConnectionLost -> "connection to the desktop lost (ice/signaling)"
    is MediaCastSession.Failure.Error -> detail
}
