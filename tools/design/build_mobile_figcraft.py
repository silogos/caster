"""Builds designs/mobile-app.figcraft from the shipped UI's measured layout tree.

Input: the export directory DesignExportTest writes — per screen state, the
Compose layout tree (outer/inner boxes, Row/Column/Box policy, arrangement,
alignment, sizing, what each node draws) plus a PNG of the real render.

Output: a FigCraft file whose frames ARE that tree, as auto layout (the way a
designer builds it in Figma), not absolutely placed layers:
  - Column → vertical auto layout, Row → horizontal: gap = the arrangement's
    spacing, main/cross alignment = the arrangement / alignment, padding = the
    node's own padding modifiers (outer box − inner box);
  - a Box with one child → auto layout aligned like the Box; with several
    children (overlays) → a plain frame with positioned children;
  - sizing: fillMax*/weight → fill, size/width/height → fixed, else hug (a
    stretched child of a Surface/Box with propagateMinConstraints → fill);
  - backgrounds/borders/clip shapes on the node's own box → the frame's
    fill/stroke/radius; anything drawn elsewhere (hairlines, rings, dots) →
    absolute children, exactly where Compose draws them.

Pages:
  - "Mobile · as shipped": every exported screen state;
  - one per --spec, copied in: "Group F · UX & a11y" (designs/mobile-ux-a11y.figcraft)
    and "Mobile design v2 · custom profiles" (build_custom_profiles_spec.py's output).

Usage:
  python3 -I build_mobile_figcraft.py EXPORT_DIR OUT.figcraft [--spec SPEC.figcraft]...
"""
import copy
import json
import math
import sys
import zipfile
from pathlib import Path

DEBUG_ONLY_TEXT = {"Debug: play test tone", "Debug: stop test tone"}  # BuildConfig.DEBUG affordance
FRAME_GAP = 40
LABEL_GAP = 34
ROW_GAP = 120
BOTTOM_MARGIN = 24
EPS = 0.75
TEXT_FAMILY = "Roboto"  # the app's Android default
MONO_FAMILY = "Liberation Mono"  # FontFamily.Monospace stand-in FigCraft bundles
PAGE_BG = {"r": 0.043, "g": 0.051, "b": 0.063, "a": 1.0}
CAPTION = (0.54, 0.58, 0.63, 1.0)
WEIGHTS = {100: "Thin", 200: "ExtraLight", 300: "Light", 400: "Regular", 500: "Medium", 600: "SemiBold",
           700: "Bold", 800: "ExtraBold", 900: "Black"}
ALIGN = {"start": "min", "center": "center", "end": "max"}
MAIN_ALIGN = {"start": "min", "center": "center", "end": "max", "spaceBetween": "spaceBetween",
              "spaceAround": "spaceAround", "spaceEvenly": "spaceEvenly"}

nodes = {}
next_id = [1]


# ---------------------------------------------------------------- primitives

def nid():
    i = next_id[0]
    next_id[0] += 1
    return i


def paint(rgba):
    r, g, b, a = rgba
    return {"type": "solid", "color": {"r": r, "g": g, "b": b, "a": 1.0}, "opacity": a, "visible": True,
            "blend": "normal"}


def stroke_spec(weight, cap="none", join="miter", align="inside"):
    return {"weight": weight, "align": align, "sides": None, "cap_start": cap, "cap_end": cap, "join": join,
            "miter_limit": 4.0, "dashes": []}


def child_layout(h="fixed", v="fixed", absolute=False, min_w=None, min_h=None):
    return {"sizing_h": h, "sizing_v": v, "absolute": absolute, "min_w": min_w, "max_w": None, "min_h": min_h,
            "max_h": None, "grid_column": None, "grid_row": None, "grid_column_span": 0, "grid_row_span": 0}


def auto_layout(mode, gap=0.0, padding=(0, 0, 0, 0), main="min", cross="min"):
    return {"mode": mode, "wrap": False, "gap": float(gap), "cross_gap": 0.0,
            "padding": [round(float(p), 2) for p in padding], "main_align": main, "cross_align": cross,
            "content_align": "auto", "grid_columns": 2, "grid_rows": 2, "column_tracks": [], "row_tracks": [],
            "strokes_included": True, "reverse_z": False}


