"""Draws the USB cast (ADR-034) on the desktop deck: frames 10–14 of
designs/desktop-signal-deck.figcraft, copied from frame 07 (the newest deck)
and frame 01 (the QR hero) and changed layer by layer — absolute layers,
like the other desktop builders.

  10  Deck · connected by cable, standby    the phone proved the pairing over the cable
  11  Deck · on air by cable                 the cast runs over USB
  12  Deck · cable unplugged                 the link is gone; nothing to reconnect to
  13  QR hero · a phone on the cable isn't paired with this Mac
  14  Deck · opened with a saved phone that isn't connected yet (not the QR)

Re-running replaces frames 10–14; the others are untouched.

    python3 -I tools/design/build_desktop_usb_cast.py designs/desktop-signal-deck.figcraft
"""

import copy
import json
import sys
import zipfile

DECK_SOURCE = "07 Deck · On air, Syphon on, audio and mic to Caster devices"
QR_SOURCE = "01 QR hero"
CAPTION_SOURCE = 280
FRAME_Y = 112.0
FIRST_X = 9040.0
STEP_X = 1000.0

INK = {"r": 0.933, "g": 0.945, "b": 0.957, "a": 1.0}
MUTED = {"r": 0.541, "g": 0.576, "b": 0.627, "a": 1.0}
SIGNAL = {"r": 0.184, "g": 0.82, "b": 0.714, "a": 1.0}
SIGNAL_DIM = {"r": 0.07, "g": 0.22, "b": 0.2, "a": 1.0}
FAULT = {"r": 1.0, "g": 0.416, "b": 0.302, "a": 1.0}
QUIET_FILL = {"r": 0.086, "g": 0.106, "b": 0.129, "a": 1.0}
BANNER_FILL = {"r": 0.118, "g": 0.141, "b": 0.169, "a": 1.0}

FRAMES = [
    ("10 Deck · Connected by cable, standby", "DESKTOP — 10 · DECK / CONNECTED BY CABLE, STANDBY (USB CAST, ADR-034)"),
    ("11 Deck · On air by cable", "DESKTOP — 11 · DECK / ON AIR BY CABLE (USB CAST)"),
    ("12 Deck · Cable unplugged", "DESKTOP — 12 · DECK / CABLE UNPLUGGED — NOTHING TO RECONNECT TO (USB CAST)"),
    ("13 QR hero · Cable phone not paired", "DESKTOP — 13 · QR HERO / A PHONE ON THE CABLE ISN'T PAIRED WITH THIS MAC (USB CAST)"),
    ("14 Deck · Saved phone, not connected", "DESKTOP — 14 · DECK / OPENED WITH A SAVED PHONE THAT ISN'T CONNECTED YET — NOT THE QR"),
]


def solid(color):
    return [{"type": "solid", "color": dict(color), "opacity": 1.0, "visible": True, "blend": "normal"}]


