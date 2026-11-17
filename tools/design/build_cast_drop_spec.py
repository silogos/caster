"""Builds the "Mobile design v5 · Cast survives a drop (ADR-036)" page: what
the phone shows while a cast waits for its link to come back, over Wi-Fi and
over the cable, and when the five-minute window runs out.

A design ahead of the code, in the shipped amber tokens (spec_dsl.py), all
auto layout placed by autolayout.solve(). Screens:
  D1 Home · Wi-Fi lost, waiting      the ICE/socket ladder is running
  D2 Home · cable unplugged, waiting nothing is captured; the display waits
  D3 Notification shade · waiting    both links' wording, with Stop
  D4 Home · the window ran out       FAULT, Share screen again
  D5 Home · on air by cable          U2 with the new unplug sentence

Usage:
  python3 -I tools/design/build_cast_drop_spec.py OUT.figcraft
  python3 -I tools/design/build_cast_drop_spec.py OUT.figcraft --into designs/mobile-app.figcraft
--into also writes the page into that file, replacing an earlier copy of it.
"""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import autolayout  # noqa: E402

from spec_dsl import *  # noqa: E402,F401,F403

PAGE_NAME = "Mobile design v5 · Cast survives a drop (ADR-036)"
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


PAGE = nid()
nodes[PAGE] = {"id": PAGE, "name": PAGE_NAME, "type": "page", "background": hexc(PAGE_BG), "flows": [],
               "prototype": {"device": "None", "background": hexc("#1E1E1E"), "rotation": "portrait"},
               "children": [], "width": 0.0, "height": 0.0}
page_caption(PAGE, "ADR-036 · A CAST SURVIVES A FIVE-MINUTE DROP — CABLE AND WI-FI · NO NEW SHARE, NO NEW CONSENT", 40,
             30, 13, "Bold", ON)
COLUMN = PHONE_W + PHONE_GAP
X = [40 + i * COLUMN for i in range(5)]
for x, label in zip(X, ("D1 · HOME — WI-FI LOST, WAITING", "D2 · HOME — CABLE UNPLUGGED, WAITING",
                        "D3 · NOTIFICATION — WAITING", "D4 · HOME — THE FIVE MINUTES RAN OUT",
                        "D5 · HOME — ON AIR BY CABLE (U2, NEW LINE)")):
    page_caption(PAGE, label, x, 78)

# D1: a Wi-Fi cast whose network went away. The encoder still runs (ADR-036 §5).
d1 = phone(PAGE, "D1 Home · Wi-Fi lost, waiting", X[0], TOP, grow=True)
bar(d1, "RECONNECTING", "amber")
hero(d1, "Gaming PC")
status_card(d1, wifi_glyph, "Wi-Fi connection lost",
            "The cast carries on by itself when the desktop is reachable again. No need to share your screen again.",
            color=PRIMARY, countdown="4:12")
stop_button(d1, "Stop share screen")
profile_strip(d1, "1920×1200 · 120 fps · 20–30 Mbps · Wi-Fi")
audio(d1)

# D2: a cable cast whose cable was pulled. Nothing is captured (ADR-036 §4).
d2 = phone(PAGE, "D2 Home · cable unplugged, waiting", X[1], TOP, grow=True)
bar(d2, "RECONNECTING", "amber")
hero(d2, "Gaming PC")
status_card(d2, usb_glyph, "Cable unplugged",
            "Plug it back in to carry on. No need to share your screen again. Nothing is captured while it's out.",
            color=PRIMARY, pill_label="USB", countdown="4:12")
stop_button(d2, "Stop share screen")
profile_strip(d2, "1920×1200 · 120 fps · 30 Mbps · USB")
audio(d2, paused=True)

# D3: the notification shade, both links' wording.
d3 = phone(PAGE, "D3 Notification shade · waiting", X[2], TOP)
shade = vstack(d3, "shade", gap=12, h="fill")


