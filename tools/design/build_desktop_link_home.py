"""Draws the receiver's Home (the deck) for ADR-037: frames 19–24 of
designs/desktop-signal-deck.figcraft. Each is copied from a deck frame
(10, 11, 12 or 14) with its top block (SOURCE, the phone's name, the USB tag,
the status pill, Disconnect) replaced by one connection card, the phone's
mirror of the phone's own Home card: which phone, over which link, its state,
and Terminate in the card's footer. Everything below (Open video, STATUS,
Sound on this computer, Output to OBS) is the deck as shipped, moved down.

  19  Home · Wireless, connected        (from 10)
  20  Home · Cable, connected           (from 10)
  21  Home · Cable, on air              (from 11)
  22  Home · Cable, unplugged mid-cast  (from 12)
  23  Home · Cable, not connected       (from 14)
  24  Home · Wireless, not connected    (from 14)

Re-running replaces frames 19–24; the others are untouched.

    python3 -I tools/design/build_desktop_link_home.py designs/desktop-signal-deck.figcraft
"""

import copy
import json
import sys
import zipfile

FIRST_X = 18040.0
STEP_X = 1000.0
FRAME_Y = 112.0
CAPTION_SOURCE = 280
PHONE = "Lenovo TB321FU"
TOP_BLOCK = (70.0, 190.0)  # the deck's SOURCE … status pill band, replaced by the card
CARD_X, CARD_Y, CARD_W = 208.0, 80.0, 544.0
DROP_TEXT = ("Pair a different phone?",)

INK = {"r": 0.933, "g": 0.945, "b": 0.957, "a": 1.0}
MUTED = {"r": 0.541, "g": 0.576, "b": 0.627, "a": 1.0}
GOOD = {"r": 0.184, "g": 0.82, "b": 0.714, "a": 1.0}  # the deck's signal teal
AMBER = {"r": 0.949, "g": 0.663, "b": 0.231, "a": 1.0}
RED = {"r": 0.898, "g": 0.282, "b": 0.302, "a": 1.0}
CARD_FILL = {"r": 0.086, "g": 0.106, "b": 0.129, "a": 1.0}
EDGE = {"r": 0.165, "g": 0.192, "b": 0.227, "a": 1.0}

