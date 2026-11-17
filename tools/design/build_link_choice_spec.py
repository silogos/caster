"""Builds the "Mobile design v6 · The link is chosen at Connect (ADR-037)"
page: one link per connection, Wi-Fi or the USB cable, picked on Home's
saved-desktop hero and remembered.

A design ahead of the code, in the shipped amber tokens (spec_dsl.py), all
auto layout placed by autolayout.solve(). Screens:
  L1 Home · saved, Wi-Fi chosen       the choice above Connect
  L2 Home · cable chosen, no cable    waiting for the cable, Cancel
  L3 Home · connected by cable        Share casts over the cable
  L4 Home · connected by Wi-Fi        a cable plugged in is ignored, one line says so

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


def hint_line(parent, chars):
    h = hstack(parent, "hint", gap=10, pad=(0, 4, 0, 4), cross="min", h="fill")
    rect(h, "dot", 6, 6, VARIANT, radius=3)
    text(h, chars, 13, VARIANT, width=CONTENT_W - 30, line=19)


PAGE = nid()
nodes[PAGE] = {"id": PAGE, "name": PAGE_NAME, "type": "page", "background": hexc(PAGE_BG), "flows": [],
               "prototype": {"device": "None", "background": hexc("#1E1E1E"), "rotation": "portrait"},
               "children": [], "width": 0.0, "height": 0.0}
page_caption(PAGE, "ADR-037 · THE LINK IS CHOSEN AT CONNECT — WI-FI OR THE USB CABLE, NEVER BOTH · REMEMBERED", 40, 30, 13,
             "Bold", ON)
COLUMN = PHONE_W + PHONE_GAP
X = [40 + i * COLUMN for i in range(4)]
for x, label in zip(X, ("L1 · HOME — SAVED, WI-FI CHOSEN", "L2 · HOME — CABLE CHOSEN, NO CABLE YET",
                        "L3 · HOME — CONNECTED BY CABLE", "L4 · HOME — CONNECTED BY WI-FI, CABLE IN")):
    page_caption(PAGE, label, x, 78)

# L1: the saved desktop, offline; the remembered choice is Wi-Fi.
l1 = phone(PAGE, "L1 Home · saved, Wi-Fi chosen", X[0], TOP, grow=True)
bar(l1, "STANDBY")
hero_plate(l1, "Gaming PC", [("Terminate", "muted")])
link_choice(l1, "Wi-Fi")
go_button(l1, "Connect")
profile_strip(l1, "1920 px · 120 fps · 20–30 Mbps", casting=False)
audio(l1)

# L2: the cable is the choice, but none is in yet.
l2 = phone(PAGE, "L2 Home · cable chosen, waiting for a cable", X[1], TOP, grow=True)
bar(l2, "STANDBY")
hero_plate(l2, "Gaming PC", [("Terminate", "muted")])
link_choice(l2, "USB cable", enabled=False)
status_card(l2, usb_glyph, "Plug the cable into the Mac",
            "Caster connects as soon as it's in. Android may ask once to open Caster for the cable.",
            pill_label="USB", pill_tone="muted")
stop_button(l2, "Cancel")
profile_strip(l2, "1920 px · 120 fps · 30 Mbps · USB", casting=False)

# L3: connected over the cable; Wi-Fi is not used.
l3 = phone(PAGE, "L3 Home · connected by cable", X[2], TOP, grow=True)
bar(l3, "STANDBY")
hero_plate(l3, "Gaming PC", [("Disconnect", "muted"), ("Terminate", "muted")])
status_card(l3, usb_glyph, "Connected by cable",
            "Share screen casts over the USB cable. Wi-Fi isn't used until you disconnect and choose it.",
            pill_label="USB")
go_button(l3, "Share screen")
profile_strip(l3, "1920 px · 120 fps · 30 Mbps · USB", casting=False)
audio(l3)

# L4: connected over Wi-Fi; a cable plugged in changes nothing.
l4 = phone(PAGE, "L4 Home · connected by Wi-Fi, cable in", X[3], TOP, grow=True)
bar(l4, "STANDBY")
hero_plate(l4, "Gaming PC", [("Disconnect", "muted"), ("Terminate", "muted")])
status_card(l4, wifi_glyph, "Connected by Wi-Fi",
            "Share screen casts over Wi-Fi.", pill_label="WI-FI", pill_tone="muted")
hint_line(l4, "A cable is in. To cast over it, disconnect and choose USB cable.")
go_button(l4, "Share screen")
profile_strip(l4, "1920 px · 120 fps · 20–30 Mbps", casting=False)
audio(l4)

for f in (l1, l2, l3, l4):
    autolayout.solve(nodes, f, text_height)
for f, title, lines in (
    (l1, "L1", ["The choice sits above Connect only while not connected; the last one is remembered "
                "(Wi-Fi until the user picks the cable).",
                "Wi-Fi + Connect: today's one-tap resume (ADR-006). Pairing (the QR) is always over Wi-Fi."]),
    (l2, "L2", ["USB cable + Connect with no cable in. The choice is locked while it waits; Cancel goes back to L1.",
                "While the cable is the choice, plugging it in later connects by itself, as today (ADR-034)."]),
    (l3, "L3", ["Today's U1, with Disconnect in the hero: the connection is the cable's.",
                "The Wi-Fi socket is not opened, so nothing about Wi-Fi shows or changes the state."]),
    (l4, "L4", ["The phone doesn't open the accessory: the Mac never authenticates the cable and its deck "
                "stays on Wi-Fi.",
                "OPEN: offer a one-tap “Switch to cable” here instead of the hint? (It would stop a running cast.)"]),
):
    n = notes(PAGE, title, lines, nodes[f]["transform"][4], TOP + nodes[f]["height"] + NOTES_GAP)
    autolayout.solve(nodes, n, text_height)


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