def add(parent, node):
    i = nid()
    node["id"] = i
    node["parent"] = parent
    nodes[i] = node
    nodes[parent].setdefault("children", []).append(i)
    return i


def transform(x, y):
    return [1.0, 0.0, 0.0, 1.0, round(float(x), 2), round(float(y), 2)]


# ------------------------------------------------------------- leaf shapes

def rect_node(parent, name, x, y, w, h, fill=None, stroke=None, stroke_width=1.0, radii=(0, 0, 0, 0), layout=None):
    n = {"name": name, "type": "rectangle", "blend": "normal", "transform": transform(x, y),
         "width": round(max(w, 0.01), 2), "height": round(max(h, 0.01), 2), "fills": [paint(fill)] if fill else [],
         "corners": {"radii": [float(r) for r in radii], "smoothing": 0.0}}
    if stroke:
        n["strokes"] = [paint(stroke)]
        n["stroke"] = stroke_spec(stroke_width)
    if layout:
        n["child_layout"] = layout
    return add(parent, n)


def ellipse_node(parent, name, x, y, w, h, fill=None, stroke=None, stroke_width=1.0, layout=None):
    n = {"name": name, "type": "ellipse", "start": 0.0, "sweep": 6.283185307179586, "inner": 0.0, "blend": "normal",
         "transform": transform(x, y), "width": round(w, 2), "height": round(h, 2),
         "fills": [paint(fill)] if fill else [], "corners": {"radii": [w / 2] * 4, "smoothing": 0.0}}
    if stroke:
        n["strokes"] = [paint(stroke)]
        n["stroke"] = stroke_spec(stroke_width, align="center")
    if layout:
        n["child_layout"] = layout
    return add(parent, n)


def network(segments):
    """Absolute Move/Line/Quadratic/Cubic/Close segments → FigCraft vector network (+ fill loops)."""
    vertices, segs, loops = [], [], []
    start = cur = None
    loop = []

    def vertex(x, y):
        vertices.append({"x": round(x, 3), "y": round(y, 3), "radius": 0.0})
        return len(vertices) - 1

    def close_loop():
        nonlocal loop
        if loop:
            loops.append(loop)
        loop = []

    for s in segments:
        t, p = s["type"], s["points"]
        pts = list(zip(p[0::2], p[1::2]))
        if t == "Move":
            close_loop()
            start = cur = vertex(*pts[0])
        elif t == "Line":
            b = vertex(*pts[1])
            segs.append({"a": cur, "b": b, "ta": [0.0, 0.0], "tb": [0.0, 0.0]})
            loop.append(len(segs) - 1)
            cur = b
        elif t in ("Quadratic", "Cubic"):
            if t == "Quadratic":
                (x0, y0), (qx, qy), (x3, y3) = pts
                c1 = (x0 + 2 / 3 * (qx - x0), y0 + 2 / 3 * (qy - y0))
                c2 = (x3 + 2 / 3 * (qx - x3), y3 + 2 / 3 * (qy - y3))
            else:
                (x0, y0), c1, c2, (x3, y3) = pts
            b = vertex(x3, y3)
            segs.append({"a": cur, "b": b, "ta": [round(c1[0] - x0, 3), round(c1[1] - y0, 3)],
                         "tb": [round(c2[0] - x3, 3), round(c2[1] - y3, 3)]})
            loop.append(len(segs) - 1)
            cur = b
        elif t == "Close":
            if cur is not None and start is not None and cur != start:
                last, first = vertices[cur], vertices[start]
                if segs and abs(last["x"] - first["x"]) < 0.01 and abs(last["y"] - first["y"]) < 0.01:
                    segs[-1]["b"] = start  # already back at the start: weld, no zero-length edge
                    vertices.pop()
                else:
                    segs.append({"a": cur, "b": start, "ta": [0.0, 0.0], "tb": [0.0, 0.0]})
                    loop.append(len(segs) - 1)
            cur = start
            close_loop()
    close_loop()
    return vertices, segs, loops


