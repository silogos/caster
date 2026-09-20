package com.zerofriction.localcast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zerofriction.localcast.R
import com.zerofriction.localcast.config.CastConfig
import com.zerofriction.localcast.signaling.SignalingClient
import com.zerofriction.localcast.webrtc.MediaCastSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The cast pipeline's user-facing state, owned by [CastService] — the scan
 * screen renders it 1:1. Errors carry the simple user-facing message only
 * (AGENTS.md); technical detail lives in the service/session logs.
 */
sealed interface CastState {
    data object Idle : CastState
    data object Starting : CastState
    data object Casting : CastState
    data class Failed(val message: String) : CastState
}

/**
 * Minimal Phase5 foreground service (type `mediaProjection`). Why it exists
 * already — the roadmap puts the full CastService lifecycle in Phase6, but
 * Android 14+ requires a foreground service of type mediaProjection to be
 * running *before* the projection's virtual display is created; without it
 * `createVirtualDisplay` throws and no cast can run on the target device
 * (docs/architecture/mobile.md ordering constraint). So Phase5 ships the
 * smallest legal skeleton: startForeground → session, stop → clean teardown.
 * The full lifecycle matrix (rotation, background, permission revocation,
 * network loss, resource release) is Phase6 work.
 *
 * The session plumbing (signaling connection) still lives with the scan
 * screen (Phase3 decision); this service only owns projection + media. On
 * process death the cast does not auto-restart (START_NOT_STICKY) — Phase6.
 */
class CastService : Service() {

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.zerofriction.localcast.service.STOP"

        /** Start args travel via a holder — a Service Intent can only carry parceled values, not the live signaling client. Reworked in Phase6. */
        private var pendingStart: StartArgs? = null

        private val _state = MutableStateFlow<CastState>(CastState.Idle)
        val state: StateFlow<CastState> = _state.asStateFlow()

        fun start(context: Context, args: StartArgs) {
            if (pendingStart !== null) return // one cast at a time (v1 non-goal: multi-desktop)
            pendingStart = args
            _state.value = CastState.Starting
            ContextCompat.startForegroundService(context, Intent(context, CastService::class.java))
        }

        fun requestStop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }
    }

    /** Everything the service needs from the screen that started the cast. */
    data class StartArgs(
        val signaling: SignalingClient,
        val config: CastConfig,
        /** The consent result — single use; a new cast always re-prompts (mobile.md). */
        val projectionIntent: Intent,
        val physicalWidth: Int,
        val physicalHeight: Int,
    )

    private var session: MediaCastSession? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                endCast()
                return START_NOT_STICKY
            }
        }
        val args = pendingStart ?: run {
            // Stopped by the system with nothing pending — nothing to resume.
            endCast()
            return START_NOT_STICKY
        }
        pendingStart = null

        // Android 14+ ordering (mobile.md): foreground *before* the projection
        // starts; the consent was collected *before* the service started.
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
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
                    _state.value = CastState.Casting
                }
            },
        )
        session = newSession
        newSession.start(onFatal = { failure -> onFatal(failure) })
        Log.i(TAG, "cast service started")
        return START_NOT_STICKY
    }

    /** A first-class stop path (mobile.md): simple user-facing message, details stay in logs. */
    private fun onFatal(failure: MediaCastSession.Failure) {
        val message = when (failure) {
            MediaCastSession.Failure.ProjectionRevoked -> getString(R.string.cast_stopped_projection_revoked)
            is MediaCastSession.Failure.Error -> getString(R.string.cast_failed_generic)
        }
        Log.w(TAG, "cast ended: ${if (failure is MediaCastSession.Failure.Error) failure.detail else "projection revoked"}")
        _state.value = CastState.Failed(message)
        endCast()
    }

    private fun endCast() {
        session?.stop()
        session = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (_state.value !is CastState.Failed) {
            _state.value = CastState.Idle
        }
        Log.i(TAG, "cast service stopped")
    }

    override fun onDestroy() {
        // The system can kill the service without another onStartCommand —
        // never leak the projection (Phase6 makes this matrix exhaustive).
        session?.stop()
        session = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.cast_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            // Phase6 designs a proper launcher icon; this placeholder is the
            // minimum the system accepts.
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.cast_notification_title))
            .setContentText(getString(R.string.cast_notification_text))
            .setOngoing(true)
            .build()
}
