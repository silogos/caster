# ADR-037: The link is chosen at Connect: cable or Wi-Fi, never both

- **Status:** proposed (2026-11-18, revised the same day). The product owner's decisions of 2026-11-18: "awal connect harus milih pakai cable atau wifi", "agar tidak terjadi bug status"; the QR code is for Wi-Fi only; the cable needs no QR and no Wi-Fi pairing, only a one-time Allow on the Mac. Amends [ADR-034](ADR-034-usb-cast-over-aoa.md) §5 (the cable no longer borrows the Wi-Fi trust key) and §"Consequences" (the cable is no longer used by itself whenever it is in). Protocol: [usb-link.md](../architecture/usb-link.md) v2.

## Context

Today the two links live side by side, and the cable leans on Wi-Fi:

- The **cable** connects by itself. `MainActivity` opens Caster's accessory on launch, on resume and on Android's accessory intent. Once the desktop proves the key, Home's one Share screen casts over the cable.
- The cable's handshake accepts only a phone that holds the **trust key of a Wi-Fi pairing** (ADR-034 §5). A phone that never scanned the QR over Wi-Fi is told to pair first, so a user without a working Wi-Fi (the cable's main use) can't use the cable at all.
- **Wi-Fi** connects when the user taps Connect or scans a QR.

So both links can be up at once, and the phone and the Mac disagree about which one carries the cast. Found live 2026-11-18 while testing [ADR-036](ADR-036-a-cast-survives-a-five-minute-drop.md): a cable plugged in during a Wi-Fi cast started a cable cast next to it, and a Wi-Fi drop then looked like a cable state on one side and a Wi-Fi state on the other. ADR-036 makes this worse, because a cast now waits five minutes for *its* link while the other one may come and go.

## Decision

1. **One link per connection, chosen at Connect and remembered.** Home's saved-desktop hero gets a two-way choice, **CONNECT OVER Wi-Fi | USB cable**, above Connect. The phone stores it (`CastLinkChoice`; the last choice wins). While one link is chosen, the other is **ignored** on the phone, not merely hidden:
   - **Wi-Fi chosen:** `UsbLink` never opens the accessory, so the receiver never hears from this phone on the cable and its deck stays on Wi-Fi. A cable plugged in meanwhile shows one quiet line on Home ("A cable is in. To cast over it, disconnect and choose USB cable.").
   - **USB cable chosen:** no Wi-Fi socket is opened, no resume, no reconnect ladder. With no cable in, Home waits for one ("Plug the cable into the Mac") with Cancel. While the cable stays the choice, plugging it in connects by itself, as today.
2. **The first screen offers both links.** With nothing saved, Home's empty state offers **Scan QR code (Wi-Fi)** or **Plug the cable into the Mac**. Scanning a QR chooses Wi-Fi; a cable that opens the app while nothing is chosen (Android's accessory dialog) chooses the cable. After that, the choice above holds.
3. **The QR code is for Wi-Fi only.** It carries LAN addresses and a Wi-Fi pairing secret, and nothing else. The cable never asks for it, and the Scan screen loses its "cable but no pairing" state (design v3, U4).
4. **The cable authenticates itself, with a one-time Allow on the Mac** (usb-link.md v2):
   - The first time a phone and a Mac meet on the cable, they make a **cable trust key** by a key exchange over the cable (ECDH P-256, ephemeral keys from both sides). The key never crosses the cable.
   - The Mac asks once: **"Allow <phone> to cast over the cable?"** with Allow and Don't Allow. Only Allow stores the key; it is stored on both sides only after both prove they derived it.
   - Every later plug-in authenticates both ways with that key (the v1 HMAC proofs, the cable key in place of the Wi-Fi trust key) and asks nothing.
   - A v1 peer (receiver or app) is refused with "update Caster" on the side that can tell, never a half-working link.
5. **Cable trust and Wi-Fi trust are two separate entries on each side.** Different keys, made by different ceremonies, revoked separately:
   - The Mac keeps its single Wi-Fi entry (`trust.json`, ADR-006) and a separate list of allowed phones for the cable (`cable-trust.json`).
   - The phone keeps its saved Wi-Fi desktop and, separately, the Macs that allowed it on the cable.
   - The Mac's Disconnect on a cable connection forgets that phone's cable entry (and tells the phone, `bye forget`); its Wi-Fi Disconnect forgets only the Wi-Fi entry. A phone paired over Wi-Fi meets the Allow prompt once on its first plug-in.
