package com.zerofriction.localcast.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerofriction.localcast.BuildConfig
import com.zerofriction.localcast.R
import com.zerofriction.localcast.pairing.DeviceIdentity
import com.zerofriction.localcast.pairing.KeystoreTrustVault
import com.zerofriction.localcast.pairing.SavedDesktops
import com.zerofriction.localcast.pairing.applyGrant
import com.zerofriction.localcast.pairing.PairingClient
import com.zerofriction.localcast.pairing.PairingError
import com.zerofriction.localcast.pairing.defaultSignalingScheduler
import com.zerofriction.localcast.pairing.defaultTransportFactory
import com.zerofriction.localcast.service.ConnectedDesktop
import com.zerofriction.localcast.ui.theme.CastOnSurface
import com.zerofriction.localcast.ui.theme.CastPrimary
import com.zerofriction.localcast.ui.theme.LocalCastTheme

/**
 * The launch screen (designs/mobile-app.html): the app opens on the scanner —
 * a Telegram/WhatsApp-style full-bleed view (camera, dimmed scrim with a
 * rounded-square cutout, corner brackets, animated scan line, torch). A
 * successful pairing is handed to [ConnectedDesktop] — the app-scoped session
 * holder the Home hub renders — and [onPaired] navigates to Home. No cast can
 * be started here: the share trigger lives on Home. The failure states stay
 * above the still-live scanner (pairing.md §Failure modes) — friendly icon +
 * mapped plain words, codes in logs (AGENTS.md).
 */
@Composable
fun ScanScreen(
    onPaired: () -> Unit,
    viewModel: ScanViewModel = viewModel(factory = scanViewModelFactory()),
) {
    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()

    // Pairing succeeded → the session moves to ConnectedDesktop and Home
    // takes over. The socket handover must happen before navigation — the
    // hub (and the share flow) reads it from there.
    LaunchedEffect(pairingState) {
        val connected = pairingState as? PairingClient.State.Connected
        if (connected !== null) {
            val signaling = viewModel.releaseSignalingClient()
            if (signaling !== null) {
                ConnectedDesktop.adopt(signaling, connected.desktopName)
                onPaired()
            }
        }
    }

    ScanContent(
        pairingState = pairingState,
        onQrScanned = viewModel::onQrScanned,
        onManualPayloadSubmit = viewModel::onManualPayloadSubmit,
    )
}

/**
 * The scan VM's pairing machine needs the app context for the persistent
 * device identity (ADR-005, `hello.deviceId`) and — since ADR-006 — for the
 * saved-desktop store its grants are written into. Injected here like the
 * settings screen injects its store; the VM itself stays JVM-testable.
 */
@Composable
private fun scanViewModelFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                val store = SavedDesktops(appContext, KeystoreTrustVault())
                val pairing = PairingClient(
                    ::defaultTransportFactory,
                    ::defaultSignalingScheduler,
                    ::defaultUserAgent,
                    deviceIdProvider = { DeviceIdentity.id(appContext) },
                    onDesktopIdentity = { name, grant -> store.applyGrant(grant, name) },
                )
                return ScanViewModel(pairing) as T
            }
        }
    }
}