def main(path):
    with zipfile.ZipFile(path) as z:
        doc = json.loads(z.read("document.json"))
        versions = z.read("versions/index.json")
    nodes = doc["nodes"]
    page = nodes["1"]
    names = {n for pair in FRAMES for n in pair}
    for nid in list(page["children"]):
        n = nodes[str(nid)]
        if n["name"] in names:
            for child in n.get("children", []):
                nodes.pop(str(child), None)
            nodes.pop(str(nid), None)
            page["children"].remove(nid)

    def by_name(name):
        return next(nodes[str(c)] for c in page["children"] if nodes[str(c)]["name"] == name)

    next_id = [doc["next_id"]]

    def new_id():
        next_id[0] += 1
        return next_id[0] - 1

    def starting(layers, prefix):
        return next(v[0] for k, v in layers.items() if k.startswith(prefix))

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

    def set_text(node, text, color=None, width=None):
        node["characters"] = text
        node["name"] = text[:40]
        if color:
            node["fills"] = solid(color)
        if width:
            node["width"] = float(width)

    def move(node, x=None, y=None):
        if x is not None:
            node["transform"][4] = float(x)
        if y is not None:
            node["transform"][5] = float(y)

    def usb_tag(frame, layers, live):
        """The link's tag beside SOURCE, built from the AUDIO chip."""
        chip = copy.deepcopy(layers["chip-game"][0])
        label = copy.deepcopy(layers["AUDIO"][0])
        chip["width"] = 52.0
        chip["fills"] = solid(SIGNAL_DIM if live else QUIET_FILL)
        move(chip, 300.0, 77.5)
        set_text(label, "USB", SIGNAL if live else MUTED, width=40)
        move(label, 314.0, 83.5)
        add(frame, chip)
        add(frame, label)

    def standby(layers, status):
        ann = layers["annunciator"][0]
        ann["fills"] = solid(QUIET_FILL)
        ann["width"] = 101.1
        move(ann, 650.9)
        layers["dot"][0]["fills"] = solid(MUTED)
        move(layers["dot"][0], 664.9)
        set_text(layers["ON AIR"][0], "STANDBY", MUTED, width=92)
        move(layers["ON AIR"][0], 680.9)
        set_text(layers["Casting · 1920×1200 · 60 fps · balanced"][0], status, width=470)
        layers["status-pill"][0]["width"] = 495.0
        set_text(layers["Close video"][0], "Open video", width=139)
        move(layers["Close video"][0], 435.0)
        set_text(layers["00:14:32"][0], "00:00:00", MUTED)
        for chip_dot in layers["dot"][2:4]:
            chip_dot["fills"] = solid(MUTED)
        set_text(layers["1920×1200 · 60 fps · balanced"][0], "Waiting for the cast", width=232)
        move(layers["1920×1200 · 60 fps · balanced"][0], 556.0)

    def mic_note(layers):
        set_text(starting(layers, "The phone's mic"), "Not sent over the cable yet — the mic needs a Wi-Fi cast.", width=380)

    for i, (name, cap) in enumerate(FRAMES):
        x = FIRST_X + i * STEP_X
        caption(cap, x)
        if i == 3:
            frame, layers = clone_frame(QR_SOURCE, name, x)
            # A banner over the code: why the cable alone didn't connect.
            banner = add(frame, {"type": "rectangle", "name": "usb banner", "blend": "normal",
                                 "transform": [1.0, 0.0, 0.0, 1.0, 0.0, 0.0], "width": 544.0, "height": 58.0,
                                 "fills": solid(BANNER_FILL), "corners": {"radii": [12.0] * 4, "smoothing": 0.0}})
            move(banner, 208.0, 552.0)
            title = add(frame, copy.deepcopy(starting(layers, "Open Caster")))
            set_text(title, "A phone on the USB cable isn't paired with this Mac.", FAULT, width=500)
            move(title, 228.0, 562.0)
            body = add(frame, copy.deepcopy(starting(layers, "Open Caster")))
            set_text(body, "Scan this code with Caster once — after that the cable works on its own.", MUTED, width=500)
            move(body, 228.0, 582.0)
            continue
        frame, layers = clone_frame(DECK_SOURCE, name, x)
        if i == 0:
            standby(layers, "Connected by cable — start casting from your phone")
            layers["dot"][1]["fills"] = solid(SIGNAL)
            usb_tag(frame, layers, live=True)
        elif i == 1:
            set_text(layers["Casting · 1920×1200 · 60 fps · balanced"][0], "Casting by cable · 1920×1200 · 60 fps", width=330)
            set_text(layers["1920×1200 · 60 fps · balanced"][0], "1920×1200 · 60 fps · USB", width=250)
            move(layers["1920×1200 · 60 fps · balanced"][0], 536.0)
            usb_tag(frame, layers, live=True)
            # Game audio and the mic both arrive over the cable: both chips lit, as in frame 07.
        elif i == 2:
            standby(layers, "Cable unplugged — plug it in again, or tap Connect on the phone for Wi-Fi")
            layers["dot"][1]["fills"] = solid(MUTED)
            usb_tag(frame, layers, live=False)
        else:
            # Opened with a trusted phone that hasn't connected yet (ADR-006):
            # the deck, not the QR — the pairing is still there.
            standby(layers, "Not connected — tap Connect on the phone, or plug the cable in")
            layers["dot"][1]["fills"] = solid(MUTED)
            set_text(layers["ON AIR"][0], "OFFLINE", MUTED, width=92)
            set_text(layers["Close video"][0], "Open video", MUTED, width=139)
            layers["video-toggle"][0]["fills"] = solid(QUIET_FILL)
            hint = layers["video hint"][0]
            set_text(hint, "Pair a different phone? Show QR code", SIGNAL, width=300)
            move(hint, 356.0)

    doc["next_id"] = next_id[0]
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", versions)
    print(path, "frames 10–14 written")


if __name__ == "__main__":
    main(sys.argv[1])
