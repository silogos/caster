# ADR-037: The link is chosen at Connect: cable or Wi-Fi, never both

- **Status:** proposed (2026-11-18). The product owner's ("awal connect harus milih pakai cable atau wifi", "agar tidak terjadi bug status", 2026-11-18). Amends [ADR-034](ADR-034-usb-cast-over-aoa.md) (the cable is used by itself whenever it is in).

## Context

Today the two links live side by side:

- The **cable** connects by itself. `MainActivity` opens Caster's accessory on launch, on resume and on Android's accessory intent, and once the desktop proves the key, Home's one Share screen casts over the cable.
- **Wi-Fi** connects when the user taps Connect (or scans a QR).

So both can be up at once, and the phone and the Mac disagree about which one carries the cast. Found live 2026-11-18 while testing [ADR-036](ADR-036-a-cast-survives-a-five-minute-drop.md): a cable plugged in during a Wi-Fi cast started a cable cast next to it, and a Wi-Fi drop then looked like a cable state on one side and a Wi-Fi state on the other. ADR-036 makes this worse, because a cast now waits five minutes for *its* link while the other one may come and go.

## Decision

1. **One link per connection, chosen at Connect.** Home's saved-desktop hero gets a two-way choice, **Wi-Fi | USB cable**, above Connect. The choice is stored (`CastLinkChoice`, the last one wins; Wi-Fi until the user picks the cable).
2. **Wi-Fi chosen:** Connect resumes the saved session as today. The cable is **ignored**: `UsbLink` does not open the accessory, so the receiver never authenticates it and its deck stays on Wi-Fi. A cable plugged in meanwhile shows one quiet line on Home ("A cable is in. To cast over it, disconnect and choose USB cable.").
3. **USB cable chosen:** Connect opens the accessory and runs the cable handshake (usb-link.md). With no cable yet, Home waits for one ("Plug the cable into the Mac") with Cancel. The Wi-Fi socket is not opened. While the cable stays the choice, plugging it in connects by itself, as today.
4. **Switching** is Disconnect, choose the other, Connect. A running cast is stopped first; nothing moves a cast from one link to the other (ADR-036 already resumes a cast only on its own link).
5. **Pairing stays Wi-Fi only.** Scanning the QR needs the network; after a first pairing the choice is Wi-Fi.
6. **Status follows the chosen link only:** the annunciator, the link card ("Connected by Wi-Fi" / "Connected by cable") and the waiting card of ADR-036.
7. **No protocol change, no receiver change.** The receiver keeps claiming any Android device on the cable and sending `hello`; a phone that chose Wi-Fi just doesn't answer.

## Consequences

- **Positive:** one link carries the whole session, so no state can belong to the other one. ADR-036's wait is unambiguous.
- **A cable plugged in by mistake does nothing** while Wi-Fi is chosen. Android may still ask once to open Caster for the accessory (its dialog, with "use by default"); the app opens and ignores it.
- **One more tap to change links.** Accepted: the user asked for an explicit choice.
- **Android's accessory permission** is asked when the cable is chosen and plugged in, not at every plug-in while Wi-Fi is chosen.

## Rejected alternatives

- **Ask at Share screen** (a dialog per cast). The product owner chose the connect-time choice: the link is a property of the connection, not of each cast.
- **Keep the automatic cable and only fix the status bugs.** The bugs come from two live links; fixing each mixed state one by one leaves the next one.

## Verification (to do)

- JVM: the choice is stored and restored; `UsbLink` refuses to open the accessory while Wi-Fi is chosen; the Wi-Fi connect is not started while the cable is chosen.
- Device: Wi-Fi chosen, plug the cable → nothing changes on either side; cable chosen without a cable → waiting state, plug in → connected by cable; Disconnect → choose Wi-Fi → Connect → connected by Wi-Fi.
