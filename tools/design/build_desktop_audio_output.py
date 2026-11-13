"""Draws where the receiver plays the cast's sound (ADR-029) into the desktop design.

Writes page frame 07 into designs/desktop-signal-deck.figcraft: frame 03
(deck, on air) copied, with
  - "Game audio" renamed "Audio" (the status chip and the sound row);
  - the "Sound on this computer" card holding two tracks, kept apart end to
    end (ADR-003): **Audio** and **Mic**, each with its switch, volume and a
    "Plays on" choice — Speaker (the system default) or the track's own
    virtual device for OBS (Caster Audio / Caster Mic), as a segmented control;
  - the "Output to OBS → Syphon" card the app has had since ADR-021 (the
    file predates it), switched on, naming the devices to capture.

Re-running replaces frame 07; frames 01–06 are untouched.

    python3 -I tools/design/build_desktop_audio_output.py designs/desktop-signal-deck.figcraft
"""

import copy
import json
import sys
import zipfile

SOURCE_FRAME = 281          # 03 Deck · On air
FRAME_NAME = "07 Deck · On air, Syphon on, audio and mic to Caster devices"
CAPTION = "DESKTOP — 07 · DECK / SYPHON ON, AUDIO → CASTER AUDIO, MIC → CASTER MIC (PROPOSED)"
OLD_NAMES = (
    "07 Deck · On air, Syphon on, audio to a virtual device",
    "DESKTOP — 07 · DECK / SYPHON ON, GAME AUDIO → VIRTUAL DEVICE (PROPOSED)",
)
FRAME_X = 6040.0
CARD_GAP = 14.5             # between cards, as in frames 02–04
PLAYS_ON_GAP = 30.0         # volume track → "Plays on" row
SEG_W, SEG_H = 300.0, 36.0  # the segmented control
TRACK_GAP = 20.0            # Audio block → Mic block (a hairline in the middle)
CARD_PAD_BOTTOM = 19.0
CONTENT_RIGHT = 731.0       # the card's inner right edge
CHIP_SHRINK = 44.0          # "GAME AUDIO" → "AUDIO"

SOUND_CARD = 309
SOUND_HEAD = 310
TILE, ICON, TITLE, SUB, SWITCH, KNOB = 311, 312, 313, 314, 315, 316
VOLUME = (317, 318, 319, 320)
CHIP_GAME, CHIP_GAME_TEXT = 303, 305
MIC_CHIP_PARTS = (306, 307, 308)
VIDEO_HINT = 297
TEXT_ON_SIGNAL = {"r": 0.0235, "g": 0.1647, "b": 0.1412, "a": 1.0}  # #062a24, as on the primary button


def load(path):
    with zipfile.ZipFile(path) as z:
        return json.loads(z.read("document.json")), z.read("versions/index.json")


def save(path, doc, versions):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", versions)


