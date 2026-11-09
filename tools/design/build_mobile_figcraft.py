"""Builds designs/mobile-app.figcraft from the shipped UI's measured layout.

Input: the export directory DesignExportTest writes (one JSON + PNG per
screen state; see tools/design/README.md). Output: a FigCraft file with
  - page "Mobile · as shipped": every screen state as an editable frame,
  - page "Group F · UX & a11y": copied from designs/mobile-ux-a11y.figcraft
    when that file is passed with --spec.

Usage:
  python3 -I build_mobile_figcraft.py EXPORT_DIR OUT.figcraft [--spec SPEC.figcraft]
"""
import json
import sys
import zipfile
from pathlib import Path

ROOT_FILL_MIN_HEIGHT = 2000  # the fillMaxSize screen background spans the whole capture window
DEBUG_ONLY_TEXT = {"Debug: play test tone", "Debug: stop test tone"}  # BuildConfig.DEBUG affordances
FRAME_GAP = 40
LABEL_GAP = 34
ROW_GAP = 120
TEXT_FAMILY = "Roboto"  # the app's Android default
MONO_FAMILY = "Liberation Mono"  # FontFamily.Monospace stand-in FigCraft bundles
PAGE_BG = {"r": 0.043, "g": 0.051, "b": 0.063, "a": 1.0}
CAPTION = (0.54, 0.58, 0.63, 1.0)

nodes = {}
next_id = [1]


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


def add(parent, node):
    i = nid()
    node["id"] = i
    node["parent"] = parent
    nodes[i] = node
    nodes[parent].setdefault("children", []).append(i)
    return i


def transform(x, y):
    return [1.0, 0.0, 0.0, 1.0, round(float(x), 2), round(float(y), 2)]


def rect(parent, name, x, y, w, h, fill=None, stroke=None, stroke_width=1.0, radii=(0, 0, 0, 0)):
    n = {"name": name, "type": "rectangle", "blend": "normal", "transform": transform(x, y),
         "width": round(w, 2), "height": round(h, 2), "fills": [paint(fill)] if fill else [],
         "corners": {"radii": [float(r) for r in radii], "smoothing": 0.0}}
    if stroke:
        n["strokes"] = [paint(stroke)]
        n["stroke"] = stroke_spec(stroke_width)
    return add(parent, n)


def ellipse(parent, name, x, y, w, h, fill=None, stroke=None, stroke_width=1.0):
    n = {"name": name, "type": "ellipse", "start": 0.0, "sweep": 6.283185307179586, "inner": 0.0, "blend": "normal",
         "transform": transform(x, y), "width": round(w, 2), "height": round(h, 2),
         "fills": [paint(fill)] if fill else [], "corners": {"radii": [w / 2] * 4, "smoothing": 0.0}}
    if stroke:
        n["strokes"] = [paint(stroke)]
        n["stroke"] = stroke_spec(stroke_width, align="center")
    return add(parent, n)


WEIGHTS = {100: "Thin", 200: "ExtraLight", 300: "Light", 400: "Regular", 500: "Medium", 600: "SemiBold",
           700: "Bold", 800: "ExtraBold", 900: "Black"}


def text(parent, item, x, y):
    size = float(item["size"])
    line = item.get("lineHeight")
    lines = item["text"]
    # One measured line stays one line: FigCraft's font fallback may be a
    # hair wider than Android's Roboto, and a fixed box would wrap it.
    single = "\n" not in lines and item["h"] < (line or size * 1.17) * 1.6
    return add(parent, {
        "name": lines.split("\n")[0][:60] or "text", "type": "text", "characters": lines,
        "style": {"font_family": MONO_FAMILY if item.get("mono") else TEXT_FAMILY,
                  "font_style": WEIGHTS.get(int(item.get("weight", 400)), "Regular"),
                  "font_size": size,
                  "line_height": {"unit": "pixels", "value": float(line)} if line else {"unit": "auto"},
                  "letter_spacing": {"unit": "pixels", "value": float(item.get("letterSpacing", 0.0))},
                  "decoration": "none", "case": "original", "fills": None, "link": None},
        "auto_resize": "widthAndHeight" if single else "height",
        "align_h": "left" if single else item.get("align", "left"), "blend": "normal",
        "transform": transform(x, y), "width": round(item["w"] + 1.0, 2), "height": round(item["h"], 2),
        "fills": [paint(item["color"])],
        "child_layout": {"sizing_h": "hug" if single else "fixed", "sizing_v": "hug", "absolute": False, "min_w": None,
                         "max_w": None, "min_h": None, "max_h": None, "grid_column": None, "grid_row": None,
                         "grid_column_span": 0, "grid_row_span": 0}})


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
                last = vertices[cur]
                first = vertices[start]
                if segs and abs(last["x"] - first["x"]) < 0.01 and abs(last["y"] - first["y"]) < 0.01:
                    segs[-1]["b"] = start  # the path already returned: weld instead of a zero-length edge
                    vertices.pop()
                else:
                    segs.append({"a": cur, "b": start, "ta": [0.0, 0.0], "tb": [0.0, 0.0]})
                    loop.append(len(segs) - 1)
            cur = start
            close_loop()
    close_loop()
    return vertices, segs, loops


