"""Builds the "Mobile design v3 · USB cast" page (ADR-034): what Home and Scan
show when the phone is on a USB cable to the receiver.

A design ahead of the code, in the shipped amber tokens (spec_dsl.py), all
auto layout placed by autolayout.solve(). Four screens:
  U1 Home · cable ready     the saved desktop answered over the cable
  U2 Home · on air by cable sharing over USB
  U3 Home · cable refused   the desktop does not know this phone
  U4 Scan · cable, unpaired the phone never paired: pair over Wi-Fi once

Usage:
  python3 -I tools/design/build_usb_cast_spec.py OUT.figcraft
  python3 -I tools/design/build_usb_cast_spec.py OUT.figcraft --into designs/mobile-app.figcraft
--into also writes the page into that file, replacing an earlier copy of it.
"""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import autolayout  # noqa: E402

from spec_dsl import *  # noqa: E402,F401,F403

PAGE_NAME = "Mobile design v3 · USB cast (ADR-034)"
CARD_INNER_W = CONTENT_W - 32
PHONE_GAP = 40
NOTES_GAP = 24
TOP = 112
MONO_ADVANCE = 0.6


def mono(parent, chars, size, color=VARIANT, style="Regular", spacing=0.3, line=None):
    t = text(parent, chars, size, color, style, family=MONO, spacing=spacing, line=line or round(size * 1.3))
    nodes[t]["width"] = float(round(len(chars) * (size * MONO_ADVANCE + spacing)))
    return t


def hairline(parent, width):
    return rect(parent, "hairline", width, 1, OUTLINE)


def bar(parent, annunciator, tone="muted"):
    b = hstack(parent, "app bar", gap=10, cross="center", h="fill", v="fixed", height=40)
    brand = hstack(b, "brand", gap=8, cross="center", h="fill")
    rect(brand, "brand-mark", 24, 24, PRIMARY if tone == "amber" else ON, opacity=0.6 if tone == "amber" else 0.08,
         radius=8)
    mono(brand, "Caster", 13, ON, "Medium", spacing=1.4, line=16)
    fg = {"muted": VARIANT, "amber": PRIMARY, "bad": BAD}[tone]
    ann = hstack(b, "annunciator", pad=(5, 14, 5, 14), cross="center", fill=fg, opacity=0.12, radius=13)
    mono(ann, annunciator, 11, fg, "Bold", spacing=1.0, line=16)


def hero(parent, name, right):
    d = hstack(parent, "desktop", main="spaceBetween", cross="center", h="fill")
    text(d, name, 22, ON, "SemiBold", line=28)
    actions = hstack(d, "actions", cross="center")
    for label in right:
        text_button(actions, label, ON)


def usb_glyph(parent, color=ON):
    """The cable plug: a body and two pins, drawn like the console's other wells."""
    w = vstack(parent, "well-usb", gap=1, main="center", cross="center", h="fixed", v="fixed", width=38, height=38,
               fill=color, opacity=0.10, radius=12)
    pins = hstack(w, "pins", gap=4, main="center")
    rect(pins, "pin", 2, 4, color)
    rect(pins, "pin", 2, 4, color)
    rect(w, "plug", 12, 10, radius=2, stroke=color, stroke_weight=1.6)
    rect(w, "cord", 2, 6, color)
    return w


def link_card(parent, title, sub, color=ON, pill_label=None, pill_tone="amber", actions=()):
    c = card(parent, "card · usb link")
    row = hstack(c, "row · usb", gap=14, pad=(13, 16, 13, 16), cross="center", h="fill")
    usb_glyph(row, color)
    texts = vstack(row, "texts", gap=2, h="fill")
    head = hstack(texts, "title", gap=8, cross="center")
    text(head, title, 15, color if color != ON else ON, "Medium", line=20)
    if pill_label:
        pill(head, pill_label, pill_tone)
    text(texts, sub, 13, VARIANT, width=CARD_INNER_W - 38 - 14, line=19)
    if actions:
        a = hstack(c, "actions", gap=18, pad=(0, 16, 12, 68), h="fill")
        for label in actions:
            text_button(a, label)
    return c


