"""Builds designs/mobile-ux-a11y.figcraft — the mobile review's group F (UX/a11y)
spec, drawn in the shipped app's amber broadcast-console tokens
(apps/mobile/.../ui/theme/Color.kt), not the Signal Deck draft.

Usage: python3 -I tools/design/build_group_f_spec.py designs/mobile-ux-a11y.figcraft
"""
import json
import sys
import zipfile

# ---- tokens (Color.kt / CastUi.kt) ----
def hexc(h, a=1.0):
    h = h.lstrip("#")
    return {"r": int(h[0:2], 16) / 255, "g": int(h[2:4], 16) / 255, "b": int(h[4:6], 16) / 255, "a": a}

PRIMARY = "#F2A93B"
ON_PRIMARY = "#1A1206"
BG = "#101418"
SURFACE = "#161B22"
ON = "#E6EAF2"
VARIANT = "#95999F"
OUTLINE = "#31363D"
ERROR = "#F2B8B5"
PAGE_BG = "#0B0D10"
CAPTION = "#8A93A0"
BAD = "#E5484D"
GOOD = "#3FB950"

nodes = {}
next_id = [1]


def wrap_lines(chars, width, size):
    """Estimated wrapped line count (avg glyph ≈ 0.52 em) — keeps cards sized to their text."""
    per_line, total = width, 0
    for para in chars.split("\n"):
        cur, lines = "", 1
        for word in para.split(" "):
            trial = (cur + " " + word).strip()
            if len(trial) * size * 0.52 > width and cur:
                lines += 1
                cur = word
            else:
                cur = trial
        total += lines
    return total


def nid():
    i = next_id[0]
    next_id[0] += 1
    return i


def solid(h, opacity=1.0):
    return {"type": "solid", "color": hexc(h), "opacity": opacity, "visible": True, "blend": "normal"}


def stroke_spec(weight=1.0, dashes=None, align="inside"):
    return {"weight": weight, "align": align, "sides": None, "cap_start": "none", "cap_end": "none",
            "join": "miter", "miter_limit": 4.0, "dashes": dashes or []}


def layout(sizing_h="fixed"):
    return {"sizing_h": sizing_h, "sizing_v": "hug", "absolute": False, "min_w": None, "max_w": None,
            "min_h": None, "max_h": None, "grid_column": None, "grid_row": None,
            "grid_column_span": 0, "grid_row_span": 0}


def add(parent, node):
    i = nid()
    node["id"] = i
    node["parent"] = parent
    nodes[i] = node
    nodes[parent].setdefault("children", []).append(i)
    return i


def rect(parent, name, x, y, w, h, fill=None, opacity=1.0, radius=0.0, stroke=None, stroke_weight=1.0,
         dashes=None, stroke_opacity=1.0):
    n = {"name": name, "type": "rectangle", "blend": "normal", "transform": [1.0, 0.0, 0.0, 1.0, float(x), float(y)],
         "width": float(w), "height": float(h),
         "fills": [solid(fill, opacity)] if fill else [],
         "corners": {"radii": [float(radius)] * 4, "smoothing": 0.0}}
    if stroke:
        n["strokes"] = [solid(stroke, stroke_opacity)]
        n["stroke"] = stroke_spec(stroke_weight, dashes)
    return add(parent, n)


def ellipse(parent, name, x, y, d, fill, opacity=1.0):
    return add(parent, {"name": name, "type": "ellipse", "start": 0.0, "sweep": 6.283185307179586, "inner": 0.0,
                        "blend": "normal", "transform": [1.0, 0.0, 0.0, 1.0, float(x), float(y)],
                        "width": float(d), "height": float(d), "fills": [solid(fill, opacity)],
                        "corners": {"radii": [d / 2] * 4, "smoothing": 0.0}})


