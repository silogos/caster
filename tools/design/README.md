# Design files from the shipped UI

`designs/mobile-app.figcraft` is the mobile app **as it ships** (the amber broadcast console), measured from the real Compose UI rather than redrawn. Regenerate it after a UI change, so the design and the app never drift apart.

## Pipeline

1. **Measure.** `DesignExportTest` (apps/mobile, Robolectric with native text metrics) composes every screen state at a 412dp phone width. For each state it writes a JSON file and a PNG of the real render:
   - **JSON:** the layout tree, which covers boxes (background, corner radii, border, `clip` shapes), text (string, size, weight, color, alignment), icons (the vector drawables' own path data, arcs converted to cubics) and every `drawBehind` drawing (recorded through a canvas).
   - **PNG:** the real render, to compare against.

   It is opt-in, so CI skips it:

   ```bash
   cd apps/mobile && DESIGN_EXPORT_DIR=/tmp/zfc-design ./gradlew :app:testDebugUnitTest --tests '*DesignExportTest'
   ```

2. **Build.**

   ```bash
   python3 -I tools/design/build_mobile_figcraft.py /tmp/zfc-design designs/mobile-app.figcraft --spec designs/mobile-ux-a11y.figcraft
   ```

   This produces two pages:
   - **Mobile · as shipped:** twelve states. Row 1 is Home: standby, starting, on air, auto quality stepped down, fault, saved/offline, reconnect failed, and mic needs permission. Row 2 is Settings · Advanced plus Scan: camera permission, pairing, and failed.
   - **Group F · UX & a11y:** the accessibility spec, copied in. It comes from `build_group_f_spec.py`, which writes `designs/mobile-ux-a11y.figcraft`.

3. **Check.** Open the file in FigCraft, which validates it (`FigCraft --control PORT … FILE`, then `document.inspect`). Compare the frames with the exported PNGs.

## Known simplifications

- **Text font.** Text uses **Roboto**, the Android default; FigCraft falls back if Roboto isn't installed. Monospace becomes Liberation Mono. A single measured line is set to no-wrap, so a slightly wider fallback font can't break it.
- **Debug-only affordances are dropped.** The test-tone row is removed and its gap closed. The Scan screen's debug "paste the QR payload" field stays and is labeled debug in the app itself.
- **Clipping.** Horizontally scrolling chip rows show every chip; the app clips them at the card edge.
- **Not shown.** The camera preview (CameraX doesn't run on the JVM) and animations, which are captured at a single frame.
