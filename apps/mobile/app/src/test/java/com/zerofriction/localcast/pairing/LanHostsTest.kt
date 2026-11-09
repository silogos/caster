package com.zerofriction.localcast.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The LAN-only host policy (mobile review 2026-11-09, group C). */
class LanHostsTest {

    private val none = emptyList<LanHosts.Subnet>()

    private fun subnet(address: String, prefix: Int) = LanHosts.Subnet(LanHosts.parseIpv4(address)!!, prefix)

    @Test
    fun `home, office and hotspot LAN addresses are accepted`() {
        for (host in listOf("192.168.1.42", "192.168.137.1", "10.0.0.5", "172.16.0.1", "172.31.255.254", "169.254.10.20")) {
            assertTrue(host, LanHosts.isLan(host, none))
        }
    }

    @Test
    fun `internet, loopback, CGNAT and edge-of-range addresses are refused`() {
        for (host in listOf("8.8.8.8", "1.1.1.1", "127.0.0.1", "100.64.0.1", "172.32.0.1", "172.15.255.255", "224.0.0.1", "0.0.0.0")) {
            assertFalse(host, LanHosts.isLan(host, none))
        }
    }

    @Test
    fun `names and malformed literals are refused without any DNS lookup`() {
        for (host in listOf("evil.example.com", "localhost", "192.168.1", "192.168.1.256", "192.168.01.1x", "", "::1", "192.168.1.1.1")) {
            assertFalse(host, LanHosts.isLan(host, none))
        }
        assertNull(LanHosts.parseIpv4("1.2.3.-4"))
    }

    @Test
    fun `a public address on the phone's own subnet is the same LAN - a campus network`() {
        val campus = listOf(subnet("130.89.12.34", 22))
        assertTrue(LanHosts.isLan("130.89.13.7", campus))
        assertFalse(LanHosts.isLan("130.89.16.1", campus)) // outside the /22
        assertFalse(LanHosts.isLan("130.89.13.7", none)) // not on that network now
    }

    @Test
    fun `a zero-length prefix never admits the whole internet`() {
        assertFalse(LanHosts.isLan("8.8.8.8", listOf(subnet("8.8.4.4", 0))))
    }

    @Test
    fun `filter keeps LAN hosts in their order and drops the rest`() {
        assertEquals(
            listOf("192.168.1.42", "10.0.0.2"),
            LanHosts.filter(listOf("203.0.113.9", "192.168.1.42", "evil.example.com", "10.0.0.2"), none),
        )
    }
}
