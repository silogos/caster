package io.github.silogos.caster.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.silogos.caster.service.CastService

/**
 * Debug builds only: `adb shell am broadcast -n
 * io.github.silogos.caster/.debug.DebugIceRestartReceiver` asks the running
 * cast for one ICE restart — the recovery path a Wi-Fi blip takes (ADR-019).
 */
class DebugIceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startService(Intent(context, CastService::class.java).setAction(CastService.DEBUG_ACTION_ICE_RESTART))
    }
}