@Composable
fun ScanContent(
    pairingState: PairingClient.State,
    onQrScanned: (String) -> Unit,
    onManualPayloadSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    Scaffold { innerPadding ->
        when (val state = pairingState) {
            // The Phase13 intermediate step, unchanged: the QR decoded, a
            // desktop is being paired with. Success navigates to Home.
            PairingClient.State.Connecting, PairingClient.State.Authenticating -> {
                PairingProgress(Modifier.fillMaxSize().padding(innerPadding))
            }

            is PairingClient.State.Reconnecting -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = state.desktopName?.let { stringResource(R.string.reconnecting_to, it) }
                            ?: stringResource(R.string.reconnecting),
                        fontSize = 15.sp,
                    )
                }
            }

            // Scanner states: with the camera granted, the full-bleed scanner
            // owns the whole screen (failure messages ride in its status pill).
            else -> {
                if (hasCameraPermission) {
                    var torchOn by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        QrCamera(
                            onQrText = onQrScanned,
                            torchEnabled = torchOn,
                            modifier = Modifier.fillMaxSize(),
                        )
                        ScannerOverlay(
                            statusIcon = when (state) {
                                is PairingClient.State.Failed -> Icons.Filled.Warning
                                PairingClient.State.Ended -> Icons.Filled.Info
                                else -> null
                            },
                            statusText = when (state) {
                                is PairingClient.State.Failed -> pairingErrorMessage(state.error)
                                PairingClient.State.Ended -> stringResource(R.string.session_ended)
                                else -> null
                            },
                            statusTint = when (state) {
                                is PairingClient.State.Failed -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            torchOn = torchOn,
                            onTorchToggle = { torchOn = !torchOn },
                        )
                        if (BuildConfig.DEBUG) {
                            ManualPayloadInput(
                                onSubmit = onManualPayloadSubmit,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 88.dp)
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                            )
                        }
                    }
                } else {
                    // No camera permission yet: the pre-camera state stays the
                    // classic centered layout (designs/mobile-app.html) — the
                    // QR glyph marks what the camera will be for.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_qr_code),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.scan_title),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                        when (state) {
                            is PairingClient.State.Failed -> StatusMessage(
                                icon = Icons.Filled.Warning,
                                message = pairingErrorMessage(state.error),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            PairingClient.State.Ended -> StatusMessage(
                                icon = Icons.Filled.Info,
                                message = stringResource(R.string.session_ended),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            else -> {}
                        }
                        Text(
                            text = stringResource(R.string.camera_permission_needed),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
                        )
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.grant_camera))
                        }
                        if (BuildConfig.DEBUG) {
                            ManualPayloadInput(
                                onSubmit = onManualPayloadSubmit,
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The scanner's chrome over the live camera (the port of the prototype's
 * `.scan-full`): a dimmed scrim with a rounded-square cutout, four corner
 * brackets, an animated scan line, the caption (or the failure/ended status
 * pill in its place), and the torch toggle. Geometry constants follow the
 * prototype: cutout ≈ 63% of the shorter screen side, 26dp corner radius,
 * 3dp brackets, 2.4s sweep.
 */
@Composable
private fun ScannerOverlay(
    statusIcon: ImageVector?,
    statusText: String?,
    statusTint: Color,
    torchOn: Boolean,
    onTorchToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The cutout's rect in canvas-local coordinates — measured from the
    // (invisible) stage placeholder so text layout below it can wrap freely.
    var cutout by remember { mutableStateOf<Rect?>(null) }
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

    val sweep = rememberInfiniteTransition(label = "scan-sweep")
    val sweepProgress by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan-sweep-progress",
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = minOf(maxWidth, maxHeight) * 0.63f

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { canvasOrigin = it.positionInRoot() }
                // Offscreen so BlendMode.Clear punches a real hole in the scrim.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            val hole = cutout ?: return@Canvas
            val bracket = 3.dp.toPx()
            val holeRect = Rect(
                left = hole.left - bracket,
                top = hole.top - bracket,
                right = hole.right + bracket,
                bottom = hole.bottom + bracket,
            )
            // Scrim-with-hole: everything dimmed except the cutout.
            drawRect(Color.Black.copy(alpha = 0.55f))
            drawRoundRect(
                color = Color.Transparent,
                topLeft = holeRect.topLeft,
                size = holeRect.size,
                cornerRadius = CornerRadius(26.dp.toPx()),
                blendMode = BlendMode.Clear,
            )
            drawBrackets(holeRect)
            drawScanLine(holeRect, sweepProgress)
        }

        // Title over the scrim (the prototype's .scan-title).
        Text(
            text = stringResource(R.string.scan_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 22.dp),
        )

        // The stage (cutout) + caption group, centered as one block.
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(side)
                    .onGloballyPositioned { coordinates ->
                        cutout = coordinates.boundsInRoot().translate(-canvasOrigin)
                    },
            )
            if (statusText != null && statusIcon != null) {
                Row(
                    modifier = Modifier
                        .padding(top = 26.dp)
                        .widthIn(max = 330.dp)
                        .background(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusTint,
                        modifier = Modifier.height(20.dp),
                    )
                    Text(text = statusText, fontSize = 13.sp, color = statusTint)
                }
            } else {
                Text(
                    text = stringResource(R.string.scan_hint),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 26.dp)
                        .widthIn(max = 300.dp),
                )
            }
        }

        // Torch (the prototype's .torch): one icon; the fill state carries on/off.
        OutlinedButton(
            onClick = onTorchToggle,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp)
                .size(48.dp),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (torchOn) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (torchOn) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
                },
                contentColor = if (torchOn) {
                    MaterialTheme.colorScheme.background
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_flashlight),
                contentDescription = stringResource(R.string.scan_torch),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** The four L-shaped cutout corners (the prototype's .scan-c). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBrackets(hole: Rect) {
    val color = CastOnSurface // drawn over the feed, outside the themed layer
    val stroke = 3.dp.toPx()
    val length = hole.width * 0.10f
    val cap = StrokeCap.Round

    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x, y), Offset(x + dx * length, y), stroke, cap)
        drawLine(color, Offset(x, y), Offset(x, y + dy * length), stroke, cap)
    }
    corner(hole.left, hole.top, 1f, 1f)
    corner(hole.right, hole.top, -1f, 1f)
    corner(hole.left, hole.bottom, 1f, -1f)
    corner(hole.right, hole.bottom, -1f, -1f)
}

