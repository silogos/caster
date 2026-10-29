package com.zerofriction.localcast.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.zerofriction.localcast.config.CastSettings
import com.zerofriction.localcast.config.CastSettingsStore
import com.zerofriction.localcast.config.DegradationStrategy
import com.zerofriction.localcast.config.EncoderBitrateMode
import com.zerofriction.localcast.config.EncoderImplementation
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

    // The system/predictive back gesture mirrors the on-screen back button
    // (state-based navigation, no nav library — MainActivity's decision).
    BackHandler(onBack = onBack)

    CastSettingsContent(
        settings = settings,
        encoderInUse = encoderInUse,
        onSelectProfile = viewModel::selectProfile,
        onSelectLongEdge = viewModel::selectLongEdge,
        onSelectFps = viewModel::selectFps,
        onSetBitrateAuto = viewModel::setBitrateAuto,
        onSetManualBitrateMax = viewModel::setManualBitrateMax,
        onSetAutoQuality = viewModel::setAutoQuality,
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
    onSelectProfile: (QualityProfile) -> Unit,
    onSelectLongEdge: (Int) -> Unit,
    onSelectFps: (Int) -> Unit,
    onSetBitrateAuto: (Boolean) -> Unit,
    onSetManualBitrateMax: (Int) -> Unit,
    onSetAutoQuality: (Boolean) -> Unit,
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

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun CastSettingsContentPreview() {
    LocalCastTheme {
        CastSettingsContent(
            settings = CastSettings.default(),
            encoderInUse = null,
            onSelectProfile = {},
            onSelectLongEdge = {},
            onSelectFps = {},
            onSetBitrateAuto = {},
            onSetManualBitrateMax = {},
            onSetAutoQuality = {},
            onSetEncoderImpl = {},
            onSetH264HighProfile = {},
            onSetPreferredCodec = {},
            onSetDegradationStrategy = {},
            onSetEncoderFpsLimit = {},
            onSetBitrateMode = {},
        )
    }
}