def notification(parent, title, body, action="Stop"):
    n = vstack(parent, f"notification · {title}", gap=6, pad=(14, 16, 10, 16), h="fill", fill="#22272F", radius=22)
    head = hstack(n, "app", gap=8, cross="center")
    rect(head, "small icon", 16, 16, PRIMARY, radius=4)
    mono(head, "Caster · now", 11, VARIANT, spacing=0.4, line=14)
    text(n, title, 15, ON, "Medium", line=20)
    text(n, body, 13.5, VARIANT, width=CONTENT_W - 32, line=19)
    acts = hstack(n, "actions", gap=6, h="fill")
    text_button(acts, action)
    return n


notification(shade, "Waiting for the cable — 4:12 left",
             "Your screen share stays on. Plug the cable back in and the cast carries on.")
notification(shade, "Reconnecting to Gaming PC — 4:12 left",
             "Your screen share stays on. The cast carries on when the Wi-Fi connection is back.")
notification(shade, "Sharing your screen with Gaming PC", "Your screen is being shared with the desktop. (Today's text, "
             "for comparison: shown again once the link is back.)")

# D4: the window ran out; the cast ended as any lost connection does today.
d4 = phone(PAGE, "D4 Home · five minutes ran out", X[3], TOP, grow=True)
bar(d4, "FAULT", "bad")
hero(d4, "Gaming PC", ["Connect", "Terminate"])
status_card(d4, usb_glyph, "The cast stopped",
            "The cable stayed unplugged for 5 minutes. Plug it in and share your screen again.", color=BAD)
go_button(d4, "Share screen")
profile_strip(d4, "1920 px · 120 fps · 20–30 Mbps", casting=False)

# D5: U2 with the unplug sentence ADR-036 makes true.
d5 = phone(PAGE, "D5 Home · on air by cable", X[4], TOP, grow=True)
bar(d5, "ON AIR", "amber")
hero(d5, "Gaming PC")
status_card(d5, usb_glyph, "Sharing by cable",
            "Unplugging the cable pauses the cast for up to 5 minutes; plug it back in to carry on.",
            color=PRIMARY, pill_label="USB")
stop_button(d5, "Stop share screen")
profile_strip(d5, "1920×1200 · 120 fps · 30 Mbps · USB")
audio(d5)

for f in (d1, d2, d3, d4, d5):
    autolayout.solve(nodes, f, text_height)
for f, title, lines in (
    (d1, "D1", ["Replaces ON AIR as soon as the ICE ladder starts (a failed, or a disconnect past its 10 s grace). "
                "Back to ON AIR at the next connected; a socket drop alone is not an interruption.",
                "STOPS IN counts down the 5-minute window (CastDropWindow.MS) from the start of the drop.",
                "Today the phone shows ON AIR for the whole of ADR-009's 60 s ladder."]),
    (d2, "D2", ["The VirtualDisplay has no surface: nothing is captured or encoded. Game audio and mic stop "
                "recording; their switches keep the user's choice and come back as they were.",
                "Only the same desktop (desktopId) resumes the cast; another Mac on the cable ends it.",
                "The USB card shows even though the cable is out: it is this cast's state, not the link's."]),
    (d3, "D3", ["The foreground notification's text changes while waiting; Stop stays its one action.",
                "The system's own screen-sharing indicator stays on for the whole wait (ADR-036, Privacy)."]),
    (d4, "D4", ["The cable's wording. Over Wi-Fi it is today's connection-lost message.",
                "The same end as today's connection lost, only later: Share screen asks for consent again."]),
    (d5, "D5", ["Only the sentence changes (usb_casting_sub, today: “Unplugging the cable stops the cast.”).",
                "OPEN: the receiver's deck says “Cable unplugged — nothing to wait for” (desktop frame 12). "
                "Change it to “Cable unplugged — plug it back in to carry on”?"]),
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


write(sys.argv[1], nodes, [PAGE], "mobile-cast-drop")
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
