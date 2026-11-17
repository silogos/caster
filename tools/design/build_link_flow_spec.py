"""Builds the "Mobile flow v7 · Cable and Wi-Fi, end to end (ADR-037)" page:
the whole UI flow of ADR-037 as a storyboard, step by step with arrows, the
Mac's side drawn in line where it matters. Screens are the ones of design v6
(cable auth C1–C4, Home with its WI-FI / CABLE tag) plus the ones a full
flow needs: Scan, Android's two dialogs, and Home with a Mac saved by cable.

  A  First time by cable      Scan → plug in → Android dialog → checking → the Mac asks → Home CABLE
  B  Later, by cable          saved Home → plug in → reconnecting (no prompt) → Home → Share → on air
  C  Wi-Fi, as today          Scan → pairing → Home WI-FI → Disconnect → saved → Connect → Home WI-FI
  D  When it goes wrong       not allowed · older Mac · unplugged mid-cast · forget (Terminate)

Usage:
  python3 -I tools/design/build_link_flow_spec.py OUT.figcraft
  python3 -I tools/design/build_link_flow_spec.py OUT.figcraft --into designs/mobile-app.figcraft
--into also writes the page into that file, replacing an earlier copy of it.
"""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import autolayout  # noqa: E402

from spec_dsl import *  # noqa: E402,F401,F403

PAGE_NAME = "Mobile flow v7 · Cable and Wi-Fi, end to end (ADR-037)"
CARD_INNER_W = CONTENT_W - 32
GAP = 170
COLUMN = PHONE_W + GAP
NOTES_GAP = 24
ROW_GAP = 150
MONO_ADVANCE = 0.6
MAC = "MacBook Pro"
PHONE_NAME = "Lenovo TB321FU"
TEAL, MAC_BG, MAC_SHEET = "#2FD1B6", "#0C1013", "#1E242B"
DIALOG_BG = "#2B2F36"


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



def hero_tagless(parent, name, actions):
    hero_plate(parent, name, actions)


def cable_auth(f, annunciator="CABLE", tone="amber"):
    bar(f, annunciator, tone=tone)
    head = vstack(f, "heading", gap=4, h="fill")
    text(head, "Connecting by cable", 22, ON, "SemiBold", line=28)
    text(head, "Over the USB cable. No QR code needed.", 14, VARIANT, line=20)


def scan_screen(f, pairing=False):
    head = vstack(f, "heading", gap=8, h="fill")
    text(head, "Connect to your desktop", 22, ON, "SemiBold", line=28)
    text(head, "Scan the QR code shown by Caster on your computer.", 14, VARIANT, width=CONTENT_W, line=20)
    v = vstack(f, "camera", main="center", cross="center", h="fill", v="fixed", height=364, fill=ON, opacity=0.05,
               radius=18, stroke=OUTLINE)
    mono(v, "CAMERA", 11, CAPTION, spacing=1.2, line=14)
    if pairing:
        status_card(f, wifi_glyph, "Pairing with the desktop…", "Over Wi-Fi, from the QR code.",
                    pill_label="WI-FI", pill_tone="muted")
    else:
        hint_line(f, "No Wi-Fi? Plug the cable into the Mac instead.")


def system_dialog(f, title, body, options, buttons):
    dim = vstack(f, "dimmed app", main="center", cross="center", h="fill", v="fixed", height=780)
    d = vstack(dim, "dialog", gap=14, pad=(22, 22, 14, 22), h="fixed", width=330, fill=DIALOG_BG, radius=26)
    text(d, title, 17, ON, "Medium", width=286, line=23)
    if body:
        text(d, body, 13.5, VARIANT, width=286, line=19)
    for label, on in options:
        row = hstack(d, f"option · {label}", gap=10, cross="center", h="fill")
        hstack(row, "check", main="center", cross="center", h="fixed", v="fixed", width=18, height=18, radius=4,
               stroke=PRIMARY if on else VARIANT, fill=PRIMARY if on else None)
        text(row, label, 13.5, ON, width=250, line=18)
    acts = hstack(d, "buttons", gap=6, main="max", h="fill")
    for label in buttons:
        text_button(acts, label)


