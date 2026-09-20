package com.zerofriction.localcast.ui.pairing

import com.zerofriction.localcast.pairing.PairingClient
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.signaling.Envelope
import com.zerofriction.localcast.signaling.EnvelopeCodec
import com.zerofriction.localcast.signaling.Handshake
import com.zerofriction.localcast.signaling.SignalingScheduler
import com.zerofriction.localcast.signaling.SignalingTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Behavior tests for the scan screen's ViewModel (Phase13): the happy-path
 * gating the screen depends on — a scan shows "Desktop found" immediately,
 * in-flight scans are ignored, failures accept a fresh scan, and the cast
 * handover releases the live connection without ending it.
 */
class ScanViewModelTest {

    private fun newViewModel(fake: FakeTransport): ScanViewModel {
        val pairing = PairingClient({ fake }, { IdleScheduler() }) { TEST_UA }
        return ScanViewModel(pairing, MutableStateFlow(CastState.Idle))
    }

    @Test
    fun `a scan shows Desktop found immediately and further scans are ignored while in flight`() {
        val fake = FakeTransport()
        val viewModel = newViewModel(fake)

        viewModel.onQrScanned(livePayload())

        // Phase13: a decoded QR is instant feedback — Connecting before any
        // socket even opens.
        assertEquals(PairingClient.State.Connecting, viewModel.pairingState.value)

        // Camera frames keep flowing; a second detection must not restart.
        viewModel.onQrScanned(livePayload())
        assertEquals(1, fake.openedUrls.size)

        fake.listener?.onTransportOpen()
        assertEquals(PairingClient.State.Authenticating, viewModel.pairingState.value)
        viewModel.onQrScanned(livePayload())
        assertEquals(1, fake.openedUrls.size)
    }

    @Test
    fun `a failed pairing accepts a fresh scan`() {
        val fake = FakeTransport(failOnOpen = true)
        val viewModel = newViewModel(fake)

        viewModel.onQrScanned(livePayload())
        // Both candidate hosts fail → CONNECT_UNREACHABLE (pairing.md copy).
        assertEquals(
            com.zerofriction.localcast.pairing.PairingError.CONNECT_UNREACHABLE,
            (viewModel.pairingState.value as PairingClient.State.Failed).error,
        )

        viewModel.onQrScanned(livePayload())
        assertEquals(4, fake.openedUrls.size) // two hosts per attempt
    }

    @Test
    fun `a connected pairing ignores scans and hands its connection over on cast start`() {
        val fake = FakeTransport()
        val viewModel = newViewModel(fake)

        viewModel.onQrScanned(livePayload())
        fake.listener?.onTransportOpen()
        fake.sentFrames.removeAt(0) // hello
        fake.listener?.onTransportText(challengeFrame(VECTOR_NONCE))
        fake.sentFrames.removeAt(0) // auth
        fake.listener?.onTransportText(authOkFrame(TEST_DESKTOP_NAME))
        assertEquals(
            PairingClient.State.Connected(TEST_DESKTOP_NAME),
            viewModel.pairingState.value,
        )

        viewModel.onQrScanned(livePayload()) // ignored — still paired
        assertEquals(1, fake.openedUrls.size)

        val handedOver = viewModel.releaseSignalingClient()
        assertNotNull(handedOver)
        assertEquals(PairingClient.State.Idle, viewModel.pairingState.value)
        assertEquals(0, fake.sentFrames.size) // no bye — the cast owns it now
    }

    @Test
    fun `onDisconnect returns to the scanner`() {
        val fake = FakeTransport()
        val viewModel = newViewModel(fake)
        viewModel.onQrScanned(livePayload())
        fake.listener?.onTransportOpen()
        fake.sentFrames.clear()
        fake.listener?.onTransportText(challengeFrame(VECTOR_NONCE))
        fake.sentFrames.clear()
        fake.listener?.onTransportText(authOkFrame(TEST_DESKTOP_NAME))

        viewModel.onDisconnect()

        assertEquals(PairingClient.State.Idle, viewModel.pairingState.value)
    }

    // ---- shared handshake scaffolding (same vector as PairingClientTest) ----

    private fun livePayload(): String = VALID_PAYLOAD.replace(
        "\"e\":1758300000",
        "\"e\":${System.currentTimeMillis() /1000 +600}",
    )

    private fun challengeFrame(nonce: String): String =
        EnvelopeCodec.encode(Envelope.TYPE_CHALLENGE, 1, VECTOR_SID, buildJsonObject { put("n", nonce) })

    private fun authOkFrame(desktopName: String): String = EnvelopeCodec.encode(
        Envelope.TYPE_AUTH_OK,
        2,
        VECTOR_SID,
        buildJsonObject {
            put("name", desktopName)
            put("proto", 1)
        },
    )

    private companion object {
        const val TEST_UA = "ZeroFrictionCast/0.1.0 (Android 15; Pixel8)"
        const val TEST_DESKTOP_NAME = "test-desktop"
        const val VECTOR_SID = "Jm1LIOO4mWm0lRSSl2fClw"
        const val VECTOR_NONCE = "EBESExQVFhcYGRobHB0eHw"
        const val VALID_PAYLOAD =
            """{"v":1,"t":"zfc","h":["192.168.1.42","192.168.137.1"],"p":52341,"s":"Jm1LIOO4mWm0lRSSl2fClw","k":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","e":1758300000}"""
    }
}

/** Scripts the desktop's side of the transport; records everything the phone sends. */
private class FakeTransport(private val failOnOpen: Boolean = false) : SignalingTransport {

    val sentFrames = ArrayDeque<String>()
    val openedUrls = mutableListOf<String>()
    var listener: SignalingTransport.Listener? = null
        private set

    override fun open(url: String, listener: SignalingTransport.Listener) {
        openedUrls.add(url)
        this.listener = listener
        if (failOnOpen) listener.onTransportFailure(RuntimeException("simulated unreachable host"))
    }

    override fun send(text: String): Boolean {
        sentFrames.add(text)
        return true
    }

    override fun close(code: Int, reason: String) {
        listener?.onTransportClosed(code, reason)
    }
}

/** Never fires scheduled work — reconnect timers stay dormant in these tests. */
private class IdleScheduler : SignalingScheduler {
    override fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit = {
        // never run: reconnect timers stay dormant in these tests
    }
}
