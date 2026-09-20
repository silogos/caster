package com.zerofriction.localcast.ui.pairing

import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Live camera preview that decodes QR codes on-device with ML Kit (bundled
 * barcode model — no network dependency; Phase3 scanner decision). Only
 * QR-format barcodes are requested. The first decoded payload wins.
 */
@Composable
fun QrCamera(
    onQrText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    // Holder so the DisposableEffect can unbind the provider acquired in LaunchedEffect.
    val cameraProviderRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    LaunchedEffect(lifecycleOwner) {
        val provider = awaitCameraProvider(context)
        cameraProviderRef[0] = provider

        val preview = Preview.Builder().build().also { preview ->
            preview.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
            analyzeFrame(imageProxy, scanner, onQrText)
        }

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            cameraProviderRef[0]?.unbindAll()
            scanner.close()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

/** Blocking-safe way to obtain the camera provider from a coroutine. */
private suspend fun awaitCameraProvider(context: android.content.Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resumeWith(Result.success(future.get()))
                } catch (error: Throwable) {
                    continuation.resumeWith(Result.failure(error))
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

@OptIn(ExperimentalGetImage::class)
private fun analyzeFrame(
    imageProxy: androidx.camera.core.ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onQrText: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
        .addOnSuccessListener { barcodes -> barcodes.firstOrNull()?.rawValue?.let(onQrText) }
        .addOnCompleteListener { imageProxy.close() }
}
