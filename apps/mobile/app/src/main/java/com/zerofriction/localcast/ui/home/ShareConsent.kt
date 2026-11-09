package com.zerofriction.localcast.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The share consent ladder (mobile.md), in order: RECORD_AUDIO (the mic's
 * permission, asked up front so a mid-cast toggle needs no prompt) →
 * POST_NOTIFICATIONS (Android 13+, the cast notification's visibility) →
 * the per-cast projection consent. Every grant but the last is **non-fatal**:
 * a denial just moves to the next step (a video-only cast beats no cast).
 */
internal enum class ShareStep { RECORD_AUDIO, NOTIFICATIONS, PROJECTION }

/**
 * The step after [after] (null = the ladder's start), skipping permissions
 * already held. A step is asked at most once per Share tap — the answer,
 * granted or denied, always advances — so a denial can never loop.
 */
internal fun nextShareStep(after: ShareStep?, recordAudioGranted: Boolean, notificationsGranted: Boolean): ShareStep =
    ShareStep.entries.first { step ->
        (after == null || step > after) && when (step) {
            ShareStep.RECORD_AUDIO -> !recordAudioGranted
            ShareStep.NOTIFICATIONS -> !notificationsGranted
            ShareStep.PROJECTION -> true
        }
    }

/**
 * Wires the ladder's three launchers once (it used to be written out three
 * times in HomeScreen). Returns the Share action; [onConsent] receives the
 * projection consent result — single use, never stored.
 */
@Composable
internal fun rememberShareScreenFlow(onConsent: (Intent) -> Unit): () -> Unit {
    val context = LocalContext.current
    // Set below; the permission callbacks run long after composition.
    var advance: (ShareStep?) -> Unit = {}
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val consentData = result.data
        if (result.resultCode == Activity.RESULT_OK && consentData != null) onConsent(consentData)
    }
    val recordAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        advance(ShareStep.RECORD_AUDIO)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        advance(ShareStep.NOTIFICATIONS)
    }
    advance = { after ->
        when (nextShareStep(after, isGranted(context, Manifest.permission.RECORD_AUDIO), notificationsGranted(context))) {
            ShareStep.RECORD_AUDIO -> {
                MicPermissionAsks.markAsked(context)
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            ShareStep.NOTIFICATIONS -> notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            ShareStep.PROJECTION ->
                projectionLauncher.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }
    return { advance(null) }
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Below Android 13 notifications need no runtime grant. */
private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
