package com.zerofriction.localcast.ui.settings

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
 * applies on the next cast; the note at the top says so. Back returns to
 * the Home hub; the pairing session is untouched.
 */
@Composable
fun CastSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = settingsFactory()),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val encoderInUse by CastService.encoderImplementation.collectAsStateWithLifecycle()
    val micOptions = rememberMicOptions()

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("‹ " + stringResource(R.string.cast_settings_title))
        }
        HintText(R.string.settings_changes_next_cast)

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
        Spacer(Modifier.height(20.dp))
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
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The mic device section's options from the currently connected inputs: the
 * built-in mic always, plus a kind only while one of its input devices is
 * connected (MicDeviceResolver resolves the same way at capture time, so the
 * offered chips and the actual fallback can't drift apart). Not reactive to
 * plug/unplug while the screen is open — recomposed on re-entry, and the
 * capture-side fallback covers a device that leaves between selection and
 * the mic start.
 */
@Composable
private fun rememberMicOptions(): List<MicDeviceOption> {
    val context = LocalContext.current
    return remember {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val connectedTypes = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.map { it.type }
            ?.toSet()
            ?: emptySet()
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
