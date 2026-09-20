package com.zerofriction.localcast.signaling

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase4 lifecycle tests (webrtc.md): heartbeat, reconnect-with-backoff within
 * the QR session window, expiry stop, desktop bye — with a scripted transport
 * and a virtual-clock scheduler. No network, no media.
 */
class SignalingClientTest {

    private lateinit var fake: FakeTransport
    private lateinit var scheduler: FakeScheduler
    private lateinit var client: SignalingClient
    private val events = mutableListOf<SignalingClient.Event>()

    private var clockMs = 0L

    /** Advance both clocks together — silence is measured in wall time. */
    private fun advance(ms: Long) {
        clockMs += ms
        scheduler.advance(ms)
    }

    private fun setUp() {
        fake = FakeTransport()
        scheduler = FakeScheduler()
        events.clear()
        clockMs = 0
        client = SignalingClient(
            hosts = listOf("192.168.1.42"),
            port = 52341,
            sessionId = VECTOR_SID,
            secret = Handshake.decodeSecret(VECTOR_SECRET),
            userAgent = TEST_UA,
            expiresAtUnixSeconds = EXPIRES_AT,
            transport = fake,
            scheduler = scheduler,
            nowMs = { clockMs },
            heartbeatIntervalMs = HEARTBEAT_INTERVAL,
            heartbeatTimeoutMs = HEARTBEAT_TIMEOUT,
        )
        client.connect { events += it }
    }

    /** Drive connect → hello → challenge → auth → auth-ok. */
    private fun authorize(desktopName: String = TEST_DESKTOP_NAME) {
        fake.listener?.onTransportOpen()
        fake.sentFrames.removeFirstOrNull() // hello
        fake.listener?.onTransportText(challengeFrame())
        fake.sentFrames.removeFirstOrNull() // auth
        fake.listener?.onTransportText(authOkFrame(desktopName))
    }

    @Test
    fun `sends a heartbeat ping on every interval while authorized`() {
        setUp()
        authorize()

        advance(HEARTBEAT_INTERVAL)
        val firstPing = parseFrame(fake.sentFrames.removeFirstOrNull())
        assertEquals(Envelope.TYPE_PING, firstPing.type)
        assertEquals(HEARTBEAT_INTERVAL, Payloads.pingT(firstPing))

        advance(HEARTBEAT_INTERVAL)
        assertEquals(Envelope.TYPE_PING, parseFrame(fake.sentFrames.removeFirstOrNull()).type)
    }

    @Test
    fun `answers a desktop ping with a pong carrying the same t`() {
        setUp()
        authorize()

        fake.listener?.onTransportText(
            EnvelopeCodec.encode(Envelope.TYPE_PING, 7, VECTOR_SID, buildJsonObject { put("t", 999L) }),
        )

        val pong = parseFrame(fake.sentFrames.removeFirstOrNull())
        assertEquals(Envelope.TYPE_PONG, pong.type)
        assertEquals(999L, Payloads.pingT(pong))
    }

    @Test
    fun `drops the connection when the desktop is silent past the timeout and schedules a reconnect`() {
        setUp()
        authorize()

        // No inbound frames at all — the desktop is gone. The watchdog
        // samples on the ping cadence, so wait past timeout + one interval.
        advance(HEARTBEAT_TIMEOUT + HEARTBEAT_INTERVAL + 1)

        assertTrue(fake.closeCount >= 1) // the client hung up first
        assertEquals(SignalingClient.State.RECONNECTING, client.state.value)
        assertEquals(1000L, scheduler.nextDelayMs()) // webrtc.md: backoff starts at 1 s
    }

