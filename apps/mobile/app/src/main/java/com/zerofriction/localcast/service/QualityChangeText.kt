package com.zerofriction.localcast.service

import android.content.Context
import com.zerofriction.localcast.R
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.Direction
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.QualityChange
import com.zerofriction.localcast.adaptive.AdaptiveQualityController.Reason
import com.zerofriction.localcast.adaptive.QualityLevel

/*
 * A step of the cast's quality in plain words (ADR-025): what was lowered,
 * why, and that it comes back — for Home's STATUS row and the ongoing
 * notification. The thresholds and holds stay in the logs (AGENTS.md).
 */

/** "1280 px", "30 fps", or both when a restore undid two different steps. */
internal fun steppedTo(context: Context, change: QualityChange): String {
    val parts = buildList {
        if (change.to.longEdgePx != change.from.longEdgePx) add(context.getString(R.string.settings_px, change.to.longEdgePx))
        if (change.to.fps != change.from.fps) add(context.getString(R.string.settings_fps_value, change.to.fps))
    }
    return parts.joinToString(" · ").ifEmpty { context.getString(R.string.settings_px, change.to.longEdgePx) }
}

/** The STATUS row: "Lowered to 1280 px: the phone is too hot. Comes back once it cools." */
internal fun qualityChangeLine(context: Context, change: QualityChange, belowProfile: Boolean): String {
    val to = steppedTo(context, change)
    return when {
        change.direction == Direction.DOWN -> context.getString(R.string.adaptive_lowered, to)
        change.reason == Reason.USER -> context.getString(R.string.adaptive_restored, to)
        belowProfile -> context.getString(R.string.adaptive_raised_partly, to)
        else -> context.getString(R.string.adaptive_raised, to)
    }
}

/** The ongoing notification: one short line. */
internal fun qualityChangeNotification(context: Context, change: QualityChange): String {
    val to = steppedTo(context, change)
    return when {
        change.direction == Direction.DOWN -> context.getString(R.string.adaptive_notification_lowered, to)
        change.reason == Reason.USER -> context.getString(R.string.adaptive_notification_restored)
        else -> context.getString(R.string.adaptive_notification_raised, to)
    }
}

/** Whether [level] is below the profile's own values on either dimension. */
internal fun QualityLevel.isBelow(profile: QualityLevel): Boolean =
    longEdgePx < profile.longEdgePx || fps < profile.fps
