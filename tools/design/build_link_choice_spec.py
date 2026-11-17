"""Builds the "Mobile design v6 · The link is chosen at Connect (ADR-037)"
page: one link per connection, Wi-Fi or the USB cable, picked at Connect and
remembered. The QR code is Wi-Fi's; the cable needs no QR and no Wi-Fi
pairing, only a one-time Allow on the Mac (usb-link.md v2).

A design ahead of the code, in the shipped amber tokens (spec_dsl.py), all
auto layout placed by autolayout.solve(). Two rows of screens:

Row 1, the first run (nothing saved) and the cable-first flow:
  F1 First screen · choose a link     Scan QR code (Wi-Fi) or Plug the cable into the Mac
  F2 Cable picked, no cable yet       waiting for the cable, Back
  F3 Cable in, the Mac asks           the Mac's one-time Allow; the hero names the Mac from the cable
  F4 The Mac didn't allow             ask again by replugging, or Wi-Fi instead

Row 2, a saved desktop:
  L1 Home · saved, Wi-Fi chosen       the choice above Connect
  L2 Home · cable chosen, no cable    waiting for the cable, Cancel
  L3 Home · connected by cable        Share casts over the cable
  L4 Home · connected by Wi-Fi        a cable plugged in is ignored, one line says so

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

PAGE_NAME = "Mobile design v6 · The link is chosen at Connect (ADR-037)"
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


def link_choice(parent, chosen, enabled=True):
    """The two-way choice: a segmented control, the console's chip style."""
    z = vstack(parent, "link choice", gap=8, h="fill")
    mono(z, "CONNECT OVER", 11, VARIANT, "Medium", spacing=1.1, line=14)
    seg = hstack(z, "segmented · link", gap=4, pad=(4, 4, 4, 4), h="fill", fill=SURFACE, radius=14, stroke=OUTLINE)
    for label, glyph in (("Wi-Fi", wifi_glyph), ("USB cable", usb_glyph)):
        on = label == chosen
        seg_item = hstack(seg, f"segment · {label}", gap=8, main="center", cross="center", h="fill", v="fixed",
                          height=44, radius=11, fill=PRIMARY if on else None, opacity=0.16 if on else 1.0)
        text(seg_item, label, 14, PRIMARY if on else (ON if enabled else VARIANT), "Medium" if on else "Regular",
             line=20)
    return z


def option_card(parent, glyph, title, sub):
    """A first-screen choice: the whole card is the button (48dp+ tall), chevron at the end."""
    c = card(parent, f"option · {title}")
    row = hstack(c, "row", gap=14, pad=(16, 16, 16, 16), cross="center", h="fill")
    glyph(row, PRIMARY)
    texts = vstack(row, "texts", gap=2, h="fill")
    text(texts, title, 15, ON, "Medium", line=20)
    text(texts, sub, 13, VARIANT, width=CARD_INNER_W - 38 - 14 - 14 - 12, line=19)
    text(row, "›", 22, VARIANT, line=24)
    return c


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
page_caption(PAGE, "ADR-037 · THE LINK IS CHOSEN AT CONNECT — WI-FI OR THE USB CABLE, NEVER BOTH · "
                   "THE QR IS WI-FI'S · THE CABLE ASKS THE MAC ONCE", 40, 30, 13, "Bold", ON)
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


# ---- Row 1: the first run and the cable-first flow --------------------------

def f1(f):  # nothing saved: the first screen offers both links
    bar(f, "STANDBY")
    hero_plate(f, "Not connected", [])
    mono(f, "CONNECT OVER", 11, VARIANT, "Medium", spacing=1.1, line=14)
    option_card(f, wifi_glyph, "Scan QR code (Wi-Fi)",
                "Open Caster on the Mac and scan the code it shows. Both on the same Wi-Fi.")
    option_card(f, usb_glyph, "Plug the cable into the Mac",
                "No QR code and no Wi-Fi. The Mac asks once to allow this phone.")
    hint_line(f, "Caster remembers your choice. To change it later, disconnect and choose the other.")


def f2(f):  # the cable was picked; none is in yet
    bar(f, "STANDBY")
    hero_plate(f, "Not connected", [])
    status_card(f, usb_glyph, "Plug the cable into the Mac",
                "Caster connects as soon as it's in. Android asks once to open Caster for the cable.",
                pill_label="USB", pill_tone="muted")
    hint_line(f, "No QR code needed. The first time, the Mac asks you to allow this phone.")
    stop_button(f, "Back")


def f3(f):  # the cable is in and the Mac is asking
    bar(f, "WAITING", tone="amber")
    hero_plate(f, MAC, [])
    status_card(f, usb_glyph, "Allow this phone on the Mac",
                f"{MAC} asks “Allow Lenovo TB321FU to cast over the cable?” Click Allow there. "
                "It asks only this once.", color=PRIMARY, pill_label="USB")
    hint_line(f, "Not your Mac? Unplug the cable. Nothing is shared until you tap Share screen.")
    stop_button(f, "Cancel")


def f4(f):  # the Mac's user said no (or let the prompt run out)
    bar(f, "STANDBY")
    hero_plate(f, MAC, [])
    c = status_card(f, usb_glyph, "The Mac didn't allow this phone",
                    "Unplug the cable and plug it in again to ask again.", color=BAD)
    link_row(c, ["Scan QR code (Wi-Fi) instead"])