    @Test
    fun `reconnects with the backoff ladder on repeated failures and re-authenticates within the window`() {
        setUp()
        authorize()

        // First drop → 1 s.
        fake.listener?.onTransportClosed(1000, "simulated drop")
        assertEquals(SignalingClient.State.RECONNECTING, client.state.value)
        assertEquals(1000L, scheduler.nextDelayMs())
        advance(1000)

        // The reconnect attempt itself fails →2 s → 5 s → 10 s → 30 s cap.
        fake.listener?.onTransportFailure(RuntimeException("wi-fi blip"))
        assertEquals(2000L, scheduler.nextDelayMs())
        advance(2000)
        fake.listener?.onTransportFailure(RuntimeException("wi-fi blip"))
        assertEquals(5000L, scheduler.nextDelayMs())
        advance(5000)
        fake.listener?.onTransportFailure(RuntimeException("wi-fi blip"))
        assertEquals(10_000L, scheduler.nextDelayMs())
        advance(10_000)
        fake.listener?.onTransportFailure(RuntimeException("wi-fi blip"))
        assertEquals(30_000L, scheduler.nextDelayMs()) // capped (webrtc.md)

        // The next attempt succeeds — the ladder resets to 1 s after re-auth.
        advance(30_000)
        authorize()
        assertEquals(SignalingClient.State.AUTHORIZED, client.state.value)
        // 1 initial connect + 5 reconnect attempts (each ladder step opens a socket).
        assertEquals(6, fake.openedUrls.size)
        assertEquals(2, events.count { it is SignalingClient.Event.Authorized })

        fake.listener?.onTransportClosed(1000, "simulated drop")
        assertEquals(1000L, scheduler.nextDelayMs())
    }

    @Test
    fun `stops retrying once the QR session has expired`() {
        setUp()
        authorize()

        clockMs = EXPIRES_AT * 1000 + 1 // the desktop clock is authoritative (pairing.md)
        fake.listener?.onTransportClosed(1000, "simulated drop")

        assertEquals(SignalingClient.State.FAILED, client.state.value)
        assertEquals(
            listOf(SignalingClient.Event.Failed(SignalingError.EXPIRED)),
            events.filterIsInstance<SignalingClient.Event.Failed>(),
        )
        assertEquals(0, scheduler.pendingCount()) // no auto-retry past expiry (webrtc.md)
    }

