"""Builds designs/mobile-ux-a11y.figcraft — the mobile review's group F (UX/a11y)
spec, in the shipped app's amber broadcast-console tokens
(apps/mobile/.../ui/theme/Color.kt), not the Signal Deck draft.

Every frame is auto layout (stacks, rows, cards — sized hug/fill/fixed with
padding and gaps); no layer is hand-placed. Only annotations sit on top as
absolute layers inside the element they annotate (number badges, 48dp touch
outlines, focus rings). autolayout.solve() places everything.

Usage: python3 -I tools/design/build_group_f_spec.py designs/mobile-ux-a11y.figcraft
"""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import autolayout  # noqa: E402

from spec_dsl import *  # noqa: E402,F401,F403 — tokens, the frame DSL and the component ports


# ---- document ----
PAGE = nid()
nodes[PAGE] = {"id": PAGE, "name": "Group F · UX & a11y", "type": "page", "background": hexc(PAGE_BG),
               "flows": [], "prototype": {"device": "None", "background": hexc("#1E1E1E"), "rotation": "portrait"},
               "children": [], "width": 0.0, "height": 0.0}
page_caption(PAGE, "MOBILE REVIEW 2026-11-09 · GROUP F — UX & ACCESSIBILITY (AMBER CONSOLE, AS SHIPPED)", 40, 30,
             13, "Bold", ON)
page_caption(PAGE, "F1 · MICROPHONE — PERMISSION STATES", 40, 78)
page_caption(PAGE, "F2 · TALKBACK — WHAT IS READ ALOUD", 492, 78)
page_caption(PAGE, "F3 · TOUCH TARGETS & 200% FONT", 944, 78)

# ======== F1 · mic states ========
f1 = phone(PAGE, "F1 Mic · permission states", 40, 112)
head = vstack(f1, "heading", gap=8, h="fill")
text(head, "Microphone row — every state", 20, ON, "SemiBold", line=26)
text(head, "The switch shows what is really recording. A missing permission is OFF, never ON.", 13, VARIANT,
     width=CONTENT_W, line=19)
states = vstack(f1, "states", gap=14, h="fill")


def state(label, tone, sub, on, note=None, blocked=False):
    s = vstack(states, f"state · {label}", gap=8, h="fill")
    pill(s, label, tone)
    c = card(s, f"card · {label}")
    footer = None
    if blocked:
        def footer(parent):
            holder = hstack(parent, "open settings", pad=(4, 0, 0, 0))
            b = text_button(holder, "Open settings")
            touch_box(b, 120, 48, (-4, -4))
    switch_row(c, "Microphone", sub, on, footer=footer)
    if note:
        caption(s, note, PRIMARY)


state("ON · RECORDING", "good", "Recording from Phone mic", True)
state("OFF", "muted", "Off removes the microphone from the cast and frees it for the game's own voice chat.", False)
state("NEEDS ACCESS · was drawn ON", "amber", "Needs microphone access. Turn it on to allow it.", False,
      "TAP → ANDROID'S PERMISSION DIALOG")
state("BLOCKED · don't ask again", "bad", "Microphone access is off for this app. Turn it on in Android settings.",
      False, "→ APP INFO · PERMISSIONS", blocked=True)
rules = vstack(f1, "rules", gap=6, h="fill")
text(rules, "Rules", 13, ON, "SemiBold", line=18)
text(rules, "• NeedsPermission renders OFF with its reason (it used to count as ON).\n"
            "• After a denial with no rationale left, Android shows no dialog: the row offers Open settings.\n"
            "• Back from settings with the grant, the row is ordinary again (checked on resume).",
     12.5, VARIANT, width=CONTENT_W, line=18)

# ======== F2 · TalkBack ========
f2 = phone(PAGE, "F2 TalkBack · announcements", 492, 112)
appbar = hstack(f2, "app bar", gap=10, cross="center", h="fill")
brand = hstack(appbar, "brand", gap=8, cross="center", h="fill")
rect(brand, "brand-mark", 24, 24, ON, opacity=0.08, radius=8)
text(brand, "Caster", 13, ON, "Medium", family=MONO, spacing=1.4, line=16)
ann = hstack(appbar, "annunciator", pad=(5, 14, 5, 14), cross="center", fill=ON, opacity=0.06, radius=13)
text(ann, "STANDBY", 11, VARIANT, "Bold", family=MONO, spacing=1.0, line=16)
gear = hstack(appbar, "gear", main="center", cross="center", h="fixed", v="fixed", width=40, height=40, radius=12,
              stroke=OUTLINE)