def text(parent, chars, x, y, size, color=ON, style="Regular", width=None, line=None, family="Inter",
         spacing=0.0, name=None, opacity=1.0):
    line = line or round(size * 1.35)
    lines = 1
    if width:
        per_line = max(1, int(width / (size * 0.52)))
        words, cur, lines = chars.split(" "), 0, 1
        for w in words:
            if cur and cur + 1 + len(w) > per_line:
                lines += 1
                cur = len(w)
            else:
                cur += (1 if cur else 0) + len(w)
        lines += chars.count("\n")
    w = width or round(len(chars) * size * (0.62 if family == "Liberation Mono" else 0.55))
    return add(parent, {
        "name": name or chars.split("\n")[0][:60], "type": "text", "characters": chars,
        "style": {"font_family": family, "font_style": style, "font_size": float(size),
                  "line_height": {"unit": "pixels", "value": float(line)},
                  "letter_spacing": {"unit": "pixels", "value": float(spacing)},
                  "decoration": "none", "case": "original", "fills": None, "link": None},
        "auto_resize": "height" if width else "widthAndHeight",
        "blend": "normal", "transform": [1.0, 0.0, 0.0, 1.0, float(x), float(y)],
        "width": float(w), "height": float(line * lines), "fills": [solid(color, opacity)],
        "child_layout": layout("fixed" if width else "hug")})


def frame(parent, name, x, y, w, h, fill=BG, radius=28.0, clip=True, stroke="#2A313A"):
    n = {"name": name, "type": "frame", "transform": [1.0, 0.0, 0.0, 1.0, float(x), float(y)],
         "width": float(w), "height": float(h), "fills": [solid(fill)] if fill else [],
         "corners": {"radii": [float(radius)] * 4, "smoothing": 0.0}, "clip": clip}
    if stroke:
        n["strokes"] = [solid(stroke)]
        n["stroke"] = stroke_spec(1.0)
    return add(parent, n)


# ---- components (CastUi.kt ports) ----
def switch(parent, x, y, on):
    """Material 3 Switch, 52x32 — amber track + dark thumb when on (CastSwitch)."""
    if on:
        rect(parent, "switch-on", x, y, 52, 32, PRIMARY, radius=16)
        ellipse(parent, "thumb", x + 24, y + 4, 24, ON_PRIMARY)
    else:
        rect(parent, "switch-off", x, y, 52, 32, ON, opacity=0.14, radius=16)
        ellipse(parent, "thumb", x + 8, y + 8, 16, ON, opacity=0.92)


def icon_well(parent, x, y, glyph="mic"):
    rect(parent, f"well-{glyph}", x, y, 38, 38, ON, opacity=0.08, radius=12)
    if glyph == "mic":
        rect(parent, "ic-mic", x + 15, y + 9, 8, 13, None, radius=4, stroke=ON, stroke_weight=1.6)
        rect(parent, "ic-mic-stem", x + 18.5, y + 24, 1.6, 5, ON)
    elif glyph == "gamepad":
        rect(parent, "ic-gamepad", x + 9, y + 13, 20, 12, None, radius=6, stroke=ON, stroke_weight=1.6)
    else:
        rect(parent, "ic-cast", x + 10, y + 12, 18, 14, None, radius=2, stroke=ON, stroke_weight=1.6)


def card(parent, x, y, w, h, name="card"):
    return rect(parent, name, x, y, w, h, SURFACE, radius=18, stroke=OUTLINE)


SUB_WIDTH = 364 - 68 - 82


def icon_row(parent, x, y, w, title, sub, on, glyph="mic", sub_color=VARIANT, sub_lines=None):
    sub_lines = sub_lines or wrap_lines(sub, w - 68 - 82, 13)
    """IconListRow with a switch (the whole row is the toggle since group F)."""
    icon_well(parent, x + 16, y + 13, glyph)
    text(parent, title, x + 68, y + 13, 15, ON, "Medium", line=20)
    text(parent, sub, x + 68, y + 35, 13, sub_color, width=w - 68 - 82, line=19)
    switch(parent, x + w - 16 - 52, y + 13 + (19 * sub_lines + 22 - 32) / 2 + 3, on)


def text_button(parent, x, y, label, h=40, color=PRIMARY):
    w = round(len(label) * 14 * 0.55) + 24
    text(parent, label, x + 12, y + (h - 20) / 2, 14, color, "Medium", line=20)
    return w


def caption(parent, x, y, chars, color=CAPTION):
    return text(parent, chars, x, y, 11, color, family="Liberation Mono", spacing=1.2, line=14)


def pill(parent, x, y, label, tone):
    fg = {"bad": BAD, "good": GOOD, "amber": PRIMARY, "muted": VARIANT}[tone]
    w = round(len(label) * 10 * 0.62) + 20
    rect(parent, f"pill-{tone}", x, y, w, 20, fg, opacity=0.14, radius=10)
    text(parent, label, x + 10, y + 3, 10, fg, "Bold", family="Liberation Mono", spacing=0.8, line=14)
    return w


