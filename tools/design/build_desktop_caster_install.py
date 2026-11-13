"""Draws installing Caster's virtual audio devices from "Plays on" into the desktop design.

The product owner's flow (2026-11-14): there is no separate Install button.
While the driver is not installed, the "Caster Audio" / "Caster Mic" segment
of each track's "Plays on" choice stays clickable and carries a round "i";
hovering it (or focusing the segment) says the driver is not installed and
that clicking installs it. Clicking the segment starts the install: one
macOS password prompt, then the devices appear and the choice moves to
Caster. Cancelled → back on Speaker, no message; failed → Speaker and a
plain message.

Writes two frames into designs/desktop-signal-deck.figcraft, next to frame 07
(built by build_desktop_audio_output.py, which must run first):
  - 08: frame 07's deck with the driver not installed — both tracks on
    Speaker, the "i" on both Caster segments, the Audio one hovered;
  - 09: the "Plays on" row through the install, one row per state.

Re-running replaces frames 08 and 09; frames 01–07 are untouched.

    python3 -I tools/design/build_desktop_caster_install.py designs/desktop-signal-deck.figcraft
"""

import copy
import json
import sys
import zipfile

SOURCE_FRAME_NAME = "07 Deck · On air, Syphon on, audio and mic to Caster devices"
CAPTION_SOURCE = 280        # a frame caption to copy the style from

DECK_NAME = "08 Deck · Caster devices not installed, hovering the i"
DECK_CAPTION = "DESKTOP — 08 · DECK / CASTER DEVICES NOT INSTALLED — HOVER ON ⓘ (PROPOSED)"
STATES_NAME = "09 Plays on · installing the Caster devices"
STATES_CAPTION = "DESKTOP — 09 · PLAYS ON / INSTALL FROM THE CASTER CHOICE, STATE BY STATE (PROPOSED)"
DECK_X, STATES_X, FRAME_Y = 7040.0, 8040.0, 112.0

# Frame 07's layers, found by name and position (its ids change on rebuild).
SEG_W, SEG_H = 300.0, 36.0
INFO_SIZE = 14.0            # the round "i"
INFO_GAP = 6.0              # label → "i"
LABEL_NUDGE = 10.0          # the label moves left to make room for the "i"
TOOLTIP_W = 262.0
TOOLTIP_PAD = 12.0
TOOLTIP_LINE = 16.0

TEXT_ON_SIGNAL = {"r": 0.0235, "g": 0.1647, "b": 0.1412, "a": 1.0}   # #062a24
MUTED = {"r": 0.541, "g": 0.576, "b": 0.627, "a": 1.0}
FAINT = {"r": 0.365, "g": 0.4, "b": 0.447, "a": 1.0}
INK = {"r": 0.933, "g": 0.945, "b": 0.957, "a": 1.0}
SIGNAL = {"r": 0.184, "g": 0.82, "b": 0.714, "a": 1.0}
FAULT = {"r": 1.0, "g": 0.416, "b": 0.302, "a": 1.0}
TOOLTIP_FILL = {"r": 0.118, "g": 0.141, "b": 0.169, "a": 1.0}
TOOLTIP_STROKE = {"r": 0.165, "g": 0.192, "b": 0.227, "a": 1.0}

TOOLTIP_TEXT = {
    "Caster Audio": "Caster Audio isn't installed on this Mac yet. Click it to install — macOS asks for your password once.",
    "Caster Mic": "Caster Mic isn't installed on this Mac yet. Click it to install — macOS asks for your password once.",
}
# Approximate label widths at Inter SemiBold 14 (to place the "i").
LABEL_WIDTH = {"Caster Audio": 86.0, "Caster Mic": 70.0, "Installing…": 74.0}


def load(path):
    with zipfile.ZipFile(path) as z:
        return json.loads(z.read("document.json")), z.read("versions/index.json")


def save(path, doc, versions):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", versions)


def solid(color):
    return [{"type": "solid", "color": dict(color), "opacity": 1.0, "visible": True, "blend": "normal"}]


