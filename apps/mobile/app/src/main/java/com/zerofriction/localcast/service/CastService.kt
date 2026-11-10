package com.zerofriction.localcast.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.R
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.audio.MicState
import com.zerofriction.localcast.capture.CaptureSize
import com.zerofriction.localcast.config.CastConfig
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.config.PROFILE_CUSTOM
import com.zerofriction.localcast.config.matchingProfile
import com.zerofriction.localcast.signaling.ByeReasons
import com.zerofriction.localcast.signaling.SignalingClient
import com.zerofriction.localcast.thermal.ThermalState
import com.zerofriction.localcast.webrtc.MediaCastSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 *    fatal → Failed("The screen cast was stopped.") — the pairing session
 *    survives (retry needs no new scan)
 *  - `bye`/terminal signaling failure/ICE failure → session fatal → Failed —
 *    the pairing session is dead too (rescan)
 *  - user "Stop share screen" → clean stop, the pairing session returns to
 *    [ConnectedDesktop] (no `bye` — the desktop keeps the session; Home
 *    shows the connected hub)
 *  - user "Disconnect" → clean stop + `bye` (fresh QR on the desktop)
 *  - system kill of the service or process → `onDestroy` tears the session
 *    down; the cast does not auto-restart (`START_NOT_STICKY`) — projection
 *    consent cannot be reused after process death, so the user starts a fresh
 *    cast (new scan + new consent).
 */
class CastService : Service() {

