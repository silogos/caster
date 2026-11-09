package com.zerofriction.localcast.pairing

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LAN-only host policy (AGENTS.md: "LAN only. No internet relay"): which of a
 * desktop's advertised hosts the phone may open a socket to. Pure and
 * JVM-testable; [LocalSubnets] reads the phone's own interfaces.
 *
 * Why: the QR's `h` (and `auth-ok.hosts`) used to be trusted as-is, so a
 * crafted QR could point the phone at any internet host — and, with the
 * user's own tap on "Start", the screen cast after it (mobile review
 * 2026-11-09, group C). A host is accepted when it is an **IPv4 literal**
 * (pairing.md: `h` carries IPv4 addresses — never a name, which would need
 * DNS) and either
 *  - in a private or link-local range (RFC 1918 + RFC 3927) — every home,
 *    office and phone-hotspot LAN, or
 *  - inside a subnet the phone itself is on right now — a LAN that hands out
 *    public addresses (some campus networks) is still the same LAN.
 * Loopback, multicast, CGNAT/VPN ranges and everything else are refused.
 */
object LanHosts {

    /** One IPv4 subnet of a local interface (address + prefix length). */
    data class Subnet(val address: Int, val prefixLength: Int) {
        fun contains(host: Int): Boolean {
            if (prefixLength <= 0) return false // a /0 would admit the whole internet
            val mask = if (prefixLength >= 32) -1 else (-1 shl (32 - prefixLength))
            return (host and mask) == (address and mask)
        }
    }

    /** The hosts the phone may contact, in their original order. */
    fun filter(hosts: List<String>, localSubnets: List<Subnet>): List<String> =
        hosts.filter { isLan(it, localSubnets) }

    fun isLan(host: String, localSubnets: List<Subnet>): Boolean {
        val address = parseIpv4(host) ?: return false
        return PRIVATE_RANGES.any { it.contains(address) } || localSubnets.any { it.contains(address) }
    }

    /** A dotted-quad IPv4 literal as an Int, or null — never resolves a name. */
    fun parseIpv4(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val octet = part.toInt()
            if (octet > 255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    private fun subnet(cidr: String): Subnet {
        val (address, prefix) = cidr.split('/')
        return Subnet(checkNotNull(parseIpv4(address)), prefix.toInt())
    }

    /** RFC 1918 private ranges + RFC 3927 link-local. */
    private val PRIVATE_RANGES = listOf(
        subnet("10.0.0.0/8"),
        subnet("172.16.0.0/12"),
        subnet("192.168.0.0/16"),
        subnet("169.254.0.0/16"),
    )
}

/** The phone's current IPv4 subnets — the "same LAN" half of [LanHosts]. */
object LocalSubnets {
    fun current(): List<LanHosts.Subnet> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { entry ->
                val address = entry.address as? Inet4Address ?: return@mapNotNull null
                LanHosts.parseIpv4(address.hostAddress ?: return@mapNotNull null)
                    ?.let { LanHosts.Subnet(it, entry.networkPrefixLength.toInt()) }
            }
    } catch (_: Exception) {
        emptyList() // no interface data: the private ranges alone still apply
    }
}
