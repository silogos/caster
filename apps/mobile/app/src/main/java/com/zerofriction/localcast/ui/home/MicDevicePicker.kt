package com.zerofriction.localcast.ui.home

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerofriction.localcast.R
import com.zerofriction.localcast.audio.MicDeviceResolver
import com.zerofriction.localcast.config.MicDeviceSource
import com.zerofriction.localcast.ui.components.CastChipRow

/** One offered mic kind: the config value plus its localized label. */
internal data class MicDeviceOption(val source: MicDeviceSource, val labelRes: Int)

/** Connected input device types as AudioManager reports them right now. */
internal fun connectedInputTypes(context: Context): Set<Int> =
    (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
        ?.map { it.type }
        ?.toSet()
        ?: emptySet()

/** Localized kind name ("Phone mic", …). */
@Composable
internal fun micSourceName(source: MicDeviceSource): String = stringResource(
    when (source) {
        MicDeviceSource.BUILTIN -> R.string.mic_device_builtin
        MicDeviceSource.WIRED_HEADSET -> R.string.mic_device_wired
        MicDeviceSource.USB -> R.string.mic_device_usb
        MicDeviceSource.BLUETOOTH -> R.string.mic_device_bluetooth
    },
)

/**
 * The connected kinds from the inputs plugged in right now — the phone mic
 * always, a kind only while one of its inputs is connected (MicDeviceResolver
 * resolves the same way at capture time, so the offered chips and the actual
 * fallback can't drift apart). Follows plug/unplug **live**.
 */
@Composable
internal fun rememberMicOptions(): List<MicDeviceOption> {
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

/**
 * The mic-device picker under the Microphone row (designs/mobile-app.html):
 * the connected kinds as chips — picking one while casting reroutes the LIVE
 * record (no session rebuild); idle it sets the next-start default. A kind
 * that vanished mid-cast shows the plain-words fallback fact instead.
 */
@Composable
internal fun MicDevicePicker(
    effective: MicDeviceSource,
    fallbackFrom: MicDeviceSource?,
    onSelect: (MicDeviceSource) -> Unit,
) {
    val options = rememberMicOptions()
    val dividerColor = MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val y = 0.5.dp.toPx()
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 14.dp),
    ) {
        CastChipRow(
            options = options.map { option -> micSourceName(option.source) to (option.source == effective) },
            onSelect = { index -> onSelect(options[index].source) },
        )
        if (fallbackFrom != null) {
            Text(
                text = stringResource(R.string.mic_device_fallback_fact, micSourceName(fallbackFrom)),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

internal const val TAG = "HomeScreen"
