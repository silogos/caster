package com.zerofriction.localcast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.MainActivity
import com.zerofriction.localcast.R
import com.zerofriction.localcast.audio.GameAudioState
import com.zerofriction.localcast.config.CastConfig
import com.zerofriction.localcast.signaling.SignalingClient
import com.zerofriction.localcast.webrtc.MediaCastSession
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
        }
        val args = pendingStart ?: run {
            // Stopped by the system with nothing pending — nothing to resume.
            endCast()
            return START_NOT_STICKY
        }
        pendingStart = null
        signaling = args.signaling

        // Android 14+ ordering (mobile.md): foreground *before* the projection
        // starts; the consent was collected *before* the service started.
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(args.desktopName),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            },
        )

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
                }
            },
            onGameAudioState = { gameAudioState ->
                _gameAudioState.value = gameAudioState
            },
        )
        session = newSession
        newSession.start(onFatal = { failure -> onFatal(failure) })
        Log.i(TAG, "cast service started")
        return START_NOT_STICKY
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
        // The service owns the pairing connection now (Phase6): ending the
        // cast ends the pairing session too — `bye` invalidates it on the
        // desktop (fresh QR there), and the mobile must scan again to cast.
        signaling?.disconnect()
        signaling = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        _gameAudioState.value = GameAudioState.Off
        if (_state.value !is CastState.Failed) {
            _state.value = CastState.Idle
        }
        Log.i(TAG, "cast service stopped")
    }

    override fun onDestroy() {
        // The system can kill the service without another onStartCommand —
        // never leak the projection, the pc, or the socket.
        session?.stop()
        session = null
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

    private fun buildNotification(desktopName: String): Notification {
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
            .setContentText(getString(R.string.cast_notification_text))
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
