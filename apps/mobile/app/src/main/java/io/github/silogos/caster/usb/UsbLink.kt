package io.github.silogos.caster.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.silogos.caster.link.Link
import io.github.silogos.caster.link.LinkMode
import io.github.silogos.caster.pairing.CableTrust
import io.github.silogos.caster.pairing.DeviceIdentity
import io.github.silogos.caster.ui.pairing.defaultUserAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

/**
 * The USB link's lifetime (ADR-034, usb-link.md v2): opened when Android
 * hands the app Caster's accessory — in Cable mode only (ADR-037) —,
 * authenticated with the cable pairing's key (or enrolled, with the
 * computer's one-time Allow), closed when the cable goes. The *cast* over it
 * is [UsbCastService]'s — this object only owns the link, like
 * ConnectedDesktop owns the Wi-Fi signaling connection between casts.
 */
object UsbLink {
    private const val TAG = "UsbLink"
    private const val ACTION_PERMISSION = "io.github.silogos.caster.usb.PERMISSION"
    /** After the receiver drops the link (a restart, a quit), how soon to open the still-attached accessory again. */
    private const val REOPEN_AFTER_MS = 1_500L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    const val ACCESSORY_MANUFACTURER = "Caster"
    const val ACCESSORY_MODEL = "Caster Receiver"

    private const val CABLE_UNPLUGGED = "cable unplugged"
    /** The `bye` reason that also ends the cable pairing (usb-link.md). */
    private const val BYE_FORGET = "forget"
    private const val CLOSED_BY_APP = "closed by the app"

    sealed interface State {
        data object None : State
        /** The cable is in and the link is open; the two sides are proving the shared key, or enrolling. */
        data object Checking : State
        /** Enrolling: the computer is asking its user to Allow this phone (the cable guide's step 3). */
        data class Asking(val desktopName: String) : State
        /** Authenticated: Share screen casts over the cable. */
        data class Ready(val desktopName: String) : State
        /** The computer refused this phone, or it can't be used on this cable (the reason says which). */
        data class Refused(val refusal: UsbHandshake.Refusal) : State
        data class Casting(val desktopName: String) : State
    }

    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state.asStateFlow()

    private var link: AccessoryLink? = null
    private var handshake: UsbHandshake? = null
    private var accessory: UsbAccessory? = null
    private var detachReceiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    /** The accessory a permission request is out for — asked once per plug-in, never in a loop. */
    private var permissionAskedFor: UsbAccessory? = null
    private var permissionReceiver: BroadcastReceiver? = null

    /** Called by the cast when the link drops under it. */
    @Volatile var onLinkClosed: (() -> Unit)? = null

    /** The desktop that proved the shared key on this link; null until it has (ADR-036 resumes only to it). */
    @Volatile var trustedDesktopId: String? = null
        private set

    fun isCaster(accessory: UsbAccessory): Boolean =
        accessory.manufacturer == ACCESSORY_MANUFACTURER && accessory.model == ACCESSORY_MODEL