def go_button(parent, label):
    b = hstack(parent, f"button · {label}", main="center", cross="center", h="fill", v="fixed", height=56,
               fill=PRIMARY, radius=16)
    text(b, label, 15, ON_PRIMARY, "Bold", line=20)


def stop_button(parent, label):
    b = hstack(parent, f"button · {label}", main="center", cross="center", h="fill", v="fixed", height=56,
               radius=16, stroke=ON)
    nodes[b]["strokes"] = [solid(ON, 0.30)]
    text(b, label, 15, ON, "Medium", line=20)


def profile_strip(parent, casting=False):
    c = card(parent, "card · profile")
    z = vstack(c, "profile", gap=6, pad=(14, 16, 14, 16), h="fill")
    mono(z, "STATUS" if casting else "NEXT CAST", 11, VARIANT, "Medium", spacing=1.1, line=14)
    text(z, "Performance", 15, ON, "Medium", line=20)
    mono(z, "1280 px · 60 fps · 6–10 Mbps" if not casting else "1280×800 · 60 fps · 10 Mbps · USB", 11)
    if casting:
        hairline(c, CONTENT_W)
        s = vstack(c, "status rows", gap=4, pad=(12, 16, 14, 16), h="fill")
        text(s, "Thermal: normal", 13, VARIANT, line=19)
        text(s, "Over the cable the picture stays at the profile for the whole cast: automatic quality and live "
                "changes work over Wi-Fi only for now.", 13, VARIANT, width=CARD_INNER_W, line=19)
    return c


def audio(parent, mic_on=False):
    mono(parent, "AUDIO", 11, VARIANT, "Medium", spacing=1.1, line=14)
    a = card(parent, "card · audio")
    switch_row(a, "Audio", "Off silences the audio, but its track stays part of the cast.", True, glyph="gamepad")
    hairline(a, CONTENT_W)
    switch_row(a, "Microphone", "Recording from the headset — its own track." if mic_on else
               "Off removes the microphone from the cast and frees it for the game's own voice chat.", mic_on)


def notes(page, title, lines, x, y, width=PHONE_W):
    n = vstack(page, f"notes · {title}", gap=8, h="fixed", width=width)
    nodes[n]["transform"][4], nodes[n]["transform"][5] = float(x), float(y)
    nodes[n]["child_layout"]["absolute"] = False
    mono(n, "NOTES", 11, PRIMARY, "Bold", spacing=1.2, line=14)
    for line in lines:
        text(n, "• " + line, 12.5, VARIANT, width=width, line=18)
    return n


PAGE = nid()
nodes[PAGE] = {"id": PAGE, "name": PAGE_NAME, "type": "page", "background": hexc(PAGE_BG), "flows": [],
               "prototype": {"device": "None", "background": hexc("#1E1E1E"), "rotation": "portrait"},
               "children": [], "width": 0.0, "height": 0.0}
page_caption(PAGE, "ADR-034 · USB CAST — VIDEO + GAME AUDIO OVER THE CABLE · THE PHONE STARTS THE CAST", 40, 30, 13,
             "Bold", ON)
COLUMN = PHONE_W + PHONE_GAP
X = [40 + i * COLUMN for i in range(4)]
X = [40 + i * COLUMN for i in range(6)]
for x, label in zip(X, ("U1 · HOME — CABLE READY", "U2 · HOME — ON AIR BY CABLE", "U3 · HOME — CABLE REFUSED",
                        "U4 · SCAN — CABLE, NEVER PAIRED", "S1 · ANDROID — OPEN CASTER FOR THE CABLE?",
                        "S2 · ANDROID — SCREEN-CAPTURE CONSENT")):
    page_caption(PAGE, label, x, 78)

# U1: the saved desktop answered over the cable; Wi-Fi may be off.
u1 = phone(PAGE, "U1 Home · cable ready", X[0], TOP, grow=True)
bar(u1, "STANDBY")
hero(u1, "Gaming PC", ["Connect", "Terminate"])
link_card(u1, "Connected by cable", "Sharing goes over the USB cable. Wi-Fi isn't needed while it's plugged in.",
          pill_label="USB")
