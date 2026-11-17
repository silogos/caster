"""Builds the "Mobile design v6 · One link per connection (ADR-037)" page:
the link is the one the phone connected with, Wi-Fi (the QR, as today) or the
USB cable, never both. The first screen stays Scan; plugging the cable in
redirects to a cable auth screen (usb-link.md v2: the Mac asks once). Home
is unchanged except for a WI-FI / CABLE tag in the app bar.

A design ahead of the code, in the shipped amber tokens (spec_dsl.py), all
auto layout placed by autolayout.solve(). Two rows of screens:

Row 1, the cable auth screen (reached by plugging in, from Scan or a Home that isn't connected):
  C1 Checking the Mac on the cable
  C2 Waiting for the Mac's one-time Allow
  C3 The Mac didn't allow
  C4 The Mac's Caster is too old for the cable

Row 2, Home, unchanged but for the tag:
  H1 Connected by Wi-Fi          tag WI-FI; a cable plugged in now is ignored
  H2 Connected by cable          tag CABLE
  H3 On air by cable             tag CABLE
  H4 Saved, not connected        no tag; Connect is Wi-Fi as today, a plug-in goes to C1

The Mac's side (the Allow prompt) is drawn by build_desktop_cable_allow.py.

Usage:
  python3 -I tools/design/build_link_choice_spec.py OUT.figcraft
  python3 -I tools/design/build_link_choice_spec.py OUT.figcraft --into designs/mobile-app.figcraft
--into also writes the page into that file, replacing an earlier copy of it.
"""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import autolayout  # noqa: E402

from spec_dsl import *  # noqa: E402,F401,F403

PAGE_NAME = "Mobile design v6 · One link per connection (ADR-037)"
OLD_PAGE_NAMES = ("Mobile design v6 · The link is chosen at Connect (ADR-037)",)
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


def bar(parent, annunciator, tone="muted", tag=None):
    b = hstack(parent, "app bar", gap=10, cross="center", h="fill", v="fixed", height=40)
    brand = hstack(b, "brand", gap=8, cross="center", h="fill")
    rect(brand, "brand-mark", 24, 24, PRIMARY if tone == "amber" else ON, opacity=0.6 if tone == "amber" else 0.08,
         radius=8)
    mono(brand, "Caster", 13, ON, "Medium", spacing=1.4, line=16)
    fg = {"muted": VARIANT, "amber": PRIMARY, "bad": BAD}[tone]
    if tag:  # ADR-037: the link this connection runs on, the only change to Home
        pill(b, tag, "amber")
    ann = hstack(b, "annunciator", pad=(5, 14, 5, 14), cross="center", fill=fg, opacity=0.12, radius=13)
    mono(ann, annunciator, 11, fg, "Bold", spacing=1.0, line=16)


def hero(parent, name, right=()):
    d = hstack(parent, "desktop", main="spaceBetween", cross="center", h="fill")
    text(d, name, 22, ON, "SemiBold", line=28)
    actions = hstack(d, "actions", cross="center")
    for label in right:
        text_button(actions, label, ON)


def usb_glyph(parent, color=ON):
    w = vstack(parent, "well-usb", gap=1, main="center", cross="center", h="fixed", v="fixed", width=38, height=38,
               fill=color, opacity=0.10, radius=12)
    pins = hstack(w, "pins", gap=4, main="center")
    rect(pins, "pin", 2, 4, color)
    rect(pins, "pin", 2, 4, color)
    rect(w, "plug", 12, 10, radius=2, stroke=color, stroke_weight=1.6)
    rect(w, "cord", 2, 6, color)
    return w


def wifi_glyph(parent, color=ON):
    """Three arcs drawn as stacked bars, the console's other wells' weight."""
    w = vstack(parent, "well-wifi", gap=3, main="center", cross="center", h="fixed", v="fixed", width=38, height=38,
               fill=color, opacity=0.10, radius=12)
    for width in (18, 12, 6):
        rect(w, "arc", width, 2, color, radius=1)
    rect(w, "dot", 3, 3, color, radius=1.5)
    return w