def saved_home(f, by_cable, by_wifi):
    bar(f, "STANDBY")
    hero_plate(f, MAC, [("Terminate", "muted")])
    if by_wifi:
        go_button(f, "Connect")
        if by_cable:
            hint_line(f, "Or plug the cable in.")
    else:
        c = status_card(f, usb_glyph, "Plug the cable in to reconnect",
                        f"{MAC} remembers this phone. No QR code needed.", pill_label="USB", pill_tone="muted")
        link_row(c, ["Scan QR code"])
    profile_strip(f, "1920 px · 120 fps · 30 Mbps · USB" if not by_wifi else "1920 px · 120 fps · 20–30 Mbps",
                  casting=False)
    audio(f)


def home(f, tag, on_air=False, drop=None):
    bar(f, "RECONNECTING" if drop else ("ON AIR" if on_air else "STANDBY"),
        tone="amber" if (on_air or drop) else "muted", tag=tag)
    hero_plate(f, MAC, [] if (on_air or drop) else [("Disconnect", "muted"), ("Terminate", "muted")])
    usb = tag == "CABLE"
    if drop:
        status_card(f, usb_glyph, "Cable unplugged", "Plug it back in to carry on. The cast waits up to 5 minutes.",
                    color=PRIMARY, pill_label="USB", countdown="4:52")
    if on_air or drop:
        stop_button(f, "Stop sharing")
    else:
        go_button(f, "Share screen")
    profile_strip(f, "1920 px · 120 fps · 30 Mbps · USB" if usb else "1920 px · 120 fps · 20–30 Mbps",
                  casting=on_air or bool(drop))
    audio(f, paused=bool(drop))


def mac_card(page, x, y, title, lines, buttons=()):
    m = vstack(page, f"mac · {title}", gap=14, pad=(20, 20, 22, 20), h="fixed", width=PHONE_W, fill=MAC_BG,
               radius=14, stroke="#2A313A")
    nodes[m]["transform"][4], nodes[m]["transform"][5] = float(x), float(y)
    nodes[m]["child_layout"]["absolute"] = False
    mono(m, "ON THE MAC", 11, TEAL, "Bold", spacing=1.2, line=14)
    s = vstack(m, "sheet", gap=10, pad=(18, 18, 18, 18), h="fill", fill=MAC_SHEET, radius=14, stroke="#2A313A")
    text(s, title, 17, ON, "SemiBold", width=PHONE_W - 76, line=22)
    for line in lines:
        text(s, line, 13, VARIANT, width=PHONE_W - 76, line=18)
    if buttons:
        row = hstack(s, "buttons", gap=10, h="fill")
        for label, primary in buttons:
            b = hstack(row, f"button · {label}", main="center", cross="center", h="fill", v="fixed", height=38,
                       radius=12, fill=TEAL if primary else None, stroke=None if primary else "#3A424C")
            text(b, label, 13.5, "#08171A" if primary else ON, "SemiBold", line=18)
    return m


def arrow(page, x, y, label):
    a = vstack(page, f"arrow · {label}", gap=6, h="fixed", width=GAP - 24)
    nodes[a]["transform"][4], nodes[a]["transform"][5] = float(x), float(y)
    nodes[a]["child_layout"]["absolute"] = False
    text(a, label, 13, PRIMARY, "Medium", width=GAP - 24, line=18)
    line = hstack(a, "line", gap=0, cross="center", h="fill")
    rect(line, "shaft", GAP - 34, 2, PRIMARY)
    text(line, "▶", 10, PRIMARY, line=12)
    autolayout.solve(nodes, a, text_height)


PAGE = nid()
nodes[PAGE] = {"id": PAGE, "name": PAGE_NAME, "type": "page", "background": hexc(PAGE_BG), "flows": [],
               "prototype": {"device": "None", "background": hexc("#1E1E1E"), "rotation": "portrait"},
               "children": [], "width": 0.0, "height": 0.0}
page_caption(PAGE, "ADR-037 · THE WHOLE FLOW — SCAN STAYS FIRST · THE CABLE ASKS THE MAC ONCE, THEN RECONNECTS BY "
                   "ITSELF LIKE WI-FI · HOME ONLY GAINS A WI-FI / CABLE TAG", 40, 30, 15, "Bold", ON)


