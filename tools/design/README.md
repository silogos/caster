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
   cd apps/mobile && DESIGN_EXPORT_DIR=/tmp/zfc-design ./gradlew :app:testDebugUnitTest --tests '*DesignExportTest'
   ```

2. **Build.**

   ```bash
   python3 -I tools/design/build_custom_profiles_spec.py /tmp/zfc-design/custom-profiles.figcraft
   python3 -I tools/design/build_mobile_figcraft.py /tmp/zfc-design designs/mobile-app.figcraft \
     --spec designs/mobile-ux-a11y.figcraft --spec /tmp/zfc-design/custom-profiles.figcraft
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

## The desktop receiver's sound and the Caster devices

`designs/desktop-signal-deck.figcraft` holds the native receiver's deck. Two builders add proposals to it as absolute layers copied from the existing frames. They do not use auto layout, so `check_auto_layout.py` does not apply.

- `build_desktop_audio_output.py` writes **frame 07**: Audio and Mic, each with **Plays on: Speaker | Caster Audio/Mic** (ADR-029), and the Syphon card.
- `build_desktop_caster_install.py` writes **frames 08–09**. This is the install flow the product owner chose on 2026-11-14: no Install button. While the driver is missing, the Caster segment stays clickable and shows a round ⓘ, and hovering or focusing it explains what is missing. Clicking the segment installs the driver (one macOS password prompt). Frame 08 is the deck with nothing installed, hovered. Frame 09 is the row state by state: not installed, hover, installing, installed, cancelled, failed. Renders: `designs/preview/desktop-08-caster-not-installed.png`, `designs/preview/desktop-09-caster-install-states.png`.

```bash
python3 -I tools/design/build_desktop_audio_output.py designs/desktop-signal-deck.figcraft    # 07
python3 -I tools/design/build_desktop_caster_install.py designs/desktop-signal-deck.figcraft  # 08–09 (needs 07)
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
- **Debug-only affordances are dropped.** The test-tone row is removed and its gap closed. The Scan screen's debug "paste the QR payload" field stays and is labeled debug in the app itself.
- **Not shown.** The camera preview (CameraX doesn't run on the JVM) and animations, which are captured at a single frame.
