# ADR-027: The product is called Caster, with one icon

- **Status:** accepted (2026-11-11): the name is the product owner's decision; the icon is the proposal drawn in the design file (`designs/mobile-app.figcraft`, "The name and the icon"), built and awaiting the product owner's confirmation. Unit-tested and built; see Consequences for what was seen running.

## Context

Before the second release the product carried three names: "Local Cast" on the phone, "Zero-Friction Cast" in the receiver's tray and video window, and "Zero-Friction Local Cast Native" as the Mac app. The repository was already `caster`. The phone's launcher icon was a placeholder (its waves ran into its screen), and the Mac app's icon was an empty dark square.

## Decision

1. **The name is Caster** wherever a person reads it: the phone's launcher label and app bar, the Mac app, its window, tray, nameplate, video window and wake assertion, the Syphon source, the release's files and title.
2. **Nothing a machine keys on changes**: the Android application id (`com.zerofriction.localcast`), the Mac bundle identifier (`app.zerofriction.desktopnative`), the signing key (`CN=Zero-Friction Local Cast`), the receiver's data folder (`~/Library/Application Support/Zero-Friction Local Cast`), the QR payload and the handshake's client string. Changing any of them would refuse an update or lose saved pairings.
3. **One icon**: the mark that already sat beside the receiver's nameplate (a screen with two waves from its corner), white on an orange tile (`#F0603F`). It is drawn once (`tools/design/build_app_icon.py` → `designs/app-icon.figcraft`), rendered for the Mac app and the menu-bar template, and written as a vector for the phone's adaptive icon (with a monochrome layer for themed icons).

| Icon option | Result |
|---|---|
| The phone's launcher glyph | Rejected: a placeholder; the waves merge into the screen |
| The Material cast glyph (the phone's notification icon) | Rejected as the product's face: it is every cast app's. It stays as the notification icon, which Android draws in one colour |
| **The receiver's mark** | **Chosen**: already the product's own, and legible at menu-bar size |
| Blue instead of orange | Drawn beside it in the design file; orange is the tile the receiver already shows |

## Consequences

- **OBS:** the Syphon source is now "Caster". A scene that used "Zero-Friction Local Cast" must pick the source again once.
- The menu-bar image is its own template file (`icons/tray-template.png`): the app icon is a filled tile, which a template would draw as a square.
- Older documents (ADRs, roadmap entries, measurements) keep the name they were written under.
- The phone's app bar still writes the name in mixed case ("Caster") and the receiver's nameplate in capitals ("CASTER"), as before the rename.
- **Revisit if** the app is published to a store: "Caster" is a common name there.