go_button(u1, "Share screen")
profile_strip(u1)
audio(u1)

# U2: on air over the cable.
u2 = phone(PAGE, "U2 Home · on air by cable", X[1], TOP, grow=True)
bar(u2, "ON AIR", "amber")
hero(u2, "Gaming PC", [])
c2 = link_card(u2, "Sharing by cable", "Unplugging the cable stops the cast.", color=PRIMARY, pill_label="USB")
note = hstack(c2, "quality note", pad=(0, 16, 14, 68), h="fill")
text(note, "Over the cable the picture stays at the profile for the whole cast: automatic quality and live changes "
           "work over Wi-Fi only for now.", 13, VARIANT, width=CARD_INNER_W - 52, line=19)
stop_button(u2, "Stop share screen")
profile_strip(u2)
audio(u2, mic_on=True)

# U3: the desktop answered but does not trust this phone.
u3 = phone(PAGE, "U3 Home · cable refused", X[2], TOP, grow=True)
bar(u3, "STANDBY")
hero(u3, "Gaming PC", ["Connect", "Terminate"])
link_card(u3, "This desktop doesn't know this phone",
          "Scan the desktop's QR code over Wi-Fi once, then plug the cable in again.", color=BAD,
          actions=("Scan QR code",))
profile_strip(u3)
audio(u3)

# U4: the scan screen with a cable in and no pairing at all.
u4 = phone(PAGE, "U4 Scan · cable, never paired", X[3], TOP)
head = vstack(u4, "heading", gap=8, h="fill")
text(head, "Connect to your desktop", 22, ON, "SemiBold", line=28)
text(head, "Scan the QR code shown by Caster on your computer.", 14, VARIANT, width=CONTENT_W, line=20)
link_card(u4, "Cable connected", "Pair once over Wi-Fi by scanning the QR code. After that, casting works by "
                                 "cable too.", pill_label="USB", pill_tone="muted")
viewfinder = vstack(u4, "camera", main="center", cross="center", h="fill", v="fixed", height=364, fill=ON,
                    opacity=0.05, radius=18, stroke=OUTLINE)
mono(viewfinder, "CAMERA", 11, CAPTION, spacing=1.2, line=14)

# ---- S1 / S2: Android's own dialogs (system UI, drawn only to place them in the flow) ----
DIALOG_BG = "#2B2F36"


def system_dialog(x, name, title, body, options, buttons):
    f = phone(PAGE, name, x, TOP)
    dim = vstack(f, "dimmed app", main="center", cross="center", h="fill", v="fill")
    d = vstack(dim, "dialog", gap=14, pad=(22, 22, 14, 22), h="fixed", width=330, fill=DIALOG_BG, radius=26)
    text(d, title, 17, ON, "Medium", width=286, line=23)
    if body:
        text(d, body, 13.5, VARIANT, width=286, line=19)
    for label, on in options:
        row = hstack(d, f"option · {label}", gap=10, cross="center", h="fill")
        box = hstack(row, "check", main="center", cross="center", h="fixed", v="fixed", width=18, height=18, radius=4,
                     stroke=PRIMARY if on else VARIANT, fill=PRIMARY if on else None)
        text(row, label, 13.5, ON, width=250, line=18)
    acts = hstack(d, "buttons", gap=6, main="max", h="fill")
    for label in buttons:
        text_button(acts, label)
    return f


s1 = system_dialog(X[4], "S1 Android · open Caster for the accessory", "Open Caster to handle Caster Receiver?",
                   None, [("Use by default for this USB accessory", True)], ["Cancel", "OK"])
s2 = system_dialog(X[5], "S2 Android · screen-capture consent", "Start recording or casting with Caster?",
                   "Caster will have access to everything shown on your screen or played from your device while "
                   "recording or casting.", [("Share entire screen", True)], ["Cancel", "Start"])
for f in (s1, s2):
    autolayout.solve(nodes, f, text_height)