rect(gear, "gear-glyph", 16, 16, radius=8, stroke=ON, stroke_weight=1.6)
badge(gear, 1, (26, -8))

quality = card(f2, "card · quality")
qpad = vstack(quality, "quality", gap=12, pad=(16, 16, 16, 16), h="fill")
text(qpad, "QUALITY", 11, VARIANT, "Medium", family=MONO, spacing=1.1, line=14)
seg = hstack(qpad, "tabs", h="fill", radius=12, stroke=OUTLINE)
tab_on = hstack(seg, "tab · Preset", main="center", cross="center", h="fill", v="fixed", height=48, fill=PRIMARY,
                opacity=0.15, radius=11)
text(tab_on, "Preset", 14, PRIMARY, "Medium", line=20)
tab_off = hstack(seg, "tab · Custom", main="center", cross="center", h="fill", v="fixed", height=48)
text(tab_off, "Custom", 14, ON, line=20)
badge(seg, 2, (-10, -10))
chips = hstack(qpad, "mic chips", gap=8)
for label, sel in (("Phone mic", True), ("Headset", False), ("USB mic", False)):
    chip = hstack(chips, f"chip · {label}", pad=(8, 14, 8, 14), cross="center", fill=PRIMARY if sel else None,
                  opacity=0.15, radius=18, stroke=PRIMARY if sel else OUTLINE)
    text(chip, label, 14, PRIMARY if sel else ON, "Medium" if sel else "Regular", line=20)
badge(chips, 3, (-10, -10))

audio = card(f2, "card · audio")
row = switch_row(audio, "Game audio", "Off silences the game audio, but its track stays part of the cast.", True,
                 glyph="gamepad")
nodes[row]["strokes"] = [solid(PRIMARY)]
nodes[row]["stroke"] = stroke_spec(2.0)
nodes[row]["corners"] = {"radii": [16.0] * 4, "smoothing": 0.0}
badge(row, 4, (-8, -8))
caption(f2, "WHOLE ROW = ONE FOCUS · ROLE SWITCH", PRIMARY)

back = hstack(f2, "settings app bar", gap=10, cross="center")
chev = hstack(back, "back", main="center", cross="center", h="fixed", v="fixed", width=40, height=40, radius=12,
              stroke=OUTLINE)
rect(chev, "chevron", 8, 14, radius=1, stroke=ON, stroke_weight=1.6)
badge(chev, 5, (-10, -10))
text(back, "Advanced", 17, ON, "Medium", line=22)

legend = vstack(f2, "read aloud", gap=12, h="fill")
text(legend, "Read aloud", 15, ON, "SemiBold", line=20)
for n, said, was, tone in [
    (1, "Advanced, Button", "unchanged", "good"),
    (2, "Preset, Selected, Tab, 1 of 2", "was: “Preset” — no state", "bad"),
    (3, "Phone mic, Selected, Radio button", "was: “Phone mic, Button” — no state", "bad"),
    (4, "Game audio, Off silences the game audio…, Switch, On", "was: “Switch, on” — no label", "bad"),
    (5, "Back, Button", "was: “Advanced, Button” — the screen title", "bad"),
]:
    entry = hstack(legend, f"entry {n}", gap=10, h="fill")
    num = hstack(entry, f"number {n}", main="center", cross="center", h="fixed", v="fixed", width=22, height=22,
                 fill=PRIMARY, radius=11)
    text(num, str(n), 12, ON_PRIMARY, "Bold", line=16)
    lines = vstack(entry, "lines", gap=2, h="fill")
    text(lines, f"“{said}”", 13, ON, "Medium", width=CONTENT_W - 32, line=18)
    text(lines, was, 12, BAD if tone == "bad" else GOOD, width=CONTENT_W - 32, line=17)

# ======== F3 · touch targets & large font ========
f3 = phone(PAGE, "F3 Touch targets · 200% font", 944, 112)
head = vstack(f3, "heading", gap=8, h="fill")
text(head, "Touch targets", 20, ON, "SemiBold", line=26)
text(head, "Visual 40dp is fine inside a 48dp touch area (Material). Compose's minimum touch target already pads "
           "every clickable to 48dp; the test guards it.", 13, VARIANT, width=CONTENT_W, line=19)
