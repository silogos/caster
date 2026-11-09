"""A small Figma-style auto layout engine over FigCraft nodes.

Used two ways:
  - build_group_f_spec.py builds its frames from layout parameters only and
    calls solve() to place every layer (no hand-placed coordinates);
  - check_auto_layout.py re-runs it on a finished file to prove the stored
    positions follow from the auto-layout parameters.

Rules: per axis fixed / hug / fill; padding (top, right, bottom, left); gap;
main alignment min / center / max / spaceBetween; cross alignment min /
center / max; min sizes; absolute children keep their own position; a frame
without `layout` keeps its children where they are.
"""

H = "horizontal"


def lay(n):
    return n.get("child_layout") or {}


def sizing(n, axis):
    return lay(n).get("sizing_h" if axis == "x" else "sizing_v") or "fixed"


class Engine:
    def __init__(self, nodes, text_height=None):
        """nodes: {id: node}; text_height(node, width) → height for a wrapping text (None: keep stored)."""
        self.N = nodes
        self.text_height = text_height
        self.placed = {}

    def kids(self, n):
        return [self.N[c] for c in n.get("children", [])]

    def flow(self, n):
        return [c for c in self.kids(n) if not lay(c).get("absolute")]

    def stored(self, n, axis):
        return n["width"] if axis == "x" else n["height"]

    def natural(self, n, axis):
        """The hug size along an axis."""
        if n["type"] != "frame" or not n.get("layout") or sizing(n, axis) != "hug":
            return self.stored(n, axis)
        L = n["layout"]
        t, r, b, l = L["padding"]
        pad = (l + r) if axis == "x" else (t + b)
        main = "x" if L["mode"] == H else "y"
        sizes = [self.natural(c, axis) for c in self.flow(n)]
        total = 0.0
        if sizes:
            total = sum(sizes) + L["gap"] * (len(sizes) - 1) if axis == main else max(sizes)
        mn = lay(n).get("min_w" if axis == "x" else "min_h") or 0
        return max(total + pad, mn)

    def layout(self, n, x, y, w, h):
        self.placed[n["id"]] = (x, y, w, h)
        if n["type"] == "text" and self.text_height and n.get("auto_resize") == "height":
            h = self.text_height(n, w)
            self.placed[n["id"]] = (x, y, w, h)
        if n["type"] != "frame":
            return
        if not n.get("layout"):
            for c in self.kids(n):
                self.layout(c, x + c["transform"][4], y + c["transform"][5], c["width"], c["height"])
            return
        L = n["layout"]
        t, r, b, l = L["padding"]
        main, cross = ("x", "y") if L["mode"] == H else ("y", "x")
        inner_main = (w - l - r) if main == "x" else (h - t - b)
        inner_cross = (h - t - b) if main == "x" else (w - l - r)
        items = self.flow(n)
        sizes = []
        for c in items:
            ms = self.natural(c, main) if sizing(c, main) != "fill" else None
            cs = inner_cross if sizing(c, cross) == "fill" else self.natural(c, cross)
            mn = lay(c).get("min_h" if cross == "y" else "min_w")
            if mn:
                cs = max(cs, mn)
            sizes.append([ms, cs])
        fixed_total = sum(s[0] for s in sizes if s[0] is not None)
        fills = [s for s in sizes if s[0] is None]
        gaps = L["gap"] * max(len(items) - 1, 0)
        if fills:
            share = max(inner_main - fixed_total - gaps, 0) / len(fills)
            for s in fills:
                s[0] = share
        for c, s in zip(items, sizes):
            mn = lay(c).get("min_w" if main == "x" else "min_h")
            if mn:
                s[0] = max(s[0], mn)
        free = inner_main - (sum(s[0] for s in sizes) + gaps)
        pos, gap = 0.0, L["gap"]
        if L["main_align"] == "center":
            pos = free / 2
        elif L["main_align"] == "max":
            pos = free
        elif L["main_align"] == "spaceBetween" and len(items) > 1:
            gap = L["gap"] + free / (len(items) - 1)
        start_main = l if main == "x" else t
        start_cross = t if main == "x" else l
        for c, (ms, cs) in zip(items, sizes):
            ca = L["cross_align"]
            off = 0.0 if ca == "min" else (inner_cross - cs) / 2 if ca == "center" else inner_cross - cs
            if main == "x":
                self.layout(c, x + start_main + pos, y + start_cross + off, ms, cs)
            else:
                self.layout(c, x + start_cross + off, y + start_main + pos, cs, ms)
            pos += ms + gap
        for c in self.kids(n):
            if lay(c).get("absolute"):
                self.layout(c, x + c["transform"][4], y + c["transform"][5], c["width"], c["height"])

    def apply(self, root_id):
        """Writes the solved boxes back as parent-relative transforms and sizes."""
        def visit(i, px, py):
            n = self.N[i]
            if i in self.placed:
                x, y, w, h = self.placed[i]
                n["transform"][4] = round(x - px, 2)
                n["transform"][5] = round(y - py, 2)
                n["width"] = round(w, 2)
                n["height"] = round(h, 2)
                px, py = x, y
            for c in n.get("children", []):
                visit(c, px, py)
        r = self.N[root_id]
        visit(root_id, self.placed[root_id][0] - r["transform"][4], self.placed[root_id][1] - r["transform"][5])


def solve(nodes, root_id, text_height=None):
    """Lays out a top-level frame from its own size and its descendants' layout parameters."""
    eng = Engine(nodes, text_height)
    r = nodes[root_id]
    w = eng.natural(r, "x") if sizing(r, "x") == "hug" else r["width"]
    h = eng.natural(r, "y") if sizing(r, "y") == "hug" else r["height"]
    eng.layout(r, r["transform"][4], r["transform"][5], w, h)
    eng.apply(root_id)
    return eng
