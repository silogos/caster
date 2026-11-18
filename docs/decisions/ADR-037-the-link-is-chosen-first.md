# ADR-037: The link is chosen first — Wireless or Cable, never both

- **Status:** accepted (2026-11-18): the product owner approved the phone's design (Mobile flow v8) and the receiver's (deck frames 15–24) the same day. The product owner's decisions of 2026-11-18:
  - one link at a time ("agar tidak terjadi bug status"), and the link not chosen is **not run at all** ("ketika pilih wireless kita baru buka connection webrtc, ketika buka cable kita hanya buka connection aoa");
  - **both apps open on a choice, Wireless or Cable**; then a pairing screen: Wireless → the QR (desktop) / Scan (phone), Cable → a cable guide, like TikTok LIVE Studio's (with a driver step on Windows);
  - once paired, both apps open straight on Home; **switching link needs Terminate**;
  - the phone's Home opens on one connection card: the computer, the link and its status, with Disconnect and Terminate in its footer;
  - the QR code is for Wi-Fi only; the cable needs no QR and no Wi-Fi pairing, only a one-time Allow on the computer.

  Earlier drafts (a link picker on Home; Scan first with the cable redirecting by itself) were superseded on review. Amends [ADR-034](ADR-034-usb-cast-over-aoa.md) §5 (the cable no longer borrows the Wi-Fi trust key) and §"Consequences" (a phone on the cable is switched to accessory mode only while the receiver is in Cable mode). Protocol: [usb-link.md](../architecture/usb-link.md) v2.

## Context

Today the two links live side by side, and the cable leans on Wi-Fi:

- The **receiver runs both from launch**: a QR session, the signaling socket on port 52341 and its pairing sweep (Wi-Fi), and the USB thread that polls every second and switches any Android phone plugged in to accessory mode (cable). A phone plugged in only to charge gets Android's "Open Caster?" dialog.
- The **phone** opens Caster's accessory on launch, on resume and on Android's accessory intent, whatever else is connected.
- The cable's handshake accepts only a phone that holds the **trust key of a Wi-Fi pairing** (ADR-034 §5), so a user without a working Wi-Fi (the cable's main use) can't use the cable at all.

So both links can be up at once, and the phone and the Mac disagree about which one carries the cast. Found live 2026-11-18 while testing [ADR-036](ADR-036-a-cast-survives-a-five-minute-drop.md): a cable plugged in during a Wi-Fi cast started a cable cast next to it, and a Wi-Fi drop then looked like a cable state on one side and a Wi-Fi state on the other. ADR-036 makes this worse, because a cast now waits five minutes for *its* link while the other one may come and go.

## Decision