6. **Switching** is Disconnect, choose the other, Connect. A running cast is stopped first; nothing moves a cast from one link to the other (ADR-036 already resumes a cast only on its own link).
7. **The receiver serves one link at a time.** While a phone is connected on one link, the receiver refuses the other (`busy` on Wi-Fi as today; `busy` on the cable). Its deck and tray show only the link that is connected.
8. **Status follows the chosen link only:** the annunciator, the link card ("Connected by Wi-Fi" / "Connected by cable") and the waiting card of ADR-036.

## Risks

- **The phone can't prove the far end of the cable is the user's own receiver** (accepted by the product owner, 2026-11-18). Anything that acts as a USB host and speaks the protocol can enrol, if the phone's owner plugs into it and taps through Android's accessory dialog and Share's capture consent. Mitigations: the phone shows the desktop's name from the cable on Home before Share, Android's own capture consent still guards every cast, and the cable is a physical act of the phone's owner.
- **The Mac's Allow is the only gate on the receiver.** A phone plugged in by someone else gets the prompt; a user who taps Allow without reading lets that phone cast. The prompt names the phone (its model, from the cable) and defaults to Don't Allow.
- **No man-in-the-middle check.** A device spliced into the cable could run two key exchanges. Rejected as impractical for the threat (it needs hardware in the user's cable); see "Compare a code" below.
- **A new dependency on the receiver** (`p256`, RustCrypto, the family of `hmac`/`hkdf`/`sha2` already in use). The phone uses the platform's `KeyPairGenerator("EC")` / `KeyAgreement("ECDH")`, available since well before minSdk 29.

## Consequences

- **Positive:** one link carries the whole session, so no state can belong to the other one. ADR-036's wait is unambiguous. The cable works with no Wi-Fi at all, which is when it is needed most.
- **A cable plugged in by mistake does nothing** while Wi-Fi is chosen. Android may still ask once to open Caster for the accessory (its dialog, with "use by default"); the app opens and ignores it.
- **One more tap to change links.** Accepted: the user asked for an explicit choice.
- **Existing users of the cable** meet the Allow prompt once after updating both apps, because v1 cable trust was the Wi-Fi key and v2 does not read it.
- **Both apps must update together for the cable** (v1 and v2 refuse each other). Wi-Fi is unaffected.
- **Android's accessory permission** is asked when the cable is chosen (or on the first screen) and plugged in, not at every plug-in while Wi-Fi is chosen.

## Rejected alternatives

| Option | Verdict |
|---|---|
| Ask the link at Share (a dialog per cast) | Rejected by the product owner: the link is a property of the connection, not of each cast |
| Keep the automatic cable and only fix the status bugs | Rejected: the bugs come from two live links; fixing each mixed state one by one leaves the next one |
| Keep cable auth on the Wi-Fi trust key (v1) | Rejected by the product owner: the cable must work for a phone that never paired over Wi-Fi |
| A QR for the cable too (scan, then plug in) | Rejected by the product owner: the QR is Wi-Fi's; the cable is already a physical, deliberate pairing act |
| The phone sends a random key in the clear after Allow | Rejected: a USB capture (a logging hub, an analyser, the Mac's USB logs) would hold a key that authenticates every later plug-in. ECDH costs one exchange and keeps the key off the wire |
| Compare a short code on both screens during the first Allow | Not now: it defends only against hardware spliced into the cable, and costs the user a step the product owner did not ask for. The transcript hash it needs is in v2 (`enrol-ok` binds both public keys), so it can be added as a display-only line later without a version bump |
| One trust entry per peer for both links | Rejected: a Wi-Fi Disconnect would silently end the cable trust (or the other way round), and the two ceremonies (QR secret vs. ECDH + Allow) would have to share one key's lifetime |
| No Allow prompt (any phone on the cable is trusted) | Rejected: any phone plugged into the Mac, by anyone, could cast to it |

## Verification (to do)

- JVM: the choice is stored and restored; `UsbLink` refuses to open the accessory while Wi-Fi is chosen; the Wi-Fi connect is not started while the cable is chosen; the v2 handshake (enrol, auth, refusals) against the vectors shared with the receiver.
- `cargo test --lib`: the receiver's half of the same vectors; the cable trust list stores, finds and forgets; a v1 frame is refused.
- Device (Lenovo TB321FU, the receiver on the Mac): first plug-in → Allow on the Mac → connected by cable; unplug and plug in again → connected, no prompt; Don't Allow → the phone says so and does not ask again until replugged; Wi-Fi chosen, plug the cable → nothing changes on either side; Disconnect → choose the other link → Connect, both ways.