    companion object {
        private const val TAG = "CastService"
        private const val ACTION_STOP = "com.zerofriction.localcast.service.STOP"
        private const val ACTION_DISCONNECT = "com.zerofriction.localcast.service.DISCONNECT"
        private const val ACTION_TERMINATE = "com.zerofriction.localcast.service.TERMINATE"
        private const val ACTION_TOGGLE_GAME_AUDIO = "com.zerofriction.localcast.service.TOGGLE_GAME_AUDIO"
        private const val ACTION_TOGGLE_MIC = "com.zerofriction.localcast.service.TOGGLE_MIC"
        private const val ACTION_SET_MIC_DEVICE = "com.zerofriction.localcast.service.SET_MIC_DEVICE"
        private const val EXTRA_MIC_DEVICE = "micDevice"
        private const val ACTION_RESTORE_QUALITY = "com.zerofriction.localcast.service.RESTORE_QUALITY"
        /** Debug builds only — sent by the debug source set's DebugIceRestartReceiver. */
        const val DEBUG_ACTION_ICE_RESTART = "com.zerofriction.localcast.service.DEBUG_ICE_RESTART"
        private const val ACTION_SET_LIVE_QUALITY = "com.zerofriction.localcast.service.SET_LIVE_QUALITY"
        private const val ACTION_TOGGLE_VIRTUAL_CAMERA = "com.zerofriction.localcast.service.TOGGLE_VIRTUAL_CAMERA"
        private const val ACTION_SET_AUDIO_ROUTING = "com.zerofriction.localcast.service.SET_AUDIO_ROUTING"
        private const val EXTRA_AUDIO_ROUTING_MODE = "audioRoutingMode"
        private const val EXTRA_LONG_EDGE = "longEdgePx"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_BITRATE_MIN = "bitrateMinBps"
        private const val EXTRA_BITRATE_MAX = "bitrateMaxBps"

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

        private val _micRouting = MutableStateFlow<MicRoutingUiState?>(null)
        val micRouting: StateFlow<MicRoutingUiState?> = _micRouting.asStateFlow()

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

        /**
         * What the running cast actually sends right now (designs/
         * mobile-app.html, the STATUS header's readout) — the cast-start
         * targets, then every change, whether the adaptive policy's or the
         * user's live pick ([requestSetLiveQuality]). Null without a cast;
         * unlike [adaptiveState] it is populated even when auto quality is
         * off — the readout states the stream, not the policy.
         */
        private val _liveQuality = MutableStateFlow<QualityLevel?>(null)
        val liveQuality: StateFlow<QualityLevel?> = _liveQuality.asStateFlow()

        /**
         * What libwebrtc actually picked as the video encoder for the
         * running cast ([SenderSample.encoderImplementation], ~1 Hz getStats)
         * — the read-only "Encoder in use" readout in the Advanced settings.
         * Null without a cast — never a faked value.
         */
        private val _encoderImplementation = MutableStateFlow<String?>(null)
        val encoderImplementation: StateFlow<String?> = _encoderImplementation.asStateFlow()

        /**
         * The virtual camera's UI state (ADR-007): driven by the desktop's
         * vc-status pushes only — Hidden (no card) until one arrives with
         * `available: true`. Reset to Hidden whenever the cast ends.
         */
        private val _vcState = MutableStateFlow(VirtualCameraUiState())
        val vcState: StateFlow<VirtualCameraUiState> = _vcState.asStateFlow()

        /**
         * The audio output routing mirror (proto 2): `known = false` until the
         * desktop's first status — the row stays hidden without it.
         */
        private val _audioRouting = MutableStateFlow(AudioRoutingUiState())
        val audioRouting: StateFlow<AudioRoutingUiState> = _audioRouting.asStateFlow()

        /** The notification's Stop action — the same clean stop as [requestStop]. */
        internal fun stopIntent(context: Context): Intent =
            Intent(context, CastService::class.java).setAction(ACTION_STOP)

        /** Turn the desktop's virtual camera on/off (no-op without a cast). */
        fun requestToggleVirtualCamera(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_TOGGLE_VIRTUAL_CAMERA))
        }

        /** Pick the desktop's audio output route (no-op without a cast). */
        fun requestSetAudioRouting(context: Context, mode: String) {
            context.startService(
                Intent(context, CastService::class.java)
                    .setAction(ACTION_SET_AUDIO_ROUTING)
                    .putExtra(EXTRA_AUDIO_ROUTING_MODE, mode),
            )
        }

        /**
         * Starts a cast with the handed-over pairing. Returns false when the
         * start is refused — the caller still owns [StartArgs.signaling] and
         * must hand it back ([ConnectedDesktop.takeBack]); a refused start
         * used to leave the live socket with no listener at all (review
         * 2026-11-09).
         */
        fun start(context: Context, args: StartArgs): Boolean {
            when (val refusal = startRefusal(pendingStart !== null, _state.value, args.signaling.state.value)) {
                null -> Unit
                StartRefusal.BUSY -> {
                    Log.w(TAG, "refusing to start: a cast is already starting or running")
                    return false
                }
                StartRefusal.DEAD_PAIRING -> {
                    Log.w(TAG, "refusing to start: signaling is ${args.signaling.state.value}")
                    _state.value = CastState.Failed(context.getString(R.string.cast_failed_generic))
                    return false
                }
            }
            pendingStart = args
            _state.value = CastState.Starting(args.desktopName)
            return try {
                ContextCompat.startForegroundService(context, Intent(context, CastService::class.java))
                true
            } catch (e: RuntimeException) {
                // A start the OS refuses (e.g. a background FGS start) must not
                // leave pendingStart set — it would block every later cast.
                Log.w(TAG, "cast service start refused by the OS: ${e.message}")
                pendingStart = null
                _state.value = CastState.Failed(context.getString(R.string.cast_failed_generic))
                false
            }
        }

        /**
         * "Stop share screen" (designs/mobile-app.html): stops the video (and
         * mic/thermal/auto-quality with it) but the pairing session stays
         * connected — [ConnectedDesktop] holds the socket, Home shows the
         * connected hub, and the next Share screen needs no new scan.
         */
        fun requestStop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }

        /**
         * "Disconnect" (ADR-006, refined 2026-10-30): stop the cast and close
         * the *connection* only — the saved session survives; the next Connect
         * is one tap and the desktop keeps its no-input stage.
         */
        fun requestDisconnect(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_DISCONNECT))
        }

        /**
         * "Terminate": stop the cast *and* delete the saved session — `bye
         * forget` revokes it on both sides (fresh QR on the desktop).
         */
        fun requestTerminate(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_TERMINATE))
        }

        /** Mute/unmute the game-audio track of the running cast (no-op without one). */
        fun requestToggleGameAudio(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_TOGGLE_GAME_AUDIO))
        }

        /** Turn the mic of the running cast on/off (no-op without one). */
        fun requestToggleMic(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_TOGGLE_MIC))
        }

        /**
         * Switch the LIVE mic input mid-cast (no-op without a recording mic
         * session — the caller persists the setting for the next start
         * itself; keeping the setting out of the service preserves the
         * "settings live in the store, the service reads fresh" rule).
         */
        fun requestSetMicDevice(context: Context, device: MicDeviceSource) {
            context.startService(
                Intent(context, CastService::class.java)
                    .setAction(ACTION_SET_MIC_DEVICE)
                    .putExtra(EXTRA_MIC_DEVICE, device.label),
            )
        }

        /** The user puts the cast back at their own settings (auto quality is reversible). */
        fun requestRestoreQuality(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_RESTORE_QUALITY))
        }

        /**
         * The user steers the RUNNING cast's quality (designs/
         * mobile-app.html, the quality console's Preset/Custom tabs): the
         * values apply live via the same path the adaptive policy uses, the
         * persisted settings are untouched — this is "until the cast ends",
         * not a settings change. Auto quality, when on, is **reset** onto
         * the new values (the controller is rebuilt with them as its
         * ceiling): the policy keeps running from the user's new choice.
         */
        fun requestSetLiveQuality(context: Context, level: QualityLevel) {
            context.startService(
                Intent(context, CastService::class.java)
                    .setAction(ACTION_SET_LIVE_QUALITY)
                    .putExtra(EXTRA_LONG_EDGE, level.longEdgePx)
                    .putExtra(EXTRA_FPS, level.fps)
                    .putExtra(EXTRA_BITRATE_MIN, level.bitrateMinBps)
                    .putExtra(EXTRA_BITRATE_MAX, level.bitrateMaxBps),
            )
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

    /** The cast's modules — each owns one concern for exactly the cast's lifetime (AGENTS.md: no CastManager). */
    private var mic: MicController? = null
    private val notifications by lazy { CastNotifications(this) }
    private val wifiLock by lazy { CastWifiLock(applicationContext) }
    private val thermalWatch by lazy { CastThermalWatch(applicationContext, _thermalState) }
    private val desktopControls = DesktopControls(_vcState, _audioRouting)
    private val quality = LiveQualityCoordinator(
        liveQuality = _liveQuality,
        adaptiveState = _adaptiveState,
        encoderImplementation = _encoderImplementation,
        thermalStatus = { _thermalState.value.status },
        applyLevel = { level -> applyQualityLevel(level) },
        onAdapted = { change -> notifications.announceQualityChange(desktopName, change) },
    )

    /** Service-thread hops (capture-death degrade arrives on a session thread). */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Kept from [StartArgs] for the `session-info` resends; follows live quality changes. */
    private var config: CastConfig? = null
    private var physicalWidth: Int = 0
    private var physicalHeight: Int = 0

    /** Kept from [StartArgs] for the notification the FGS type updates rebuild. */
    private var desktopName: String = ""

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                endCast(keepSession = true)
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                // Connection-level only (ADR-006, refined 2026-10-30): the
                // saved session survives; the desktop keeps its no-input stage.
                endCast(keepSession = false, forget = false)
                return START_NOT_STICKY
            }
            ACTION_TERMINATE -> {
                // Session-level: `bye forget` + the saved desktop cleared on
                // both sides — the next pairing is a fresh QR scan.
                endCast(keepSession = false, forget = true)
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
                if (session !== null) mic?.toggle() // no cast — nothing to toggle
                return START_NOT_STICKY
            }
            ACTION_SET_MIC_DEVICE -> {
                val label = intent.getStringExtra(EXTRA_MIC_DEVICE)
                val device = MicDeviceSource.fromLabel(label ?: "")
                if (device !== null) {
                    config = config?.copy(micDevice = device)
                    mic?.setDevice(device)
                }
                return START_NOT_STICKY
            }
            ACTION_RESTORE_QUALITY -> {
                quality.restore()
                return START_NOT_STICKY
            }
            // Debug builds only: an ICE restart on demand, to exercise the
            // recovery path (ADR-019) without breaking the network.
            DEBUG_ACTION_ICE_RESTART -> {
                if (BuildConfig.DEBUG) session?.debugRestartIce()
                return START_NOT_STICKY
            }
            ACTION_SET_LIVE_QUALITY -> {
                val longEdgePx = intent.getIntExtra(EXTRA_LONG_EDGE, 0)
                val fps = intent.getIntExtra(EXTRA_FPS, 0)
                val bitrateMinBps = intent.getIntExtra(EXTRA_BITRATE_MIN, 0)
                val bitrateMaxBps = intent.getIntExtra(EXTRA_BITRATE_MAX, 0)
                if (longEdgePx > 0 && fps > 0 && bitrateMinBps > 0 && bitrateMaxBps > 0) {
                    if (session !== null) quality.applyUserLevel(QualityLevel(longEdgePx, fps, bitrateMinBps, bitrateMaxBps))
                } else {
                    Log.w(TAG, "set-live-quality with incomplete extras — ignored")
                }
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_VIRTUAL_CAMERA -> {
                desktopControls.toggleVirtualCamera()
                return START_NOT_STICKY
            }
            ACTION_SET_AUDIO_ROUTING -> {
                desktopControls.setAudioRouting(intent.getStringExtra(EXTRA_AUDIO_ROUTING_MODE))
                return START_NOT_STICKY
            }
        }
        val args = pendingStart ?: run {
            // Stopped by the system with nothing pending — nothing to resume.
            endCast(keepSession = false)
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
        notifications.ensureChannel()
        startCastForeground(micOn = false)
        wifiLock.acquire()

        val newSession = MediaCastSession(
            context = applicationContext,
            signaling = args.signaling,
            config = args.config,
            projectionPermissionIntent = args.projectionIntent,
            physicalWidth = args.physicalWidth,
            physicalHeight = args.physicalHeight,
            onState = { sessionState ->
                if (sessionState == MediaCastSession.State.STREAMING) {
                    _state.value = CastState.Casting(args.desktopName, System.currentTimeMillis())
                    // The media session's own summary rides its build; this
                    // one is the authoritative last word — by streaming time
                    // the mic decision (config + permission) is settled, so
                    // the flag can't be stale.
                    sendSessionInfo()
                }
            },
            onGameAudioState = { gameAudioState ->
                _gameAudioState.value = gameAudioState
            },
            onVideoStats = { sample, _ -> quality.onSample(sample) },
            // The display-only summary follows every live capture-format
            // change (rotation, quality step) — the session owns the truth.
            onCaptureFormat = { _, _, _ -> sendSessionInfo() },
        )
        session = newSession
        newSession.start(onFatal = { failure -> onFatal(failure) })
        thermalWatch.start()
        desktopControls.bind(args.signaling)
        // The STATUS readout's baseline is the user's settings; auto quality
        // rides the cast only when the user left it on (config module: the
        // switch is a cast setting like any other — next-cast semantics).
        quality.start(
            QualityLevel(args.config.longEdgePx, args.config.fps, args.config.bitrateMinBps, args.config.bitrateMaxBps),
            autoQuality = args.config.autoQuality,
        )
        val newMic = MicController(
            context = applicationContext,
            signaling = args.signaling,
            selectedDevice = args.config.micDevice,
            mainHandler = mainHandler,
            micState = _micState,
            micRouting = _micRouting,
            onMicOnChanged = { on ->
                startCastForeground(micOn = on)
                sendSessionInfo()
            },
        )
        mic = newMic
        // The mic rides along only when the config says so AND the runtime
        // permission is there (audio.md: off by default).
        if (args.config.mic) newMic.startForCast()
        Log.i(TAG, "cast service started")
        return START_NOT_STICKY
    }

    /**
     * A quality change — the policy's or the user's live pick — applied on
     * the media session; the per-cast config follows so `session-info`
     * describes what is actually sent. The user's persisted settings are
     * untouched — changes there are next-cast.
     */
    private fun applyQualityLevel(level: QualityLevel) {
        session?.changeQuality(level.longEdgePx, level.fps, level.bitrateMinBps, level.bitrateMaxBps)
        config = config?.copy(
            profile = matchingProfile(level.longEdgePx, level.fps, level.bitrateMinBps, level.bitrateMaxBps)?.label
                ?: PROFILE_CUSTOM,
            longEdgePx = level.longEdgePx,
            fps = level.fps,
            bitrateMinBps = level.bitrateMinBps,
            bitrateMaxBps = level.bitrateMaxBps,
        )
        sendSessionInfo()
    }

    /**
     * Re-send the display-only summary (webrtc.md) — the media session sends
     * it at build; this is the follow-up whenever the mic toggles or the
     * capture format changes live (rotation, quality step). Size/fps come
     * from the session's *live* capture format when it has one; the frozen
     * recompute only covers the pre-cast window. `gameAudio` mirrors the
     * media session's semantics: "part of this cast".
     */
    private fun sendSessionInfo() {
        val currentSignaling = signaling ?: return
        val currentConfig = config ?: return
        val live = session?.currentCaptureFormat
        val (width, height) = live?.let { it.width to it.height }
            ?: CaptureSize.scaleTo(currentConfig.longEdgePx, physicalWidth, physicalHeight)
        val fps = live?.fps ?: currentConfig.fps
        currentSignaling.sendSessionInfo(
            profile = currentConfig.profile,
            width = width,
            height = height,
            fps = fps,
            gameAudio = _gameAudioState.value != GameAudioState.Off,
            mic = mic?.isOn == true,
        )
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
            CastNotifications.NOTIFICATION_ID,
            notifications.build(desktopName),
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
        // Every cast failure keeps the session (ADR-006): the saved entry
        // survives AND the socket is parked, not byed — a failure is not a
        // user decision, and the desktop must keep the session for the
        // one-tap reconnect. (Found live 2026-11-01: an ICE failure mid-game
        // ran the disconnect path and sent `bye user-ended` over a perfectly
        // healthy socket — the desktop regenerated a fresh QR for nothing
        // and the log filled with misleading "user ended" sequences.)
        endCast(keepSession = true)
    }

    /** Everything the cast holds except the signaling socket — shared by [endCast] and [onDestroy]. */
    private fun releaseCastResources() {
        session?.stop()
        session = null
        mic?.stop()
        mic = null
        thermalWatch.stop()
        wifiLock.release()
        quality.stop()
        desktopControls.unbind()
    }

    /**
     * The single exit door. Idempotent: every lifecycle edge funnels here, and
     * any of them may fire twice (e.g. `bye` racing a user stop). Nothing is
     * left behind — session, foreground notification, and (unless
     * [keepSession]) the pairing socket.
     *
     * [keepSession] is the "Stop share screen" path: the signaling socket
     * goes back to [ConnectedDesktop] — no `bye`, the desktop keeps the
     * authorized session, Home shows the connected hub. `false` (Disconnect,
     * a dead session, a system kill) sends `bye`/closes the socket and the
     * user rescans.
     */
    private fun endCast(keepSession: Boolean, forget: Boolean = false) {
        releaseCastResources()
        val currentSignaling = signaling
        val currentDesktopName = desktopName
        signaling = null
        if (keepSession && currentSignaling !== null) {
            ConnectedDesktop.adopt(currentSignaling, currentDesktopName)
        } else if (forget) {
            // "Terminate": revoke the persistent session — `bye forget` on the
            // cast's socket, and [ConnectedDesktop.terminate] handles a socket
            // it still owns plus the store clear on both sides.
            currentSignaling?.disconnect(ByeReasons.FORGET)
            ConnectedDesktop.terminate()
        } else {
            // "Disconnect" or a dead session (desktop ended it, connection
            // lost, system kill): close the socket — bying it when this side
            // still owns a live one (found live 2026-10-30: the no-cast
            // Disconnect used the dead-socket path, so no `bye` left the
            // phone, the desktop stayed "connected", and the next Connect hit
            // the leaked authorized socket as `busy`) — but the saved session
            // always stays: Home shows a one-tap Connect, never a rescan.
            currentSignaling?.disconnect()
            ConnectedDesktop.disconnect()
        }
        config = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        _gameAudioState.value = GameAudioState.Off
        _micState.value = MicState.Off
        if (_state.value !is CastState.Failed) {
            _state.value = CastState.Idle
        }
        Log.i(TAG, "cast service stopped (keepSession=$keepSession)")
    }

    override fun onDestroy() {
        // The system can kill the service without another onStartCommand —
        // never leak the projection, the pcs, or the socket. A clean
        // [endCast] nulls `signaling` before stopSelf, so this only fires
        // on a genuine system kill — the session dies with the cast.
        releaseCastResources()
        signaling?.let { current ->
            signaling = null
            current.disconnect()
            // The process is dying; the saved session (ADR-006) survives it —
            // a relaunch offers the one-tap Connect.
            ConnectedDesktop.transitionOffline()
        }
        super.onDestroy()
    }
}