controls = hstack(f3, "controls", gap=10, cross="center", h="fill", pad=(4, 4, 4, 4))
for label in ("15 fps", "30 fps", "60 fps"):
    chip = hstack(controls, f"chip · {label}", pad=(10, 14, 10, 14), cross="center", radius=20, stroke=OUTLINE)
    text(chip, label, 14, ON, line=20)
    touch_box(chip, round(len(label) * 14 * 0.55) + 28, 48, (0, -4))
for name in ("back", "gear"):
    b = hstack(controls, name, main="center", cross="center", h="fixed", v="fixed", width=40, height=40, radius=12,
               stroke=OUTLINE)
    rect(b, f"{name}-glyph", 14, 14, radius=7 if name == "gear" else 1, stroke=ON, stroke_weight=1.6)
    touch_box(b, 48, 48, (-4, -4))
caption(f3, "CHIP · VISUAL 40 · TOUCH 48   ICON · 40/48")
tabs = hstack(f3, "tabs", h="fill", radius=12, stroke=OUTLINE)
t_on = hstack(tabs, "tab · Preset", main="center", cross="center", h="fill", v="fixed", height=48, fill=PRIMARY,
              opacity=0.15, radius=11)
text(t_on, "Preset", 14, PRIMARY, "Medium", line=20)
t_off = hstack(tabs, "tab · Custom", main="center", cross="center", h="fill", v="fixed", height=48)
text(t_off, "Custom", 14, ON, line=20)
caption(f3, "TABS · 44 → 48 TALL")

big = vstack(f3, "200% system font", gap=10, h="fill")
text(big, "200% system font", 20, ON, "SemiBold", line=26)
text(big, "Text buttons had a fixed 40dp height: at 200% the 14sp label (needs 28dp+) was squeezed to 24dp and its "
          "glyphs were cut. Now a minimum: the button grows.", 13, VARIANT, width=CONTENT_W, line=19)


def font_case(label, tone, height, clip_note, color):
    case = vstack(big, f"case · {label}", gap=8, h="fill")
    pill(case, label, tone)
    line = hstack(case, "sample", gap=12, cross="center", h="fill")
    btn = hstack(line, "button", pad=(0, 12, 0, 12), cross="center", h="fixed", v="fixed", width=200, height=height,
                 fill=color, opacity=0.06, radius=8, stroke=color, dashes=[4.0, 3.0], clip=True)
    text(btn, "Disconnect", 28, PRIMARY, "Medium", line=37)
    caption(line, clip_note, color)


font_case("BEFORE · height(40.dp)", "bad", 40, "LABEL 24DP — CUT", BAD)
font_case("AFTER · heightIn(min = 40.dp)", "good", 53, "LABEL 37DP — WHOLE", GOOD)
transport = vstack(big, "case · transport", gap=8, h="fill")
pill(transport, "TRANSPORT · heightIn(min = 54.dp)", "amber")
share = hstack(transport, "share", main="center", cross="center", h="fill", v="fixed", height=56, fill=PRIMARY,
               radius=16)
text(share, "Share screen", 30, ON_PRIMARY, "Bold", line=36)
caption(transport, "ALREADY FIT AT 200% · NOW GROWS TOO")
text(f3, "Verified by AccessibilityUiTest (Robolectric, native text metrics): every clickable ≥ 48dp touch; "
         "text-button label ≥ 2× its size at 200%.", 11.5, CAPTION, width=CONTENT_W, line=16)

for f in (f1, f2, f3):
    autolayout.solve(nodes, f, text_height)

doc = {"format": "figcraft", "version": 1, "imageIndex": [], "name": "mobile-ux-a11y", "pages": [PAGE],
       "nodes": {str(k): v for k, v in nodes.items()}, "next_id": next_id[0], "styles": [],
       "variables": {"collections": [], "variables": []}, "current_page": PAGE, "document_colors": [],
       "comments": []}
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("document.json", json.dumps(doc))
    z.writestr("versions/index.json", json.dumps({"versions": []}))
frames = [n for n in nodes.values() if n["type"] == "frame"]
print(sys.argv[1], len(nodes), "nodes,", len(frames), "frames,", sum(1 for n in frames if n.get("layout")), "auto layout")
