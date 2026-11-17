"""Builds the "Mobile flow v8 · The link is chosen first (ADR-037)" page: the
whole phone flow as a storyboard, screen to screen with arrows, the
computer's side drawn in line where it matters.

The phone opens on a choice, Wireless or Cable. Wireless leads to today's
Scan; Cable leads to a cable guide (plug in → Android's "Open Caster" → the
computer's one-time Allow). Once paired, the app opens straight on Home;
switching link needs Terminate. Home is today's, plus one card above Share
screen naming the link and its connection status.

  A  First run, Wireless   choose → Scan → pairing → Home · Wireless · Connected
  B  First run, Cable      cable guide → Android's dialog → the computer allows → Home · Cable · Connected
  C  Later, already paired straight to Home; the cable reconnects by itself; Share → on air
  D  Wireless later, switching and errors

It replaces the earlier drafts' pages v6 and v7 (removed from the file).

Usage:
  python3 -I tools/design/build_link_first_spec.py OUT.figcraft
  python3 -I tools/design/build_link_first_spec.py OUT.figcraft --into designs/mobile-app.figcraft
--into also writes the page into that file, replacing an earlier copy of it.
"""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import autolayout  # noqa: E402

from spec_dsl import *  # noqa: E402,F401,F403

PAGE_NAME = "Mobile flow v8 · The link is chosen first (ADR-037)"
OLD_PAGE_NAMES = ("Mobile design v6 · The link is chosen at Connect (ADR-037)",
                  "Mobile design v6 · One link per connection (ADR-037)",
                  "Mobile flow v7 · Cable and Wi-Fi, end to end (ADR-037)")
CARD_INNER_W = CONTENT_W - 32
GAP = 170
COLUMN = PHONE_W + GAP
NOTES_GAP = 24
ROW_GAP = 150
MONO_ADVANCE = 0.6
PC = "MacBook Pro"
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



# ---- v8 components ------------------------------------------------------------

def back(f, label="Back"):
    r = hstack(f, "back", cross="center", h="fill")
    text_button(r, "‹  " + label, ON)


def choice_screen(f):
    bar(f, "STANDBY")
    head = vstack(f, "heading", gap=6, h="fill")
    text(head, "Connect to your computer", 22, ON, "SemiBold", line=28)
    text(head, "Choose how. To change it later, Terminate on Home.", 14, VARIANT, width=CONTENT_W, line=20)
    tiles = hstack(f, "choices", gap=12, h="fill")
    for title, sub, glyph in (("Wireless", "Same Wi-Fi. Scan the code on the computer.", wifi_glyph),
                              ("Cable", "USB cable. No Wi-Fi needed.", usb_glyph)):
        t = vstack(tiles, f"choice · {title}", gap=10, pad=(22, 16, 22, 16), cross="center", h="fill",
                   fill=SURFACE, radius=18, stroke=OUTLINE)
        glyph(t, PRIMARY)
        text(t, title, 16, ON, "SemiBold", line=22)
        text(t, sub, 12.5, VARIANT, width=(CONTENT_W - 12) / 2 - 32, line=17)
    hint_line(f, "Both need Caster open on the computer, set to the same choice.")


def step(parent, n, title, sub, state):
    """One guide step: done (check), now (amber ring) or later (muted)."""
    r = hstack(parent, f"step {n}", gap=14, cross="min", h="fill")
    color = {"done": GOOD, "now": PRIMARY, "later": OUTLINE}[state]
    c = hstack(r, "number", main="center", cross="center", h="fixed", v="fixed", width=30, height=30, radius=15,
               fill=color if state == "done" else None, stroke=None if state == "done" else color)
    text(c, "✓" if state == "done" else str(n), 13, BG if state == "done" else (PRIMARY if state == "now" else VARIANT),
         "Bold", line=16)
    t = vstack(r, "texts", gap=3, h="fill")
    text(t, title, 15, ON if state != "later" else VARIANT, "Medium", width=CARD_INNER_W - 44, line=20)
    text(t, sub, 13, VARIANT, width=CARD_INNER_W - 44, line=19)


GUIDE = [
    ("Plug the cable into the computer", "Use a cable that carries data, not one for charging only."),
    ("Tap OK on “Open Caster”", "Android asks once. Tick “Use by default” and later plug-ins open Caster by "
                                "themselves."),
    ("Allow this phone on the computer", f"The computer asks once: “Allow {PHONE_NAME} to cast over the cable?”"),
]