def vector(parent, name, x, y, w, h, path):
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
    return add(parent, n)


def top(it):
    if it["kind"] == "line":
        return it["oy"] + min(it["y1"], it["y2"])
    if it["kind"] == "circle":
        return it["oy"] + it["cy"] - it["r"]
    if it["kind"] in ("drect", "arc"):
        return it["oy"] + it["y"]
    return it["y"]


def shift(it, dy):
    it = dict(it)
    if "oy" in it:
        it["oy"] -= dy
    else:
        it["y"] -= dy
    return it


def drop_debug_row(items):
    """Removes the debug-build-only test-tone row and closes the gap it leaves.

    The row is the last one of its card: it spans from its own top hairline
    to the card's bottom edge, so everything under the card moves up by
    that height and the card shrinks by it.
    """
    debug = next((it for it in items if it["kind"] == "text" and it["text"] in DEBUG_ONLY_TEXT), None)
    if debug is None:
        return items
    cards = [it for it in items if it["kind"] == "rect" and it["x"] <= debug["x"] and it["y"] <= debug["y"]
             and it["y"] + it["h"] >= debug["y"] + debug["h"] and it["w"] > 300]
    card_bottom = min(c["y"] + c["h"] for c in cards)
    hairlines = [it for it in items if it["kind"] == "line" and debug["y"] - 30 <= top(it) <= debug["y"]]
    row_top = max((top(h) for h in hairlines), default=debug["y"] - 8)
    gap = card_bottom - row_top
    out = []
    for it in items:
        t = top(it)
        if row_top - 0.5 <= t < card_bottom - 0.5 and not (it["kind"] == "rect" and it.get("h", 0) > gap + 1):
            continue  # inside the debug row
        if it["kind"] == "rect" and it["y"] < row_top and it["y"] + it["h"] >= card_bottom - 0.5:
            it = dict(it, h=it["h"] - gap)  # the cards that held the row
        elif t >= card_bottom - 0.5:
            it = shift(it, gap)
        out.append(it)
    return out