def vector_node(parent, name, x, y, w, h, path, layout=None):
    vertices, segs, loops = network(path["segments"])
    if not segs:
        return None
    n = {"name": name, "type": "vector", "blend": "normal", "transform": transform(x, y),
         "width": round(max(w, 0.01), 2), "height": round(max(h, 0.01), 2),
         "network": {"vertices": vertices, "segments": segs, "regions": []}}
    if path.get("stroke"):
        n["fills"] = []
        n["strokes"] = [paint(path["color"])]
        n["stroke"] = stroke_spec(round(path.get("strokeWidth", 1.0), 3), cap="round", join="round", align="center")
    else:
        n["fills"] = [paint(path["color"])]
        n["network"]["regions"] = [{"loops": loops, "winding": "nonZero"}]
    if layout:
        n["child_layout"] = layout
    return add(parent, n)


def text_node(parent, item, x, y, sizing_h, layout_extra=None):
    size = float(item["size"])
    line = item.get("lineHeight")
    chars = item["text"]
    single = "\n" not in chars and item["h"] < (line or size * 1.17) * 1.6
    hug = single and sizing_h != "fill"
    n = {"name": chars.split("\n")[0][:60] or "text", "type": "text", "characters": chars,
         "style": {"font_family": MONO_FAMILY if item.get("mono") else TEXT_FAMILY,
                   "font_style": WEIGHTS.get(int(item.get("weight", 400)), "Regular"),
                   "font_size": size,
                   "line_height": {"unit": "pixels", "value": float(line)} if line else {"unit": "auto"},
                   "letter_spacing": {"unit": "pixels", "value": float(item.get("letterSpacing", 0.0))},
                   "decoration": "none", "case": "original", "fills": None, "link": None},
         # One measured line hugs (FigCraft's font fallback may be a hair wider
         # than Roboto — a fixed box would wrap it); wrapped text fills its slot.
         "auto_resize": "widthAndHeight" if hug else "height",
         "align_h": item.get("align", "left"), "blend": "normal",
         "transform": transform(x, y), "width": round(item["w"], 2), "height": round(item["h"], 2),
         "fills": [paint(item["color"])],
         "child_layout": child_layout("hug" if hug else "fill", "hug", **(layout_extra or {}))}
    return add(parent, n)


# --------------------------------------------------------------- the tree

def same_box(a, b):
    return all(abs(p - q) < EPS for p, q in zip(a, b))


def padding_of(node):
    ox, oy, ow, oh = node["outer"]
    ix, iy, iw, ih = node["inner"]
    pad = [iy - oy, (ox + ow) - (ix + iw), (oy + oh) - (iy + ih), ix - ox]
    return [0.0 if abs(p) < 0.05 else round(p, 2) for p in pad]


def texts_in(node):
    for it in node["items"]:
        if it["kind"] == "text":
            yield it["text"]
    for c in node["children"]:
        yield from texts_in(c)


def item_top(it):
    if "oy" in it:
        return it["oy"] + min(it.get("y1", it.get("y", it.get("cy", 0))), it.get("y2", it.get("y", it.get("cy", 0))))
    return it.get("y", 0)


def shift(node, dy):
    node["outer"][1] -= dy
    node["inner"][1] -= dy
    for it in node["items"]:
        if "oy" in it:
            it["oy"] -= dy
        elif "y" in it:
            it["y"] -= dy
    for c in node["children"]:
        shift(c, dy)


def drop_subtree(root, predicate):
    """Removes the first node matching predicate; what sat below moves up, ancestors shrink (as Compose would lay it out)."""
    def visit(parent, path):
        for i, c in enumerate(parent["children"]):
            if predicate(c):
                h, y0 = c["outer"][3], c["outer"][1]
                del parent["children"][i]
                chain = path + [parent]
                for anc in chain:
                    anc["outer"][3] -= h
                    anc["inner"][3] -= h

                def below(n):
                    for k in n["children"]:
                        if k["outer"][1] >= y0 + h - EPS:
                            shift(k, h)
                        else:
                            below(k)
                    for it in n["items"]:
                        if item_top(it) >= y0 + h - EPS:
                            if "oy" in it:
                                it["oy"] -= h
                            elif "y" in it:
                                it["y"] -= h
                below(root)
                return True
            if visit(c, path + [parent]):
                return True
        return False
    visit(root, [])


def debug_row(node):
    """The test-tone row: a node whose only text is the debug label."""
    t = list(texts_in(node))
    return len(t) == 1 and t[0] in DEBUG_ONLY_TEXT and node["policy"] in ("row", "box", "other")