def status_bar(parent):
    text(parent, "21:47", 24, 16, 13, ON, "Medium", line=16)
    rect(parent, "battery", 360, 18, 22, 11, None, radius=3, stroke=ON, stroke_weight=1.2)


def badge(parent, x, y, n):
    ellipse(parent, f"badge-{n}", x, y, 22, PRIMARY)
    text(parent, str(n), x + (7 if n < 10 else 4), y + 3, 12, ON_PRIMARY, "Bold", line=16)


def hit_box(parent, x, y, w, h, name="touch-48dp"):
    """A 48dp touch target, drawn as a dashed amber outline around the control."""
    rect(parent, name, x, y, w, h, PRIMARY, opacity=0.06, radius=6, stroke=PRIMARY, stroke_weight=1.0,
         dashes=[4.0, 3.0], stroke_opacity=0.9)


# ---- document ----
PAGE = nid()
nodes[PAGE] = {"id": PAGE, "name": "Group F · UX & a11y", "type": "page", "background": hexc(PAGE_BG),
               "flows": [], "prototype": {"device": "None", "background": hexc("#1E1E1E"), "rotation": "portrait"},
               "children": [], "width": 0.0, "height": 0.0}

FX = [40, 492, 944]
text(PAGE, "MOBILE REVIEW 2026-11-09 · GROUP F — UX & ACCESSIBILITY (AMBER CONSOLE, AS SHIPPED)", 40, 30, 13,
     ON, "Bold", family="Liberation Mono", spacing=1.4, line=16)
caption(PAGE, 40, 78, "F1 · MICROPHONE — PERMISSION STATES")
caption(PAGE, 492, 78, "F2 · TALKBACK — WHAT IS READ ALOUD")
caption(PAGE, 944, 78, "F3 · TOUCH TARGETS & 200% FONT")

# ======== F1 · mic states ========
f1 = frame(PAGE, "F1 Mic · permission states", FX[0], 112, 412, 915)
status_bar(f1)
text(f1, "Microphone row — every state", 24, 52, 20, ON, "SemiBold", line=26)
text(f1, "The switch shows what is really recording. A missing permission is OFF, never ON.", 24, 84,
     13, VARIANT, width=364, line=19)

y = 140
states = [
    ("ON · RECORDING", "good", "Recording from Phone mic", ON, True, None, 1),
    ("OFF", "muted", "Off removes the microphone from the cast and frees it for the game's own voice chat.",
     VARIANT, False, None, 2),
    ("NEEDS ACCESS · was drawn ON", "amber", "Needs microphone access. Turn it on to allow it.", VARIANT, False,
     "Tap → Android's permission dialog", 2),
    ("BLOCKED · don't ask again", "bad", "Microphone access is off for this app. Turn it on in Android settings.",
     VARIANT, False, "Open settings", 2),
]
for label, tone, sub, sub_color, on, extra, _ in states:
    sub_lines = wrap_lines(sub, SUB_WIDTH, 13)
    pill(f1, 24, y, label, tone)
    y += 28
    h = 13 + 20 + 2 + 19 * sub_lines + 13 + (52 if extra == "Open settings" else 0)
    card(f1, 24, y, 364, h, name=f"card-{tone}")
    icon_row(f1, 24, y, 364, "Microphone", sub, on, sub_color=sub_color, sub_lines=sub_lines)
    if extra == "Open settings":
        bw = text_button(f1, 24 + 68 - 12, y + 35 + 19 * sub_lines + 10, "Open settings")
        hit_box(f1, 24 + 68 - 12, y + 35 + 19 * sub_lines + 6, bw, 48, "touch-open-settings")
    y += h + 8
    if extra and extra != "Open settings":
        caption(f1, 28, y, extra.upper(), PRIMARY)
        y += 22
    elif extra == "Open settings":
        caption(f1, 28, y, "→ APP INFO · PERMISSIONS", PRIMARY)
        y += 22
    y += 10

text(f1, "Rules", 24, y + 4, 13, ON, "SemiBold", line=18)
text(f1, "• NeedsPermission renders OFF with its reason (it used to count as ON).\n"
         "• After a denial with no rationale left, Android shows no dialog: the row offers Open settings.\n"
         "• Back from settings with the grant, the row is ordinary again (checked on resume).",
     24, y + 26, 12.5, VARIANT, width=364, line=18)

