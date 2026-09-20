import { networkInterfaces } from 'node:os'

/**
 * Enumerate the desktop's candidate LAN IPv4 addresses for the QR payload.
 * docs/architecture/pairing.md: `h` = ALL candidate IPv4 addresses on
 * non-loopback interfaces — the mobile tries them in order, which survives
 * multi-interface/VPN ambiguity that breaks single-IP designs.
 */
export function getLanIpv4Hosts(): string[] {
  const hosts: string[] = []
  for (const interfaces of Object.values(networkInterfaces())) {
    for (const net of interfaces ?? []) {
      if (net.family === 'IPv4' && !net.internal) {
        hosts.push(net.address)
      }
    }
  }
  return hosts
}