    @Test
    fun `a terminal error during reconnect stops the retries`() {
        setUp()
        authorize()
        fake.listener?.onTransportClosed(1000, "simulated drop")
        advance(1000)

        // The desktop regenerated its QR while we were down — the old sid is dead.
        fake.listener?.onTransportOpen()
        fake.sentFrames.removeFirstOrNull() // hello
        fake.listener?.onTransportText(errorFrame("unknown-session"))

        assertEquals(SignalingClient.State.FAILED, client.state.value)
        assertEquals(
            listOf(SignalingClient.Event.Failed(SignalingError.EXPIRED)),
            events.filterIsInstance<SignalingClient.Event.Failed>(),
        )
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun `desktop bye ends the session without a reconnect`() {
        setUp()
        authorize()

        fake.listener?.onTransportText(
            EnvelopeCodec.encode(Envelope.TYPE_BYE, 9, VECTOR_SID, buildJsonObject { put("reason", "window-closed") }),
        )

        assertEquals(SignalingClient.State.CLOSED, client.state.value)
        assertEquals(
            listOf("window-closed"),
            events.filterIsInstance<SignalingClient.Event.SessionEnded>().map { it.reason },
        )
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun `forwards sdp answers and ice candidates to consumers`() {
        setUp()
        authorize()

        fake.listener?.onTransportText(
            EnvelopeCodec.encode(
                Envelope.TYPE_SDP_ANSWER, 10, VECTOR_SID,
                Payloads.sdp(Envelope.PC_MEDIA, SDP_ANSWER),
            ),
        )
        val mdnsCandidate = buildJsonObject { put("candidate", "candidate:842163049 1 udp 1677729535 7f8a9b2c.local 51812 typ host") }
        fake.listener?.onTransportText(
            EnvelopeCodec.encode(Envelope.TYPE_ICE, 11, VECTOR_SID, Payloads.ice(Envelope.PC_MIC, mdnsCandidate)),
        )
        fake.listener?.onTransportText(
            EnvelopeCodec.encode(Envelope.TYPE_ICE, 12, VECTOR_SID, Payloads.ice(Envelope.PC_MIC, null)),
        )

        assertEquals(
            listOf(SignalingClient.Event.SdpAnswer(Envelope.PC_MEDIA, SDP_ANSWER)),
            events.filterIsInstance<SignalingClient.Event.SdpAnswer>(),
        )
        val iceEvents = events.filterIsInstance<SignalingClient.Event.IceCandidate>()
        assertEquals(listOf(Envelope.PC_MIC, Envelope.PC_MIC), iceEvents.map { it.pc })
        assertEquals(mdnsCandidate, iceEvents[0].candidate)
        assertEquals(JsonNull, iceEvents[1].candidate) // end-of-gathering (webrtc.md)
    }

    @Test
    fun `sends media messages with the right envelope shape`() {
        setUp()
        authorize()

        client.sendSdpOffer(Envelope.PC_MEDIA, SDP_OFFER)
        client.sendIceCandidate(Envelope.PC_MEDIA, JsonNull) // end-of-gathering
        client.sendSessionInfo(profile = "balanced", width = 1280, height = 720, fps = 30, gameAudio = true, mic = false)

        val offer = parseFrame(fake.sentFrames.removeFirstOrNull())
        assertEquals(Envelope.TYPE_SDP_OFFER, offer.type)
        assertEquals(Envelope.PC_MEDIA, Payloads.pc(offer))
        assertEquals(SDP_OFFER, Payloads.sdpText(offer))

        val ice = parseFrame(fake.sentFrames.removeFirstOrNull())
        assertEquals(Envelope.TYPE_ICE, ice.type)
        assertEquals(JsonNull, Payloads.iceCandidate(ice))

        val info = parseFrame(fake.sentFrames.removeFirstOrNull())
        assertEquals(Envelope.TYPE_SESSION_INFO, info.type)
        assertEquals("balanced", info.payload["profile"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    private fun parseFrame(raw: String?): Envelope {
        assertTrue(raw != null)
        val parsed = EnvelopeCodec.parse(raw!!)
        assertTrue(parsed is EnvelopeParseResult.Valid)
        return (parsed as EnvelopeParseResult.Valid).envelope
    }

    private fun challengeFrame(): String =
        EnvelopeCodec.encode(Envelope.TYPE_CHALLENGE, 1, VECTOR_SID, buildJsonObject { put("n", VECTOR_NONCE) })

    private fun authOkFrame(desktopName: String): String = EnvelopeCodec.encode(
        Envelope.TYPE_AUTH_OK,
        2,
        VECTOR_SID,
        buildJsonObject {
            put("name", desktopName)
            put("proto", 1)
        },
    )

    private fun errorFrame(code: String): String =
        EnvelopeCodec.encode(Envelope.TYPE_ERROR, 3, VECTOR_SID, buildJsonObject { put("code", code) })

    private companion object {
        const val TEST_UA = "ZeroFrictionCast/0.1.0 (Android15; Pixel8)"
        const val TEST_DESKTOP_NAME = "test-desktop"
        const val VECTOR_SID = "Jm1LIOO4mWm0lRSSl2fClw"
        const val VECTOR_NONCE = "EBESExQVFhcYGRobHB0eHw"
        const val VECTOR_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

        /** Fake expiry far from the clock origin; tests jump past it deliberately. */
        const val EXPIRES_AT = 2_000L

        /** webrtc.md values; the virtual clock keeps the test instant. */
        const val HEARTBEAT_INTERVAL = 5_000L
        const val HEARTBEAT_TIMEOUT = 15_000L

        const val SDP_OFFER = "v=0\r\no=-12 IN IP4 127.0.0.1\r\n"
        const val SDP_ANSWER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n"
    }
}

/** Scripts the desktop's side of the transport; records everything the phone sends. */
private class FakeTransport : SignalingTransport {

    val sentFrames = ArrayDeque<String>()
    val openedUrls = mutableListOf<String>()
    var listener: SignalingTransport.Listener? = null
        private set
    var closeCount = 0
        private set

    override fun open(url: String, listener: SignalingTransport.Listener) {
        openedUrls.add(url)
        this.listener = listener
    }

    override fun send(text: String): Boolean {
        sentFrames.add(text)
        return true
    }

    override fun close(code: Int, reason: String) {
        closeCount += 1
        listener?.onTransportClosed(code, reason)
    }
}

/** Deterministic scheduler: tasks fire on [advance], nothing runs on its own. */
private class FakeScheduler : SignalingScheduler {

    var now = 0L
        private set
    private val tasks = mutableListOf<Task>()

    private class Task(val dueAt: Long, val action: () -> Unit) {
        var canceled = false
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
        val task = Task(now + delayMs, action)
        tasks.add(task)
        return { task.canceled = true }
    }

    fun advance(ms: Long) {
        now += ms
        while (true) {
            val due = tasks.filter { !it.canceled && it.dueAt <= now }.minByOrNull { it.dueAt } ?: break
            tasks.remove(due)
            due.action()
        }
    }

    fun pendingCount(): Int = tasks.count { !it.canceled }

    /** Delay of the next pending task, or null when the ladder is empty. */
    fun nextDelayMs(): Long? =
        tasks.filter { !it.canceled }.minByOrNull { it.dueAt }?.let { it.dueAt - now }
}
