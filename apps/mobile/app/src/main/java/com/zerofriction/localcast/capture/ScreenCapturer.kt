package com.zerofriction.localcast.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * The project's MediaProjection capturer — a faithful port of libwebrtc's
 * `ScreenCapturerAndroid` with the ONE difference Android14 forces (found
 * live in the Phase14 device session, via a SecurityException crash):
 * changing the capture format **resizes the one virtual display**
 * (`VirtualDisplay.resize`) instead of releasing it and calling
 * `MediaProjection.createVirtualDisplay` again — Android 14+ forbids a
 * second `createVirtualDisplay` on one projection instance, which the stock
 * capturer triggers on every rotation and every live quality step.
 *
 * Everything else mirrors the stock class (libwebrtc 1.3.8, BSD-licensed):
 * frames flow through the injected `SurfaceTextureHelper` to the
 * `CapturerObserver`; the projection callback rides the helper's thread;
 * `stopCapture` tears the display and the projection down. Rotation and the
 * adaptive quality steps call [changeCaptureFormat]; both then survive on
 * Android 14+ because the display is only ever created once.
 */
class ScreenCapturer(
    private val mediaProjectionPermissionResultData: Intent,
    private val mediaProjectionCallback: MediaProjection.Callback,
) : VideoCapturer, VideoSink {

    /** Same public surface as the stock class — the game-audio source reuses the projection. */
    var mediaProjection: MediaProjection? = null
        private set

    private var width = 0
    private var height = 0
    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var isDisposed = false

    private fun checkNotDisposed() {
        check(!isDisposed) { "capturer is disposed." }
    }

    @Synchronized
    override fun initialize(
        newSurfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        newCapturerObserver: CapturerObserver,
    ) {
        checkNotDisposed()
        checkNotNull(newCapturerObserver) { "capturerObserver not set." }
        checkNotNull(newSurfaceTextureHelper) { "surfaceTextureHelper not set." }
        capturerObserver = newCapturerObserver
        surfaceTextureHelper = newSurfaceTextureHelper
        mediaProjectionManager =
            applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    @Synchronized
    override fun startCapture(newWidth: Int, newHeight: Int, ignoredFramerate: Int) {
        checkNotDisposed()
        width = newWidth
        height = newHeight
        val newHelper = checkNotNull(surfaceTextureHelper)
        val projectionManager = checkNotNull(mediaProjectionManager)
        mediaProjection =
            projectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionPermissionResultData)
        // Let the MediaProjection callback use the SurfaceTextureHelper thread.
        mediaProjection?.registerCallback(mediaProjectionCallback, newHelper.handler)
        createVirtualDisplay()
        capturerObserver?.onCapturerStarted(true)
        newHelper.startListening(this)
    }

    @Synchronized
    override fun stopCapture() {
        checkNotDisposed()
        val newHelper = checkNotNull(surfaceTextureHelper)
        ThreadUtils.invokeAtFrontUninterruptibly(newHelper.handler) {
            newHelper.stopListening()
            capturerObserver?.onCapturerStopped()
            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.let { projection ->
                // Unregister the callback before stopping, otherwise the callback
                // recursively calls this method.
                projection.unregisterCallback(mediaProjectionCallback)
                projection.stop()
            }
            mediaProjection = null
        }
    }

    @Synchronized
    override fun dispose() {
        isDisposed = true
    }

    /**
     * Changes the output video format — used for rotation and the adaptive
     * quality steps. The one virtual display is resized in place (Android
     * 14+ single-capture rule, see the class docs); the texture size moves
     * so incoming frames arrive at the new dimensions. Runs the resize on
     * the SurfaceTextureHelper thread, serialized with frame processing,
     * same as the stock class.
     */
    @Synchronized
    override fun changeCaptureFormat(newWidth: Int, newHeight: Int, ignoredFramerate: Int) {
        checkNotDisposed()
        width = newWidth
        height = newHeight
        val currentDisplay = virtualDisplay ?: return // stopped — startCapture will size it
        val newHelper = checkNotNull(surfaceTextureHelper)
        ThreadUtils.invokeAtFrontUninterruptibly(newHelper.handler) {
            newHelper.setTextureSize(width, height)
            currentDisplay.resize(width, height, VIRTUAL_DISPLAY_DPI)
        }
    }

    private fun createVirtualDisplay() {
        val newHelper = checkNotNull(surfaceTextureHelper)
        val projection = checkNotNull(mediaProjection)
        newHelper.setTextureSize(width, height)
        virtualDisplay = projection.createVirtualDisplay(
            "ZFC_ScreenCapture",
            width,
            height,
            VIRTUAL_DISPLAY_DPI,
            DISPLAY_FLAGS,
            Surface(newHelper.surfaceTexture),
            null, // callback
            null, // callback handler
        )
    }

    /** Runs on the SurfaceTextureHelper's internal looper thread. */
    override fun onFrame(frame: VideoFrame) {
        capturerObserver?.onFrameCaptured(frame)
    }

    override fun isScreencast(): Boolean = true

    companion object {
        private const val DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        /** DPI for the VirtualDisplay — does not seem to matter for us (stock class value). */
        private const val VIRTUAL_DISPLAY_DPI = 400
    }
}