def cable_guide(f, states, card=None, hint=None):
    bar(f, "STANDBY")
    back(f)
    head = vstack(f, "heading", gap=6, h="fill")
    text(head, "Connect with a cable", 22, ON, "SemiBold", line=28)
    text(head, "No Wi-Fi and no QR code needed.", 14, VARIANT, line=20)
    steps = card_steps(f)
    for i, ((title, sub), state) in enumerate(zip(GUIDE, states), 1):
        step(steps, i, title, sub, state)
    if card:
        card(f)
    if hint:
        hint_line(f, hint)


def card_steps(f):
    c = card(f, "card · steps")
    return vstack(c, "steps", gap=18, pad=(18, 16, 18, 16), h="fill")


def link_card(parent, link, status, sub=None):
    """The one Home change (ADR-037 §7): the link and its connection status, above Share screen."""
    dot = {"Connected": GOOD, "Reconnecting…": PRIMARY, "Not connected": VARIANT}[status]
    c = card(parent, f"card · link · {link}")
    row = hstack(c, "row", gap=14, pad=(13, 16, 13, 16), cross="center", h="fill")
    (usb_glyph if link == "Cable" else wifi_glyph)(row, ON)
    texts = vstack(row, "texts", gap=3, h="fill")
    text(texts, link, 15, ON, "Medium", line=20)
    st = hstack(texts, "status", gap=8, cross="center")
    rect(st, "dot", 8, 8, dot, radius=4)
    text(st, status, 13, dot if status != "Not connected" else VARIANT, "Medium", line=18)
    if sub:
        text(texts, sub, 13, VARIANT, width=CARD_INNER_W - 52, line=19)
    return c


def home(f, link, status, on_air=False, sub=None, countdown=None):
    bar(f, "RECONNECTING" if countdown else ("ON AIR" if on_air else "STANDBY"),
        tone="amber" if (on_air or countdown) else "muted")
    connected = status == "Connected" or countdown
    hero_plate(f, PC, [] if (on_air or countdown) else
               ([("Disconnect", "muted"), ("Terminate", "muted")] if connected else [("Terminate", "muted")]))
    c = link_card(f, link, status, sub)
    if countdown:
        hairline(c, CONTENT_W)
        z = hstack(c, "countdown", gap=10, pad=(12, 16, 14, 16), main="spaceBetween", cross="center", h="fill")
        mono(z, "STOPS IN", 11, VARIANT, "Medium", spacing=1.1, line=14)
        mono(z, countdown, 15, PRIMARY, "Bold", spacing=0.6, line=20)
    if on_air or countdown:
        stop_button(f, "Stop sharing")
    elif status == "Connected":
        go_button(f, "Share screen")
    elif link == "Wireless":
        go_button(f, "Connect")
    usb = link == "Cable"
    profile_strip(f, "1920 px · 120 fps · 30 Mbps · USB" if usb else "1920 px · 120 fps · 20–30 Mbps",
                  casting=on_air or bool(countdown))
    audio(f, paused=bool(countdown))


def computer_guide(page, x, y, allowed=False):
    m = vstack(page, "computer · cable guide", gap=14, pad=(20, 20, 22, 20), h="fixed", width=PHONE_W, fill=MAC_BG,
               radius=14, stroke="#2A313A")
    nodes[m]["transform"][4], nodes[m]["transform"][5] = float(x), float(y)
    nodes[m]["child_layout"]["absolute"] = False
    mono(m, "ON THE COMPUTER · CABLE GUIDE", 11, TEAL, "Bold", spacing=1.2, line=14)
    s = vstack(m, "steps", gap=14, pad=(18, 18, 18, 18), h="fill", fill=MAC_SHEET, radius=14, stroke="#2A313A")
    for n, title, state in ((1, f"Plug your phone in — {PHONE_NAME} found", "done"),
                            (2, "On the phone, tap OK on “Open Caster”", "done"),
                            (3, f"Allow {PHONE_NAME} to cast over the cable?", "now"),
                            (4, "Start casting from the phone", "later")):
        r = hstack(s, f"step {n}", gap=12, cross="center", h="fill")
        col = {"done": TEAL, "now": TEAL, "later": "#3A424C"}[state]
        c = hstack(r, "n", main="center", cross="center", h="fixed", v="fixed", width=24, height=24, radius=12,
                   fill=col if state == "done" else None, stroke=None if state == "done" else col)
        text(c, "✓" if state == "done" else str(n), 11, "#08171A" if state == "done" else ON, "Bold", line=14)
        text(r, title, 13.5, ON if state != "later" else VARIANT, "Medium" if state == "now" else "Regular",
             width=PHONE_W - 112, line=18)
        if state == "now":
            row = hstack(s, "buttons", gap=10, pad=(0, 0, 0, 36), h="fill")
            for label, primary in (("Don't Allow", False), ("Allow", True)):
                b = hstack(row, f"button · {label}", main="center", cross="center", h="fill", v="fixed", height=36,
                           radius=12, fill=TEAL if primary else None, stroke=None if primary else "#3A424C")
                text(b, label, 13.5, "#08171A" if primary else ON, "SemiBold", line=18)
    return m