row1_bottom = lay_out_row(
    TOP,
    ("F1 · FIRST SCREEN — CHOOSE A LINK", "F2 · CABLE PICKED, NO CABLE YET", "F3 · CABLE IN — THE MAC ASKS ONCE",
     "F4 · THE MAC DIDN'T ALLOW"),
    (("F1 First screen · choose a link", f1), ("F2 Cable picked, no cable yet", f2),
     ("F3 Cable in, waiting for the Mac's Allow", f3), ("F4 The Mac didn't allow", f4)),
    (("F1", ["Replaces today's scan-first launch when nothing is saved. Scan QR → Wi-Fi is chosen; "
             "the cable option → F2 and the cable is chosen.",
             "A cable plugged in on this screen (Android opens Caster for it) chooses the cable without a tap.",
             "The QR is only ever for Wi-Fi: no screen asks for it on the cable."]),
     ("F2", ["Back returns to F1. Nothing is stored until the Mac allows the phone.",
             "The same card as L2; here there is no saved desktop yet, so the hero says Not connected."]),
     ("F3", ["The hero names the Mac from the cable's hello, so the user sees where the screen would go "
             "before anything is shared (ADR-037 §Risks).",
             "The phone has made its half of the cable key; the Mac's Allow stores it on both sides "
             "(usb-link.md v2). No answer in 60 s reads as Don't Allow → F4.",
             "Cancel sends bye and closes the accessory; the choice stays the cable."]),
     ("F4", ["error not-allowed: Don't Allow, a closed prompt, or 60 s. The phone asks again only on a new "
             "plug-in (one prompt per plug-in on the Mac).",
             "Other cable errors use this card: “Update Caster on the Mac to cast over the cable.” (a v1 "
             "receiver) and “The desktop is already connected to another device.” (busy).",
             "Scan QR code (Wi-Fi) instead → the Scan screen; pairing there chooses Wi-Fi."])),
)

# ---- Row 2: a saved desktop -------------------------------------------------

def l1(f):  # the saved desktop, offline; the remembered choice is Wi-Fi
    bar(f, "STANDBY")
    hero_plate(f, MAC, [("Terminate", "muted")])
    link_choice(f, "Wi-Fi")
    go_button(f, "Connect")
    profile_strip(f, "1920 px · 120 fps · 20–30 Mbps", casting=False)
    audio(f)


def l2(f):  # the cable is the choice, but none is in yet
    bar(f, "STANDBY")
    hero_plate(f, MAC, [("Terminate", "muted")])
    link_choice(f, "USB cable", enabled=False)
    status_card(f, usb_glyph, "Plug the cable into the Mac",
                "Caster connects as soon as it's in. Android may ask once to open Caster for the cable.",
                pill_label="USB", pill_tone="muted")
    stop_button(f, "Cancel")
    profile_strip(f, "1920 px · 120 fps · 30 Mbps · USB", casting=False)


def l3(f):  # connected over the cable; Wi-Fi is not used
    bar(f, "STANDBY")
    hero_plate(f, MAC, [("Disconnect", "muted"), ("Terminate", "muted")])
    status_card(f, usb_glyph, "Connected by cable",
                "Share screen casts over the USB cable. Wi-Fi isn't used until you disconnect and choose it.",
                pill_label="USB")
    go_button(f, "Share screen")
    profile_strip(f, "1920 px · 120 fps · 30 Mbps · USB", casting=False)
    audio(f)


def l4(f):  # connected over Wi-Fi; a cable plugged in changes nothing
    bar(f, "STANDBY")
    hero_plate(f, MAC, [("Disconnect", "muted"), ("Terminate", "muted")])
    status_card(f, wifi_glyph, "Connected by Wi-Fi",
                "Share screen casts over Wi-Fi.", pill_label="WI-FI", pill_tone="muted")
    hint_line(f, "A cable is in. To cast over it, disconnect and choose USB cable.")
    go_button(f, "Share screen")
    profile_strip(f, "1920 px · 120 fps · 20–30 Mbps", casting=False)
    audio(f)


lay_out_row(
    row1_bottom + ROW_GAP + 34,
    ("L1 · HOME — SAVED, WI-FI CHOSEN", "L2 · HOME — CABLE CHOSEN, NO CABLE YET", "L3 · HOME — CONNECTED BY CABLE",
     "L4 · HOME — CONNECTED BY WI-FI, CABLE IN"),
    (("L1 Home · saved, Wi-Fi chosen", l1), ("L2 Home · cable chosen, waiting for a cable", l2),
     ("L3 Home · connected by cable", l3), ("L4 Home · connected by Wi-Fi, cable in", l4)),
    (("L1", ["The choice sits above Connect only while not connected; the last one is remembered.",
             "Wi-Fi + Connect: today's one-tap resume (ADR-006). USB cable + Connect: L2, or L3 at once "
             "when the cable is in.",
             "A desktop known on only one link still offers both: the other link pairs on first use "
             "(the QR for Wi-Fi, F3's Allow for the cable)."]),
     ("L2", ["The choice is locked while it waits; Cancel goes back to L1.",
             "While the cable is the choice, plugging it in later connects by itself (ADR-034)."]),
     ("L3", ["Disconnect sends a plain bye: both keep the cable trust, the next plug-in connects by itself. "
             "Terminate sends bye forget: the Mac forgets this phone's cable trust, and the next plug-in "
             "asks again (F3).",
             "The Wi-Fi socket is not opened, so nothing about Wi-Fi shows or changes the state."]),
     ("L4", ["The phone doesn't open the accessory: the Mac never hears from it on the cable, and its deck "
             "stays on Wi-Fi.",
             "OPEN: offer a one-tap “Switch to cable” here instead of the hint? (It would stop a running cast.)"])),
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