def flow_row(top, title, steps):
    """steps: (caption, kind, draw-or-mac-args, arrow label to the next step or None, notes)."""
    page_caption(PAGE, title, 40, top - 74, 14, "Bold", PRIMARY)
    bottom = top
    for i, (cap, kind, what, go, lines) in enumerate(steps):
        x = 40 + i * COLUMN
        page_caption(PAGE, cap, x, top - 34)
        if kind == "mac":
            f = mac_card(PAGE, x, top, *what)
        else:
            f = phone(PAGE, cap, x, top, grow=True)
            what(f)
        autolayout.solve(nodes, f, text_height)
        if go:
            arrow(PAGE, x + PHONE_W + 12, top + 300, go)
        if lines:
            n = notes(PAGE, cap[:2], lines, x, top + nodes[f]["height"] + NOTES_GAP)
            autolayout.solve(nodes, n, text_height)
            bottom = max(bottom, nodes[n]["transform"][5] + nodes[n]["height"])
        bottom = max(bottom, top + nodes[f]["height"])
    return bottom


ALLOW_LINES = ["It's plugged into this Mac. If you allow it, it connects by itself whenever it's plugged in. "
               "Allow only a phone you know.", "Closes in 0:52 — no answer means Don't Allow."]

y = 190
y = flow_row(y, "A · FIRST TIME BY CABLE — NO QR CODE, NO WI-FI, THE MAC ASKS ONCE", [
    ("A1 · SCAN — THE FIRST SCREEN", "phone", lambda f: scan_screen(f), "Plug the cable into the Mac",
     ["The app opens on Scan, as today. Nothing asks which link to use.",
      "OPEN: the one quiet line under the camera is the only new thing here; drop it if Scan must stay exactly as is."]),
    ("A2 · ANDROID — OPEN CASTER?", "phone",
     lambda f: system_dialog(f, "Open Caster to handle Caster Receiver?", None,
                             [("Use by default for this USB accessory", True)], ["Cancel", "OK"]),
     "OK",
     ["Android's own dialog, once. With “use by default” ticked, every later plug-in opens Caster by itself — "
      "that is what makes flow B one step."]),
    ("A3 · CABLE AUTH — CHECKING", "phone",
     lambda f: (cable_auth(f), status_card(f, usb_glyph, MAC, "Checking the Mac on the cable…", pill_label="USB",
                                           pill_tone="muted"), stop_button(f, "Cancel")),
     "A new phone",
     ["The redirect: plugging in on Scan (or on a Home that isn't connected) opens this screen.",
      "The card names the Mac from the cable, before anything is shared."]),
    ("A4 · CABLE AUTH — THE MAC ASKS", "phone",
     lambda f: (cable_auth(f), status_card(f, usb_glyph, "Allow this phone on the Mac",
                                           f"{MAC} asks “Allow {PHONE_NAME} to cast over the cable?” Click Allow "
                                           "there. It asks only this once.", color=PRIMARY, pill_label="USB"),
                hint_line(f, "Not your Mac? Unplug the cable."), stop_button(f, "Cancel")),
     "Meanwhile, on the Mac",
     ["The phone and the Mac have made a cable key; the Mac now asks its user."]),
    ("A5 · MAC — ALLOW, ONCE", "mac",
     (f"Allow {PHONE_NAME} to cast over the cable?", ALLOW_LINES, (("Don't Allow", False), ("Allow", True))),
     "Allow",
     ["Desktop frame 15. Return = Don't Allow; Allow needs a click.",
      "Allow stores the key on both sides: this phone is remembered on the cable from now on."]),
    ("A6 · HOME — CONNECTED BY CABLE", "phone", lambda f: home(f, "CABLE"), None,
     ["Home as it ships, tagged CABLE. Share screen casts over the cable."]),
])

y = flow_row(y + ROW_GAP + 74, "B · LATER — PLUG IN AND IT RECONNECTS BY ITSELF (REMEMBERED, LIKE WI-FI)", [
    ("B1 · HOME — SAVED BY CABLE, UNPLUGGED", "phone", lambda f: saved_home(f, by_cable=True, by_wifi=False),
     "Plug the cable in",
     ["What the app opens on when the Mac is remembered by cable only (no Wi-Fi pairing, so no Connect).",
      "Scan QR code adds Wi-Fi to the same Mac; Terminate forgets it."]),
    ("B2 · CABLE AUTH — RECONNECTING", "phone",
     lambda f: (cable_auth(f), status_card(f, usb_glyph, MAC, "Reconnecting — this Mac remembers this phone.",
                                           pill_label="USB", pill_tone="muted"), stop_button(f, "Cancel")),
     "A moment, no prompt",
     ["Android opens Caster by itself (A2's “use by default”); even with the app closed, plugging in is enough.",
      "Shown for well under a second: the stored key proves both sides."]),
    ("B3 · MAC — NOTHING TO ANSWER", "mac",
     ("Connected by cable", [f"No prompt: this Mac remembers {PHONE_NAME} on the cable.",
                             "The deck shows “Connected by cable — start casting from your phone” (frame 10)."]),
     "Connected", ["Disconnect on the deck forgets the phone on the cable: the next plug-in asks again (A5)."]),
    ("B4 · HOME — CONNECTED BY CABLE", "phone", lambda f: home(f, "CABLE"), "Share screen",
     ["Same as A6."]),
    ("B5 · ANDROID — CAPTURE CONSENT", "phone",
     lambda f: system_dialog(f, "Start recording or casting with Caster?",
                             "Caster will have access to everything shown on your screen or played from your device "
                             "while recording or casting.", [("Share entire screen", True)], ["Cancel", "Start"]),
     "Start", ["Android asks before every cast, on both links."]),
    ("B6 · HOME — ON AIR BY CABLE", "phone", lambda f: home(f, "CABLE", on_air=True), None,
     ["Unplug the cable now → D3. Stop sharing → B4; the cable stays connected."]),
])