PAGE = nid()
nodes[PAGE] = {"id": PAGE, "name": PAGE_NAME, "type": "page", "background": hexc(PAGE_BG), "flows": [],
               "prototype": {"device": "None", "background": hexc("#1E1E1E"), "rotation": "portrait"},
               "children": [], "width": 0.0, "height": 0.0}
page_caption(PAGE, "ADR-037 · THE LINK IS CHOSEN FIRST — WIRELESS OR CABLE · ONLY THAT LINK RUNS · PAIRED → STRAIGHT "
                   "TO HOME · SWITCHING NEEDS TERMINATE · HOME GAINS ONE LINK CARD", 40, 30, 15, "Bold", ON)


def mac_card(page, x, y, *_):
    return computer_guide(page, x, y)


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



y = 190
y = flow_row(y, "A · FIRST RUN, WIRELESS — CHOOSE, THEN TODAY'S SCAN", [
    ("A1 · CHOOSE THE LINK", "phone", choice_screen, "Wireless",
     ["The first screen when nothing is paired. Nothing runs yet: no Wi-Fi socket, no accessory.",
      "The computer opens on the same choice (desktop frame 15)."]),
    ("A2 · SCAN — TODAY'S SCREEN", "phone", lambda f: (back(f), scan_screen(f)), "Scan the computer's QR code",
     ["Today's Scan, with Back to the choice. The computer shows its QR after choosing Wireless (frame 16).",
      "Only now does the phone use Wi-Fi."]),
    ("A3 · SCAN — PAIRING", "phone", lambda f: (back(f), scan_screen(f, pairing=True)), "Paired",
     ["The QR pairing and its auth are unchanged (ADR-002/ADR-006)."]),
    ("A4 · HOME — WIRELESS, CONNECTED", "phone", lambda f: home(f, "Wireless", "Connected"), None,
     ["Home as it ships, plus the link card above Share screen.",
      "From now on the app opens here. The cable is never opened while this pairing is Wireless."]),
])

y = flow_row(y + ROW_GAP + 74, "B · FIRST RUN, CABLE — A GUIDE, ANDROID'S DIALOG, THE COMPUTER ALLOWS ONCE", [
    ("B1 · CABLE GUIDE — PLUG IN", "phone", lambda f: cable_guide(f, ("now", "later", "later")),
     "Plug the cable in",
     ["A1 → Cable. Each step ticks by itself as it happens (no Done buttons).",
      "Only the accessory side runs: no Wi-Fi socket, no WebRTC."]),
    ("B2 · ANDROID — OPEN CASTER?", "phone",
     lambda f: system_dialog(f, "Open Caster to handle Caster Receiver?", None,
                             [("Use by default for this USB accessory", True)], ["Cancel", "OK"]), "OK",
     ["Android's own dialog, once. “Use by default” is what lets later plug-ins reconnect with no tap (C1)."]),
    ("B3 · CABLE GUIDE — THE COMPUTER ASKS", "phone",
     lambda f: cable_guide(f, ("done", "done", "now"),
                           card=lambda g: status_card(g, usb_glyph, PC, "Found on the cable. Waiting for Allow…",
                                                      color=PRIMARY, pill_label="USB")),
     "Meanwhile, on the computer",
     ["The card names the computer from the cable before anything is shared (ADR-037 §Risks).",
      "No answer in 60 s reads as Don't Allow → D4."]),
    ("B4 · COMPUTER — ALLOW, INLINE", "mac", (), "Allow",
     ["The computer's cable guide, step 3 (desktop frame 17). The Allow lives in the guide, not a separate dialog."]),
    ("B5 · HOME — CABLE, CONNECTED", "phone", lambda f: home(f, "Cable", "Connected"), None,
     ["Same Home, the link card says Cable. The pairing is remembered on both sides."]),
])