def collapse(node):
    """Unwraps decoration-less, padding-less single-child wrappers with the same box (Material scaffolding)."""
    node["children"] = [collapse(c) for c in node["children"]]
    while (len(node["children"]) == 1 and not node["items"] and node["policy"] in ("box", "other")
           and not any(padding_of(node)) and same_box(node["outer"], node["children"][0]["outer"])):
        child = node["children"][0]
        for axis in ("h", "v"):  # the wrapper's stronger sizing claim (fill/fixed) wins over hug
            if child["sizing"][axis] == "hug" and node["sizing"][axis] != "hug":
                child["sizing"][axis] = node["sizing"][axis]
        node = child
    return node




def decorate(fid, frame, node):
    """Backgrounds/borders on the node's own box → the frame's fill/stroke/radius; everything else → absolute children."""
    absolute = child_layout(absolute=True)
    ox, oy = node["outer"][0], node["outer"][1]
    for it in node["items"]:
        if it["kind"] != "rect":
            continue
        box = [it["x"], it["y"], it["w"], it["h"]]
        if same_box(box, node["outer"]):
            if it.get("fill"):
                frame["fills"] = [paint(it["fill"])]
            if it.get("stroke"):
                frame["strokes"] = [paint(it["stroke"])]
                frame["stroke"] = stroke_spec(it.get("strokeWidth", 1.0))
            frame["corners"] = {"radii": [float(r) for r in it.get("radii", [0, 0, 0, 0])], "smoothing": 0.0}
        else:
            rect_node(fid, "background", it["x"] - ox, it["y"] - oy, it["w"], it["h"], fill=it.get("fill"),
                      stroke=it.get("stroke"), stroke_width=it.get("strokeWidth", 1.0),
                      radii=it.get("radii", (0, 0, 0, 0)), layout=absolute)
    for it in node["items"]:
        k = it["kind"]
        if k in ("rect", "text", "vector") or it.get("clear") or it["color"][3] == 0:
            continue
        if k == "line":
            x1, y1 = it["ox"] + it["x1"] - ox, it["oy"] + it["y1"] - oy
            x2, y2 = it["ox"] + it["x2"] - ox, it["oy"] + it["y2"] - oy
            bx, by = min(x1, x2), min(y1, y2)
            vector_node(fid, "hairline", bx, by, abs(x2 - x1), abs(y2 - y1), {
                "segments": [{"type": "Move", "points": [x1 - bx, y1 - by]},
                             {"type": "Line", "points": [x1 - bx, y1 - by, x2 - bx, y2 - by]}],
                "stroke": True, "color": it["color"], "strokeWidth": it["strokeWidth"] or 1.0}, layout=absolute)
        elif k == "circle":
            cx, cy, r = it["ox"] + it["cx"] - ox, it["oy"] + it["cy"] - oy, it["r"]
            if it["stroke"]:
                ellipse_node(fid, "ring", cx - r, cy - r, 2 * r, 2 * r, stroke=it["color"],
                             stroke_width=it["strokeWidth"], layout=absolute)
            else:
                ellipse_node(fid, "dot", cx - r, cy - r, 2 * r, 2 * r, fill=it["color"], layout=absolute)
        elif k == "drect":
            x, y = it["ox"] + it["x"] - ox, it["oy"] + it["y"] - oy
            rect_node(fid, "drawn", x, y, it["w"], it["h"], fill=None if it["stroke"] else it["color"],
                      stroke=it["color"] if it["stroke"] else None, stroke_width=it["strokeWidth"],
                      radii=[it["r"]] * 4, layout=absolute)
        elif k == "arc":
            x, y, w, h = it["ox"] + it["x"] - ox, it["oy"] + it["y"] - oy, it["w"], it["h"]
            steps = max(2, int(abs(it["sweep"]) / 10))
            pts = [(w / 2 + w / 2 * math.cos(math.radians(it["start"] + it["sweep"] * i / steps)),
                    h / 2 + h / 2 * math.sin(math.radians(it["start"] + it["sweep"] * i / steps)))
                   for i in range(steps + 1)]
            segs = [{"type": "Move", "points": list(pts[0])}] + [
                {"type": "Line", "points": [*pts[i - 1], *pts[i]]} for i in range(1, len(pts))]
            vector_node(fid, "arc", x, y, w, h, {"segments": segs, "stroke": True, "color": it["color"],
                                                 "strokeWidth": it["strokeWidth"] or 1.0}, layout=absolute)


