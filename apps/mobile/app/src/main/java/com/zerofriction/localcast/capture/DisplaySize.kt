package com.zerofriction.localcast.capture

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * The physical display size in px (mobile.md module `capture`). API 30+ has
 * window metrics that include system bars; on 29 the legacy real metrics are
 * the closest equivalent. Read LIVE wherever it matters: the cast-start
 * snapshot goes stale the moment the device rotates — the stock
 * ScreenCapturerAndroid does NOT follow rotation on its own (found live in
 * the Phase14 device session; MediaCastSession re-applies the capture format
 * on every display change).
 */
object DisplaySize {

    fun physicalPx(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }
}
