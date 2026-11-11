"""The spec DSL shared by the hand-written design specs (build_group_f_spec.py,
build_custom_profiles_spec.py): the shipped app's amber broadcast-console
tokens (apps/mobile/.../ui/theme/Color.kt), frames with auto-layout
parameters, and ports of the app's components (CastUi.kt).

Nothing is hand-placed: a spec builds frames from layout parameters and calls
autolayout.solve() on each top-level frame. Only annotations are absolute.
"""


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


def phone(page, name, x, y, height=915, grow=False):
    """A phone screen at (x, y) on the page. grow: at least `height` tall, taller when the content is."""
    f = vstack(page, name, gap=16, pad=(16, 24, 24, 24), h="fixed", v="hug" if grow else "fixed", width=PHONE_W,
               height=height, fill=BG, radius=28, stroke="#2A313A", clip=True)
    nodes[f]["transform"][4], nodes[f]["transform"][5] = float(x), float(y)
    nodes[f]["child_layout"]["absolute"] = False
    if grow:
        nodes[f]["child_layout"]["min_h"] = float(height)
    bar = hstack(f, "status bar", main="spaceBetween", cross="center", h="fill")
    text(bar, "21:47", 13, ON, "Medium", line=16)
    rect(bar, "battery", 22, 11, radius=3, stroke=ON, stroke_weight=1.2)
    return f


def page_caption(page, chars, x, y, size=11, style="Regular", color=CAPTION):
    t = text(page, chars, size, color, style, family=MONO, spacing=1.2 if size < 13 else 1.4, line=16)
    nodes[t]["transform"][4], nodes[t]["transform"][5] = float(x), float(y)
    nodes[t]["child_layout"]["absolute"] = False
    return t


# ---- vector glyphs (an SVG / Android vector path, drawn as one FigCraft vector) ----

def _arc_cubics(x0, y0, rx, ry, large, sweep, x1, y1):
    """An SVG elliptical arc (no rotation) as cubic segments: (c1, c2, end) triples."""
    import math
    if rx == 0 or ry == 0 or (x0 == x1 and y0 == y1):
        return [((x0, y0), (x1, y1), (x1, y1))]
    dx, dy = (x0 - x1) / 2, (y0 - y1) / 2
    lam = dx * dx / (rx * rx) + dy * dy / (ry * ry)
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = max(rx * rx * ry * ry - rx * rx * dy * dy - ry * ry * dx * dx, 0.0)
    den = rx * rx * dy * dy + ry * ry * dx * dx
    k = (-1 if large == sweep else 1) * math.sqrt(num / den) if den else 0.0
    cx, cy = k * rx * dy / ry + (x0 + x1) / 2, -k * ry * dx / rx + (y0 + y1) / 2
    a0 = math.atan2((y0 - cy) / ry, (x0 - cx) / rx)
    a1 = math.atan2((y1 - cy) / ry, (x1 - cx) / rx)
    da = a1 - a0
    if sweep and da < 0:
        da += 2 * math.pi
    if not sweep and da > 0:
        da -= 2 * math.pi
    pieces = max(1, math.ceil(abs(da) / (math.pi / 2) - 1e-9))
    step = da / pieces
    t = 4 / 3 * math.tan(step / 4)
    out = []
    for i in range(pieces):
        b0, b1 = a0 + i * step, a0 + (i + 1) * step
        p0 = (cx + rx * math.cos(b0), cy + ry * math.sin(b0))
        p1 = (cx + rx * math.cos(b1), cy + ry * math.sin(b1))
        out.append(((p0[0] - t * rx * math.sin(b0), p0[1] + t * ry * math.cos(b0)),
                    (p1[0] + t * rx * math.sin(b1), p1[1] - t * ry * math.cos(b1)), p1))
    return out


