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


def hexc(h, a=1.0):
    h = h.lstrip("#")
    return {"r": int(h[0:2], 16) / 255, "g": int(h[2:4], 16) / 255, "b": int(h[4:6], 16) / 255, "a": a}


# ---- tokens (Color.kt / CastUi.kt) ----
PRIMARY, ON_PRIMARY = "#F2A93B", "#1A1206"
BG, SURFACE, ON, VARIANT, OUTLINE = "#101418", "#161B22", "#E6EAF2", "#95999F", "#31363D"
PAGE_BG, CAPTION, BAD, GOOD = "#0B0D10", "#8A93A0", "#E5484D", "#3FB950"
SANS, MONO = "Inter", "Liberation Mono"
PHONE_W = 412
CONTENT_W = 364  # 412 − 2 × 24 padding

nodes = {}
next_id = [1]


def nid():
    i = next_id[0]
    next_id[0] += 1
    return i


def solid(h, opacity=1.0):
    return {"type": "solid", "color": hexc(h), "opacity": opacity, "visible": True, "blend": "normal"}


def stroke_spec(weight=1.0, dashes=None, align="inside"):
    return {"weight": weight, "align": align, "sides": None, "cap_start": "none", "cap_end": "none",
            "join": "miter", "miter_limit": 4.0, "dashes": dashes or []}


def child_layout(h="hug", v="hug", absolute=False):
    return {"sizing_h": h, "sizing_v": v, "absolute": absolute, "min_w": None, "max_w": None, "min_h": None,
            "max_h": None, "grid_column": None, "grid_row": None, "grid_column_span": 0, "grid_row_span": 0}


def add(parent, node):
    i = nid()
    node["id"] = i
    node["parent"] = parent
    nodes[i] = node
    nodes[parent].setdefault("children", []).append(i)
    return i


def wrap_lines(chars, width, size):
    """Estimated wrapped line count (average glyph ≈ 0.52 em)."""
    total = 0
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


def text_height(n, width):
    s = n["style"]
    return wrap_lines(n["characters"], width, s["font_size"]) * s["line_height"]["value"]


# ---- the DSL: frames with auto layout, leaves with fixed sizes ----

def frame(parent, name, mode=None, gap=0.0, pad=(0, 0, 0, 0), main="min", cross="min", h="hug", v="hug",
          fill=None, opacity=1.0, stroke=None, radius=0.0, width=0.0, height=0.0, dashes=None, stroke_weight=1.0,
          absolute_at=None, clip=False):
    n = {"name": name, "type": "frame", "transform": [1.0, 0.0, 0.0, 1.0, 0.0, 0.0], "width": float(width),
         "height": float(height), "fills": [solid(fill, opacity)] if fill else [],
         "corners": {"radii": [float(radius)] * 4, "smoothing": 0.0}, "clip": clip,
         "child_layout": child_layout(h, v, absolute=absolute_at is not None)}
    if absolute_at is not None:
        n["transform"][4], n["transform"][5] = float(absolute_at[0]), float(absolute_at[1])
    if mode:
        n["layout"] = {"mode": mode, "wrap": False, "gap": float(gap), "cross_gap": 0.0,
                       "padding": [float(p) for p in pad], "main_align": main, "cross_align": cross,
                       "content_align": "auto", "grid_columns": 2, "grid_rows": 2, "column_tracks": [],
                       "row_tracks": [], "strokes_included": True, "reverse_z": False}
    if stroke:
        n["strokes"] = [solid(stroke)]
        n["stroke"] = stroke_spec(stroke_weight, dashes)
    return add(parent, n)


def vstack(parent, name, gap=0.0, pad=(0, 0, 0, 0), **kw):
    return frame(parent, name, "vertical", gap, pad, **kw)


def hstack(parent, name, gap=0.0, pad=(0, 0, 0, 0), **kw):
    return frame(parent, name, "horizontal", gap, pad, **kw)


def text(parent, chars, size, color=ON, style="Regular", width=None, line=None, family=SANS, spacing=0.0,
         align="left", h=None):
    line = line or round(size * 1.35)
    if width:
        w, ht = float(width), float(wrap_lines(chars, width, size) * line)
    else:
        w, ht = round(len(chars) * size * (0.62 if family == MONO else 0.55)), float(line)
    return add(parent, {
        "name": chars.split("\n")[0][:60], "type": "text", "characters": chars,
        "style": {"font_family": family, "font_style": style, "font_size": float(size),
                  "line_height": {"unit": "pixels", "value": float(line)},
                  "letter_spacing": {"unit": "pixels", "value": float(spacing)},
                  "decoration": "none", "case": "original", "fills": None, "link": None},
        "auto_resize": "height" if width else "widthAndHeight", "align_h": align, "blend": "normal",
        "transform": [1.0, 0.0, 0.0, 1.0, 0.0, 0.0], "width": w, "height": ht, "fills": [solid(color)],
        "child_layout": child_layout(h or ("fill" if width else "hug"), "hug")})


def rect(parent, name, w, h, fill=None, opacity=1.0, radius=0.0, stroke=None, absolute_at=None, dashes=None,
         stroke_weight=1.0):
    n = {"name": name, "type": "rectangle", "blend": "normal",
         "transform": [1.0, 0.0, 0.0, 1.0, *(map(float, absolute_at) if absolute_at else (0.0, 0.0))],
         "width": float(w), "height": float(h), "fills": [solid(fill, opacity)] if fill else [],
         "corners": {"radii": [float(radius)] * 4, "smoothing": 0.0},
         "child_layout": child_layout("fixed", "fixed", absolute=absolute_at is not None)}
    if stroke:
        n["strokes"] = [solid(stroke)]
        n["stroke"] = stroke_spec(stroke_weight, dashes)
    return add(parent, n)