1. **Both apps open on a choice: Wireless or Cable.** When nothing is paired, the first screen on the phone and on the receiver offers the two links. The choice leads to that link's pairing screen:

   | | Wireless | Cable |
   |---|---|---|
   | Receiver | The QR hero (today's first screen), with a way back to the choice | A cable guide whose steps tick as they happen: plug the phone in → tap OK on the phone's "Open Caster" → **Allow this phone** (the one-time Allow, inline in the guide) → start casting from the phone. On Windows, a driver step comes second (below) |
   | Phone | Today's Scan screen, with a way back to the choice | A cable guide: plug into the computer → tap OK on Android's "Open Caster" → allow on the computer → Home |

   The two sides choose separately; a mismatch (Cable on the phone, Wireless on the receiver) shows on the phone's guide as a cable that is never found, with the hint "On the computer, choose Cable too."
2. **The chosen link is the only one that runs**, on both sides. The other is not hidden but stopped:

   | | Wireless chosen | Cable chosen |
   |---|---|---|
   | Receiver · Wi-Fi | QR session, signaling socket (52341), pairing sweep; WebRTC peer connections only while a phone is connected | Stopped: the port is closed, no QR, no sweep, no WebRTC |
   | Receiver · USB | Stopped: no USB thread, no device polling, no accessory switch. A phone plugged in to charge stays an ordinary phone | The USB thread only |
   | Phone · Wi-Fi | Socket, resume, reconnect ladder; WifiLock and WebRTC while casting | Stopped: no socket, no resume, no WebRTC |
   | Phone · USB | The accessory is never opened, no detach receiver | The accessory link and the USB media pipeline (its own encoder, no WebRTC) |

   A receiver-side choice is a receiver control (which input the receiver listens on), not a cast setting: resolution, frame rate, bitrate, audio and mic stay the phone's (AGENTS.md "Mobile controls. Desktop receives.").
3. **One pairing per side, tagged with its link; once paired, both apps open on Home.** The phone keeps at most one paired computer and the receiver at most one paired phone, each with the link it was paired on. With a pairing, the choice screen is skipped: the phone opens on Home, the receiver on its deck (frame 14), each in the pairing's mode. Pairing on one link removes any pairing on the other.
4. **Switching link needs Terminate.** Terminate, on the phone or on the deck, ends the pairing (`bye forget` on its link, both sides forget it) and returns that app to the choice. Disconnect on the phone keeps the pairing, as today. A running cast is stopped first; nothing moves a cast from one link to the other.
5. **Wi-Fi keeps its authentication unchanged**: the QR pairing and the trusted resume of ADR-002/ADR-006. The QR code is for Wi-Fi only.
6. **The cable authenticates itself, with a one-time Allow on the computer** (usb-link.md v2):
   - The first time a phone and a receiver meet on the cable, they make a **cable trust key** by a key exchange over the cable (ECDH P-256, ephemeral keys from both sides). The key never crosses the cable.
   - The receiver asks once, in step 3 of its cable guide: **"Allow <phone> to cast over the cable?"** with Allow and Don't Allow. Only Allow stores the key; it is stored on both sides only after both prove they derived it.
   - **Every later plug-in reconnects by itself, like Wi-Fi's resume:** Android opens Caster for the accessory (its "use by default" from the first plug-in), the stored key proves both sides, and the phone lands on Home. No prompt, no tap.
   - A receiver paired with a phone refuses a different phone on the cable (`busy`) until it is Terminated, as the Wi-Fi side shows no QR while it is paired.
   - A v1 peer (receiver or app) is refused with "update Caster" on the side that can tell, never a half-working link.
7. **The phone's Home opens on one connection card** (the product owner asked for a suggestion on 2026-11-18; this is it). The card replaces today's computer name and its text buttons. It shows the computer's name, the link's glyph, and one status line, **Cable · Connected**, **Wireless · Not connected** or **Cable · Reconnecting…**, in green, grey or amber. A hint follows when something is needed. The pairing's actions sit in the card's footer: Disconnect (Wireless only; the cable's disconnect is unplugging, said there) and, set apart in red, Terminate. The footer is hidden while casting, and the countdown of ADR-036 takes its place on a drop. The big button below is always present and always the next step: Connect (Wireless, not connected), Share screen, greyed Share screen (Cable, not plugged in) or Stop sharing. Home therefore keeps its shape in every state. The rest of Home is as shipped: the profile list (ADR-024) and the audio card. The list belongs to the pairing's link: on the cable, Capture is picked by itself and carries CABLE; on Wireless, the user's pick applies and no row carries CABLE. The cable's own Home card of design v3 goes away. The receiver's deck (frames 19–24) opens on the same card, mirrored: the phone's name, the link and its status, and a footer with a red **Terminate** (the deck's Disconnect is renamed, because it always forgot the phone) and a hint on how to switch link. The footer is hidden while casting; on a drop, ADR-036's countdown takes its place and the annunciator reads RECONNECTING. The deck's "Pair a different phone? Show QR code" goes: switching is Terminate. Below the card, the deck is as shipped (Open video, STATUS, Sound on this computer, Output to OBS). How each mode's Home is coded is the implementation's choice.
8. **The receiver serves one link at a time** by construction (point 2).
9. **Windows (later phase):** the cable guide on Windows adds "Install the USB driver" (WinUSB, installed by the receiver with one Windows permission prompt; "if the phone isn't found, unplug it and plug it in again"). The receiver is macOS-only today (ADR-015, ADR-034 §7), so this step is drawn now and built with the Windows receiver.

## Risks

- **The phone can't prove the far end of the cable is the user's own receiver** (accepted by the product owner, 2026-11-18). Anything that acts as a USB host and speaks the protocol can enrol, if the phone's owner plugs into it and taps through Android's accessory dialog and Share's capture consent. Mitigations: the phone shows the computer's name from the cable (guide and Home) before Share, Android's own capture consent still guards every cast, and the cable is a physical act of the phone's owner.
- **The receiver's Allow is the only gate on the receiver.** It is asked only while the receiver is in Cable mode with no pairing; a phone plugged in by someone else then gets the prompt; a user who taps Allow without reading lets that phone cast. The prompt names the phone (its model, from the cable) and defaults to Don't Allow.
- **No man-in-the-middle check.** A device spliced into the cable could run two key exchanges. Rejected as impractical for the threat (it needs hardware in the user's cable); see "Compare a code" below.
- **A new dependency on the receiver** (`p256`, RustCrypto, the family of `hmac`/`hkdf`/`sha2` already in use). The phone uses the platform's `KeyPairGenerator("EC")` / `KeyAgreement("ECDH")`, available since well before minSdk 29.

## Consequences

- **Positive:** one link carries the whole session, so no state can belong to the other one, and the link not chosen costs nothing (no open port, no USB polling, no WebRTC on the cable). ADR-036's wait is unambiguous. The cable works with no Wi-Fi at all.
- **A phone plugged in only to charge is left alone** while the receiver is in Wireless mode (today it is switched to accessory mode).
- **The first connection takes one more choice on each side.** Accepted: the product owner asked for it, and later launches skip it (point 3).
- **Switching link is deliberate** (Terminate, then pair the other way). A cable plugged in during a Wi-Fi pairing does nothing on either side.
- **Existing users** of the cable meet the Allow once after updating both apps (v1 cable trust was the Wi-Fi key). Existing Wi-Fi pairings carry over as a Wireless pairing: both apps open on Home in Wireless mode.
- **Both apps must update together for the cable** (v1 and v2 refuse each other). Wi-Fi is unaffected.

## Rejected alternatives

| Option | Verdict |
|---|---|
| Ask the link at Share (a dialog per cast) | Rejected by the product owner: the link is a property of the connection, not of each cast |
| A link picker on Home ("CONNECT OVER Wi-Fi \| USB cable", the first draft) | Rejected on review: the link is a pairing's, chosen once before pairing, not at every Connect |
| Scan first on the phone, the cable redirecting to its own auth by itself (the second draft) | Rejected on review: both apps choose first, and only the chosen link runs |
| The phone sends a link choice and the receiver follows it | Rejected: the receiver must know which link to run before any phone talks to it (that is what keeps the other link stopped) |
| Keep the automatic cable and only fix the status bugs | Rejected: the bugs come from two live links; fixing each mixed state one by one leaves the next one |
| Keep cable auth on the Wi-Fi trust key (v1) | Rejected by the product owner: the cable must work for a phone that never paired over Wi-Fi |
| A QR for the cable too (scan, then plug in) | Rejected by the product owner: the QR is Wi-Fi's; the cable is already a physical, deliberate pairing act |
| The phone sends a random key in the clear after Allow | Rejected: a USB capture (a logging hub, an analyser, the Mac's USB logs) would hold a key that authenticates every later plug-in. ECDH costs one exchange and keeps the key off the wire |
| Compare a short code on both screens during the first Allow | Not now: it defends only against hardware spliced into the cable, and costs the user a step the product owner did not ask for. The transcript hash it needs is in v2 (`enrol-ok` binds both public keys), so it can be added as a display-only line later without a version bump |
| Keep a pairing per link (a phone paired on both) | Rejected on review: switching link is a Terminate, so a second live pairing would only be a way back to two links |
| No Allow prompt (any phone on the cable is trusted) | Rejected: any phone plugged into the Mac, by anyone, could cast to it |

## Verification

Done (2026-11-18):
- **The cable key agrees across the two apps:** the receiver computes the vectors with the `p256` crate (`usb_protocol.rs`), the phone pins them and derives the same Z, transcript, key and both macs with the platform's EC provider (`UsbHandshakeTest`).
- **JVM, 452 tests green:** `UsbHandshakeTest` (enrol → Asking → Trusted with the key to store; stored key; stale key → enrol; refusals: not-allowed, busy, another computer, older computer; a point off the curve), `LinkUiTest` (every Home state of the design, and that each link reads only its own state), `UsbFrameTest` (v2 header, a v1 frame refused).
- **`cargo test --lib`, 128 green:** the handshake (Allow pairs, the next plug-in is trusted with no prompt, Don't Allow refuses and isn't asked again on the attach, busy, a peer that can't derive the key never reaches the prompt, a point off the curve closes, ready mid-prompt drops it); one pairing at a time (`store_pairing` removes the other link's, `forget_pairing` → the choice).
- **`node --test`, 14 green:** the receiver window's reducer and view (`window_state.test.mjs`): every deck state of the design, the clock running through a drop and stopping when the five minutes run out, a mid-cast unplug reading RECONNECTING, another link starting clean, the cable guide.
- **The receiver on this Mac** (a scratch data folder): nothing paired → no listening socket and no USB thread; a cable pairing → the USB thread only, no socket; a Wi-Fi pairing → signaling only, no USB thread.

To do on the device (Lenovo TB321FU, the receiver on the Mac):
- both on Cable → plug in → Allow in the receiver's guide → Home (Cable · Connected);
- unplug and plug in → Home, no prompt;
- Don't Allow → the phone says so;
- receiver on Wireless, plug the phone in → nothing on either side (no accessory dialog);
- Terminate on the phone → choice → Wireless → Scan → Home (Wireless · Connected).
