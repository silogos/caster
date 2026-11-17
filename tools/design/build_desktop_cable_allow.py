"""Draws the cable's one-time Allow (ADR-037, usb-link.md v2) on the desktop
deck: frames 15–17 of designs/desktop-signal-deck.figcraft, copied from
frame 01 (the QR hero) and frame 14 (the deck with a saved phone) and changed
layer by layer — absolute layers, like the other desktop builders.

  15  QR hero · a new phone on the cable: "Allow <phone> to cast over the cable?"
  16  Deck · the same Allow, on a Mac that already knows this phone over Wi-Fi
      (cable trust and Wi-Fi trust are separate entries, so the cable asks once too)
  17  QR hero · the phone on the cable runs an older Caster (link v1)

Re-running replaces frames 15–17; the others are untouched. Run it after
build_desktop_usb_cast.py (it copies frame 14).

    python3 -I tools/design/build_desktop_cable_allow.py designs/desktop-signal-deck.figcraft
"""

import copy
import json
import sys
import zipfile

QR_SOURCE = "01 QR hero"
SAVED_SOURCE = "14 Deck · Saved phone, not connected"
CAPTION_SOURCE = 280
TITLE_SOURCE = "Scan with your Android device"
BODY_SOURCE = "Open Caster on the phone and scan th"
BUTTON_TEXT_SOURCE = "New QR code"
FRAME_Y = 112.0
FIRST_X = 14040.0
STEP_X = 1000.0

PHONE = "Lenovo TB321FU"
SHEET_W = 440.0
SHEET_PAD = 24.0
BUTTON_H = 42.0

INK = {"r": 0.933, "g": 0.945, "b": 0.957, "a": 1.0}
MUTED = {"r": 0.541, "g": 0.576, "b": 0.627, "a": 1.0}
SIGNAL = {"r": 0.184, "g": 0.82, "b": 0.714, "a": 1.0}
SIGNAL_DIM = {"r": 0.07, "g": 0.22, "b": 0.2, "a": 1.0}
ON_SIGNAL = {"r": 0.03, "g": 0.09, "b": 0.08, "a": 1.0}
FAULT = {"r": 1.0, "g": 0.416, "b": 0.302, "a": 1.0}
QUIET_FILL = {"r": 0.086, "g": 0.106, "b": 0.129, "a": 1.0}
BANNER_FILL = {"r": 0.118, "g": 0.141, "b": 0.169, "a": 1.0}
EDGE = {"r": 0.165, "g": 0.192, "b": 0.227, "a": 1.0}
BLACK = {"r": 0.0, "g": 0.0, "b": 0.0, "a": 1.0}

FRAMES = [
    ("15 QR hero · Allow a new phone on the cable",
     "DESKTOP — 15 · ALLOW A NEW PHONE ON THE CABLE — ASKED ONCE PER PHONE (ADR-037, USB LINK v2)"),
    ("16 Deck · Allow on a Mac that knows the phone over Wi-Fi",
     "DESKTOP — 16 · THE SAME ALLOW ON A MAC PAIRED OVER WI-FI — CABLE TRUST IS ITS OWN ENTRY"),
    ("17 QR hero · Phone's Caster too old for the cable",
     "DESKTOP — 17 · QR HERO / THE PHONE ON THE CABLE RUNS AN OLDER CASTER (LINK v1)"),
]


def solid(color, opacity=1.0):
    return [{"type": "solid", "color": dict(color), "opacity": opacity, "visible": True, "blend": "normal"}]


def corners(r):
    return {"radii": [r] * 4, "smoothing": 0.0}


def stroke(weight=1.0):
    return {"weight": weight, "align": "inside", "sides": None, "cap_start": "none", "cap_end": "none",
            "join": "miter", "miter_limit": 4.0, "dashes": []}


