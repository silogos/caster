# ADR-037: One link per connection — the one you connect with, cable or Wi-Fi

- **Status:** proposed (2026-11-18, revised twice the same day). The product owner's decisions of 2026-11-18: one link at a time, "agar tidak terjadi bug status"; the QR code is for Wi-Fi only; the cable needs no QR and no Wi-Fi pairing, only a one-time Allow on the Mac. Second revision, after reviewing the first draft: no link choice on Home — "halaman awal itu scan qr, tapi ketika cable dicolokan dia akan redirect ke auth cable … webrtc tetap pakai auth sebelumnya … di halaman home tidak ada perubahan, paling cuman tag atau label". Amends [ADR-034](ADR-034-usb-cast-over-aoa.md) §5 (the cable no longer borrows the Wi-Fi trust key) and §"Consequences" (the cable connects by itself only while no Wi-Fi connection is up). Protocol: [usb-link.md](../architecture/usb-link.md) v2.

## Context

Today the two links live side by side, and the cable leans on Wi-Fi:

- The **cable** connects by itself. `MainActivity` opens Caster's accessory on launch, on resume and on Android's accessory intent. Once the desktop proves the key, Home's one Share screen casts over the cable.
- The cable's handshake accepts only a phone that holds the **trust key of a Wi-Fi pairing** (ADR-034 §5). A phone that never scanned the QR over Wi-Fi is told to pair first, so a user without a working Wi-Fi (the cable's main use) can't use the cable at all.
- **Wi-Fi** connects when the user taps Connect or scans a QR.

So both links can be up at once, and the phone and the Mac disagree about which one carries the cast. Found live 2026-11-18 while testing [ADR-036](ADR-036-a-cast-survives-a-five-minute-drop.md): a cable plugged in during a Wi-Fi cast started a cable cast next to it, and a Wi-Fi drop then looked like a cable state on one side and a Wi-Fi state on the other. ADR-036 makes this worse, because a cast now waits five minutes for *its* link while the other one may come and go.

## Decision

