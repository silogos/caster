# Design files from the shipped UI

`designs/mobile-app.figcraft` is the mobile app **as it ships** (the amber broadcast console), measured from the real Compose UI rather than redrawn, and built the way a designer builds in Figma: **nested auto-layout frames**, not absolutely placed layers. Regenerate it after a UI change, so the design and the app never drift apart.

**Compose → auto layout:**

| Compose | FigCraft |
|---|---|
| `Column` / `Row` | Vertical / horizontal auto layout. The gap is the arrangement's spacing; main alignment comes from the arrangement and cross alignment from the alignment. |
| `Box` with one child | Auto layout aligned like the Box. |
| `Box` with several children (overlays) | A plain frame. |
| `padding` modifiers | Frame padding: the node's outer box minus its inner box. |
| `fillMax*` / `weight` | `fill`. A child a Surface stretches is also `fill`. |
| `size` / `width` / `height` | `fixed`. Anything else is `hug`, with min sizes where the measurement honored them. |
| `RowScope`/`ColumnScope.align` | A wrapper spanning the cross axis. |
| `offset` (e.g. a Switch thumb) | An absolute layer where it is drawn. |
| Background / border / clip on the node's own box | The frame's fill, stroke and radius. Other drawings (hairlines, rings, dots) become absolute layers.

## Pipeline