def screen_frame(parent, data, fx, fy):
    items = data["items"]
    root_fill = next((i["fill"] for i in items if i["kind"] == "rect" and i.get("fill") and i["h"] >= ROOT_FILL_MIN_HEIGHT),
                     (0.063, 0.078, 0.094, 1.0))
    drawn = [it for it in items if not (it["kind"] == "rect" and it["h"] >= ROOT_FILL_MIN_HEIGHT)]
    drawn = drop_debug_row(drawn)

    def bottom(it):
        if it["kind"] in ("line",):
            return it["oy"] + max(it["y1"], it["y2"])
        if it["kind"] == "circle":
            return it["oy"] + it["cy"] + it["r"]
        if it["kind"] in ("drect", "arc"):
            return it["oy"] + it["y"] + it["h"]
        return it["y"] + it["h"]

    height = data["height"] if data.get("fixedHeight") else round(max((bottom(it) for it in drawn), default=600) + 24)
    f = add(parent, {"name": data["name"], "type": "frame", "transform": transform(fx, fy), "width": float(data["width"]),
                     "height": float(height), "fills": [paint(root_fill)],
                     "corners": {"radii": [0.0] * 4, "smoothing": 0.0}, "clip": True,
                     "strokes": [paint((0.16, 0.19, 0.23, 1.0))], "stroke": stroke_spec(1.0)})
    for it in drawn:
        k = it["kind"]
        if k == "rect":
            rect(f, "box", it["x"], it["y"], it["w"], it["h"], fill=it.get("fill"), stroke=it.get("stroke"),
                 stroke_width=it.get("strokeWidth", 1.0), radii=it.get("radii", (0, 0, 0, 0)))
        elif k == "text":
            text(f, it, it["x"], it["y"])
        elif k == "vector":
            for j, p in enumerate(it["paths"]):
                vector(f, "icon" if len(it["paths"]) == 1 else f"icon-{j}", it["x"], it["y"], it["w"], it["h"], p)
        elif k == "line":
            if it.get("clear"):
                continue
            x1, y1, x2, y2 = it["ox"] + it["x1"], it["oy"] + it["y1"], it["ox"] + it["x2"], it["oy"] + it["y2"]
            ox, oy = min(x1, x2), min(y1, y2)
            vector(f, "line", ox, oy, abs(x2 - x1), abs(y2 - y1), {
                "segments": [{"type": "Move", "points": [x1 - ox, y1 - oy]},
                             {"type": "Line", "points": [x1 - ox, y1 - oy, x2 - ox, y2 - oy]}],
                "stroke": True, "color": it["color"], "strokeWidth": it["strokeWidth"] or 1.0})
        elif k == "circle":
            if it.get("clear"):
                continue
            cx, cy, r = it["ox"] + it["cx"], it["oy"] + it["cy"], it["r"]
            if it["stroke"]:
                ellipse(f, "ring", cx - r, cy - r, 2 * r, 2 * r, stroke=it["color"], stroke_width=it["strokeWidth"])
            else:
                ellipse(f, "dot", cx - r, cy - r, 2 * r, 2 * r, fill=it["color"])
        elif k == "arc":
            if it.get("clear") or it["color"][3] == 0:
                continue  # a transparent track draws nothing
            import math
            x, y, w, h = it["ox"] + it["x"], it["oy"] + it["y"], it["w"], it["h"]
            steps = max(2, int(abs(it["sweep"]) / 10))
            pts = []
            for i in range(steps + 1):
                a = math.radians(it["start"] + it["sweep"] * i / steps)
                pts.append((w / 2 + w / 2 * math.cos(a), h / 2 + h / 2 * math.sin(a)))
            segs = [{"type": "Move", "points": list(pts[0])}]
            segs += [{"type": "Line", "points": [*pts[i - 1], *pts[i]]} for i in range(1, len(pts))]
            vector(f, "arc", x, y, w, h, {"segments": segs, "stroke": True, "color": it["color"],
                                         "strokeWidth": it["strokeWidth"] or 1.0})
        elif k == "drect":
            if it.get("clear"):
                continue
            x, y = it["ox"] + it["x"], it["oy"] + it["y"]
            if it["stroke"]:
                rect(f, "drawn", x, y, it["w"], it["h"], stroke=it["color"], stroke_width=it["strokeWidth"],
                     radii=[it["r"]] * 4)
            else:
                rect(f, "drawn", x, y, it["w"], it["h"], fill=it["color"], radii=[it["r"]] * 4)
    return height


def caption(parent, chars, x, y):
    add(parent, {"name": chars, "type": "text", "characters": chars,
                 "style": {"font_family": MONO_FAMILY, "font_style": "Regular", "font_size": 12.0,
                           "line_height": {"unit": "pixels", "value": 14.0},
                           "letter_spacing": {"unit": "pixels", "value": 1.5}, "decoration": "none",
                           "case": "original", "fills": None, "link": None},
                 "auto_resize": "height", "blend": "normal", "transform": transform(x, y), "width": 412.0,
                 "height": 14.0, "fills": [paint(CAPTION)],
                 "child_layout": {"sizing_h": "fixed", "sizing_v": "hug", "absolute": False, "min_w": None,
                                  "max_w": None, "min_h": None, "max_h": None, "grid_column": None,
                                  "grid_row": None, "grid_column_span": 0, "grid_row_span": 0}})


def copy_spec_pages(spec_path):
    """Appends the pages of another FigCraft file (the group F spec), renumbering its ids."""
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
    spec = sys.argv[sys.argv.index("--spec") + 1] if "--spec" in sys.argv else None
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
            tallest = max(tallest, screen_frame(page, data, x, y + LABEL_GAP))
            x += data["width"] + FRAME_GAP
        y += LABEL_GAP + tallest + ROW_GAP
    pages = [page] + (copy_spec_pages(spec) if spec else [])
    doc = {"format": "figcraft", "version": 1, "imageIndex": [], "name": Path(out).stem, "pages": pages,
           "nodes": {str(k): v for k, v in nodes.items()}, "next_id": next_id[0], "styles": [],
           "variables": {"collections": [], "variables": []}, "current_page": page, "document_colors": [],
           "comments": []}
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", json.dumps({"versions": []}))
    print(out, len(nodes), "nodes,", len(pages), "pages")


if __name__ == "__main__":
    main()