def main(path):
    doc, versions = load(path)
    nodes = doc["nodes"]
    page = nodes["1"]

    # Drop an earlier frame 07 (and its caption) so the script is re-runnable.
    for nid in list(page["children"]):
        n = nodes[str(nid)]
        if n["name"] in (FRAME_NAME, CAPTION) + OLD_NAMES:
            for child in n.get("children", []):
                nodes.pop(str(child), None)
            nodes.pop(str(nid), None)
            page["children"].remove(nid)

    next_id = [doc["next_id"]]

    def new_id():
        next_id[0] += 1
        return next_id[0] - 1

    def place(node, x, y):
        node["transform"] = [1.0, 0.0, 0.0, 1.0, x, y]

    def x_of(src):
        return nodes[str(src)]["transform"][4]

    def y_of(src):
        return nodes[str(src)]["transform"][5]

    caption = copy.deepcopy(nodes["280"])
    caption.update(id=new_id(), name=CAPTION, characters=CAPTION, width=900.0)
    place(caption, FRAME_X, 78.0)

    frame = copy.deepcopy(nodes[str(SOURCE_FRAME)])
    frame.update(id=new_id(), name=FRAME_NAME, children=[])
    place(frame, FRAME_X, 112.0)

    def add(node):
        node["parent"] = frame["id"]
        nodes[str(node["id"])] = node
        frame["children"].append(node["id"])
        return node

    def clone(src, **changes):
        node = copy.deepcopy(nodes[str(src)])
        node["id"] = new_id()
        node.update(changes)
        return node

    def copy_at(src, dy=0.0, dx=0.0, **changes):
        node = add(clone(src, **changes))
        place(node, x_of(src) + dx, y_of(src) + dy)
        return node

    def text(src, chars, x, y, width=None):
        node = add(clone(src, name=chars, characters=chars))
        if width:
            node["width"] = width
        place(node, x, y)
        return node

    # Everything in frame 03 above the sound card, as it is — but "Game
    # audio" is now "Audio", and the hint under the button speaks of video.
    for src in nodes[str(SOURCE_FRAME)]["children"]:
        if y_of(src) >= y_of(SOUND_CARD):
            continue
        dx = -CHIP_SHRINK if src in MIC_CHIP_PARTS else 0.0
        node = copy_at(src, dx=dx)
        if src == VIDEO_HINT:
            node.update(characters="Video plays while the video window is open.", name="video hint", width=300.0)
            place(node, 208.0 + (544.0 - 268.0) / 2, y_of(src))
        elif src == CHIP_GAME_TEXT:
            node.update(characters="AUDIO", name="AUDIO")
        elif src == CHIP_GAME:
            node["width"] -= CHIP_SHRINK

    def segmented(left, right, y):
        """Two choices in a well; the right one selected (the proposal's state)."""
        x = CONTENT_RIGHT - SEG_W
        track = add(clone(300, name="plays-on track", width=SEG_W, height=SEG_H))
        track["corners"]["radii"] = [10.0] * 4
        track["strokes"] = copy.deepcopy(nodes[str(SOUND_CARD)]["strokes"])
        track["stroke"] = copy.deepcopy(nodes[str(SOUND_CARD)]["stroke"])
        place(track, x, y)
        half = (SEG_W - 6.0) / 2
        selected = add(clone(SWITCH, name="plays-on selected", width=half, height=SEG_H - 6.0))
        selected["corners"]["radii"] = [8.0] * 4
        place(selected, x + 3.0 + half, y + 3.0)
        for i, label in enumerate((left, right)):
            node = text(TITLE, label, x + 3.0 + i * half, y + 9.0, width=half)
            node["style"]["font_size"] = 14.0
            node["align_h"] = "center"
            node["fills"][0]["color"] = TEXT_ON_SIGNAL if i == 1 else copy.deepcopy(nodes[str(SUB)]["fills"][0]["color"])

    def track_block(dy, title, sub, device, icon_size):
        """One track's row: tile + title/sub + switch, volume, "Plays on"."""
        copy_at(TILE, dy)
        w, h = icon_size
        icon = copy_at(ICON, dy, name=f"{title} icon", width=w, height=h)
        place(icon, x_of(TILE) + (44.0 - w) / 2, y_of(TILE) + dy + (44.0 - h) / 2)
        text(TITLE, title, x_of(TITLE), y_of(TITLE) + dy, width=200.0)
        text(SUB, sub, x_of(SUB), y_of(SUB) + dy, width=380.0)
        copy_at(SWITCH, dy)
        copy_at(KNOB, dy)
        for src in VOLUME:
            copy_at(src, dy)
        plays_y = y_of(VOLUME[0]) + dy + PLAYS_ON_GAP
        text(TITLE, "Plays on", x_of(TILE), plays_y + 9.0, width=120.0)
        segmented("Speaker", device, plays_y)
        return plays_y + SEG_H

    card_top = y_of(SOUND_CARD)
    card = copy_at(SOUND_CARD)
    copy_at(SOUND_HEAD)
    audio_bottom = track_block(0.0, "Audio", "Plays while the video window is open or Syphon is on.", "Caster Audio", (22.0, 12.0))
    mic_dy = audio_bottom - y_of(TILE) + TRACK_GAP
    rule = add(clone(300, name="hairline", width=CONTENT_RIGHT - x_of(TILE), height=1.0))
    rule["fills"] = copy.deepcopy(nodes[str(SOUND_CARD)]["strokes"])
    place(rule, x_of(TILE), audio_bottom + TRACK_GAP / 2)
    mic_bottom = track_block(mic_dy, "Mic", "The phone's mic — its own track, never mixed with the audio.", "Caster Mic", (10.0, 16.0))
    card["height"] = mic_bottom + CARD_PAD_BOTTOM - card_top

    # "Output to OBS" card, below the sound card.
    obs_top = mic_bottom + CARD_PAD_BOTTOM + CARD_GAP
    shift = obs_top - card_top
    obs = copy_at(SOUND_CARD, shift, height=104.0)
    text(SOUND_HEAD, "OUTPUT TO OBS", x_of(SOUND_HEAD), y_of(SOUND_HEAD) + shift)
    copy_at(TILE, shift)
    cam = copy_at(ICON, shift, name="camera", width=18.0, height=16.0)
    place(cam, x_of(ICON) + 2.0, y_of(ICON) + shift - 2.0)
    text(TITLE, "Syphon", x_of(TITLE), y_of(TITLE) + shift)
    text(
        SUB,
        'On. In OBS add a Syphon Client source named "Caster", and Audio Input Captures of "Caster Audio" and "Caster Mic".',
        x_of(SUB), y_of(SUB) + shift, width=370.0,
    )
    copy_at(SWITCH, shift)
    copy_at(KNOB, shift)
    frame["height"] = obs_top + obs["height"] + 40.0

    page["children"] += [caption["id"], frame["id"]]
    nodes[str(caption["id"])] = caption
    nodes[str(frame["id"])] = frame
    doc["next_id"] = next_id[0]
    save(path, doc, versions)
    print(f"frame 07 (id {frame['id']}): {len(frame['children'])} layers, {frame['height']:.0f} high")


if __name__ == "__main__":
    main(sys.argv[1])