def icon(parent_id, vec, x, y, layout):
    """An icon is one layer: its single path, or a fixed, layout-free frame of its paths (auto layout would stack them)."""
    if len(vec["paths"]) == 1:
        return vector_node(parent_id, "icon", x, y, vec["w"], vec["h"], vec["paths"][0], layout=layout)
    holder = {"name": "icon", "type": "frame", "transform": transform(x, y), "width": round(vec["w"], 2),
              "height": round(vec["h"], 2), "fills": [], "corners": {"radii": [0.0] * 4, "smoothing": 0.0},
              "clip": False}
    if layout:
        holder["child_layout"] = layout
    hid = add(parent_id, holder)
    for j, path in enumerate(vec["paths"]):
        vector_node(hid, f"path-{j}", 0, 0, vec["w"], vec["h"], path)
    return hid


def name_of(node):
    t = next(iter(texts_in(node)), "")
    kind = {"row": "row", "column": "column", "box": "box", "spacer": "spacer", "text": "text",
            "other": node.get("policyClass") or "layout"}.get(node["policy"], node["policy"])
    return f"{kind} · {t[:40]}" if t and kind != "spacer" else kind


def sizing_in(node, parent):
    """fill/fixed/hug for this node inside its parent; a child stretched to the parent's inner box → fill."""
    h, v = node["sizing"]["h"], node["sizing"]["v"]
    if parent is not None:
        pw, ph = parent["inner"][2], parent["inner"][3]
        w, hh = node["outer"][2], node["outer"][3]
        if h == "hug" and abs(w - pw) < EPS and parent["policy"] != "row":
            h = "fill"
        if v == "hug" and abs(hh - ph) < EPS and parent["policy"] != "column":
            v = "fill"
    return h, v


