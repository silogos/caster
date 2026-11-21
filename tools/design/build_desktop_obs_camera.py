"""Draws the receiver's "OBS Virtual Camera" switch (ADR-039) into the desktop design.

The product owner's call (2026-11-22): Syphon is not replaced. The camera is a
new, separate switch in the "Output to OBS" card, under Syphon; either or both
can be on.

Writes two frames into designs/desktop-signal-deck.figcraft, after frame 24:
  - 25: frame 21 (cable, on air) with Syphon on and the camera on;
  - 26: the camera row, state by state, with the open questions.

Like frames 07–24 these are absolute layers copied from an existing frame, so
check_auto_layout.py does not apply. Re-running replaces frames 25 and 26.

    python3 -I tools/design/build_desktop_obs_camera.py designs/desktop-signal-deck.figcraft
"""

import copy
import json
import sys
import zipfile

SOURCE_FRAME_NAME = "21 Home · Cable, on air"
CAPTION_SOURCE = 8320       # frame 21's caption, for the caption style

DECK_NAME = "25 Home · Cable, on air, Syphon and OBS Virtual Camera on"
DECK_CAPTION = "DESKTOP — 25 · HOME / OUTPUT TO OBS — SYPHON STAYS, OBS VIRTUAL CAMERA IS A NEW SWITCH (ADR-039, PROPOSED)"
STATES_NAME = "26 OBS Virtual Camera · the switch, state by state"
STATES_CAPTION = "DESKTOP — 26 · OBS VIRTUAL CAMERA / STATE BY STATE (ADR-039, PROPOSED)"
DECK_X, STATES_X, FRAME_Y = 24040.0, 25040.0, 112.0

ROW_STEP = 76.0             # hairline + one more 44 px row inside the card (Syphon's sub line runs two lines)
CARD_X, CARD_W = 208.0, 544.0
ROW_X = 229.0               # the card's inner left edge
SUB_W = 370.0
SWITCH_W, SWITCH_H, KNOB = 52.0, 30.0, 24.0
DISABLED_OPACITY = 0.4

INK = {"r": 0.933, "g": 0.945, "b": 0.957, "a": 1.0}
MUTED = {"r": 0.541, "g": 0.576, "b": 0.627, "a": 1.0}
FAINT = {"r": 0.365, "g": 0.4, "b": 0.447, "a": 1.0}
FAULT = {"r": 1.0, "g": 0.416, "b": 0.302, "a": 1.0}
SWITCH_OFF = {"r": 0.165, "g": 0.192, "b": 0.227, "a": 1.0}    # #2a313a, the UI's .switch
KNOB_OFF = {"r": 0.541, "g": 0.576, "b": 0.627, "a": 1.0}      # #8a93a0, the UI's .switch::after
HAIRLINE = {"r": 0.118, "g": 0.141, "b": 0.169, "a": 1.0}

TITLE = "OBS Virtual Camera"
SUB = {
    "ready": 'Off. Turn on to show the cast as the camera "OBS Virtual Camera" in Zoom, Meet, browsers and TikTok LIVE Studio.',
    "feeding": 'On. In any app, pick the camera "OBS Virtual Camera". OBS doesn\'t need to be open.',
    "waiting": "On. Apps show OBS's own picture until the cast starts.",
    "no-obs": "Needs OBS Studio on this Mac. Install it from obsproject.com, then turn this on.",
    "not-set-up": "Open OBS once, click Start Virtual Camera and allow it in macOS. After that OBS can stay closed.",
    "taken": "OBS is using its virtual camera itself. Stop it in OBS to show the cast again.",
    "failed": "Off.",
}
AUDIO_SUB_OLD = "Plays while the video window is open or Syphon is on."
AUDIO_SUB = "Plays while the video window, Syphon or the camera is on."   # ADR-039 §7
FAILED_MESSAGE = "Couldn't start OBS Virtual Camera. Try again."


def load(path):
    with zipfile.ZipFile(path) as z:
        return json.loads(z.read("document.json")), z.read("versions/index.json")


def save(path, doc, versions):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", versions)


def solid(color, opacity=1.0):
    return [{"type": "solid", "color": dict(color), "opacity": opacity, "visible": True, "blend": "normal"}]