# ---- The flow: phone and Mac side by side, step by step ----
FLOW_W = 6 * COLUMN - PHONE_GAP
LANE_W = (FLOW_W - 2 * 24 - 120 - 2 * 16) // 2
FLOW = [
    ("0", "(Phone not on the cable yet.) Its saved desktop is on Home with Connect, as today.", "Home",
     "Caster opens on the deck, not the QR, when a phone is saved: “Not connected — tap Connect or plug the cable "
     "in”. The QR is one click away (Show QR code).", "14"),
    ("1", "Plug the cable in", "—",
     "The receiver sees an Android vendor id and sends the AOA switch, once per plug-in. The phone re-enumerates as an accessory.", "deck unchanged"),
    ("2", "Android asks: Open Caster for Caster Receiver?  [S1]  — “use by default” skips it next time",
     "S1", "Claims the accessory and sends hello every second until the app answers.", "deck unchanged"),
    ("3", "Caster opens on Home: Cable connected · Checking the desktop…", "U1 (checking)",
     "Both sides prove the Wi-Fi pairing's key (usb-link.md). No proof, no media.", "—"),
    ("4", "Home: Connected by cable. Share screen now casts over the cable.", "U1",
     "Deck leaves the QR: Connected by cable, USB tag. The main window comes forward.", "10"),
    ("4a", "Never paired with this Mac: Scan shows why — pair once over Wi-Fi.", "U4",
     "QR hero with the banner: a phone on the cable isn't paired with this Mac.", "13"),
    ("4b", "Paired with a Mac that forgot it: Home shows the refusal + Scan QR code.", "U3",
     "QR hero with the same banner.", "13"),
    ("5", "Tap Share screen → Android's capture consent  [S2]", "S2", "—", "10"),
    ("6", "ON AIR · Sharing by cable. Profile held for the whole cast. Game audio and the mic (built-in or headset) "
          "travel as two tracks; the mic switch works live.", "U2",
     "ON AIR, “Casting by cable”. The video window follows its last state, remembered across restarts: open last time → "
     "it opens; closed → Open video. Same rule for Wi-Fi.", "11 → 05"),
    ("7", "Stop share screen, or the notification's Stop → back to step 4", "U1",
     "NO INPUT on the video window, deck back to standby by cable.", "10 / 06"),
    ("8", "Unplug → the cable card goes; Home is as before the cable (Connect for Wi-Fi).", "Home",
     "Deck: Cable unplugged — not “reconnecting”: there is nothing to wait for.", "12"),
]
flow = vstack(PAGE, "FLOW · USB cast, phone ↔ Mac", gap=0, pad=(20, 24, 24, 24), h="fixed", width=FLOW_W,
              fill=SURFACE, radius=18, stroke=OUTLINE)
nodes[flow]["child_layout"]["absolute"] = False
head = hstack(flow, "head", gap=16, pad=(0, 0, 12, 0), h="fill")
mono(head, "STEP", 11, VARIANT, "Bold", spacing=1.2, line=14)
hb = hstack(head, "lane · phone", h="fixed", width=LANE_W + 60)
mono(hb, "PHONE (CASTER APP)  ·  SCREEN", 11, PRIMARY, "Bold", spacing=1.2, line=14)
hm = hstack(head, "lane · mac", h="fixed", width=LANE_W + 60)
mono(hm, "MAC (RECEIVER)  ·  DESKTOP FRAME", 11, PRIMARY, "Bold", spacing=1.2, line=14)
for step, phone_text, phone_ref, mac_text, mac_ref in FLOW:
    hairline(flow, FLOW_W - 48)
    branch = step[-1].isalpha()
    row = hstack(flow, f"step {step}", gap=16, pad=(12, 0, 12, 0), cross="min", h="fill")
    mono(row, step, 13, BAD if branch else ON, "Bold", line=18)
    lane = hstack(row, "phone", gap=10, h="fixed", width=LANE_W + 60)
    text(lane, phone_text, 13.5, ON, width=LANE_W, line=19)
    tag_box = hstack(lane, "ref", pad=(2, 8, 2, 8), fill=PRIMARY, opacity=0.14, radius=8)
    mono(tag_box, phone_ref, 10, PRIMARY, "Bold", line=14)
    lane2 = hstack(row, "mac", gap=10, h="fixed", width=LANE_W + 60)
    text(lane2, mac_text, 13.5, VARIANT, width=LANE_W, line=19)
    tag2 = hstack(lane2, "ref", pad=(2, 8, 2, 8), fill=ON, opacity=0.08, radius=8)
    mono(tag2, mac_ref, 10, VARIANT, "Bold", line=14)