1. **The link is the one the phone connected with; nothing is chosen up front.** There is no link picker. Scanning the QR (or Home's Connect for a saved desktop) connects over Wi-Fi; plugging the cable in connects over the cable. Whichever connects first owns the connection until it ends, and the other link is **ignored** on the phone, not merely hidden:
   - **Connected (or connecting) over Wi-Fi:** `UsbLink` does not open the accessory, so the receiver never hears from this phone on the cable and its deck stays on Wi-Fi. Home shows nothing about the cable.
   - **Connected (or connecting) over the cable:** no Wi-Fi socket is opened, no resume, no reconnect ladder.
   - **Not connected:** plugging the cable in takes over at once, wherever the user is (Scan or Home).
2. **The first screen stays Scan QR code**, as today (scan-first launch). Plugging the cable in **redirects to a cable auth screen** (from Scan, or from Home while not connected): "Connecting by cable", the Mac's name from the cable, and its states — checking, waiting for the Mac's Allow, not allowed. Once the cable is trusted, the screen hands over to Home. A phone the Mac already allowed passes through it in a moment.
3. **Wi-Fi keeps its authentication unchanged**: the QR pairing and the trusted resume of ADR-002/ADR-006. The QR code is for Wi-Fi only; the cable never asks for it.
4. **The cable authenticates itself, with a one-time Allow on the Mac** (usb-link.md v2):
   - The first time a phone and a Mac meet on the cable, they make a **cable trust key** by a key exchange over the cable (ECDH P-256, ephemeral keys from both sides). The key never crosses the cable.
   - The Mac asks once: **"Allow <phone> to cast over the cable?"** with Allow and Don't Allow. Only Allow stores the key; it is stored on both sides only after both prove they derived it.
   - Every later plug-in authenticates both ways with that key (the v1 HMAC proofs, the cable key in place of the Wi-Fi trust key) and asks nothing.
   - A v1 peer (receiver or app) is refused with "update Caster" on the side that can tell, never a half-working link.
5. **Cable trust and Wi-Fi trust are two separate entries on each side.** Different keys, made by different ceremonies, revoked separately:
   - The Mac keeps its single Wi-Fi entry (`trust.json`, ADR-006) and a separate list of allowed phones for the cable (`cable-trust.json`).
   - The phone keeps its saved Wi-Fi desktop and, separately, the Macs that allowed it on the cable.
   - Ending one never ends the other. A phone paired over Wi-Fi meets the Allow prompt once on its first plug-in.
6. **Home is unchanged except for one tag.** In the app bar, beside the status (STANDBY / ON AIR), a tag says how this connection runs: **WI-FI** or **CABLE**. The cable's own Home card (design v3, "Connected by cable") goes away: the cable auth screen carries its states, and Home is the same screen for both links. Disconnect and Terminate act on the connected link.
7. **Switching** is Disconnect on Home (or unplug, for the cable), then connect the other way. A running cast is stopped first; nothing moves a cast from one link to the other (ADR-036 already resumes a cast only on its own link).
8. **The receiver serves one link at a time.** While a phone is connected on one link, the receiver refuses the other (`busy` on Wi-Fi as today; `busy` on the cable). Its deck and tray show only the link that is connected.

## Risks

- **The phone can't prove the far end of the cable is the user's own receiver** (accepted by the product owner, 2026-11-18). Anything that acts as a USB host and speaks the protocol can enrol, if the phone's owner plugs into it and taps through Android's accessory dialog and Share's capture consent. Mitigations: the phone shows the desktop's name from the cable on Home before Share, Android's own capture consent still guards every cast, and the cable is a physical act of the phone's owner.
- **The Mac's Allow is the only gate on the receiver.** A phone plugged in by someone else gets the prompt; a user who taps Allow without reading lets that phone cast. The prompt names the phone (its model, from the cable) and defaults to Don't Allow.
- **No man-in-the-middle check.** A device spliced into the cable could run two key exchanges. Rejected as impractical for the threat (it needs hardware in the user's cable); see "Compare a code" below.
- **A new dependency on the receiver** (`p256`, RustCrypto, the family of `hmac`/`hkdf`/`sha2` already in use). The phone uses the platform's `KeyPairGenerator("EC")` / `KeyAgreement("ECDH")`, available since well before minSdk 29.

## Consequences

- **Positive:** one link carries the whole session, so no state can belong to the other one. ADR-036's wait is unambiguous. The cable works with no Wi-Fi at all, which is when it is needed most.
- **A cable plugged in by mistake does nothing** during a Wi-Fi connection. Android may still ask once to open Caster for the accessory (its dialog, with "use by default"); the app opens and ignores it.
- **A cable plugged in during a Wi-Fi connection is ignored silently** (the product owner wants Home unchanged). To cast over it, the user disconnects first; the cable then takes over by itself.
- **Existing users of the cable** meet the Allow prompt once after updating both apps, because v1 cable trust was the Wi-Fi key and v2 does not read it.
- **Both apps must update together for the cable** (v1 and v2 refuse each other). Wi-Fi is unaffected.
- **Android's accessory permission** is asked on a plug-in while not connected over Wi-Fi.

## Rejected alternatives

| Option | Verdict |
|---|---|
| Ask the link at Share (a dialog per cast) | Rejected by the product owner: the link is a property of the connection, not of each cast |
| A link picker on Home ("CONNECT OVER Wi-Fi \| USB cable", the first draft of this ADR) and a first screen offering both | Rejected by the product owner on review: the first screen stays Scan, the cable redirects by itself, and Home changes by one tag only |
| Keep the automatic cable and only fix the status bugs | Rejected: the bugs come from two live links; fixing each mixed state one by one leaves the next one |
| Keep cable auth on the Wi-Fi trust key (v1) | Rejected by the product owner: the cable must work for a phone that never paired over Wi-Fi |
| A QR for the cable too (scan, then plug in) | Rejected by the product owner: the QR is Wi-Fi's; the cable is already a physical, deliberate pairing act |
| The phone sends a random key in the clear after Allow | Rejected: a USB capture (a logging hub, an analyser, the Mac's USB logs) would hold a key that authenticates every later plug-in. ECDH costs one exchange and keeps the key off the wire |
| Compare a short code on both screens during the first Allow | Not now: it defends only against hardware spliced into the cable, and costs the user a step the product owner did not ask for. The transcript hash it needs is in v2 (`enrol-ok` binds both public keys), so it can be added as a display-only line later without a version bump |
| One trust entry per peer for both links | Rejected: a Wi-Fi Disconnect would silently end the cable trust (or the other way round), and the two ceremonies (QR secret vs. ECDH + Allow) would have to share one key's lifetime |
| No Allow prompt (any phone on the cable is trusted) | Rejected: any phone plugged into the Mac, by anyone, could cast to it |

## Verification (to do)

- JVM: `UsbLink` refuses to open the accessory while connected or connecting over Wi-Fi; the Wi-Fi connect and resume are not started while the cable is connected; a plug-in while not connected leads to the cable auth screen; the v2 handshake (enrol, auth, refusals) against the vectors shared with the receiver.
- `cargo test --lib`: the receiver's half of the same vectors; the cable trust list stores, finds and forgets; a v1 frame is refused.
- Device (Lenovo TB321FU, the receiver on the Mac): on Scan, plug in → cable auth → Allow on the Mac → Home tagged CABLE; unplug and plug in again → Home, no prompt; Don't Allow → the phone says so and does not ask again until replugged; connected over Wi-Fi, plug the cable → nothing changes on either side; Disconnect → plug in → cable, and unplug → Connect → Wi-Fi.