def main(path):
    doc, versions = load(path)
    nodes = doc["nodes"]
    page = nodes["1"]

    for nid in list(page["children"]):
        n = nodes[str(nid)]
        if n["name"] in (DECK_NAME, DECK_CAPTION, STATES_NAME, STATES_CAPTION):
            for child in n.get("children", []):
                nodes.pop(str(child), None)
            nodes.pop(str(nid), None)
            page["children"].remove(nid)

    source = next((nodes[str(c)] for c in page["children"] if nodes[str(c)]["name"] == SOURCE_FRAME_NAME), None)
    if source is None:
        raise SystemExit(f"{SOURCE_FRAME_NAME!r} not found — run build_desktop_link_home.py first")
    kids = [nodes[str(c)] for c in source["children"]]

    def last(name):
        return [k for k in kids if k["name"] == name][-1]

    tpl = {
        "card": last("card"),
        "tile": last("icon-tile"),
        "glyph": last("camera"),
        "title": next(k for k in kids if k.get("characters") == "Syphon"),
        "sub": next(k for k in kids if (k.get("characters") or "").startswith("On. In OBS add a Syphon")),
        "switch": last("game-switch"),
        "knob": last("dot"),
    }
    card_y = tpl["card"]["transform"][5]

    next_id = [doc["next_id"]]

    def new_id():
        next_id[0] += 1
        return next_id[0] - 1

    def place(node, x, y):
        node["transform"] = [1.0, 0.0, 0.0, 1.0, x, y]

    def clone(src, **changes):
        node = copy.deepcopy(src)
        node["id"] = new_id()
        node.pop("children", None)
        node.update(changes)
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

    def lines_for(chars, size, width):
        return max(1, round(len(chars) * size * 0.52 / width + 0.49))

    def text(add, chars, x, y, width, size=12.0, color=MUTED, style="Regular", line=14.0):
        node = add(clone(tpl["sub"], name=chars[:60], characters=chars, width=width))
        node["style"]["font_size"] = size
        node["style"]["font_style"] = style
        node["style"]["line_height"] = {"unit": "pixels", "value": line}
        node["fills"] = solid(color)
        node["height"] = line * lines_for(chars, size, width)
        place(node, x, y)
        return node

    def hairline(add, x, y, width):
        rule = add(clone(tpl["tile"], name="hairline", width=width, height=1.0))
        rule["fills"] = solid(HAIRLINE)
        rule["corners"]["radii"] = [0.0] * 4
        place(rule, x, y)

    def camera_row(add, x, y, state, message=None):
        """The camera's row: icon tile, title, sub line, switch. Returns its bottom."""
        enabled = state not in ("no-obs", "not-set-up")
        on = state in ("feeding", "waiting", "taken")
        dim = 1.0 if enabled else DISABLED_OPACITY
        tile = add(clone(tpl["tile"], name="icon-tile"))
        place(tile, x, y)
        # A webcam: a rounded body with a lens, not Syphon's video glyph.
        body = add(clone(tpl["glyph"], name="webcam", width=18.0, height=14.0))
        body["corners"]["radii"] = [7.0] * 4
        place(body, x + 13.0, y + 13.0)
        lens = add(clone(tpl["glyph"], name="lens", width=6.0, height=6.0))
        lens["corners"]["radii"] = [3.0] * 4
        place(lens, x + 19.0, y + 17.0)
        stand = add(clone(tpl["glyph"], name="stand", width=8.0, height=1.0))
        stand["corners"]["radii"] = [0.5] * 4
        place(stand, x + 18.0, y + 31.0)
        title = add(clone(tpl["title"], name=TITLE, characters=TITLE, width=200.0))
        place(title, x + 58.0, y + 5.0)
        sub = text(add, SUB[state], x + 58.0, y + 25.0, SUB_W)
        bottom = max(y + 44.0, y + 25.0 + sub["height"])
        if message:
            msg = text(add, message, x + 58.0, bottom + 4.0, SUB_W, color=FAULT)
            bottom = msg["transform"][5] + msg["height"]
        sw = add(clone(tpl["switch"], name="camera-switch", opacity=dim))
        if not on:
            sw["fills"] = solid(SWITCH_OFF)
        place(sw, x + 450.0, y + 7.0)
        knob = add(clone(tpl["knob"], name="dot", opacity=dim))
        if not on:
            knob["fills"] = solid(KNOB_OFF)
        place(knob, x + 450.0 + (SWITCH_W - KNOB - 3.0 if on else 3.0), y + 10.0)
        return bottom

    # ---- 25: the deck, Syphon on and the camera on -------------------------
    deck = frame_like(DECK_NAME, DECK_X, source["height"] + ROW_STEP)
    add = adder(deck)
    for k in kids:
        node = add(clone(k))
        if k.get("characters") == AUDIO_SUB_OLD:
            node.update(name=AUDIO_SUB, characters=AUDIO_SUB)
        if k is tpl["card"]:
            node["height"] = k["height"] + ROW_STEP
    syphon_row_y = tpl["tile"]["transform"][5]
    hairline(add, ROW_X, syphon_row_y + 64.0, CARD_W - 2 * (ROW_X - CARD_X))
    camera_row(add, ROW_X, syphon_row_y + ROW_STEP, "feeding")

    # ---- 26: the row, state by state ---------------------------------------
    rows = [
        ("1 · Off", "OBS and its camera are ready. Off by default.", "ready", None),
        ("2 · On, casting", "The cast goes into OBS's camera at its own size and frame rate. Syphon is independent.", "feeding", None),
        ("3 · On, no cast", "Between casts the camera shows OBS's picture, never a frozen last frame.", "waiting", None),
        ("4 · OBS not installed", "The switch is greyed out. Checked again whenever the window comes to the front.", "no-obs", None),
        ("5 · OBS's camera not set up", "Installed, but macOS has not allowed its camera yet: OBS must do that once.", "not-set-up", None),
        ("6 · Taken by OBS", "OBS started its own virtual camera. Shown only if V1 can detect it (ADR-039).", "taken", None),
        ("7 · Couldn't start", "Back to off, with a plain message. The detail goes to the log.", "failed", FAILED_MESSAGE),
    ]
    row_h = 96.0
    top = 40.0
    notes = (
        "Notes. "
        "(1) Sound, decided 2026-11-22: the camera counts like Syphon (ADR-039 §7). The tracks play while the video window is open, "
        "or Syphon or the camera is on, because a call app takes the picture from the camera and the sound from Caster Audio / Caster Mic. "
        "(2) No extra chip in STATUS: the card's line says the camera is on. "
        "(3) Text is in English like the rest of the receiver."
    )
    states = frame_like(STATES_NAME, STATES_X, top + 28.0 + row_h * len(rows) + 120.0)
    add = adder(states)
    text(add, "OUTPUT TO OBS · THE NEW ROW, UNDER SYPHON", 40.0, top - 8.0, 700.0,
         size=11.0, color=FAINT, style="SemiBold", line=14.0)
    for i, (title, note, state, message) in enumerate(rows):
        y = top + 28.0 + i * row_h
        if i:
            hairline(add, 40.0, y - 16.0, 880.0)
        text(add, title, 40.0, y, 320.0, size=15.0, color=INK, style="SemiBold", line=18.0)
        text(add, note, 40.0, y + 24.0, 320.0, line=16.0)
        camera_row(add, 400.0, y, state, message)
    foot_y = top + 28.0 + len(rows) * row_h
    text(add, "Notes", 40.0, foot_y, 300.0, size=15.0, color=INK, style="SemiBold", line=18.0)
    text(add, notes, 40.0, foot_y + 24.0, 880.0, line=16.0)

    for frame, cap, x in ((deck, DECK_CAPTION, DECK_X), (states, STATES_CAPTION, STATES_X)):
        c = copy.deepcopy(nodes[str(CAPTION_SOURCE)])
        c.update(id=new_id(), name=cap, characters=cap, width=900.0)
        place(c, x, 78.0)
        nodes[str(c["id"])] = c
        nodes[str(frame["id"])] = frame
        page["children"] += [c["id"], frame["id"]]
        print(f"{frame['name']} (id {frame['id']}): {len(frame['children'])} layers")
    doc["next_id"] = next_id[0]
    save(path, doc, versions)


if __name__ == "__main__":
    main(sys.argv[1])
