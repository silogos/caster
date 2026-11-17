"""Draws the receiver's side of ADR-037 (the link is chosen first) on the
desktop deck: frames 15–18 of designs/desktop-signal-deck.figcraft, copied
from frame 01 (the QR hero) and changed layer by layer — absolute layers,
like the other desktop builders.

  15  First screen · choose Wireless or Cable (nothing paired yet)
  16  Wireless · the QR hero, with Back to the choice
  17  Cable guide (macOS) · steps tick as they happen; the one-time Allow is step 3
  18  Cable guide (Windows) · the same, with "Install the USB driver" as step 2
      (drawn now, built with the Windows receiver)

Once paired, the receiver opens on its deck (frames 02/10, 14) in the
pairing's mode. Re-running replaces frames 15–18 (and the superseded
Allow-sheet frames of an earlier draft); the others are untouched.

    python3 -I tools/design/build_desktop_link_first.py designs/desktop-signal-deck.figcraft
"""

import copy
import json
import sys
import zipfile

QR_SOURCE = "01 QR hero"
CAPTION_SOURCE = 280
TITLE_SOURCE = "Scan with your Android device"
BODY_SOURCE = "Open Caster on the phone and scan th"
BUTTON_TEXT_SOURCE = "New QR code"
HEADER = {"brand-tile", "cast screen", "cast dot", "CASTER", "annunciator", "dot", "STANDBY"}
FRAME_Y = 112.0
FIRST_X = 14040.0
STEP_X = 1000.0
COL_X = 208.0
COL_W = 544.0
PHONE = "Lenovo TB321FU"

INK = {"r": 0.933, "g": 0.945, "b": 0.957, "a": 1.0}
MUTED = {"r": 0.541, "g": 0.576, "b": 0.627, "a": 1.0}
SIGNAL = {"r": 0.184, "g": 0.82, "b": 0.714, "a": 1.0}
SIGNAL_DIM = {"r": 0.07, "g": 0.22, "b": 0.2, "a": 1.0}
ON_SIGNAL = {"r": 0.03, "g": 0.09, "b": 0.08, "a": 1.0}
QUIET_FILL = {"r": 0.086, "g": 0.106, "b": 0.129, "a": 1.0}
TILE_FILL = {"r": 0.118, "g": 0.141, "b": 0.169, "a": 1.0}
EDGE = {"r": 0.165, "g": 0.192, "b": 0.227, "a": 1.0}
LATER = {"r": 0.227, "g": 0.259, "b": 0.298, "a": 1.0}

FRAMES = [
    ("15 First screen · Choose Wireless or Cable",
     "DESKTOP — 15 · FIRST SCREEN / CHOOSE WIRELESS OR CABLE — ONLY THE CHOSEN LINK RUNS (ADR-037)", 640.0),
    ("16 Wireless · QR hero with Back",
     "DESKTOP — 16 · WIRELESS / TODAY'S QR HERO, WITH BACK TO THE CHOICE", 640.0),
    ("17 Cable guide · macOS, Allow at step 3",
     "DESKTOP — 17 · CABLE GUIDE (macOS) / STEPS TICK AS THEY HAPPEN · THE ONE-TIME ALLOW IS STEP 3", 700.0),
    ("18 Cable guide · Windows, driver at step 2",
     "DESKTOP — 18 · CABLE GUIDE (WINDOWS) / INSTALL THE USB DRIVER FIRST — DRAWN NOW, BUILT WITH THE WINDOWS RECEIVER",
     760.0),
]
SUPERSEDED = {
    "15 QR hero · Allow a new phone on the cable",
    "16 Deck · Allow on a Mac that knows the phone over Wi-Fi",
    "17 QR hero · Phone's Caster too old for the cable",
    "DESKTOP — 15 · ALLOW A NEW PHONE ON THE CABLE — ASKED ONCE PER PHONE (ADR-037, USB LINK v2)",
    "DESKTOP — 16 · THE SAME ALLOW ON A MAC PAIRED OVER WI-FI — CABLE TRUST IS ITS OWN ENTRY",
    "DESKTOP — 17 · QR HERO / THE PHONE ON THE CABLE RUNS AN OLDER CASTER (LINK v1)",
}


def solid(color, opacity=1.0):
    return [{"type": "solid", "color": dict(color), "opacity": opacity, "visible": True, "blend": "normal"}]


def stroke(weight=1.0):
    return {"weight": weight, "align": "inside", "sides": None, "cap_start": "none", "cap_end": "none",
            "join": "miter", "miter_limit": 4.0, "dashes": []}


