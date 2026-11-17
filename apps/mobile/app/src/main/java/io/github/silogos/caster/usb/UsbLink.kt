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
import io.github.silogos.caster.pairing.DeviceIdentity
import io.github.silogos.caster.pairing.SavedDesktops
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

/**
 * The USB link's lifetime (ADR-034, usb-link.md): opened when Android hands
 * the app Caster's accessory, authenticated against the saved Wi-Fi pairing,
 * closed when the cable goes. The *cast* over it is [UsbCastService]'s —
 * this object only owns the link, like ConnectedDesktop owns the Wi-Fi
 * signaling connection between casts.
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
    private const val CLOSED_BY_APP = "closed by the app"

    sealed interface State {
        data object None : State
        /** The cable is in and the link is open; the two sides are proving the shared key. */
        data object Checking : State
        /** Authenticated: Share screen casts over the cable. */
        data class Ready(val desktopName: String) : State
        /** This phone never paired with the desktop on the cable (or paired with another). */
        data object NotPaired : State
        /** The desktop no longer trusts this phone, or failed to prove itself. */
        data object Refused : State
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

    fun isCaster(accessory: UsbAccessory): Boolean =
        accessory.manufacturer == ACCESSORY_MANUFACTURER && accessory.model == ACCESSORY_MODEL

    /** From the activity: the intent that opened it, or whatever accessory is attached now. */
    @Synchronized
    fun attachFrom(context: Context, intent: Intent?) {
        val usb = context.getSystemService(UsbManager::class.java) ?: return
        val acc = intent?.let { IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java) }
            ?: usb.accessoryList?.firstOrNull()
            ?: return
        if (!isCaster(acc)) return
        if (link != null) return
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
        val savedDesktops = SavedDesktops(app)
        val saved = savedDesktops.load()?.let { entry ->
            savedDesktops.trustKeyOf(entry)?.let { UsbHandshake.Saved(entry.desktopId, it) }
        }
        handshake = UsbHandshake(DeviceIdentity.id(app), saved)
        val opened = AccessoryLink(fd, ::onFrame, ::onClosed)
        link = opened
        _state.value = State.Checking
        registerDetach(app)
        Log.i(TAG, "accessory opened (v${acc.version}); saved pairing: ${saved != null}")
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
            link?.close("desktop said bye")
            return
        }
        when (val outcome = handshake?.onControl(message) ?: return) {
            is UsbHandshake.Outcome.Send -> sendControl(outcome.message)
            is UsbHandshake.Outcome.Trusted -> {
                Log.i(TAG, "desktop proved the shared key — link ready")
                _state.value = State.Ready(outcome.desktopName)
            }
            UsbHandshake.Outcome.NotPaired -> {
                Log.i(TAG, "no pairing with the desktop on the cable")
                _state.value = State.NotPaired
            }
            is UsbHandshake.Outcome.Refused -> {
                Log.w(TAG, "link refused: ${outcome.why}")
                _state.value = State.Refused
            }
            UsbHandshake.Outcome.Ignore -> Log.d(TAG, "control ${message.optString("type")} ignored")
        }
    }

    fun sendControl(message: JSONObject): Boolean =
        link?.send(UsbFrame.KIND_CONTROL, 0, 0, message.toString().toByteArray(Charsets.UTF_8)) ?: false

    /** Media from the cast; false once the link is gone. */
    fun sendMedia(kind: Int, flags: Int, ptsUs: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size): Boolean =
        link?.send(kind, flags, ptsUs, payload, offset, length) ?: false

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
        accessory = null
        permissionAskedFor = null
        _state.value = State.None
        appContext?.let { ctx -> detachReceiver?.let { runCatching { ctx.unregisterReceiver(it) } } }
        detachReceiver = null
        onLinkClosed?.invoke()
        // The cable is still in when the receiver restarted or quit: open the
        // accessory again so its next hello finds the app listening.
        if (reason != CABLE_UNPLUGGED && reason != CLOSED_BY_APP) {
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
}