# ======== F2 · TalkBack ========
f2 = frame(PAGE, "F2 TalkBack · announcements", FX[1], 112, 412, 915)
status_bar(f2)
# app bar
rect(f2, "brand-mark", 24, 54, 24, 24, ON, opacity=0.08, radius=8)
text(f2, "LOCAL CAST", 56, 58, 13, ON, "Medium", family="Liberation Mono", spacing=1.4, line=16)
rect(f2, "annunciator", 246, 54, 88, 26, ON, opacity=0.06, radius=13)
text(f2, "STANDBY", 262, 59, 11, VARIANT, "Bold", family="Liberation Mono", spacing=1.0, line=16)
rect(f2, "gear", 348, 47, 40, 40, None, radius=12, stroke=OUTLINE)
rect(f2, "gear-glyph", 360, 59, 16, 16, None, radius=8, stroke=ON, stroke_weight=1.6)
badge(f2, 378, 40, 1)
# quality card with tabs
card(f2, 24, 110, 364, 150, name="card-quality")
text(f2, "QUALITY", 40, 126, 11, VARIANT, "Medium", family="Liberation Mono", spacing=1.1, line=14)
rect(f2, "seg", 40, 150, 332, 48, None, radius=12, stroke=OUTLINE)
rect(f2, "seg-selected", 41, 151, 165, 46, PRIMARY, opacity=0.15, radius=11)
text(f2, "Preset", 98, 164, 14, PRIMARY, "Medium", line=20)
text(f2, "Custom", 262, 164, 14, ON, line=20)
badge(f2, 30, 140, 2)
# chips (mic picker)
x = 40
for label, sel in (("Phone mic", True), ("Headset", False), ("USB mic", False)):
    w = round(len(label) * 14 * 0.55) + 28
    rect(f2, f"chip-{label}", x, 214, w, 36, PRIMARY if sel else None, opacity=0.15, radius=18,
         stroke=PRIMARY if sel else OUTLINE)
    text(f2, label, x + 14, 222, 14, PRIMARY if sel else ON, "Medium" if sel else "Regular", line=20)
    x += w + 8
badge(f2, 30, 204, 3)
# audio rows
card(f2, 24, 276, 364, 150, name="card-audio")
icon_row(f2, 24, 276, 364, "Game audio", "Off silences the game audio, but its track stays part of the cast.", True,
         glyph="gamepad")
rect(f2, "row-focus", 26, 278, 360, 96, None, radius=16, stroke=PRIMARY, stroke_weight=2.0)
badge(f2, 30, 262, 4)
caption(f2, 40, 388, "WHOLE ROW = ONE FOCUS · ROLE SWITCH", PRIMARY)
# settings back button
rect(f2, "back", 24, 446, 40, 40, None, radius=12, stroke=OUTLINE)
rect(f2, "chevron", 40, 459, 8, 14, None, radius=1, stroke=ON, stroke_weight=1.6)
text(f2, "Advanced", 74, 455, 17, ON, "Medium", line=22)
badge(f2, 14, 436, 5)

# legend
ly = 512
text(f2, "Read aloud", 24, ly, 15, ON, "SemiBold", line=20)
ly += 30
legend = [
    (1, "Advanced, Button", "unchanged", "good"),
    (2, "Preset, Selected, Tab, 1 of 2", "was: “Preset” — no state", "bad"),
    (3, "Phone mic, Selected, Radio button", "was: “Phone mic, Button” — no state", "bad"),
    (4, "Game audio, Off silences the game audio…, Switch, On", "was: “Switch, on” — no label", "bad"),
    (5, "Back, Button", "was: “Advanced, Button” — the screen title", "bad"),
]
for n, said, was, tone in legend:
    badge(f2, 24, ly, n)
    text(f2, f"“{said}”", 56, ly + 1, 13, ON, "Medium", width=332, line=18)
    lines = 1 + (len(said) + 2) // 46
    text(f2, was, 56, ly + 1 + 18 * lines, 12, BAD if tone == "bad" else GOOD, width=332, line=17)
    ly += 18 * lines + 17 + 16

# ======== F3 · touch targets & large font ========
f3 = frame(PAGE, "F3 Touch targets · 200% font", FX[2], 112, 412, 915)
status_bar(f3)
text(f3, "Touch targets", 24, 52, 20, ON, "SemiBold", line=26)
text(f3, "Visual 40dp is fine inside a 48dp touch area (Material). Compose's minimum touch target already "
         "pads every clickable to 48dp; the test guards it.", 24, 84, 13, VARIANT, width=364, line=19)