def build(node, parent_id, parent_node, parent_auto):
    ox, oy, ow, oh = node["outer"]
    # A child with its own cross alignment (RowScope/ColumnScope.align): auto
    # layout has no per-child alignment, so it gets a wrapper spanning the
    # parent's cross axis, aligned the way the child asks.
    self_align = node["sizing"].get("selfAlign")
    if parent_auto and self_align and parent_node["policy"] in ("row", "column") and self_align != parent_node.get("cross"):
        node["sizing"]["selfAlign"] = None
        pix, piy, piw, pih = parent_node["inner"]
        column = parent_node["policy"] == "column"
        wrap = [pix, oy, piw, oh] if column else [ox, piy, ow, pih]
        wrapper = {"name": "align · " + name_of(node), "type": "frame",
                   "transform": transform(wrap[0] - parent_node["outer"][0], wrap[1] - parent_node["outer"][1]),
                   "width": round(wrap[2], 2), "height": round(wrap[3], 2), "fills": [],
                   "corners": {"radii": [0.0] * 4, "smoothing": 0.0}, "clip": False,
                   "child_layout": child_layout("fill", "hug") if column else child_layout("hug", "fill"),
                   "layout": auto_layout("vertical" if column else "horizontal", cross=ALIGN[self_align])}
        wid = add(parent_id, wrapper)
        holder = {"outer": wrap, "inner": list(wrap), "policy": "column" if column else "row", "items": [],
                  "children": [node], "cross": self_align}
        build(node, wid, holder, True)
        return
    px, py = parent_node["outer"][0], parent_node["outer"][1]
    h, v = sizing_in(node, parent_node)
    # A min size only where the measurement honored it: Material's defaultMinSize
    # (a button's 58dp) is a soft default that an explicit size (40dp) overrides.
    min_w, min_h = node["sizing"].get("minW"), node["sizing"].get("minH")
    if min_w and ow + EPS < min_w:
        min_w = None
    if min_h and oh + EPS < min_h:
        min_h = None
    lay = child_layout(h, v, min_w=min_w, min_h=min_h) if parent_auto else child_layout()

    texts = [it for it in node["items"] if it["kind"] == "text"]
    vectors = [it for it in node["items"] if it["kind"] == "vector"]
    others = [it for it in node["items"] if it["kind"] not in ("text", "vector")]
    pad = padding_of(node)
    # Modifier.offset shows up as negative padding (the content is drawn
    # beside the layout slot — e.g. a Switch's thumb). Auto layout has no
    # offset: the node becomes an absolute layer at the place it is drawn.
    same_size = abs(node["inner"][2] - ow) < EPS and abs(node["inner"][3] - oh) < EPS
    scroll_overflow = any(p < 0 for p in pad) and not same_size
    if scroll_overflow:
        # A horizontally scrolling row wider than its slot: it stays in the
        # flow at its slot's size and clips (the extra chips scroll in).
        pad = [max(p, 0.0) for p in pad]
    elif any(p < 0 for p in pad):
        node["outer"] = list(node["inner"])
        ox, oy, ow, oh = node["outer"]
        pad = [0.0, 0.0, 0.0, 0.0]
        if parent_auto:
            lay = child_layout("fixed", "fixed", absolute=True)

    # A plain text: a text layer.
    if texts and not node["children"] and not others and not any(pad):
        text_node(parent_id, texts[0], texts[0]["x"] - px, texts[0]["y"] - py, h,
                  {"min_w": min_w, "min_h": min_h} if parent_auto else None)
        return
    # A plain icon: one layer — the vector, or a fixed frame holding a multi-path icon's paths.
    if vectors and not node["children"] and not texts and not others and not any(pad):
        icon(parent_id, vectors[0], vectors[0]["x"] - px, vectors[0]["y"] - py, lay)
        return

    frame = {"name": name_of(node), "type": "frame", "transform": transform(ox - px, oy - py),
             "width": round(ow, 2), "height": round(oh, 2), "fills": [],
             "corners": {"radii": [0.0] * 4, "smoothing": 0.0}, "clip": scroll_overflow, "child_layout": lay}
    kids = node["children"]
    policy = node["policy"]
    if policy in ("row", "column"):
        arr = node.get("arrangement", {})
        frame["layout"] = auto_layout("horizontal" if policy == "row" else "vertical", arr.get("spacing", 0.0), pad,
                                      MAIN_ALIGN.get(arr.get("kind", "start"), "min"),
                                      ALIGN.get(node.get("cross", "start"), "min"))
    elif policy == "box" and len(kids) <= 1:
        # A one-child Box is an aligned container: vertical auto layout, aligned like the Box.
        frame["layout"] = auto_layout("vertical", 0.0, pad, ALIGN.get(node.get("boxV", "start"), "min"),
                                      ALIGN.get(node.get("boxH", "start"), "min"))
    elif policy in ("text", "spacer"):
        frame["layout"] = auto_layout("vertical", 0.0, pad)
    auto = "layout" in frame
    fid = add(parent_id, frame)
    decorate(fid, frame, node)
    for t in texts:
        text_node(fid, t, t["x"] - ox, t["y"] - oy, "fill" if h == "fill" else "hug")
    for vec in vectors:
        icon(fid, vec, vec["x"] - ox, vec["y"] - oy, child_layout() if auto else None)
    for c in kids:
        build(c, fid, node, auto)
    # Nothing in the flow (only drawings or absolute layers): hugging would
    # collapse the frame to 0×0 — it keeps its measured size.
    flow = [nodes[c] for c in nodes[fid].get("children", []) if not (nodes[c].get("child_layout") or {}).get("absolute")]
    if not flow and parent_auto:
        frame["child_layout"]["sizing_h"] = "fixed" if frame["child_layout"]["sizing_h"] == "hug" else frame["child_layout"]["sizing_h"]
        frame["child_layout"]["sizing_v"] = "fixed" if frame["child_layout"]["sizing_v"] == "hug" else frame["child_layout"]["sizing_v"]


def frame_height(root):
    """Content height: the deepest real content, ignoring containers stretched to the capture window."""
    window = root["outer"][3]
    best = 0.0

    def visit(n):
        nonlocal best
        x, y, w, h = n["outer"]
        if abs(h - window) >= EPS and (n["items"] or not n["children"]):
            best = max(best, y + h)
        for c in n["children"]:
            visit(c)
    visit(root)
    return math.ceil(best + BOTTOM_MARGIN)


def resize_stretched(node, window, height):
    if abs(node["outer"][3] - window) < EPS:
        node["inner"][3] = height - (node["outer"][3] - node["inner"][3])
        node["outer"][3] = height
    for c in node["children"]:
        resize_stretched(c, window, height)