# (name, caption, base frame prefix, link, status, sub, footer hint, countdown)
FRAMES = [
    ("19 Home · Wireless, connected", "DESKTOP — 19 · HOME / WIRELESS · CONNECTED — ONE CONNECTION CARD (ADR-037)",
     "10 ", "Wireless", "Connected", None, "To switch to the cable, Terminate", None),
    ("20 Home · Cable, connected", "DESKTOP — 20 · HOME / CABLE · CONNECTED",
     "10 ", "Cable", "Connected", None, "Unplug the phone to disconnect", None),
    ("21 Home · Cable, on air", "DESKTOP — 21 · HOME / CABLE · ON AIR — NO TERMINATE WHILE CASTING",
     "11 ", "Cable", "Connected", "1920×1200 · 60 fps", None, None),
    ("22 Home · Cable, unplugged mid-cast", "DESKTOP — 22 · HOME / CABLE · RECONNECTING — THE CAST WAITS FIVE MINUTES (ADR-036)",
     "12 ", "Cable", "Reconnecting…", "Plug the phone back in to carry on.", None, "4:52"),
    ("23 Home · Cable, not connected", "DESKTOP — 23 · HOME / CABLE · NOT CONNECTED — IT RECONNECTS BY ITSELF",
     "14 ", "Cable", "Not connected", "Plug the phone in. It reconnects by itself.", "To switch to Wireless, Terminate", None),
    ("24 Home · Wireless, not connected", "DESKTOP — 24 · HOME / WIRELESS · NOT CONNECTED — CONNECT IS ON THE PHONE",
     "14 ", "Wireless", "Not connected", "Tap Connect on the phone.", "To switch to the cable, Terminate", None),
]


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
    names = {n for f in FRAMES for n in f[:2]}

    def drop(nid):
        for child in nodes[str(nid)].get("children", []):
            drop(child)
        nodes.pop(str(nid), None)

    for nid in list(page["children"]):
        if nodes[str(nid)]["name"] in names:
            drop(nid)
            page["children"].remove(nid)

    frames = {nodes[str(c)]["name"][:3]: nodes[str(c)] for c in page["children"] if nodes[str(c)]["type"] == "frame"}
    next_id = [doc["next_id"]]

    def new_id():
        next_id[0] += 1
        return next_id[0] - 1

    def add(frame, node):
        node.update(id=new_id(), parent=frame["id"])
        nodes[str(node["id"])] = node
        frame["children"].append(node["id"])
        return node

    def caption(chars, x):
        node = copy.deepcopy(nodes[str(CAPTION_SOURCE)])
        node.update(id=new_id(), name=chars, characters=chars, width=900.0)
        node["transform"] = [1.0, 0.0, 0.0, 1.0, x, 78.0]
        node["parent"] = 1
        nodes[str(node["id"])] = node
        page["children"].append(node["id"])

    def rect(frame, name, x, y, w, h, fill, opacity=1.0, radius=0.0, edge=None, ellipse=False):
        node = {"type": "ellipse" if ellipse else "rectangle", "name": name, "blend": "normal",
                "transform": [1.0, 0.0, 0.0, 1.0, x, y], "width": w, "height": h, "fills": solid(fill, opacity)}
        if not ellipse:
            node["corners"] = {"radii": [radius] * 4, "smoothing": 0.0}
        if edge:
            node["strokes"] = solid(edge)
            node["stroke"] = stroke()
        return add(frame, node)

    def text(frame, src, chars, x, y, w, h, color, size=None, style=None):
        node = copy.deepcopy(src)
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

    def wifi_icon(frame, cx, cy, color):
        for i, w in enumerate((22.0, 15.0, 8.0)):
            rect(frame, "arc", cx - w / 2, cy - 10 + i * 5, w, 2.5, color, radius=1.25)
        rect(frame, "dot", cx - 2, cy + 6, 4.0, 4.0, color, radius=2.0)

    def usb_icon(frame, cx, cy, color):
        rect(frame, "pin", cx - 4, cy - 12, 2.5, 5.0, color)
        rect(frame, "pin", cx + 1.5, cy - 12, 2.5, 5.0, color)
        rect(frame, "plug", cx - 7.5, cy - 7, 15.0, 12.0, color, opacity=0.0, radius=3.0, edge=color)
        rect(frame, "cord", cx - 1.25, cy + 5, 2.5, 7.0, color)

    for i, (name, cap, base, link, status, sub, hint, countdown) in enumerate(FRAMES):
        x = FIRST_X + i * STEP_X
        caption(cap, x)
        src = frames[base]
        by_name = {nodes[str(c)]["name"]: nodes[str(c)] for c in src["children"]}
        name_src, body_src, button_src = by_name["TB321FU"], next(
            nodes[str(c)] for c in src["children"]
            if nodes[str(c)]["type"] == "text" and 150 < nodes[str(c)]["transform"][5] < 170), by_name["Disconnect"]
        mono_src = by_name["SOURCE"]
        tone = {"Connected": GOOD, "Reconnecting…": AMBER, "Not connected": MUTED}[status]

        # The card's height decides how far the rest of the deck moves down.
        card_h = 76.0 + (22.0 if sub else 0.0) + (44.0 if (hint or countdown) else 0.0)
        shift = CARD_Y + card_h + 18.0 - 197.0

        frame = copy.deepcopy(src)
        frame.update(id=new_id(), name=name, children=[], height=src["height"] + shift)
        frame["transform"] = [1.0, 0.0, 0.0, 1.0, x, FRAME_Y]
        nodes[str(frame["id"])] = frame
        page["children"].append(frame["id"])
        for cid in src["children"]:
            child = nodes[str(cid)]
            y = child["transform"][5]
            if TOP_BLOCK[0] < y < TOP_BLOCK[1] or any(t in child.get("characters", "") for t in DROP_TEXT):
                continue
            c = copy.deepcopy(child)
            c.pop("children", None)
            if y >= TOP_BLOCK[1]:
                c["transform"][5] = y + shift
            if countdown and c["name"] in ("annunciator", "dot", "STANDBY") and y < 60:
                # ADR-036's wait reads RECONNECTING, as on the phone.
                if c["name"] == "annunciator":
                    c["transform"][4], c["width"] = 611.0, 141.0
                    c["fills"] = solid(AMBER, 0.14)
                elif c["name"] == "dot":
                    c["transform"][4] = 625.0
                    c["fills"] = solid(AMBER)
                else:
                    c.update(characters="RECONNECTING", name="RECONNECTING", width=120.0)
                    c["transform"][4] = 641.0
                    c["fills"] = solid(AMBER)
            add(frame, c)

        # ---- the connection card ----
        rect(frame, "card · connection", CARD_X, CARD_Y, CARD_W, card_h, CARD_FILL, radius=16.0, edge=EDGE)
        well = GOOD if status == "Connected" else (AMBER if status == "Reconnecting…" else INK)
        rect(frame, "icon well", CARD_X + 20, CARD_Y + 18, 44.0, 44.0, well, opacity=0.12, radius=12.0)
        (usb_icon if link == "Cable" else wifi_icon)(frame, CARD_X + 42, CARD_Y + 40, well)
        text(frame, name_src, PHONE, CARD_X + 80, CARD_Y + 14, 400, 30, INK, size=24)
        rect(frame, "status dot", CARD_X + 81, CARD_Y + 52, 8.0, 8.0, tone, ellipse=True)
        text(frame, body_src, f"{link} · {status}", CARD_X + 96, CARD_Y + 48, 300, 16,
             INK if status == "Not connected" else tone, size=13, style="Medium")
        if sub:
            text(frame, body_src, sub, CARD_X + 80, CARD_Y + 70, 440, 16, MUTED, size=13)
        if countdown:
            fy = CARD_Y + card_h - 44
            rect(frame, "hairline", CARD_X, fy, CARD_W, 1.0, EDGE)
            text(frame, mono_src, "STOPS IN", CARD_X + 20, fy + 16, 120, 13, MUTED)
            text(frame, name_src, countdown, CARD_X + CARD_W - 70, fy + 11, 50, 22, AMBER, size=17)
        elif hint:
            fy = CARD_Y + card_h - 44
            rect(frame, "hairline", CARD_X, fy, CARD_W, 1.0, EDGE)
            text(frame, body_src, hint, CARD_X + 20, fy + 15, 360, 16, MUTED, size=13)
            text(frame, button_src, "Terminate", CARD_X + CARD_W - 96, fy + 13, 80, 18, RED, size=14)

    doc["next_id"] = next_id[0]
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", versions)
    print(path, "frames 19–24 written")


if __name__ == "__main__":
    main(sys.argv[1])