/**
 * The sweeping line inside the cutout (the prototype's .scan-line): a
 * horizontal gradient of the theme primary with a soft glow, in/out eased.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScanLine(hole: Rect, progress: Float) {
    val primary = CastPrimary // the screen's single accent
    val insetH = 12.dp.toPx()
    val insetV = 10.dp.toPx()
    val left = hole.left + insetH
    val width = hole.width - 2 * insetH
    val travel = (hole.height - 2 * insetV - 2.dp.toPx()).coerceAtLeast(0f)
    val y = hole.top + insetV + progress * travel

    // Soft glow under the line.
    drawRoundRect(
        color = primary.copy(alpha = 0.22f),
        topLeft = Offset(left, y - 4.dp.toPx()),
        size = Size(width, 10.dp.toPx()),
        cornerRadius = CornerRadius(5.dp.toPx()),
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, primary, Color.Transparent),
            startX = left,
            endX = left + width,
        ),
        topLeft = Offset(left, y),
        size = Size(width, 2.dp.toPx()),
        cornerRadius = CornerRadius(2.dp.toPx()),
    )
}

/** "Desktop found" pairing progress — the classic centered layout. */
@Composable
private fun PairingProgress(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check_circle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.desktop_found),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.pairing_with_desktop),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Debug-only manual payload input: lets the WebSocket+handshake path be
 * tested on an emulator, which cannot scan a real desktop QR (Phase3
 * decision). Never in release builds.
 */
@Composable
private fun ManualPayloadInput(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var manualPayload by rememberSaveable { mutableStateOf("") }
    Column(modifier) {
        OutlinedTextField(
            value = manualPayload,
            onValueChange = { manualPayload = it },
            label = { Text(stringResource(R.string.manual_payload_label)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                if (manualPayload.isNotBlank()) onSubmit(manualPayload)
            },
            modifier = Modifier
                .padding(top = 8.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.manual_payload_connect))
        }
    }
}

/** Icon + plain-words message row — the pairing screen's friendly states. */
@Composable
private fun StatusMessage(
    icon: ImageVector,
    message: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.height(20.dp),
        )
        Text(text = message, fontSize = 15.sp, color = tint)
    }
}

/** pairing.md failure-mode table — simple, non-technical; codes stay in logs. */
@Composable
private fun pairingErrorMessage(error: PairingError): String = when (error) {
    PairingError.NOT_ZFC_QR -> stringResource(R.string.pairing_error_not_zfc_qr)
    PairingError.UNSUPPORTED_VERSION -> stringResource(R.string.pairing_error_unsupported_version)
    PairingError.CONNECT_UNREACHABLE -> stringResource(R.string.pairing_error_connect_unreachable)
    PairingError.EXPIRED -> stringResource(R.string.pairing_error_expired)
    PairingError.BAD_AUTH -> stringResource(R.string.pairing_error_bad_auth)
    PairingError.BUSY -> stringResource(R.string.pairing_error_busy)
    PairingError.BAD_VERSION -> stringResource(R.string.pairing_error_bad_version)
    PairingError.DESKTOP_FORGOT -> stringResource(R.string.pairing_error_desktop_forgotten)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ScanContentPreview() {
    LocalCastTheme {
        ScanContent(
            pairingState = PairingClient.State.Idle,
            onQrScanned = {},
            onManualPayloadSubmit = {},
        )
    }
}