for f in (u1, u2, u3, u4):
    autolayout.solve(nodes, f, text_height)
for f, title, lines in (
    (u1, "U1", ["Shown when the receiver answers over the cable and both sides proved the shared key "
                "(usb-link.md): the cable reuses the Wi-Fi pairing, it never pairs by itself.",
                "Android asks “Open Caster for Caster Receiver?” first; this is what the app shows after it.",
                "The one Share screen button casts over the cable while it is plugged in; without a cable it is the "
                "Wi-Fi share as today.",
                "Audio and Microphone work as over Wi-Fi: two separate tracks, the mic from the built-in mic or a "
                "headset, switched on or off live."]),
    (u2, "U2", ["The same ON AIR annunciator and Stop button as a Wi-Fi cast; the USB pill says which link.",
                "Quality is the profile's, held for the cast (ADR-034 consequences); the note says so in the cable's "
                "card, and the profile list stays as for the next cast (its live controls drive Wi-Fi only).",
                "The deck on the Mac leaves its QR stage when the cable link is proven, as on a Wi-Fi connect."]),
    (u3, "U3", ["The desktop sent unknown-device: it was Disconnected there, or it is another Mac.",
                "The words are the pairing screen's, not the protocol's."]),
    (u4, "U4", ["A phone that never paired lands on Scan; the card tells it why the cable alone isn't enough.",
                "Nothing here needs the cable: the QR scan is the normal one."]),
):
    n = notes(PAGE, title, lines, nodes[f]["transform"][4], TOP + nodes[f]["height"] + NOTES_GAP)
    autolayout.solve(nodes, n, text_height)

FLOW_Y = TOP + 915 + 300
nodes[flow]["transform"][4], nodes[flow]["transform"][5] = 40.0, float(FLOW_Y)
page_caption(PAGE, "FLOW — WHAT HAPPENS ON EACH SIDE, STEP BY STEP (DESKTOP FRAMES: designs/desktop-signal-deck.figcraft 05, 06, 10–14)",
             40, FLOW_Y - 34)
autolayout.solve(nodes, flow, text_height)


def write(path, doc_nodes, pages, name):
    doc = {"format": "figcraft", "version": 1, "imageIndex": [], "name": name, "pages": pages,
           "nodes": {str(k): v for k, v in doc_nodes.items()}, "next_id": max(doc_nodes) + 1, "styles": [],
           "variables": {"collections": [], "variables": []}, "current_page": pages[0], "document_colors": [],
           "comments": []}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", json.dumps({"versions": []}))


write(sys.argv[1], nodes, [PAGE], "mobile-usb-cast")
frames = [n for n in nodes.values() if n["type"] == "frame"]
print(sys.argv[1], len(nodes), "nodes,", len(frames), "frames")

if "--into" in sys.argv:
    target = sys.argv[sys.argv.index("--into") + 1]
    doc = json.loads(zipfile.ZipFile(target).read("document.json"))
    all_nodes = {int(k): v for k, v in doc["nodes"].items()}
    # Drop an earlier copy of this page and everything under it.
    for p in [p for p in doc["pages"] if all_nodes[p]["name"] == PAGE_NAME]:
        stack = [p]
        while stack:
            i = stack.pop()
            stack += all_nodes[i].get("children", [])
            del all_nodes[i]
        doc["pages"].remove(p)
    offset = max(all_nodes) + 1
    for k, n in nodes.items():
        m = dict(n)
        m["id"] = k + offset
        if "parent" in m:
            m["parent"] += offset
        if "children" in m:
            m["children"] = [c + offset for c in m["children"]]
        all_nodes[m["id"]] = m
    pages = doc["pages"] + [PAGE + offset]
    doc.update({"nodes": {str(k): v for k, v in all_nodes.items()}, "next_id": max(all_nodes) + 1, "pages": pages})
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", json.dumps({"versions": []}))
    print("wrote page into", target, "pages:", len(pages))