/** Technical detail for logs only — never the user-facing message (AGENTS.md). */
private fun MediaCastSession.Failure.logDetail(): String = when (this) {
    MediaCastSession.Failure.ProjectionRevoked -> "projection revoked"
    MediaCastSession.Failure.DesktopEnded -> "desktop ended the session (bye)"
    MediaCastSession.Failure.ConnectionLost -> "connection to the desktop lost (ice/signaling)"
    is MediaCastSession.Failure.Error -> detail
}

/** Why [CastService.start] refuses a start. */
internal enum class StartRefusal {
    /** One cast at a time (v1 non-goal: multi-desktop) — one is starting or running. */
    BUSY,

    /**
     * The handover must carry a *live* pairing (found live, 2026-09-20: a cast
     * started with an already-closed client hangs in Starting forever — no
     * Authorized/Failed event ever arrives on a dead socket). AUTHORIZED is the
     * normal case; RECONNECTING is allowed — the session negotiates when
     * re-auth succeeds, and expiry surfaces as a Failed event.
     */
    DEAD_PAIRING,
}

internal fun startRefusal(
    pending: Boolean,
    castState: CastState,
    signaling: SignalingClient.State,
): StartRefusal? = when {
    pending || castState is CastState.Starting || castState is CastState.Casting -> StartRefusal.BUSY
    signaling != SignalingClient.State.AUTHORIZED && signaling != SignalingClient.State.RECONNECTING -> StartRefusal.DEAD_PAIRING
    else -> null
}