def ellipse(parent, name, d, fill, opacity=1.0, absolute_at=None):
    return add(parent, {"name": name, "type": "ellipse", "start": 0.0, "sweep": 6.283185307179586, "inner": 0.0,
                        "blend": "normal",
                        "transform": [1.0, 0.0, 0.0, 1.0, *(map(float, absolute_at) if absolute_at else (0.0, 0.0))],
                        "width": float(d), "height": float(d), "fills": [solid(fill, opacity)],
                        "corners": {"radii": [d / 2] * 4, "smoothing": 0.0},
                        "child_layout": child_layout("fixed", "fixed", absolute=absolute_at is not None)})


# ---- components (CastUi.kt ports) ----

def switch(parent, on):
    """Material 3 Switch 52×32 — amber track + dark thumb when on (CastSwitch)."""
    if on:
        s = hstack(parent, "switch-on", pad=(4, 4, 4, 4), main="max", cross="center", h="fixed", v="fixed",
                   width=52, height=32, fill=PRIMARY, radius=16)
        ellipse(s, "thumb", 24, ON_PRIMARY)
    else:
        s = hstack(parent, "switch-off", pad=(8, 8, 8, 8), main="min", cross="center", h="fixed", v="fixed",
                   width=52, height=32, fill=ON, opacity=0.14, radius=16)
        ellipse(s, "thumb", 16, ON, opacity=0.92)
    return s


def icon_well(parent, glyph):
    w = hstack(parent, f"well-{glyph}", main="center", cross="center", h="fixed", v="fixed", width=38, height=38,
               fill=ON, opacity=0.08, radius=12)
    if glyph == "mic":
        rect(w, "ic-mic", 8, 13, radius=4, stroke=ON, stroke_weight=1.6)
    elif glyph == "gamepad":
        rect(w, "ic-gamepad", 20, 12, radius=6, stroke=ON, stroke_weight=1.6)
    else:
        rect(w, "ic-cast", 18, 14, radius=2, stroke=ON, stroke_weight=1.6)
    return w


def card(parent, name):
    return vstack(parent, name, h="fill", fill=SURFACE, radius=18, stroke=OUTLINE)


def switch_row(parent, title, sub, on, glyph="mic", sub_color=VARIANT, footer=None):
    """IconListRow with a switch (since group F the whole row is the toggle)."""
    row = hstack(parent, f"row · {title}", gap=14, pad=(13, 16, 13, 16), cross="center", h="fill")
    icon_well(row, glyph)
    texts = vstack(row, "texts", gap=2, h="fill")
    text(texts, title, 15, ON, "Medium", line=20)
    text(texts, sub, 13, sub_color, width=CONTENT_W - 32 - 38 - 14 - 10 - 52, line=19)
    if footer:
        footer(texts)
    switch(row, on)
    return row


def text_button(parent, label, color=PRIMARY):
    b = hstack(parent, f"button · {label}", pad=(10, 12, 10, 12), cross="center")
    text(b, label, 14, color, "Medium", line=20)
    return b


def caption(parent, chars, color=CAPTION):
    return text(parent, chars, 11, color, family=MONO, spacing=1.2, line=14)


def pill(parent, label, tone):
    fg = {"bad": BAD, "good": GOOD, "amber": PRIMARY, "muted": VARIANT}[tone]
    p = hstack(parent, f"pill · {label}", pad=(3, 10, 3, 10), cross="center", fill=fg, opacity=0.14, radius=10)
    text(p, label, 10, fg, "Bold", family=MONO, spacing=0.8, line=14)
    return p


def badge(parent, n, at):
    """A number badge, absolute inside the element it annotates."""
    b = hstack(parent, f"badge {n}", main="center", cross="center", h="fixed", v="fixed", width=22, height=22,
               fill=PRIMARY, radius=11, absolute_at=at)
    text(b, str(n), 12, ON_PRIMARY, "Bold", line=16)
    return b


def touch_box(parent, w, h, at):
    """A 48dp touch target: dashed amber outline, absolute around the control."""
    return rect(parent, "touch-48dp", w, h, PRIMARY, opacity=0.06, radius=6, stroke=PRIMARY, absolute_at=at,
                dashes=[4.0, 3.0])


def phone(page, name, x, y):
    f = vstack(page, name, gap=16, pad=(16, 24, 24, 24), h="fixed", v="fixed", width=PHONE_W, height=915,
               fill=BG, radius=28, stroke="#2A313A", clip=True)
    nodes[f]["transform"][4], nodes[f]["transform"][5] = float(x), float(y)
    nodes[f]["child_layout"]["absolute"] = False
    bar = hstack(f, "status bar", main="spaceBetween", cross="center", h="fill")
    text(bar, "21:47", 13, ON, "Medium", line=16)
    rect(bar, "battery", 22, 11, radius=3, stroke=ON, stroke_weight=1.2)
    return f


def page_caption(page, chars, x, y, size=11, style="Regular", color=CAPTION):
    t = text(page, chars, size, color, style, family=MONO, spacing=1.2 if size < 13 else 1.4, line=16)
    nodes[t]["transform"][4], nodes[t]["transform"][5] = float(x), float(y)
    nodes[t]["child_layout"]["absolute"] = False
    return t


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
text(brand, "LOCAL CAST", 13, ON, "Medium", family=MONO, spacing=1.4, line=16)
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
