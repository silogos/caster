package com.zerofriction.localcast.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpSessionTest {
    private val answer = "v=0\r\no=- 3906528114416374020 2 IN IP4 127.0.0.1\r\ns=-\r\n"

    @Test
    fun `the id is the origin line's second field`() {
        assertEquals("3906528114416374020", SdpSession.id(answer))
    }

    @Test
    fun `no origin line means no id`() {
        assertNull(SdpSession.id("v=0\r\ns=-\r\n"))
    }

    @Test
    fun `the same remote pc answering again is not a new remote`() {
        val again = answer.replace(" 2 IN IP4", " 3 IN IP4")
        assertFalse(SdpSession.isNewRemote(SdpSession.id(answer), SdpSession.id(again)))
    }

    @Test
    fun `a restarted desktop answering an ice restart is a new remote`() {
        val restarted = answer.replace("3906528114416374020", "8841204415870311956")
        assertTrue(SdpSession.isNewRemote(SdpSession.id(answer), SdpSession.id(restarted)))
    }

    @Test
    fun `the first answer on a pc is never a new remote`() {
        assertFalse(SdpSession.isNewRemote(null, SdpSession.id(answer)))
    }
}