y = flow_row(y + ROW_GAP + 74, "C · WI-FI — EXACTLY AS TODAY, PLUS THE TAG", [
    ("C1 · SCAN — THE FIRST SCREEN", "phone", lambda f: scan_screen(f), "Scan the Mac's QR code",
     ["Same screen as A1."]),
    ("C2 · SCAN — PAIRING", "phone", lambda f: scan_screen(f, pairing=True), "Paired",
     ["The QR pairing and its auth are unchanged (ADR-002/ADR-006)."]),
    ("C3 · HOME — CONNECTED BY WI-FI", "phone", lambda f: home(f, "WI-FI"), "Disconnect",
     ["Tagged WI-FI. A cable plugged in now is ignored on both sides: to use it, Disconnect first."]),
    ("C4 · HOME — SAVED BY WI-FI", "phone", lambda f: saved_home(f, by_cable=False, by_wifi=True), "Connect",
     ["Today's one-tap reconnect. No tag: nothing is connected.",
      "Plugging the cable in here goes to A3 (a known Mac: B2)."]),
    ("C5 · HOME — CONNECTED BY WI-FI", "phone", lambda f: home(f, "WI-FI"), None,
     ["Same as C3."]),
])

y = flow_row(y + ROW_GAP + 74, "D · WHEN IT GOES WRONG, AND HOW TRUST ENDS", [
    ("D1 · CABLE AUTH — NOT ALLOWED", "phone",
     lambda f: (cable_auth(f, "STANDBY", "muted"),
                link_row(status_card(f, usb_glyph, "The Mac didn't allow this phone",
                                     "Unplug the cable and plug it in again to ask again.", color=BAD),
                         ["Scan QR code instead"])), None,
     ["After A5's Don't Allow, a closed prompt, or 60 s. One prompt per plug-in."]),
    ("D2 · CABLE AUTH — THE MAC IS OLDER", "phone",
     lambda f: (cable_auth(f, "STANDBY", "muted"),
                link_row(status_card(f, usb_glyph, "Update Caster on the Mac",
                                     "The Caster on this Mac is older and can't use the cable. Wi-Fi still works.",
                                     color=BAD), ["Scan QR code instead"])), None,
     ["A Mac on link v1. An older app on the phone is shown on the Mac instead (desktop frame 17)."]),
    ("D3 · HOME — UNPLUGGED MID-CAST", "phone", lambda f: home(f, "CABLE", drop=True), None,
     ["ADR-036: the cast waits five minutes. Plugging back in reconnects with no prompt (the key is remembered) "
      "and the cast carries on."]),
    ("D4 · HOME — SAVED ON BOTH LINKS", "phone", lambda f: saved_home(f, by_cable=True, by_wifi=True), None,
     ["A Mac remembered on Wi-Fi and on the cable: Connect for Wi-Fi, or plug in.",
      "Terminate on Home forgets the link it was last connected by; the other stays (separate trust)."]),
])


def write(path, doc_nodes, pages, name):
    doc = {"format": "figcraft", "version": 1, "imageIndex": [], "name": name, "pages": pages,
           "nodes": {str(k): v for k, v in doc_nodes.items()}, "next_id": max(doc_nodes) + 1, "styles": [],
           "variables": {"collections": [], "variables": []}, "current_page": pages[0], "document_colors": [],
           "comments": []}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", json.dumps({"versions": []}))


write(sys.argv[1], nodes, [PAGE], "mobile-link-flow")
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
