package com.zerofriction.localcast.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.zerofriction.localcast.config.CastSettingChoices
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CastSettingsStore
import com.zerofriction.localcast.config.QualityProfile
import com.zerofriction.localcast.ui.theme.LocalCastTheme
import kotlin.math.roundToInt

/**
 * The mobile's cast settings screen (Phase10) — the configuration-owner UI
 * (overview.md: every cast decision lives on the phone; the desktop exposes
 * none of this). Changes persist immediately and take effect on the next
 * cast: the cast-start path reads [CastSettingsStore] fresh.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val store = CastSettingsStore(context.applicationContext)
                return SettingsViewModel(store.load(), store::save) as T
            }
        }
    }
    val viewModel: SettingsViewModel = viewModel(factory = factory)

    // Same back-handling contract as the scan screen: back returns to home
    // instead of backgrounding the app.
    val backDispatcher = (context as? ComponentActivity)?.onBackPressedDispatcher
    DisposableEffect(backDispatcher, onBack) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        }
        backDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onSelectProfile = viewModel::selectProfile,
        onSelectLongEdge = viewModel::selectLongEdge,
        onSelectFps = viewModel::selectFps,
        onSetBitrateAuto = viewModel::setBitrateAuto,
        onSetManualBitrateMax = viewModel::setManualBitrateMax,
        onSetGameAudio = viewModel::setGameAudio,
        onSetMic = viewModel::setMic,
        onBack = onBack,
    )
}

@Composable
fun SettingsContent(
    settings: CastSettings,
    onSelectProfile: (QualityProfile) -> Unit,
    onSelectLongEdge: (Int) -> Unit,
    onSelectFps: (Int) -> Unit,
    onSetBitrateAuto: (Boolean) -> Unit,
    onSetManualBitrateMax: (Int) -> Unit,
    onSetGameAudio: (Boolean) -> Unit,
    onSetMic: (Boolean) -> Unit,
    onBack: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.settings_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_changes_next_cast),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel(R.string.settings_quality_profile)
        // No chip selected when the values were tweaked past the preset —
        // the honest "custom" state, not a fake preset match.
        val effectiveProfile = settings.toConfig().profile
        ChipRow(QualityProfile.entries) { preset ->
            FilterChip(
                selected = preset.label == effectiveProfile,
                onClick = { onSelectProfile(preset) },
                label = { Text(profileName(preset)) },
            )
        }

        SectionLabel(R.string.settings_resolution)
        ChipRow(CastSettingChoices.LONG_EDGE_CHOICES) { longEdge ->
            FilterChip(
                selected = longEdge == settings.longEdgePx,
                onClick = { onSelectLongEdge(longEdge) },
                label = { Text(stringResource(R.string.settings_px, longEdge)) },
            )
        }

        SectionLabel(R.string.settings_fps)
        ChipRow(CastSettingChoices.FPS_CHOICES) { fps ->
            FilterChip(
                selected = fps == settings.fps,
                onClick = { onSelectFps(fps) },
                label = { Text(stringResource(R.string.settings_fps_value, fps)) },
            )
        }

        SectionLabel(R.string.settings_bitrate)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.settings_bitrate_auto),
                fontSize = 15.sp,
                modifier = Modifier.padding(end = 16.dp),
            )
            Switch(checked = settings.bitrateAuto, onCheckedChange = onSetBitrateAuto)
        }
        if (!settings.bitrateAuto) {
            val maxMbps = settings.bitrateMaxBps / BPS_PER_MBPS
            Slider(
                value = maxMbps.toFloat(),
                onValueChange = { mbps -> onSetManualBitrateMax((mbps * BPS_PER_MBPS).roundToInt()) },
                valueRange = (CastSettingChoices.MANUAL_BITRATE_MIN_BPS / BPS_PER_MBPS).toFloat()..
                    (CastSettingChoices.MANUAL_BITRATE_MAX_BPS / BPS_PER_MBPS).toFloat(),
                steps = ((CastSettingChoices.MANUAL_BITRATE_MAX_BPS - CastSettingChoices.MANUAL_BITRATE_MIN_BPS) / BPS_PER_MBPS) - 1,
            )
            Text(
                text = stringResource(R.string.settings_bitrate_max, maxMbps),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel(R.string.settings_audio)
        ToggleRow(
            label = stringResource(R.string.settings_game_audio),
            checked = settings.gameAudio,
            onCheckedChange = onSetGameAudio,
        )
        ToggleRow(
            label = stringResource(R.string.settings_mic),
            checked = settings.mic,
            onCheckedChange = onSetMic,
        )
        Text(
            text = stringResource(R.string.settings_mic_hint),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(labelRes: Int) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(labelRes),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun <T> ChipRow(choices: List<T>, chip: @Composable (T) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        for (choice in choices) chip(choice)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(text = label, fontSize = 15.sp, modifier = Modifier.padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun profileName(profile: QualityProfile): String = when (profile) {
    QualityProfile.BALANCED -> stringResource(R.string.profile_balanced)
    QualityProfile.SHARP -> stringResource(R.string.profile_sharp)
    QualityProfile.SMOOTH -> stringResource(R.string.profile_smooth)
    QualityProfile.LIGHT -> stringResource(R.string.profile_light)
}

/** bps ↔ Mbps for the manual-bitrate slider's display range. */
private const val BPS_PER_MBPS = 1_000_000

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun SettingsContentPreview() {
    LocalCastTheme {
        SettingsContent(
            settings = CastSettings.default(),
            onSelectProfile = {},
            onSelectLongEdge = {},
            onSelectFps = {},
            onSetBitrateAuto = {},
            onSetManualBitrateMax = {},
            onSetGameAudio = {},
            onSetMic = {},
        )
    }
}