def svg_network(d, scale=1.0, dx=0.0, dy=0.0):
    """Path data (M L H V C A Z, either case) → FigCraft vector network: vertices, segments, fill loops."""
    import re
    tokens = re.findall(r"[MmLlHhVvCcAaZz]|-?\d*\.?\d+(?:e-?\d+)?", d)
    vertices, segs, loops, loop = [], [], [], []
    pos = [0.0, 0.0]
    start = cur = None
    i = 0
    cmd = None

    def vertex(x, y):
        vertices.append({"x": round((x + dx) * scale, 3), "y": round((y + dy) * scale, 3), "radius": 0.0})
        return len(vertices) - 1

    def segment(end, c1=None, c2=None):
        nonlocal cur
        a = vertices[cur]
        b = vertex(*end)
        vb = vertices[b]
        ta = [round((c1[0] + dx) * scale - a["x"], 3), round((c1[1] + dy) * scale - a["y"], 3)] if c1 else [0.0, 0.0]
        tb = [round((c2[0] + dx) * scale - vb["x"], 3), round((c2[1] + dy) * scale - vb["y"], 3)] if c2 else [0.0, 0.0]
        segs.append({"a": cur, "b": b, "ta": ta, "tb": tb})
        loop.append(len(segs) - 1)
        cur = b
        pos[0], pos[1] = end

    def flush():
        nonlocal loop
        if loop:
            loops.append(loop)
        loop = []

    def num():
        nonlocal i
        i += 1
        return float(tokens[i - 1])

    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
        rel = cmd.islower()
        ox, oy = (pos[0], pos[1]) if rel else (0.0, 0.0)
        c = cmd.upper()
        if c == "M":
            flush()
            pos[0], pos[1] = ox + num(), oy + num()
            start = cur = vertex(*pos)
            cmd = "l" if rel else "L"
        elif c == "L":
            segment((ox + num(), oy + num()))
        elif c == "H":
            segment((ox + num(), pos[1]))
        elif c == "V":
            segment((pos[0], oy + num()))
        elif c == "C":
            c1 = (ox + num(), oy + num())
            c2 = (ox + num(), oy + num())
            segment((ox + num(), oy + num()), c1, c2)
        elif c == "A":
            rx, ry, _rot, large, sweep = num(), num(), num(), num(), num()
            end = (ox + num(), oy + num())
            for c1, c2, p in _arc_cubics(pos[0], pos[1], rx, ry, int(large), int(sweep), *end):
                segment(p, c1, c2)
        elif c == "Z":
            if cur != start:
                a, b = vertices[cur], vertices[start]
                if segs and abs(a["x"] - b["x"]) < 0.01 and abs(a["y"] - b["y"]) < 0.01 and cur == len(vertices) - 1:
                    segs[-1]["b"] = start  # already back at the start: weld, no zero-length edge
                    vertices.pop()
                else:
                    segs.append({"a": cur, "b": start, "ta": [0.0, 0.0], "tb": [0.0, 0.0]})
                    loop.append(len(segs) - 1)
            cur = start
            pos[0], pos[1] = (vertices[start]["x"] / scale - dx, vertices[start]["y"] / scale - dy)
            flush()
    flush()
    return vertices, segs, loops


def glyph(parent, name, d, size, viewport, fill=None, stroke=None, stroke_weight=2.0, absolute_at=(0.0, 0.0),
          shift=(0.0, 0.0)):
    """One path of an icon, [viewport] units wide, drawn [size] wide; filled, or stroked with round caps."""
    scale = size / viewport
    vertices, segs, loops = svg_network(d, scale, *shift)
    n = {"name": name, "type": "vector", "blend": "normal",
         "transform": [1.0, 0.0, 0.0, 1.0, float(absolute_at[0]), float(absolute_at[1])],
         "width": float(size), "height": float(size),
         "network": {"vertices": vertices, "segments": segs, "regions": []},
         "child_layout": child_layout("fixed", "fixed", absolute=True)}
    if stroke:
        n["fills"] = []
        n["strokes"] = [solid(stroke)]
        spec = stroke_spec(round(stroke_weight * scale, 3), align="center")
        spec.update({"cap_start": "round", "cap_end": "round", "join": "round"})
        n["stroke"] = spec
    else:
        n["fills"] = [solid(fill)]
        n["network"]["regions"] = [{"loops": loops, "winding": "nonZero"}]
    return add(parent, n)