def screen(page, data, fx, fy):
    root = copy.deepcopy(data["root"])
    drop_subtree(root, debug_row)
    root = collapse(root)
    window = root["outer"][3]
    height = data["height"] if data.get("fixedHeight") else frame_height(root)
    resize_stretched(root, window, height)
    frame = {"name": data["name"], "type": "frame", "transform": transform(fx, fy), "width": float(data["width"]),
             "height": float(height), "fills": [], "corners": {"radii": [0.0] * 4, "smoothing": 0.0}, "clip": True,
             "strokes": [paint((0.16, 0.19, 0.23, 1.0))], "stroke": stroke_spec(1.0),
             "layout": auto_layout("vertical")}
    fid = add(page, frame)
    holder = {"outer": [root["outer"][0], root["outer"][1], data["width"], height],
              "inner": [root["outer"][0], root["outer"][1], data["width"], height],
              "policy": "box", "items": [], "children": [root]}
    root["sizing"] = {"h": "fill", "v": "fill", "minW": None, "minH": None}
    build(root, fid, holder, True)
    return height


def caption(parent, chars, x, y):
    add(parent, {"name": chars, "type": "text", "characters": chars,
                 "style": {"font_family": MONO_FAMILY, "font_style": "Regular", "font_size": 12.0,
                           "line_height": {"unit": "pixels", "value": 14.0},
                           "letter_spacing": {"unit": "pixels", "value": 1.5}, "decoration": "none",
                           "case": "original", "fills": None, "link": None},
                 "auto_resize": "height", "blend": "normal", "transform": transform(x, y), "width": 412.0,
                 "height": 14.0, "fills": [paint(CAPTION)], "child_layout": child_layout("fixed", "hug")})


def copy_spec_pages(spec_path):
    """Appends the pages of another FigCraft file (a hand-written spec), renumbering its ids."""
    doc = json.loads(zipfile.ZipFile(spec_path).read("document.json"))
    offset = next_id[0]
    remap = {int(k): int(k) + offset for k in doc["nodes"]}
    for k, n in doc["nodes"].items():
        m = dict(n)
        m["id"] = remap[int(k)]
        if "parent" in m:
            m["parent"] = remap[m["parent"]]
        if "children" in m:
            m["children"] = [remap[c] for c in m["children"]]
        nodes[m["id"]] = m
    next_id[0] = max(remap.values()) + 1
    return [remap[p] for p in doc["pages"]]


def main():
    export_dir, out = Path(sys.argv[1]), sys.argv[2]
    specs = [sys.argv[i + 1] for i, arg in enumerate(sys.argv) if arg == "--spec"]
    page = nid()
    nodes[page] = {"id": page, "name": "Mobile · as shipped", "type": "page", "background": PAGE_BG, "flows": [],
                   "prototype": {"device": "None", "background": {"r": 0.118, "g": 0.118, "b": 0.118, "a": 1.0},
                                 "rotation": "portrait"},
                   "children": [], "width": 0.0, "height": 0.0}
    screens = [json.loads(p.read_text()) for p in sorted(export_dir.glob("[0-9]*.json"))]
    rows = [[d for d in screens if "Home" in d["name"]], [d for d in screens if "Home" not in d["name"]]]
    y = 40
    for row in rows:
        x, tallest = 40, 0
        for data in row:
            caption(page, data["name"].upper(), x, y)
            tallest = max(tallest, screen(page, data, x, y + LABEL_GAP))
            x += data["width"] + FRAME_GAP
        y += LABEL_GAP + tallest + ROW_GAP
    pages = [page] + [p for spec in specs for p in copy_spec_pages(spec)]
    doc = {"format": "figcraft", "version": 1, "imageIndex": [], "name": Path(out).stem, "pages": pages,
           "nodes": {str(k): v for k, v in nodes.items()}, "next_id": next_id[0], "styles": [],
           "variables": {"collections": [], "variables": []}, "current_page": page, "document_colors": [],
           "comments": []}
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", json.dumps({"versions": []}))
    frames = [n for n in nodes.values() if n["type"] == "frame"]
    auto = [n for n in frames if n.get("layout")]
    print(out, len(nodes), "nodes,", len(frames), "frames,", len(auto), "auto layout,", len(pages), "pages")


if __name__ == "__main__":
    main()
