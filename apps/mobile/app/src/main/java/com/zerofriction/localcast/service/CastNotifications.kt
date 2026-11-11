package com.zerofriction.localcast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zerofriction.localcast.MainActivity
import com.zerofriction.localcast.R
import com.zerofriction.localcast.adaptive.AdaptiveQualityController
import com.zerofriction.localcast.adaptive.QualityLevel
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.config.matchingProfile

/**
 * The ongoing cast notification (mobile.md): one IMPORTANCE_LOW channel, one
 * notification id the foreground service re-declares, a Stop action, and the
 * user-visible text of auto-quality transitions (thermal.md acceptance:
 * transitions are logged *and* user-visible — the text updates, it never buzzes).
 */
internal class CastNotifications(private val context: Context) {

    fun ensureChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.cast_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun build(desktopName: String, textOverride: String? = null): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopCast = PendingIntent.getService(
            context,
            1,
            CastService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(context.getString(R.string.casting_to, desktopName))
            .setContentText(textOverride ?: context.getString(R.string.cast_notification_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.cast_notification_stop), stopCast)
            .build()
    }

    /** The ongoing notification's text follows an auto-quality transition. */
    fun announceQualityChange(desktopName: String, change: AdaptiveQualityController.QualityChange) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(desktopName, qualityChangeNotification(context, change)))
    }

    companion object {
        private const val CHANNEL_ID = "cast"
        const val NOTIFICATION_ID = 1
    }
}