def status_card(parent, glyph, title, sub, color=ON, pill_label=None, pill_tone="amber", countdown=None):
    c = card(parent, f"card · {title}")
    row = hstack(c, "row", gap=14, pad=(13, 16, 13, 16), cross="center", h="fill")
    glyph(row, color)
    texts = vstack(row, "texts", gap=2, h="fill")
    head = hstack(texts, "title", gap=8, cross="center")
    text(head, title, 15, color, "Medium", line=20)
    if pill_label:
        pill(head, pill_label, pill_tone)
    text(texts, sub, 13, VARIANT, width=CARD_INNER_W - 38 - 14, line=19)
    if countdown:
        hairline(c, CONTENT_W)
        z = hstack(c, "countdown", gap=10, pad=(12, 16, 14, 16), main="spaceBetween", cross="center", h="fill")
        mono(z, "STOPS IN", 11, VARIANT, "Medium", spacing=1.1, line=14)
        mono(z, countdown, 15, PRIMARY, "Bold", spacing=0.6, line=20)
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


def profile_strip(parent, readout, casting=True):
    c = card(parent, "card · profile")
    z = vstack(c, "profile", gap=6, pad=(14, 16, 14, 16), h="fill")
    mono(z, "STATUS" if casting else "NEXT CAST", 11, VARIANT, "Medium", spacing=1.1, line=14)
    text(z, "Capture", 15, ON, "Medium", line=20)
    mono(z, readout, 11)
    return c


def audio(parent, paused=False):
    mono(parent, "AUDIO", 11, VARIANT, "Medium", spacing=1.1, line=14)
    a = card(parent, "card · audio")
    switch_row(a, "Audio", "Paused until the link is back." if paused else
               "Off silences the audio, but its track stays part of the cast.", True, glyph="gamepad")
    hairline(a, CONTENT_W)
    switch_row(a, "Microphone", "Not recording until the link is back." if paused else
               "Recording from the headset — its own track.", True)


def notes(page, title, lines, x, y, width=PHONE_W):
    n = vstack(page, f"notes · {title}", gap=8, h="fixed", width=width)
    nodes[n]["transform"][4], nodes[n]["transform"][5] = float(x), float(y)
    nodes[n]["child_layout"]["absolute"] = False
    mono(n, "NOTES", 11, PRIMARY, "Bold", spacing=1.2, line=14)
    for line in lines:
        text(n, "• " + line, 12.5, VARIANT, width=width, line=18)
    return n




def hero_plate(parent, name, actions):
    d = hstack(parent, "desktop", main="spaceBetween", cross="center", h="fill")
    text(d, name, 22, ON, "SemiBold", line=28)
    a = hstack(d, "actions", cross="center")
    for label, tone in actions:
        text_button(a, label, PRIMARY if tone == "amber" else ON)


def link_row(parent, labels):
    r = hstack(parent, "links", gap=6, cross="center", h="fill")
    for label in labels:
        text_button(r, label, PRIMARY)


def hint_line(parent, chars):
    h = hstack(parent, "hint", gap=10, pad=(0, 4, 0, 4), cross="min", h="fill")
    rect(h, "dot", 6, 6, VARIANT, radius=3)
    text(h, chars, 13, VARIANT, width=CONTENT_W - 30, line=19)


PAGE = nid()
nodes[PAGE] = {"id": PAGE, "name": PAGE_NAME, "type": "page", "background": hexc(PAGE_BG), "flows": [],
               "prototype": {"device": "None", "background": hexc("#1E1E1E"), "rotation": "portrait"},
               "children": [], "width": 0.0, "height": 0.0}
page_caption(PAGE, "ADR-037 · ONE LINK PER CONNECTION — SCAN STAYS FIRST · PLUGGING THE CABLE IN GOES TO CABLE AUTH · "
                   "HOME GETS A WI-FI / CABLE TAG", 40, 30, 13, "Bold", ON)
COLUMN = PHONE_W + PHONE_GAP
X = [40 + i * COLUMN for i in range(4)]
ROW_GAP = 96
MAC = "MacBook Pro"