    /** From the activity: the intent that opened it, or whatever accessory is attached now. */
    @Synchronized
    fun attachFrom(context: Context, intent: Intent?) {
        // ADR-037: only the chosen link runs. In Wireless mode (or on the choice) the accessory is never opened.
        if (LinkMode.mode.value != Link.Cable) return
        val usb = context.getSystemService(UsbManager::class.java) ?: return
        val acc = intent?.let { IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java) }
            ?: usb.accessoryList?.firstOrNull()
            ?: return
        if (!isCaster(acc)) return
        if (link != null) return
        // v1 and v2 can't share a link (usb-link.md §Version mismatch): say "update", don't open.
        if (acc.version != UsbFrame.LINK_VERSION.toString()) {
            Log.w(TAG, "the computer speaks link ${acc.version} — not opened")
            _state.value = State.Refused(UsbHandshake.Refusal.OldComputer)
            return
        }
        if (!usb.hasPermission(acc)) {
            // The ACCESSORY_ATTACHED launch carries the grant; an app opened
            // from the launcher with the cable already in asks once.
            requestPermission(context.applicationContext, usb, acc)
            return
        }
        val fd = usb.openAccessory(acc)
        if (fd == null) {
            Log.w(TAG, "openAccessory returned null")
            return
        }
        val app = context.applicationContext
        appContext = app
        accessory = acc
        val saved = CableTrust(app).load()?.let { UsbHandshake.Saved(it.desktopId, it.key) }
        handshake = UsbHandshake(DeviceIdentity.id(app), defaultUserAgent(), saved)
        val opened = AccessoryLink(fd, ::onFrame, ::onClosed)
        link = opened
        _state.value = State.Checking
        registerDetach(app)
        Log.i(TAG, "accessory opened (v${acc.version}); cable pairing: ${saved != null}")
        opened.start()
        // A receiver still holding an earlier app's session (the app was
        // replaced or killed while the cable stayed in) sends no hello on its
        // own: `ready` asks it to start the handshake over (usb-link.md).
        sendControl(JSONObject().put("type", "ready").put("link", UsbFrame.LINK_VERSION))
    }

    private fun requestPermission(app: Context, usb: UsbManager, acc: UsbAccessory) {
        if (permissionAskedFor == acc) return
        permissionAskedFor = acc
        if (permissionReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "accessory permission ${if (granted) "granted" else "refused"}")
                    if (granted) attachFrom(c, null)
                }
            }
            permissionReceiver = receiver
            ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // Explicit and mutable: the system adds the accessory and the grant to it.
        val pending = PendingIntent.getBroadcast(
            app,
            0,
            Intent(ACTION_PERMISSION).setPackage(app.packageName),
            PendingIntent.FLAG_MUTABLE,
        )
        Log.i(TAG, "asking for the accessory permission")
        usb.requestPermission(acc, pending)
    }

    private fun onFrame(frame: UsbFrame.Frame) {
        if (frame.kind != UsbFrame.KIND_CONTROL) return // the desktop sends control only in v1
        val message = try {
            JSONObject(String(frame.payload, Charsets.UTF_8))
        } catch (e: JSONException) {
            Log.w(TAG, "unreadable control message dropped")
            return
        }
        if (message.optString("type") == "bye") {
            if (message.optString("reason") == BYE_FORGET) {
                // Terminate on the computer (usb-link.md §Cable trust): the pairing ends, back to the choice.
                Log.i(TAG, "the computer ended the cable pairing (bye forget)")
                appContext?.let { forgetPairing(it) }
            }
            link?.close("desktop said bye")
            return
        }
        when (val outcome = handshake?.onControl(message) ?: return) {
            is UsbHandshake.Outcome.Send -> sendControl(outcome.message)
            is UsbHandshake.Outcome.Asking -> {
                Log.i(TAG, "cable key made — the computer is asking its user")
                sendControl(outcome.message)
                _state.value = State.Asking(outcome.desktopName)
            }
            is UsbHandshake.Outcome.StaleKey -> {
                Log.i(TAG, "the computer forgot this phone — enrolling again")
                appContext?.let { CableTrust(it).clear() }
                sendControl(outcome.enrol)
            }
            is UsbHandshake.Outcome.Trusted -> {
                val app = appContext
                val enrolled = outcome.enrolledKey
                if (enrolled != null && app != null) {
                    CableTrust(app).save(outcome.desktopId, outcome.desktopName, enrolled)
                    LinkMode.paired(app, Link.Cable)
                    Log.i(TAG, "the computer allowed this phone — cable pairing saved")
                } else {
                    Log.i(TAG, "desktop proved the cable key — link ready")
                }
                trustedDesktopId = outcome.desktopId
                _state.value = State.Ready(outcome.desktopName)
            }
            is UsbHandshake.Outcome.Refused -> {
                Log.w(TAG, "link refused: ${outcome.why}")
                _state.value = State.Refused(outcome.refusal)
            }
            UsbHandshake.Outcome.Ignore -> Log.d(TAG, "control ${message.optString("type")} ignored")
        }
    }

    private fun forgetPairing(app: Context) {
        CableTrust(app).clear()
        main.post { LinkMode.backToChoice(app) }
    }

    fun sendControl(message: JSONObject): Boolean =
        link?.send(UsbFrame.KIND_CONTROL, 0, 0, message.toString().toByteArray(Charsets.UTF_8)) ?: false

    /** Media from the cast; false once the link is gone. */
    fun sendMedia(kind: Int, flags: Int, ptsUs: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size): Boolean =
        link?.send(kind, flags, ptsUs, payload, offset, length) ?: false

    /** A whole frame built with [UsbFrame.framed]; false once the link is gone. */
    fun sendFramed(frame: ByteArray): Boolean = link?.sendFramed(frame) ?: false

    fun bytesSent(): Long = link?.bytesSent ?: 0L

    /** Ready and not already casting: the one Share screen goes over the cable. */
    fun readyDesktopName(): String? = (_state.value as? State.Ready)?.desktopName

    fun markCasting(casting: Boolean) {
        val current = _state.value
        _state.value = when {
            casting && current is State.Ready -> State.Casting(current.desktopName)
            !casting && current is State.Casting -> State.Ready(current.desktopName)
            else -> current
        }
    }

    @Synchronized
    private fun onClosed(reason: String) {
        Log.i(TAG, "link ended: $reason")
        link = null
        handshake = null
        trustedDesktopId = null
        accessory = null
        permissionAskedFor = null
        // A refusal stays on screen while the cable is in (the guide says what to do); unplugging clears it.
        if (_state.value !is State.Refused || reason == CABLE_UNPLUGGED) _state.value = State.None
        appContext?.let { ctx -> detachReceiver?.let { runCatching { ctx.unregisterReceiver(it) } } }
        detachReceiver = null
        onLinkClosed?.invoke()
        // The cable is still in when the receiver restarted or quit: open the
        // accessory again so its next hello finds the app listening.
        if (reason != CABLE_UNPLUGGED && reason != CLOSED_BY_APP && LinkMode.mode.value == Link.Cable) {
            appContext?.let { ctx -> main.postDelayed({ attachFrom(ctx, null) }, REOPEN_AFTER_MS) }
        }
    }

    private fun registerDetach(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val gone = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                if (gone == null || gone == accessory) link?.close(CABLE_UNPLUGGED)
            }
        }
        detachReceiver = receiver
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** The app is leaving the link (not the cable): say bye so the desktop's next read ends cleanly. */
    fun close() {
        sendControl(JSONObject().put("type", "bye"))
        link?.close(CLOSED_BY_APP)
    }

    /**
     * Terminate on the phone (ADR-037 §4): `bye forget` tells the computer to
     * forget this phone too; the cable pairing ends here and the app goes back
     * to the choice. Unplugged, the computer learns it on the next plug-in
     * (`unknown-device`).
     */
    fun terminate(context: Context) {
        sendControl(JSONObject().put("type", "bye").put("reason", BYE_FORGET))
        link?.close(CLOSED_BY_APP)
        _state.value = State.None
        CableTrust(context.applicationContext).clear()
        LinkMode.backToChoice(context)
    }
}