def main(path):
    doc, versions = load(path)
    nodes = doc["nodes"]
    page = nodes["1"]

    def by_name(name):
        for nid in page["children"]:
            if nodes[str(nid)]["name"] == name:
                return nodes[str(nid)]
        raise SystemExit(f"{name!r} not found — run build_desktop_audio_output.py first")

    for nid in list(page["children"]):
        n = nodes[str(nid)]
        if n["name"] in (DECK_NAME, DECK_CAPTION, STATES_NAME, STATES_CAPTION):
            for child in n.get("children", []):
                nodes.pop(str(child), None)
            nodes.pop(str(nid), None)
            page["children"].remove(nid)

    source = by_name(SOURCE_FRAME_NAME)
    kids = [nodes[str(c)] for c in source["children"]]

    def find(name, nth=0):
        return [k for k in kids if k["name"] == name][nth]

    tpl = {
        "title": find("Plays on"),
        "track": find("plays-on track"),
        "selected": find("plays-on selected"),
        "speaker": find("Speaker"),
        "dot": find("dot"),
    }

    next_id = [doc["next_id"]]

    def new_id():
        next_id[0] += 1
        return next_id[0] - 1

    def place(node, x, y):
        node["transform"] = [1.0, 0.0, 0.0, 1.0, x, y]

    def pos(node):
        return node["transform"][4], node["transform"][5]

    def caption(text, x):
        node = copy.deepcopy(nodes[str(CAPTION_SOURCE)])
        node.update(id=new_id(), name=text, characters=text, width=900.0)
        place(node, x, 78.0)
        return node

    def frame_like(name, x, height):
        frame = copy.deepcopy(source)
        frame.update(id=new_id(), name=name, children=[], height=height)
        place(frame, x, FRAME_Y)
        return frame

    def adder(frame):
        def add(node):
            node["parent"] = frame["id"]
            nodes[str(node["id"])] = node
            frame["children"].append(node["id"])
            return node
        return add

    def clone(src, **changes):
        node = copy.deepcopy(src)
        node["id"] = new_id()
        node.pop("children", None)
        node.update(changes)
        return node

    def text(add, chars, x, y, width, size=14.0, color=MUTED, align="left", style="SemiBold", line=None):
        node = add(clone(tpl["speaker"], name=chars, characters=chars, width=width))
        node["style"]["font_size"] = size
        node["style"]["font_style"] = style
        node["style"]["line_height"] = {"unit": "pixels", "value": line or round(size * 1.25, 1)}
        node["align_h"] = align
        node["fills"] = solid(color)
        lines = 1 if line is None else max(1, round(len(chars) * size * 0.52 / width + 0.49))
        node["height"] = (line or round(size * 1.25, 1)) * lines
        place(node, x, y)
        return node

    def info_icon(add, x, y, color=MUTED):
        ring = add(clone(tpl["dot"], name="info ring", width=INFO_SIZE, height=INFO_SIZE, fills=[]))
        ring["strokes"] = solid(color)
        ring["stroke"] = copy.deepcopy(tpl["track"]["stroke"])
        ring["stroke"]["weight"] = 1.3
        ring["stroke"]["align"] = "inside"
        place(ring, x, y)
        text(add, "i", x, y + 1.0, INFO_SIZE, size=10.0, color=color, align="center", style="Bold")

    def tooltip(add, anchor_x, anchor_y, words):
        """A tooltip hanging under the point (anchor_x, anchor_y), caret on top."""
        lines = 3
        h = TOOLTIP_PAD * 2 + TOOLTIP_LINE * lines
        x = anchor_x - TOOLTIP_W + 34.0
        y = anchor_y + 8.0
        caret = add(clone(tpl["track"], name="tooltip caret", width=10.0, height=10.0))
        caret["fills"] = solid(TOOLTIP_FILL)
        caret["strokes"] = solid(TOOLTIP_STROKE)
        caret["corners"]["radii"] = [2.0] * 4
        caret["transform"] = [0.7071, 0.7071, -0.7071, 0.7071, anchor_x, y - 7.0]
        box = add(clone(tpl["track"], name="tooltip", width=TOOLTIP_W, height=h))
        box["fills"] = solid(TOOLTIP_FILL)
        box["strokes"] = solid(TOOLTIP_STROKE)
        box["corners"]["radii"] = [8.0] * 4
        box["effects"] = copy.deepcopy(source.get("effects", []))
        place(box, x, y)
        # The caret's lower half sits under the box; redraw a patch over it.
        patch = add(clone(tpl["track"], name="tooltip caret cover", width=14.0, height=6.0, strokes=[]))
        patch["fills"] = solid(TOOLTIP_FILL)
        patch["corners"]["radii"] = [0.0] * 4
        place(patch, anchor_x - 7.0, y + 1.0)
        text(add, words, x + TOOLTIP_PAD, y + TOOLTIP_PAD, TOOLTIP_W - 2 * TOOLTIP_PAD,
             size=12.0, color=INK, style="Regular", line=TOOLTIP_LINE)

    def segmented(add, seg_x, y, right, state):
        """The "Plays on" control. state: rest | caster | installing."""
        track = add(clone(tpl["track"], name="plays-on track"))
        place(track, seg_x, y)
        half = (SEG_W - 6.0) / 2
        left_x, right_x = seg_x + 3.0, seg_x + 3.0 + half
        if state == "caster":
            sel = add(clone(tpl["selected"], name="plays-on selected"))
            place(sel, right_x, y + 3.0)
        elif state == "rest":
            sel = add(clone(tpl["selected"], name="plays-on selected"))
            place(sel, left_x, y + 3.0)
        else:  # installing: Speaker still holds; the Caster half is outlined, busy
            sel = add(clone(tpl["selected"], name="plays-on selected"))
            place(sel, left_x, y + 3.0)
            ring = add(clone(tpl["track"], name="installing outline", width=half, height=SEG_H - 6.0, fills=[]))
            ring["strokes"] = solid(SIGNAL)
            ring["corners"]["radii"] = [8.0] * 4
            place(ring, right_x, y + 3.0)
        text(add, "Speaker", left_x, y + 9.0, half, color=TEXT_ON_SIGNAL if state != "caster" else MUTED, align="center")
        if state == "caster":
            text(add, right, right_x, y + 9.0, half, color=TEXT_ON_SIGNAL, align="center")
            return None
        if state == "installing":
            label = "Installing…"
            text(add, label, right_x - LABEL_NUDGE, y + 9.0, half, color=SIGNAL, align="center")
            spin_x = right_x + half / 2 - LABEL_NUDGE + LABEL_WIDTH[label] / 2 + INFO_GAP
            spin = add(clone(tpl["dot"], name="spinner", width=INFO_SIZE, height=INFO_SIZE, fills=[], sweep=4.4))
            spin["strokes"] = solid(SIGNAL)
            spin["stroke"] = copy.deepcopy(tpl["track"]["stroke"])
            spin["stroke"]["weight"] = 2.0
            spin["stroke"]["align"] = "inside"
            place(spin, spin_x, y + 11.0)
            return None
        text(add, right, right_x - LABEL_NUDGE, y + 9.0, half, color=MUTED, align="center")
        info_x = right_x + half / 2 - LABEL_NUDGE + LABEL_WIDTH[right] / 2 + INFO_GAP
        info_icon(add, info_x, y + 11.0)
        return info_x + INFO_SIZE / 2, y + 11.0 + INFO_SIZE

    # ---- 08: the deck with nothing installed -------------------------------
    deck = frame_like(DECK_NAME, DECK_X, source["height"])
    add = adder(deck)
    seg_tracks = [k for k in kids if k["name"] == "plays-on track"]
    skip = {id(k) for k in kids if k["name"] in ("plays-on track", "plays-on selected", "Speaker", "Caster Audio", "Caster Mic")}
    for k in kids:
        if id(k) not in skip:
            add(clone(k))
    anchors = []
    for track, right in zip(seg_tracks, ("Caster Audio", "Caster Mic")):
        x, y = pos(track)
        anchors.append(segmented(add, x, y, right, "rest"))
    tooltip(add, anchors[0][0], anchors[0][1], TOOLTIP_TEXT["Caster Audio"])

    # ---- 09: the row, state by state ---------------------------------------
    rows = [
        ("1 · Not installed", "Speaker plays. The Caster half stays clickable and shows ⓘ.", "rest", None, None),
        ("2 · Hover or focus on ⓘ", "Says what is missing and what a click does.", "rest", "tooltip", None),
        ("3 · Clicked — installing", "macOS asks for the password. Speaker keeps playing until it is done.", "installing", None,
         ("macOS is asking for your password.", MUTED)),
        ("4 · Installed", "The choice moves to Caster; ⓘ is gone on both tracks (one driver, two devices).", "caster", None, None),
        ("5 · Password cancelled", "Back to 1 — no message: the user chose not to.", "rest", None, None),
        ("6 · Install failed", "Back on Speaker, with a plain message. The detail goes to the log.", "rest", None,
         ("Couldn't install Caster Audio. Try again.", FAULT)),
    ]
    row_h = 104.0
    top = 40.0
    states = frame_like(STATES_NAME, STATES_X, top + row_h * len(rows) + 150.0)
    add = adder(states)
    text(add, "PLAYS ON · AUDIO TRACK (THE MIC BEHAVES THE SAME WITH CASTER MIC)", 40.0, top - 8.0, 700.0,
         size=11.0, color=FAINT, style="SemiBold")
    overlays = []  # tooltips go on top of every row, drawn last
    for i, (title, note, state, extra, message) in enumerate(rows):
        y = top + 28.0 + i * row_h
        if i:
            rule = add(clone(tpl["track"], name="hairline", width=880.0, height=1.0, strokes=[]))
            rule["fills"] = copy.deepcopy(tpl["track"]["strokes"])
            rule["corners"]["radii"] = [0.0] * 4
            place(rule, 40.0, y - 18.0)
        text(add, title, 40.0, y, 300.0, size=15.0, color=INK)
        text(add, note, 40.0, y + 24.0, 300.0, size=12.0, color=MUTED, style="Regular", line=16.0)
        text(add, "Plays on", 420.0, y + 9.0, 120.0, size=15.0, color=INK)
        anchor = segmented(add, 620.0, y, "Caster Audio", state)
        if message:
            words, color = message
            text(add, words, 620.0, y + SEG_H + 8.0, SEG_W, size=12.0, color=color, style="Regular", align="right")
        if extra == "tooltip":
            overlays.append(anchor)
    for anchor in overlays:
        tooltip(add, anchor[0], anchor[1], TOOLTIP_TEXT["Caster Audio"])
    foot_y = top + 28.0 + len(rows) * row_h
    text(add, "Notes", 40.0, foot_y, 300.0, size=15.0, color=INK)
    text(
        add,
        "The tooltip opens on hover and on keyboard focus (it is the segment's description for screen readers). "
        "Installing restarts macOS's audio service: every sound on the Mac pauses for about a second, and the cast's sound comes back by itself. "
        "No separate Install button anywhere.",
        40.0, foot_y + 24.0, 880.0, size=12.0, color=MUTED, style="Regular", line=16.0,
    )

    for frame, cap, x in ((deck, DECK_CAPTION, DECK_X), (states, STATES_CAPTION, STATES_X)):
        c = caption(cap, x)
        nodes[str(c["id"])] = c
        nodes[str(frame["id"])] = frame
        page["children"] += [c["id"], frame["id"]]
        print(f"{frame['name']} (id {frame['id']}): {len(frame['children'])} layers")
    doc["next_id"] = next_id[0]
    save(path, doc, versions)


if __name__ == "__main__":
    main(sys.argv[1])