def main(path):
    with zipfile.ZipFile(path) as z:
        doc = json.loads(z.read("document.json"))
        versions = z.read("versions/index.json")
    nodes = doc["nodes"]
    page = nodes["1"]
    names = {n for pair in FRAMES for n in pair}

    def drop(nid):
        for child in nodes[str(nid)].get("children", []):
            drop(child)
        nodes.pop(str(nid), None)

    for nid in list(page["children"]):
        if nodes[str(nid)]["name"] in names:
            drop(nid)
            page["children"].remove(nid)

    def by_name(name):
        return next(nodes[str(c)] for c in page["children"] if nodes[str(c)]["name"] == name)

    next_id = [doc["next_id"]]

    def new_id():
        next_id[0] += 1
        return next_id[0] - 1

    def clone_frame(source_name, name, x):
        source = by_name(source_name)
        frame = copy.deepcopy(source)
        frame.update(id=new_id(), name=name, children=[])
        frame["transform"] = [1.0, 0.0, 0.0, 1.0, x, FRAME_Y]
        nodes[str(frame["id"])] = frame
        page["children"].append(frame["id"])
        layers = {}

        def clone(cid, parent):
            child = copy.deepcopy(nodes[str(cid)])
            kids = child.get("children", [])
            child.update(id=new_id(), parent=parent["id"])
            if "children" in child:
                child["children"] = []
            nodes[str(child["id"])] = child
            parent["children"].append(child["id"])
            layers.setdefault(child["name"], []).append(child)
            for k in kids:
                clone(k, child)

        for cid in source["children"]:
            clone(cid, frame)
        return frame, layers

    qr_layers = {}
    for cid in by_name(QR_SOURCE)["children"]:
        qr_layers.setdefault(nodes[str(cid)]["name"], nodes[str(cid)])

    def caption(text, x):
        node = copy.deepcopy(nodes[str(CAPTION_SOURCE)])
        node.update(id=new_id(), name=text, characters=text, width=900.0)
        node["transform"] = [1.0, 0.0, 0.0, 1.0, x, 78.0]
        node["parent"] = 1
        nodes[str(node["id"])] = node
        page["children"].append(node["id"])

    def add(frame, node):
        node.update(id=new_id(), parent=frame["id"])
        nodes[str(node["id"])] = node
        frame["children"].append(node["id"])
        return node

    def rect(frame, name, x, y, w, h, fill, opacity=1.0, radius=0.0, edge=None):
        node = {"type": "rectangle", "name": name, "blend": "normal", "transform": [1.0, 0.0, 0.0, 1.0, x, y],
                "width": w, "height": h, "fills": solid(fill, opacity), "corners": corners(radius)}
        if edge:
            node["strokes"] = solid(edge)
            node["stroke"] = stroke()
        return add(frame, node)

    def text(frame, source, chars, x, y, w, h, color, size=None, style=None):
        node = copy.deepcopy(qr_layers[source])
        node.pop("children", None)
        node.update(name=chars[:40], characters=chars, width=float(w), height=float(h))
        node["transform"] = [1.0, 0.0, 0.0, 1.0, float(x), float(y)]
        node["fills"] = solid(color)
        if size:
            node["style"]["font_size"] = float(size)
            node["style"]["line_height"] = {"unit": "pixels", "value": round(size * 1.25, 1)}
        if style:
            node["style"]["font_style"] = style
        return add(frame, node)

    def allow_sheet(frame, top):
        """The prompt: a sheet over a scrim, the deck's own surfaces and type."""
        rect(frame, "scrim", 0.0, 0.0, frame["width"], frame["height"], BLACK, opacity=0.55)
        left = (frame["width"] - SHEET_W) / 2
        inner = SHEET_W - 2 * SHEET_PAD
        x = left + SHEET_PAD
        y = top + SHEET_PAD
        rect(frame, "allow sheet", left, top, SHEET_W, 0.0, BANNER_FILL, radius=16.0, edge=EDGE)
        sheet = nodes[str(frame["children"][-1])]
        rect(frame, "usb chip", x, y, 52.0, 22.0, SIGNAL_DIM, radius=11.0)
        text(frame, BUTTON_TEXT_SOURCE, "USB", x + 14, y + 4, 40, 14, SIGNAL, size=11, style="Bold")
        y += 38
        text(frame, TITLE_SOURCE, f"Allow {PHONE} to cast over the cable?", x, y, inner, 50, INK, size=20)
        y += 62
        text(frame, BODY_SOURCE,
             "It's plugged into this Mac. If you allow it, it connects by itself whenever it's plugged in. "
             "Allow only a phone you know.", x, y, inner, 54, MUTED)
        y += 66
        text(frame, BODY_SOURCE, "Closes in 0:52 — no answer means Don't Allow.", x, y, inner, 16, MUTED, size=12)
        y += 32
        half = (inner - 12) / 2
        rect(frame, "dont-allow", x, y, half, BUTTON_H, QUIET_FILL, radius=16.0, edge=EDGE)
        text(frame, BUTTON_TEXT_SOURCE, "Don't Allow", x + half / 2 - 40, y + 13, 120, 16.5, INK)
        rect(frame, "focus ring", x - 3, y - 3, half + 6, BUTTON_H + 6, SIGNAL, opacity=0.0, radius=19.0, edge=SIGNAL)
        rect(frame, "allow", x + half + 12, y, half, BUTTON_H, SIGNAL, radius=16.0)
        text(frame, BUTTON_TEXT_SOURCE, "Allow", x + half + 12 + half / 2 - 18, y + 13, 80, 16.5, ON_SIGNAL)
        sheet["height"] = y + BUTTON_H + SHEET_PAD - top

    for i, (name, cap) in enumerate(FRAMES):
        x = FIRST_X + i * STEP_X
        caption(cap, x)
        if i == 0:
            frame, _ = clone_frame(QR_SOURCE, name, x)
            allow_sheet(frame, 150.0)
        elif i == 1:
            frame, _ = clone_frame(SAVED_SOURCE, name, x)
            allow_sheet(frame, 260.0)
        else:
            frame, _ = clone_frame(QR_SOURCE, name, x)
            rect(frame, "usb banner", 208.0, 552.0, 544.0, 58.0, BANNER_FILL, radius=12.0)
            text(frame, BODY_SOURCE, "The phone on the USB cable has an older Caster.", 228.0, 562.0, 500, 16.5, FAULT)
            text(frame, BODY_SOURCE, "Update Caster on the phone to cast over the cable.",
                 228.0, 582.0, 500, 16.5, MUTED)

    doc["next_id"] = next_id[0]
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", versions)
    print(path, "frames 15–17 written")


if __name__ == "__main__":
    main(sys.argv[1])