ty = 160
# chips with hit boxes
x = 32
for label in ("15 fps", "30 fps", "60 fps"):
    w = round(len(label) * 14 * 0.55) + 28
    hit_box(f3, x, ty - 4, w, 48)
    rect(f3, f"chip-{label}", x, ty, w, 40, None, radius=20, stroke=OUTLINE)
    text(f3, label, x + 14, ty + 10, 14, ON, line=20)
    x += w + 10
caption(f3, 32, ty + 54, "CHIP · VISUAL 40 · TOUCH 48")
# gear / back
hit_box(f3, 272, ty - 4, 48, 48)
rect(f3, "back", 276, ty, 40, 40, None, radius=12, stroke=OUTLINE)
rect(f3, "chevron", 292, ty + 13, 8, 14, None, radius=1, stroke=ON, stroke_weight=1.6)
hit_box(f3, 332, ty - 4, 48, 48)
rect(f3, "gear", 336, ty, 40, 40, None, radius=12, stroke=OUTLINE)
rect(f3, "gear-glyph", 348, ty + 12, 16, 16, None, radius=8, stroke=ON, stroke_weight=1.6)
caption(f3, 272, ty + 54, "ICON · 40/48")
# tabs: now 48 tall
rect(f3, "seg", 32, ty + 84, 348, 48, None, radius=12, stroke=OUTLINE)
rect(f3, "seg-selected", 33, ty + 85, 173, 46, PRIMARY, opacity=0.15, radius=11)
text(f3, "Preset", 94, ty + 98, 14, PRIMARY, "Medium", line=20)
text(f3, "Custom", 262, ty + 98, 14, ON, line=20)
caption(f3, 32, ty + 140, "TABS · 44 → 48 TALL")

# 200% font
by = 360
text(f3, "200% system font", 24, by, 20, ON, "SemiBold", line=26)
text(f3, "Text buttons had a fixed 40dp height: at 200% the 14sp label (needs 28dp+) was squeezed to 24dp "
         "and its glyphs were cut. Now a minimum: the button grows.", 24, by + 32, 13, VARIANT, width=364, line=19)

ry = by + 110
pill(f3, 24, ry, "BEFORE · height(40.dp)", "bad")
clip = frame(f3, "before-clipped", 24, ry + 28, 200, 40, fill=None, radius=0, clip=True, stroke=None)
rect(clip, "button-bounds", 0, 0, 200, 40, BAD, opacity=0.06, radius=8, stroke=BAD, dashes=[4.0, 3.0])
text(clip, "Disconnect", 12, -4, 28, PRIMARY, "Medium", line=37)
caption(f3, 236, ry + 40, "LABEL 24DP — CUT", BAD)

ry += 96
pill(f3, 24, ry, "AFTER · heightIn(min = 40.dp)", "good")
rect(f3, "button-bounds", 24, ry + 28, 200, 53, GOOD, opacity=0.06, radius=8, stroke=GOOD, dashes=[4.0, 3.0])
text(f3, "Disconnect", 36, ry + 36, 28, PRIMARY, "Medium", line=37)
caption(f3, 236, ry + 46, "LABEL 37DP — WHOLE", GOOD)

ry += 104
pill(f3, 24, ry, "TRANSPORT · heightIn(min = 54.dp)", "amber")
rect(f3, "share", 24, ry + 28, 364, 56, PRIMARY, radius=16)
text(f3, "Share screen", 120, ry + 38, 30, ON_PRIMARY, "Bold", line=36)
caption(f3, 24, ry + 96, "ALREADY FIT AT 200% · NOW GROWS TOO")

text(f3, "Verified by AccessibilityUiTest (Robolectric, native text metrics): "
         "every clickable ≥ 48dp touch; text-button label ≥ 2× its size at 200%.",
     24, 860, 11.5, CAPTION, width=364, line=16)

doc = {"format": "figcraft", "version": 1, "imageIndex": [], "name": "mobile-ux-a11y", "pages": [PAGE],
       "nodes": {str(k): v for k, v in nodes.items()}, "next_id": next_id[0], "styles": [],
       "variables": {"collections": [], "variables": []}, "current_page": PAGE, "document_colors": [],
       "comments": []}

out = sys.argv[1]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("document.json", json.dumps(doc))
    z.writestr("versions/index.json", json.dumps({"versions": []}))
print(out, len(nodes), "nodes")