def lay_out_row(top, captions, screens, row_notes):
    """Captions, phones and notes for one row; returns the y below its lowest note."""
    for x, label in zip(X, captions):
        page_caption(PAGE, label, x, top - 34)
    frames = []
    for x, (name, draw) in zip(X, screens):
        f = phone(PAGE, name, x, top, grow=True)
        draw(f)
        autolayout.solve(nodes, f, text_height)
        frames.append(f)
    bottom = top
    for f, (title, lines) in zip(frames, row_notes):
        n = notes(PAGE, title, lines, nodes[f]["transform"][4], top + nodes[f]["height"] + NOTES_GAP)
        autolayout.solve(nodes, n, text_height)
        bottom = max(bottom, nodes[n]["transform"][5] + nodes[n]["height"])
    return bottom


# ---- Row 1: the cable auth screen ------------------------------------------

def cable_auth(f, annunciator="CABLE", tone="amber"):
    bar(f, annunciator, tone=tone)
    head = vstack(f, "heading", gap=4, h="fill")
    text(head, "Connecting by cable", 22, ON, "SemiBold", line=28)
    text(head, "Over the USB cable. No QR code needed.", 14, VARIANT, line=20)


def c1(f):  # the cable is in; the Mac is answering
    cable_auth(f)
    status_card(f, usb_glyph, MAC, "Checking the Mac on the cable…", pill_label="USB", pill_tone="muted")
    stop_button(f, "Cancel")


def c2(f):  # a new phone: the Mac is asking its user
    cable_auth(f)
    status_card(f, usb_glyph, "Allow this phone on the Mac",
                f"{MAC} asks “Allow Lenovo TB321FU to cast over the cable?” Click Allow there. "
                "It asks only this once.", color=PRIMARY, pill_label="USB")
    hint_line(f, "Not your Mac? Unplug the cable. Nothing is shared until you tap Share screen.")
    stop_button(f, "Cancel")


def c3(f):  # the Mac's user said no (or let the prompt run out)
    cable_auth(f, "STANDBY", "muted")
    c = status_card(f, usb_glyph, "The Mac didn't allow this phone",
                    "Unplug the cable and plug it in again to ask again.", color=BAD)
    link_row(c, ["Scan QR code instead"])


def c4(f):  # the receiver speaks link v1
    cable_auth(f, "STANDBY", "muted")
    c = status_card(f, usb_glyph, "Update Caster on the Mac",
                    "The Caster on this Mac is older and can't use the cable. Wi-Fi still works.", color=BAD)
    link_row(c, ["Scan QR code instead"])


row1_bottom = lay_out_row(
    TOP,
    ("C1 · CABLE AUTH — CHECKING THE MAC", "C2 · CABLE AUTH — THE MAC ASKS ONCE", "C3 · CABLE AUTH — NOT ALLOWED",
     "C4 · CABLE AUTH — THE MAC'S CASTER IS OLDER"),
    (("C1 Cable auth · checking", c1), ("C2 Cable auth · waiting for the Mac's Allow", c2),
     ("C3 Cable auth · not allowed", c3), ("C4 Cable auth · Mac too old", c4)),
    (("C1", ["The first screen stays Scan, as today. Plugging the cable in (on Scan, or on Home while not "
             "connected) redirects here; Android may first ask once to open Caster for the cable.",
             "A phone the Mac already allowed passes through C1 in a moment and lands on H2.",
             "The card names the Mac from the cable's hello (ADR-037 §Risks)."]),
     ("C2", ["A new phone: the key exchange is done and the phone has proven it; the Mac shows its prompt "
             "(desktop frame 15). No answer in 60 s reads as Don't Allow → C3.",
             "Cancel or unplugging sends bye and goes back to where the user was (Scan or Home)."]),
     ("C3", ["error not-allowed: Don't Allow, a closed prompt, or 60 s. The phone asks again only on a new "
             "plug-in (one prompt per plug-in on the Mac).",
             "Scan QR code instead → the Scan screen (Wi-Fi). “The desktop is already connected to another "
             "device.” (busy) uses this card too."]),
     ("C4", ["The receiver's version string on the cable is 1: the phone doesn't open the link at all.",
             "The reverse case (an older app on the phone) is shown on the Mac, desktop frame 17."])),
)