def main(path):
    with zipfile.ZipFile(path) as z:
        doc = json.loads(z.read("document.json"))
        versions = z.read("versions/index.json")
    nodes = doc["nodes"]
    page = nodes["1"]
    names = {n for f in FRAMES for n in f[:2]} | SUPERSEDED

    def drop(nid):
        for child in nodes[str(nid)].get("children", []):
            drop(child)
        nodes.pop(str(nid), None)

    for nid in list(page["children"]):
        if nodes[str(nid)]["name"] in names:
            drop(nid)
            page["children"].remove(nid)

    source = next(nodes[str(c)] for c in page["children"] if nodes[str(c)]["name"] == QR_SOURCE)
    src_layers = {nodes[str(c)]["name"]: nodes[str(c)] for c in source["children"]}
    next_id = [doc["next_id"]]

    def new_id():
        next_id[0] += 1
        return next_id[0] - 1

    def add(frame, node):
        node.update(id=new_id(), parent=frame["id"])
        nodes[str(node["id"])] = node
        frame["children"].append(node["id"])
        return node

    def new_frame(name, x, height, keep_all):
        frame = copy.deepcopy(source)
        frame.update(id=new_id(), name=name, children=[], height=height)
        frame["transform"] = [1.0, 0.0, 0.0, 1.0, x, FRAME_Y]
        nodes[str(frame["id"])] = frame
        page["children"].append(frame["id"])
        for cid in source["children"]:
            child = nodes[str(cid)]
            if keep_all or child["name"] in HEADER:
                c = copy.deepcopy(child)
                c.pop("children", None)
                add(frame, c)
        return frame

    def caption(chars, x):
        node = copy.deepcopy(nodes[str(CAPTION_SOURCE)])
        node.update(id=new_id(), name=chars, characters=chars, width=900.0)
        node["transform"] = [1.0, 0.0, 0.0, 1.0, x, 78.0]
        node["parent"] = 1
        nodes[str(node["id"])] = node
        page["children"].append(node["id"])

    def rect(frame, name, x, y, w, h, fill, opacity=1.0, radius=0.0, edge=None):
        node = {"type": "rectangle", "name": name, "blend": "normal", "transform": [1.0, 0.0, 0.0, 1.0, x, y],
                "width": w, "height": h, "fills": solid(fill, opacity),
                "corners": {"radii": [radius] * 4, "smoothing": 0.0}}
        if edge:
            node["strokes"] = solid(edge)
            node["stroke"] = stroke()
        return add(frame, node)

    def text(frame, src, chars, x, y, w, h, color, size=None, style=None):
        node = copy.deepcopy(src_layers[src])
        node.update(name=chars[:40], characters=chars, width=float(w), height=float(h))
        node["transform"] = [1.0, 0.0, 0.0, 1.0, float(x), float(y)]
        node["fills"] = solid(color)
        if size:
            node["style"]["font_size"] = float(size)
            node["style"]["line_height"] = {"unit": "pixels", "value": round(size * 1.3, 1)}
        if style:
            node["style"]["font_style"] = style
        return add(frame, node)

    def back(frame):
        text(frame, BUTTON_TEXT_SOURCE, "‹  Back", 36.0, 30.0, 90, 16.5, SIGNAL)

    def heading(frame, title, sub):
        text(frame, TITLE_SOURCE, title, COL_X, 94.0, COL_W, 30.5, INK)
        text(frame, BODY_SOURCE, sub, COL_X, 132.0, COL_W, 16.5, MUTED)

    def wifi_icon(frame, cx, cy):
        for i, w in enumerate((26.0, 18.0, 10.0)):
            rect(frame, "arc", cx - w / 2, cy - 12 + i * 6, w, 3.0, SIGNAL, radius=1.5)
        rect(frame, "dot", cx - 2.5, cy + 8, 5.0, 5.0, SIGNAL, radius=2.5)

    def usb_icon(frame, cx, cy):
        rect(frame, "pin", cx - 5, cy - 14, 3.0, 6.0, SIGNAL)
        rect(frame, "pin", cx + 2, cy - 14, 3.0, 6.0, SIGNAL)
        rect(frame, "plug", cx - 9, cy - 8, 18.0, 14.0, SIGNAL, opacity=0.0, radius=3.0, edge=SIGNAL)
        rect(frame, "cord", cx - 1.5, cy + 6, 3.0, 9.0, SIGNAL)

    def steps(frame, top, items):
        """items: (title, sub, state, extra) — state done | now | later; extra = buttons below the sub."""
        y = top
        for n, (title, sub, state, extra) in enumerate(items, 1):
            col = SIGNAL if state in ("done", "now") else LATER
            rect(frame, f"step {n}", COL_X, y, 28.0, 28.0, col if state == "done" else QUIET_FILL, radius=14.0,
                 edge=None if state == "done" else col)
            text(frame, BUTTON_TEXT_SOURCE, "✓" if state == "done" else str(n), COL_X + 9, y + 5.5, 14, 16.5,
                 ON_SIGNAL if state == "done" else (SIGNAL if state == "now" else MUTED))
            text(frame, BUTTON_TEXT_SOURCE, title, COL_X + 46, y + 4, COL_W - 46, 18, INK if state != "later" else MUTED,
                 size=15)
            lines = max(1, round(len(sub) / 70 + 0.49))
            text(frame, BODY_SOURCE, sub, COL_X + 46, y + 28, COL_W - 46, 18.2 * lines, MUTED, size=13)
            h = 28 + 18.2 * lines
            if extra:
                bx = COL_X + 46
                for label, primary, w in extra:
                    rect(frame, f"button · {label}", bx, y + h + 12, w, 38.0, SIGNAL if primary else QUIET_FILL,
                         radius=14.0, edge=None if primary else EDGE)
                    text(frame, BUTTON_TEXT_SOURCE, label, bx + w / 2 - len(label) * 3.9, y + h + 22.5, w, 16.5,
                         ON_SIGNAL if primary else INK)
                    bx += w + 12
                h += 12 + 38
            nxt = y + h + 26
            if n < len(items):
                rect(frame, "connector", COL_X + 13, y + 34, 2.0, nxt - y - 40, LATER)
            y = nxt
        return y

    for i, (name, cap, height) in enumerate(FRAMES):
        x = FIRST_X + i * STEP_X
        caption(cap, x)
        if i == 0:
            f = new_frame(name, x, height, keep_all=False)
            heading(f, "How do you want to connect?", "Choose the same on the phone. To switch later, Disconnect here "
                                                     "or Terminate on the phone.")
            for j, (title, sub, icon) in enumerate((
                    ("Wireless", "Phone and Mac on the same Wi-Fi. Scan a QR code once.", wifi_icon),
                    ("Cable", "A USB cable to this Mac. No Wi-Fi needed; you allow the phone once.", usb_icon))):
                tx = COL_X + j * (COL_W / 2 + 6)
                tw = COL_W / 2 - 6
                rect(f, f"tile · {title}", tx, 186.0, tw, 236.0, TILE_FILL, radius=16.0, edge=EDGE)
                rect(f, "icon well", tx + tw / 2 - 34, 214.0, 68.0, 68.0, SIGNAL_DIM, radius=34.0)
                icon(f, tx + tw / 2, 248.0)
                text(f, BUTTON_TEXT_SOURCE, title, tx + 24, 306.0, tw - 48, 22, INK, size=18)
                text(f, BODY_SOURCE, sub, tx + 24, 336.0, tw - 48, 54, MUTED, size=13)
            text(f, BODY_SOURCE, "Only the link you choose runs: Wireless leaves USB alone (a phone plugged in to "
                                 "charge stays a phone); Cable opens nothing on the network.",
                 COL_X, 452.0, COL_W, 36, MUTED, size=12)
        elif i == 1:
            f = new_frame(name, x, height, keep_all=True)
            back(f)
        elif i == 2:
            f = new_frame(name, x, height, keep_all=False)
            back(f)
            heading(f, "Connect with a cable", "Plug your phone into this Mac with a USB cable. No Wi-Fi needed.")
            steps(f, 186.0, [
                ("Plug your phone in", f"{PHONE} found on USB.", "done", None),
                ("On the phone, tap OK on “Open Caster”",
                 "Android asks once. With “Use by default”, later plug-ins connect by themselves.", "done", None),
                (f"Allow {PHONE} to cast over the cable?",
                 "Allow only a phone you know. Asked once; no answer in 60 s means Don't Allow.", "now",
                 [("Don't Allow", False, 150.0), ("Allow", True, 150.0)]),
                ("Start casting from the phone", "Tap Share screen on the phone. This Mac then shows its deck.",
                 "later", None),
            ])
        else:
            f = new_frame(name, x, height, keep_all=False)
            back(f)
            heading(f, "Connect with a cable", "Plug your phone into this PC with a USB cable. No Wi-Fi needed.")
            steps(f, 186.0, [
                ("Plug your phone in", f"{PHONE} found on USB.", "done", None),
                ("Install the USB driver (once)",
                 "Windows needs it to talk to the phone. Caster installs it; Windows asks for permission. If the "
                 "phone isn't found afterwards, unplug it and plug it in again.", "now",
                 [("Install driver", True, 170.0)]),
                ("On the phone, tap OK on “Open Caster”",
                 "Android asks once. With “Use by default”, later plug-ins connect by themselves.", "later", None),
                (f"Allow {PHONE} to cast over the cable", "Asked once, here.", "later", None),
                ("Start casting from the phone", "Tap Share screen on the phone.", "later", None),
            ])

    doc["next_id"] = next_id[0]
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", versions)
    print(path, "frames 15–18 written")


if __name__ == "__main__":
    main(sys.argv[1])