1. **Measure.** `DesignExportTest` (apps/mobile, Robolectric with native text metrics) composes every screen state at a 412dp phone width. For each state it writes a JSON file and a PNG of the real render:
   - **JSON:** the **layout tree**. Each node has its outer and inner box, its measure policy (Row/Column/Box with arrangement and alignment), its size modifiers, and what it draws: backgrounds, borders, `clip` shapes, text, icons (the vector drawables' own path data) and `drawBehind` drawings (recorded through a canvas).
   - **PNG:** the real render, to compare against.

   It is opt-in, so CI skips it:

   ```bash
   cd apps/mobile && DESIGN_EXPORT_DIR=/tmp/caster-design ./gradlew :app:testDebugUnitTest --tests '*DesignExportTest'
   ```

2. **Build.**

   ```bash
   python3 -I tools/design/build_custom_profiles_spec.py /tmp/caster-design/custom-profiles.figcraft
   python3 -I tools/design/build_mobile_figcraft.py /tmp/caster-design designs/mobile-app.figcraft \
     --spec designs/mobile-ux-a11y.figcraft --spec /tmp/caster-design/custom-profiles.figcraft
   ```

   This produces three pages (one measured, then one per `--spec`):
   - **Mobile · as shipped:** twelve states. Row 1 is Home: standby, starting, on air, auto quality stepped down, fault, saved/offline, reconnect failed, and mic needs permission. Row 2 is Profile · Edit (one of the user's profiles, ADR-024) plus Scan: camera permission, pairing, and failed.
   - **Group F · UX & a11y:** the accessibility spec, copied in. It comes from `build_group_f_spec.py`, which writes `designs/mobile-ux-a11y.figcraft`. Its layers are auto layout too, placed by `autolayout.solve()`, so nothing is hand-placed; only the annotations (badges, 48dp outlines) are absolute.
   - **Mobile design v2 · custom profiles (draft, #165):** a proposal, not the shipped UI: the profile list on Home, editing and adding a profile, the row menu, and Home without an Advanced page (a profile holds every picture setting; “Under network pressure” and “Automatic quality” are drawn as one field). Its last frame lists the issue's open questions and what the draft assumes for each. It comes from `build_custom_profiles_spec.py` and lives only as a page of this file.

   Both specs are written with `spec_dsl.py`: the shipped amber tokens, frames with auto-layout parameters, and ports of the app's components. A design that is not built yet goes in as a new page the same way (a builder script, one more `--spec`), named `Mobile design v<n> · <what>`.

3. **Check.**
   - **Auto layout.** FigCraft keeps stored positions until something is edited, so opening the file does not prove the auto layout is right. `check_auto_layout.py` re-runs the layout from the parameters alone (`autolayout.py`, Figma's rules) and fails on any layer that lands more than 1.5dp from where Compose measured it:

     ```bash
     python3 -I tools/design/check_auto_layout.py designs/mobile-app.figcraft            # page 1
     python3 -I tools/design/check_auto_layout.py designs/mobile-app.figcraft --page 1   # group F
     python3 -I tools/design/check_auto_layout.py designs/mobile-app.figcraft --page 2   # design v2
     ```

   - **FigCraft validation.** Open the file in FigCraft, which validates it (`FigCraft --control PORT … FILE`, then `document.inspect` / `node.get`; `ui.screenshot` returns the window as a PNG, to look at a page that has no exported render).
   - **Visual.** Compare the frames with the exported PNGs.

## The USB cast page

`build_usb_cast_spec.py` writes the page **"Mobile design v3 · USB cast (ADR-034)"** into `designs/mobile-app.figcraft`, replacing an earlier copy of it. It is drawn ahead of the code, in the same DSL and auto layout. It has four app screens: cable ready, on air by cable, cable refused, and Scan with a cable but no pairing. It also has Android's own two dialogs (S1 the accessory prompt, S2 the capture consent) and the **flow** table, which shows each step on the phone and on the Mac and names the screen or desktop frame for each. Renders: `designs/preview/mobile-usb-cast.png`, `designs/preview/mobile-usb-flow.png`.

`build_desktop_usb_cast.py` writes the receiver's side into `designs/desktop-signal-deck.figcraft` as frames **10–14**, copied from frames 07 and 01 as absolute layers: connected by cable, on air by cable, cable unplugged, the QR hero when the phone on the cable isn't paired, and (14) the deck opened with a saved phone that isn't connected yet, instead of the QR. Render: `designs/preview/desktop-10-14-usb-cast.png`.

```bash
python3 -I tools/design/build_usb_cast_spec.py /tmp/usb-cast.figcraft --into designs/mobile-app.figcraft
python3 -I tools/design/check_auto_layout.py designs/mobile-app.figcraft --page 3
python3 -I tools/design/build_desktop_usb_cast.py designs/desktop-signal-deck.figcraft   # 10–14
```

`build_usb_profiles_spec.py` writes **"Mobile design v4 · Profiles over the cable"** (decided 2026-11-17). It shows one profile list for both links: E1 tags the Wi-Fi-only fields where they stand, and E2 gathers them in one card. H1 is Home with the cable in: **Capture** (1920 px · 120 fps · 20–30 Mbps, BEST OVER USB) is a built-in profile, and the cable picks it by itself (the CABLE tag), while Wi-Fi keeps the user's pick. Product owner, 2026-11-17. H2 shows any profile taking the cable over with "Use when the cable is in". It also has the field map and the open questions. Renders: `designs/preview/mobile-usb-profiles-edit.png`, `designs/preview/mobile-usb-profiles-list.png`.

```bash
python3 -I tools/design/build_usb_profiles_spec.py /tmp/usb-profiles.figcraft --into designs/mobile-app.figcraft
python3 -I tools/design/check_auto_layout.py designs/mobile-app.figcraft --page 4
```

`build_cast_drop_spec.py` writes **"Mobile design v5 · Cast survives a drop (ADR-036)"**, drawn ahead of the code. A cast waits up to five minutes for its link, over Wi-Fi and over the cable. D1 is Home with Wi-Fi lost and D2 with the cable unplugged, both under a `RECONNECTING` annunciator with a STOPS IN countdown and Stop. D3 is the notification's wording for both links, D4 is Home once the five minutes ran out, and D5 is U2 with the new unplug sentence. The notes carry the open question about the receiver's "Cable unplugged" wording. Render: `designs/preview/mobile-cast-drop.png`.

```bash
python3 -I tools/design/build_cast_drop_spec.py /tmp/cast-drop.figcraft --into designs/mobile-app.figcraft
python3 -I tools/design/check_auto_layout.py designs/mobile-app.figcraft --page 5
```

`build_link_first_spec.py` writes **"Mobile flow v8 · The link is chosen first (ADR-037)"**, drawn ahead of the code, as a storyboard with arrows and the computer's side in line. The phone opens on a choice, **Wireless or Cable**: Wireless leads to today's Scan, Cable to a cable guide whose steps tick as they happen (plug in → Android's "Open Caster" → the computer's one-time Allow). Once paired the app opens on Home; switching link needs Terminate. Home opens on one connection card (computer, link, status; Disconnect and a red Terminate in its footer) above a big button that is always the next step. Rows: A first run Wireless, B first run Cable, C later (the cable reconnects by itself), D Wireless later, Terminate back to the choice, a computer left on Wireless, not allowed, unplugged mid-cast. It replaces the earlier drafts' pages v6 and v7. Renders: `designs/preview/mobile-link-first.png`, and Home in every state `designs/preview/mobile-home-suggested.png`.

`build_desktop_link_home.py` draws the receiver's Home for ADR-037, frames 19–24 of `desktop-signal-deck.figcraft`: the deck with its top block replaced by one connection card, the phone's own Home card mirrored (the phone's name, the link and its status, a red Terminate in the footer). 19 Wireless connected, 20 Cable connected, 21 Cable on air, 22 Cable unplugged mid-cast (RECONNECTING, STOPS IN), 23 Cable not connected, 24 Wireless not connected. Render: `designs/preview/desktop-19-24-link-home.png`.

```sh
python3 -I tools/design/build_desktop_link_home.py designs/desktop-signal-deck.figcraft
```

`build_desktop_link_first.py` writes the receiver's side as frames **15–18** of `designs/desktop-signal-deck.figcraft`: 15 the first screen (Wireless or Cable), 16 the QR hero with Back, 17 the macOS cable guide with the one-time Allow inline at step 3, 18 the Windows cable guide with "Install the USB driver" as step 2 (drawn now, built with the Windows receiver). Frame 13 (pair over Wi-Fi first) is kept, captioned as superseded by 17. Render: `designs/preview/desktop-15-18-link-first.png`.

```bash
python3 -I tools/design/build_link_first_spec.py /tmp/link-first.figcraft --into designs/mobile-app.figcraft
python3 -I tools/design/check_auto_layout.py designs/mobile-app.figcraft --page 6
python3 -I tools/design/build_desktop_usb_cast.py designs/desktop-signal-deck.figcraft     # 10–14
python3 -I tools/design/build_desktop_link_first.py designs/desktop-signal-deck.figcraft   # 15–18
```

## The desktop receiver's sound and the Caster devices

`designs/desktop-signal-deck.figcraft` holds the native receiver's deck. Two builders add proposals to it as absolute layers copied from the existing frames. They do not use auto layout, so `check_auto_layout.py` does not apply.

- `build_desktop_audio_output.py` writes **frame 07**: Audio and Mic, each with **Plays on: Speaker | Caster Audio/Mic** (ADR-029), and the Syphon card.
- `build_desktop_caster_install.py` writes **frames 08–09**. This is the install flow the product owner chose on 2026-11-14: no Install button. While the driver is missing, the Caster segment stays clickable and shows a round ⓘ, and hovering or focusing it explains what is missing. Clicking the segment installs the driver (one macOS password prompt). Frame 08 is the deck with nothing installed, hovered. Frame 09 is the row state by state: not installed, hover, installing, installed, cancelled, failed. Renders: `designs/preview/desktop-08-caster-not-installed.png`, `designs/preview/desktop-09-caster-install-states.png`.

```bash
python3 -I tools/design/build_desktop_audio_output.py designs/desktop-signal-deck.figcraft    # 07
python3 -I tools/design/build_desktop_caster_install.py designs/desktop-signal-deck.figcraft  # 08–09 (needs 07)
```

## The OBS Virtual Camera switch

`build_desktop_obs_camera.py` draws ADR-039's receiver control, drawn ahead of the code, as frames **25–26** of `designs/desktop-signal-deck.figcraft`. Syphon stays as it is; the camera is a **new, separate switch** under it in the "Output to OBS" card (product owner, 2026-11-22). Frame 25 is frame 21 (cable, on air) with both on. Frame 26 is the row state by state: off; on and casting; on with no cast (OBS's own picture); OBS not installed and OBS's camera not set up (greyed out); taken by OBS; couldn't start. It ends with the open questions. Like frames 07–24 it is absolute layers, so `check_auto_layout.py` does not apply. Renders: `designs/preview/desktop-25-obs-virtual-camera.png`, `designs/preview/desktop-26-obs-camera-states.png`.

```bash
python3 -I tools/design/build_desktop_obs_camera.py designs/desktop-signal-deck.figcraft   # 25–26 (needs 21)
```

## The app icon

`build_app_icon.py` draws Caster's icon once (ADR-027): the receiver's mark on the orange tile, as a Mac icon on Apple's grid, and the mark alone as the menu-bar template.

```bash
python3 -I tools/design/build_app_icon.py designs/app-icon.figcraft          # prints each frame's id
FigCraft --control PORT --automation-read-root DIR --automation-write-root DIR designs/app-icon.figcraft
#   ui.render {path: DIR/mac-1024.png, scale: 1, ids: [<mac icon 1024>]}
#   ui.render {path: DIR/tray.png,     scale: 2, ids: [<tray template>]}
cd apps/desktop-native/src-tauri && npx --yes @tauri-apps/cli@2 icon DIR/mac-1024.png -o icons   # keep the png and icns files
cp DIR/tray.png icons/tray-template.png
```

macOS 26 draws the Mac icon from a layered icon instead (`apps/desktop-native/src-tauri/icons/AppIcon.icon`: the tile's colour in `icon.json`, the mark as filled paths in `Assets/mark.svg`). Recompile it after a change, with Xcode installed, and commit the result:

```bash
cd apps/desktop-native/src-tauri/icons
xcrun actool AppIcon.icon --compile /tmp/appicon --app-icon AppIcon --platform macosx \
  --minimum-deployment-target 12.0 --output-partial-info-plist /tmp/appicon/partial.plist
cp /tmp/appicon/Assets.car Assets.car
```

The phone's launcher icon is the same mark written as a vector (`ic_launcher_foreground.xml`) on the same orange (`ic_launcher_background`); change the three together.

## Known simplifications

- **Text font.** Text uses **Roboto**, the Android default; FigCraft falls back if Roboto isn't installed. Monospace becomes Liberation Mono. A single measured line is set to no-wrap, so a slightly wider fallback font can't break it.
- **Debug-only affordances are dropped.** The test-tone row is removed and its gap closed. The Scan screen's debug "paste the QR payload" field was removed from the app on 2026-11-18.
- **Not shown.** The camera preview (CameraX doesn't run on the JVM) and animations, which are captured at a single frame.