# ---- Row 2: Home, unchanged but for the tag ---------------------------------

def h1(f):  # connected over Wi-Fi; a cable plugged in changes nothing
    bar(f, "STANDBY", tag="WI-FI")
    hero_plate(f, MAC, [("Disconnect", "muted"), ("Terminate", "muted")])
    go_button(f, "Share screen")
    profile_strip(f, "1920 px · 120 fps · 20–30 Mbps", casting=False)
    audio(f)


def h2(f):  # connected over the cable
    bar(f, "STANDBY", tag="CABLE")
    hero_plate(f, MAC, [("Disconnect", "muted"), ("Terminate", "muted")])
    go_button(f, "Share screen")
    profile_strip(f, "1920 px · 120 fps · 30 Mbps · USB", casting=False)
    audio(f)


def h3(f):  # on air over the cable
    bar(f, "ON AIR", tone="amber", tag="CABLE")
    hero_plate(f, MAC, [])
    stop_button(f, "Stop sharing")
    profile_strip(f, "1920 px · 120 fps · 30 Mbps · USB", casting=True)
    audio(f)


def h4(f):  # a saved desktop, not connected
    bar(f, "STANDBY")
    hero_plate(f, MAC, [("Terminate", "muted")])
    go_button(f, "Connect")
    profile_strip(f, "1920 px · 120 fps · 20–30 Mbps", casting=False)
    audio(f)


lay_out_row(
    row1_bottom + ROW_GAP + 34,
    ("H1 · HOME — CONNECTED BY WI-FI", "H2 · HOME — CONNECTED BY CABLE", "H3 · HOME — ON AIR BY CABLE",
     "H4 · HOME — SAVED, NOT CONNECTED"),
    (("H1 Home · connected by Wi-Fi", h1), ("H2 Home · connected by cable", h2), ("H3 Home · on air by cable", h3),
     ("H4 Home · saved, not connected", h4)),
    (("H1", ["Home as it ships; the only change is the WI-FI tag in the app bar, beside the status.",
             "A cable plugged in now is ignored: the phone doesn't open the accessory, so the Mac never "
             "hears from it on the cable. To cast over it: Disconnect, and the cable takes over by itself."]),
     ("H2", ["The same Home for the cable, tagged CABLE. Design v3's “Connected by cable” card goes away.",
             "Disconnect sends a plain bye: the cable trust stays, the next plug-in connects by itself. "
             "Terminate sends bye forget: the Mac forgets this phone on the cable and asks again next time (C2).",
             "No Wi-Fi socket is opened while the cable owns the connection."]),
     ("H3", ["On air keeps its tag, so the link is always visible; ADR-036's five-minute wait after an unplug "
             "uses its own card (design v5) under the same tag."]),
     ("H4", ["No tag: nothing is connected. Connect resumes Wi-Fi exactly as today (ADR-006).",
             "Plugging the cable in here goes to C1."])),
)


def write(path, doc_nodes, pages, name):
    doc = {"format": "figcraft", "version": 1, "imageIndex": [], "name": name, "pages": pages,
           "nodes": {str(k): v for k, v in doc_nodes.items()}, "next_id": max(doc_nodes) + 1, "styles": [],
           "variables": {"collections": [], "variables": []}, "current_page": pages[0], "document_colors": [],
           "comments": []}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", json.dumps({"versions": []}))


write(sys.argv[1], nodes, [PAGE], "mobile-link-choice")
frames = [n for n in nodes.values() if n["type"] == "frame"]
print(sys.argv[1], len(nodes), "nodes,", len(frames), "frames")

if "--into" in sys.argv:
    target = sys.argv[sys.argv.index("--into") + 1]
    doc = json.loads(zipfile.ZipFile(target).read("document.json"))
    all_nodes = {int(k): v for k, v in doc["nodes"].items()}
    # Drop an earlier copy of this page and everything under it.
    for p in [p for p in doc["pages"] if all_nodes[p]["name"] in (PAGE_NAME,) + OLD_PAGE_NAMES]:
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
