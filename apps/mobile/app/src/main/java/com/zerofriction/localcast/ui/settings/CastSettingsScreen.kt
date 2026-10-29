package com.zerofriction.localcast.ui.settings

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerofriction.localcast.R
import com.zerofriction.localcast.audio.MicDeviceResolver
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CastSettingsStore
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.config.EncoderBitrateMode
import com.zerofriction.localcast.config.EncoderImplementation
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.config.PreferredVideoCodec
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.service.CastService
import com.zerofriction.localcast.ui.theme.LocalCastTheme

/**
 * The cast settings screen (designs/mobile-app.html) — the gear from the
 * Home hub. All cast settings live here: the Share screen section and the
 * collapsible Advanced section. Every change persists immediately and
 * applies on the next cast; the note at the top says so. Back — the button
 * and the system gesture alike — returns to the Home hub; the pairing
 * session is untouched.
 */
@Composable
fun CastSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = settingsFactory()),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val encoderInUse by CastService.encoderImplementation.collectAsStateWithLifecycle()
    val micOptions = rememberMicOptions()

    // The system/predictive back gesture mirrors the on-screen back button
    // (state-based navigation, no nav library — MainActivity's decision).
    BackHandler(onBack = onBack)

    CastSettingsContent(
        settings = settings,
        encoderInUse = encoderInUse,
        micOptions = micOptions,
        onSelectProfile = viewModel::selectProfile,
        onSelectLongEdge = viewModel::selectLongEdge,
        onSelectFps = viewModel::selectFps,
        onSetBitrateAuto = viewModel::setBitrateAuto,
        onSetManualBitrateMax = viewModel::setManualBitrateMax,
        onSetAutoQuality = viewModel::setAutoQuality,
        onSetMicDevice = viewModel::setMicDevice,
        onSetEncoderImpl = viewModel::setEncoderImpl,
        onSetH264HighProfile = viewModel::setH264HighProfile,
        onSetPreferredCodec = viewModel::setPreferredCodec,
        onSetDegradationStrategy = viewModel::setDegradationStrategy,
        onSetEncoderFpsLimit = viewModel::setEncoderFpsLimit,
        onSetBitrateMode = viewModel::setBitrateMode,
        onBack = onBack,
    )
}

@Composable
fun CastSettingsContent(
    settings: CastSettings,
    encoderInUse: String?,
    micOptions: List<MicDeviceOption>,
    onSelectProfile: (QualityProfile) -> Unit,
    onSelectLongEdge: (Int) -> Unit,
    onSelectFps: (Int) -> Unit,
    onSetBitrateAuto: (Boolean) -> Unit,
    onSetManualBitrateMax: (Int) -> Unit,
    onSetAutoQuality: (Boolean) -> Unit,
    onSetMicDevice: (MicDeviceSource) -> Unit,
    onSetEncoderImpl: (EncoderImplementation) -> Unit,
    onSetH264HighProfile: (Boolean) -> Unit,
    onSetPreferredCodec: (PreferredVideoCodec) -> Unit,
    onSetDegradationStrategy: (DegradationStrategy) -> Unit,
    onSetEncoderFpsLimit: (Int?) -> Unit,
    onSetBitrateMode: (EncoderBitrateMode) -> Unit,
    onBack: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // App bar: back chevron + title (the prototype's settings .app-bar).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .padding(start = 0.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left),
                    contentDescription = stringResource(R.string.cast_settings_title),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = stringResource(R.string.cast_settings_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            text = stringResource(R.string.settings_changes_next_cast),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        ShareScreenSettingsSection(
            settings = settings,
            onSelectProfile = onSelectProfile,
            onSelectLongEdge = onSelectLongEdge,
            onSelectFps = onSelectFps,
            onSetBitrateAuto = onSetBitrateAuto,
            onSetManualBitrateMax = onSetManualBitrateMax,
            onSetAutoQuality = onSetAutoQuality,
        )
        MicDeviceSection(
            selected = settings.micDevice,
            options = micOptions,
            onSelectMicDevice = onSetMicDevice,
        )
        AdvancedSettingsSection(
            settings = settings,
            encoderInUse = encoderInUse,
            onSetEncoderImpl = onSetEncoderImpl,
            onSetH264HighProfile = onSetH264HighProfile,
            onSetPreferredCodec = onSetPreferredCodec,
            onSetDegradationStrategy = onSetDegradationStrategy,
            onSetEncoderFpsLimit = onSetEncoderFpsLimit,
            onSetBitrateMode = onSetBitrateMode,
        )
        Spacer(Modifier.height(28.dp))
        }
    }
}

/** The settings store, injected like HomeScreen's — one home for the ViewModel. */
@Composable
private fun settingsFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val store = CastSettingsStore(context.applicationContext)
                return SettingsViewModel(store.load(), store::save) as T
            }
        }
    }
}

/** Connected input device types as AudioManager reports them right now. */
private fun connectedInputTypes(context: Context): Set<Int> =
    (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
        ?.map { it.type }
        ?.toSet()
        ?: emptySet()

/**
 * The mic device section's options from the currently connected inputs: the
 * built-in mic always, plus a kind only while one of its input devices is
 * connected (MicDeviceResolver resolves the same way at capture time, so the
 * offered chips and the actual fallback can't drift apart). The list follows
 * plug/unplug **live** (`registerAudioDeviceCallback`) — a headset connected
 * while the screen is open appears without re-entering it; the capture-side
 * fallback covers a device that leaves between selection and the mic start.
 */
@Composable
private fun rememberMicOptions(): List<MicDeviceOption> {
    val context = LocalContext.current
    var connectedTypes by remember { mutableStateOf(connectedInputTypes(context)) }
    DisposableEffect(context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                connectedTypes = connectedInputTypes(context)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                connectedTypes = connectedInputTypes(context)
            }
        }
        audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager?.unregisterAudioDeviceCallback(callback) }
    }
    return remember(connectedTypes) {
        Log.d(TAG, "mic options from connected input types: $connectedTypes")
        listOf(
            MicDeviceOption(MicDeviceSource.BUILTIN, R.string.mic_device_builtin),
            MicDeviceOption(MicDeviceSource.WIRED_HEADSET, R.string.mic_device_wired),
            MicDeviceOption(MicDeviceSource.USB, R.string.mic_device_usb),
            MicDeviceOption(MicDeviceSource.BLUETOOTH, R.string.mic_device_bluetooth),
        ).filter {
            it.source == MicDeviceSource.BUILTIN ||
                MicDeviceResolver.resolveType(it.source, connectedTypes) != MicDeviceResolver.FALLBACK_TYPE
        }
    }
}

private const val TAG = "CastSettingsScreen"

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun CastSettingsContentPreview() {
    LocalCastTheme {
        CastSettingsContent(
            settings = CastSettings.default(),
            encoderInUse = null,
            micOptions = listOf(MicDeviceOption(MicDeviceSource.BUILTIN, R.string.mic_device_builtin)),
            onSelectProfile = {},
            onSelectLongEdge = {},
            onSelectFps = {},
            onSetBitrateAuto = {},
            onSetManualBitrateMax = {},
            onSetAutoQuality = {},
            onSetMicDevice = {},
            onSetEncoderImpl = {},
            onSetH264HighProfile = {},
            onSetPreferredCodec = {},
            onSetDegradationStrategy = {},
            onSetEncoderFpsLimit = {},
            onSetBitrateMode = {},
        )
    }
}