y = flow_row(y + ROW_GAP + 74, "C · LATER, ALREADY PAIRED — STRAIGHT TO HOME; THE CABLE RECONNECTS BY ITSELF", [
    ("C1 · HOME — CABLE, NOT CONNECTED", "phone",
     lambda f: home(f, "Cable", "Not connected", sub="Plug the cable in to reconnect."), "Plug the cable in",
     ["The app opens on Home, never on the choice, while a pairing exists.",
      "Nothing to tap: no Connect for the cable."]),
    ("C2 · HOME — CABLE, CONNECTED", "phone", lambda f: home(f, "Cable", "Connected"), "Share screen",
     ["Android opens Caster by itself, the stored key proves both sides: no prompt on either side.",
      "Even with the app closed, plugging in is enough."]),
    ("C3 · ANDROID — CAPTURE CONSENT", "phone",
     lambda f: system_dialog(f, "Start recording or casting with Caster?",
                             "Caster will have access to everything shown on your screen or played from your device "
                             "while recording or casting.", [("Share entire screen", True)], ["Cancel", "Start"]),
     "Start", ["Android asks before every cast, on both links."]),
    ("C4 · HOME — CABLE, ON AIR", "phone", lambda f: home(f, "Cable", "Connected", on_air=True), None,
     ["Unplug now → D5 (the cast waits five minutes, ADR-036)."]),
])

flow_row(y + ROW_GAP + 74, "D · WIRELESS LATER, SWITCHING LINK, AND WHEN IT GOES WRONG", [
    ("D1 · HOME — WIRELESS, NOT CONNECTED", "phone", lambda f: home(f, "Wireless", "Not connected"), None,
     ["Today's one-tap Connect (ADR-006), with the link card.", "Connect → A4."]),
    ("D2 · TERMINATE — BACK TO THE CHOICE", "phone", choice_screen, None,
     ["Switching link: Terminate on Home ends the pairing on both sides (bye forget) and the app is back on A1.",
      "Disconnect keeps the pairing, as today."]),
    ("D3 · CABLE GUIDE — COMPUTER ON WIRELESS", "phone",
     lambda f: cable_guide(f, ("now", "later", "later"),
                           hint="Plugged in but nothing yet? On the computer, choose Cable too."), None,
     ["The computer is set to Wireless, so it never switches the phone: the guide waits at step 1 and, after "
      "10 s, shows the hint."]),
    ("D4 · CABLE GUIDE — NOT ALLOWED", "phone",
     lambda f: cable_guide(f, ("done", "done", "now"),
                           card=lambda g: status_card(g, usb_glyph, "The computer didn't allow this phone",
                                                      "Unplug the cable and plug it in again to ask again.",
                                                      color=BAD)), None,
     ["Don't Allow, a closed prompt, or 60 s. Other cable errors use this card: “Update Caster on the computer” "
      "(link v1) and “The computer is paired with another phone.” (busy)."]),
    ("D5 · HOME — CABLE, UNPLUGGED MID-CAST", "phone",
     lambda f: home(f, "Cable", "Reconnecting…", sub="Plug it back in to carry on.", countdown="4:52"), None,
     ["ADR-036's wait, shown in the same link card. Plugging back in reconnects with no prompt."]),
])


def write(path, doc_nodes, pages, name):
    doc = {"format": "figcraft", "version": 1, "imageIndex": [], "name": name, "pages": pages,
           "nodes": {str(k): v for k, v in doc_nodes.items()}, "next_id": max(doc_nodes) + 1, "styles": [],
           "variables": {"collections": [], "variables": []}, "current_page": pages[0], "document_colors": [],
           "comments": []}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("document.json", json.dumps(doc))
        z.writestr("versions/index.json", json.dumps({"versions": []}))


write(sys.argv[1], nodes, [PAGE], "mobile-link-first")
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
