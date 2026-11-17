"""Builds the "Mobile design v4 · Profiles over the cable (draft for review)"
page of designs/mobile-app.figcraft: how one profile list serves both links
(ADR-034) — which fields act over Wi-Fi only, a 120 fps choice, a wider
bitrate range, a "Best over USB" tag, and an optional "use when the cable is
in" profile. A proposal for the product owner's review, not the shipped UI.

Two ways to show the Wi-Fi-only fields are drawn side by side (A: a tag on
each field where it stands; B: one "Over Wi-Fi only" card at the end).

Usage:
  python3 -I tools/design/build_usb_profiles_spec.py OUT.figcraft --into designs/mobile-app.figcraft
"""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import autolayout  # noqa: E402

from spec_dsl import *  # noqa: E402,F401,F403

PAGE_NAME = "Mobile design v4 · Profiles over the cable"
CARD_INNER_W = CONTENT_W - 32
PHONE_GAP = 40
NOTES_GAP = 24
TOP = 112
MONO_ADVANCE = 0.6


def mono(parent, chars, size, color=VARIANT, style="Regular", spacing=0.3, line=None):
    t = text(parent, chars, size, color, style, family=MONO, spacing=spacing, line=line or round(size * 1.3))
    nodes[t]["width"] = float(round(len(chars) * (size * MONO_ADVANCE + spacing)))
    return t


def tag(parent, label, tone="muted"):
    p = pill(parent, label, tone)
    t = nodes[nodes[p]["children"][0]]
    t["width"] = float(round(len(label) * (10 * MONO_ADVANCE + 0.8)))
    return p


def hairline(parent, width):
    return rect(parent, "hairline", width, 1, OUTLINE)


def chip(parent, label, selected=False):
    c = hstack(parent, f"chip · {label}", pad=(10, 14, 10, 14), cross="center", fill=PRIMARY if selected else None,
               opacity=0.15, radius=20, stroke=PRIMARY if selected else OUTLINE)
    text(c, label, 14, PRIMARY if selected else ON, "Medium" if selected else "Regular", line=20)
    return c


def field(parent, label, options, hint=None, wifi_only=False, width=CARD_INNER_W):
    """A sub-label (with the Wi-Fi-only tag when it applies) over a chip row, and a hint."""
    f = vstack(parent, f"field · {label}", gap=8, h="fill")
    head = hstack(f, "label", gap=8, cross="center")
    text(head, label, 13, VARIANT, line=18)
    if wifi_only:
        tag(head, "WI-FI ONLY")
    chips = hstack(f, "chips", gap=8, h="fill")
    for chip_label, selected in options:
        chip(chips, chip_label, selected)
    if hint:
        text(f, hint, 13, VARIANT, width=width, line=19)
    return f


def section(parent, title):
    c = card(parent, f"card · {title}")
    inner = vstack(c, title, gap=16, pad=(14, 16, 16, 16), h="fill")
    mono(inner, title.upper(), 11, VARIANT, "Medium", spacing=1.1, line=14)
    return inner


def app_bar(parent, title):
    bar = hstack(parent, "app bar", gap=10, cross="center", h="fill")
    back = hstack(bar, "back", main="center", cross="center", h="fixed", v="fixed", width=40, height=40, radius=12,
                  stroke=OUTLINE)
    rect(back, "chevron", 8, 14, radius=1, stroke=ON, stroke_weight=1.6)
    text(bar, title, 17, ON, "Medium", line=22)


def bitrate(parent, low, high, top=40):
    b = vstack(parent, "field · Bitrate", gap=8, h="fill")
    text(b, f"Bitrate — {low} to {high} Mbps", 13, VARIANT, line=18)
    slider = hstack(b, "range slider", cross="center", h="fill", v="fixed", height=32)
    track = CARD_INNER_W - 40
    below = round(track * (low - 1) / (top - 1))
    on = round(track * (high - low) / (top - 1))
    rect(slider, "track-below", below, 4, ON, opacity=0.14, radius=2)
    ellipse(slider, "thumb · least (Wi-Fi only)", 20, VARIANT)
    rect(slider, "track-on", on, 4, PRIMARY, radius=2)
    ellipse(slider, "thumb · most", 20, PRIMARY)
    rect(slider, "track-above", track - below - on, 4, ON, opacity=0.14, radius=2)
    scale = hstack(b, "scale", main="spaceBetween", h="fill")
    mono(scale, "1", 11)
    mono(scale, f"{top} Mbps", 11)
    text(b, f"Over the cable the cast runs at the most ({high} Mbps). Over Wi-Fi it moves inside the range by "
            f"itself; the least is Wi-Fi's floor.", 13, VARIANT, width=CARD_INNER_W, line=19)


def picture(parent):
    pic = section(parent, "Picture")
    field(pic, "Resolution", [("1920 px", True), ("1280 px", False), ("960 px", False)])
    field(pic, "Frame rate", [("120 fps", True), ("60 fps", False), ("30 fps", False)],
          hint="120 fps is smoothest over the USB cable. Over Wi-Fi the cast lowers it when the network can't "
               "keep up.")
    bitrate(pic, 10, 30)


def radio(parent, selected):
    r = hstack(parent, "radio-on" if selected else "radio-off", main="center", cross="center", h="fixed", v="fixed",
               width=20, height=20, radius=10, stroke=PRIMARY, stroke_weight=2.0)
    if selected:
        ellipse(r, "dot", 10, PRIMARY)
    else:
        nodes[r]["strokes"] = [solid(ON, 0.30)]


def profile_row(parent, name, spec, tags=(), intent=None, selected=False, more=False):
    row = hstack(parent, f"profile · {name}", gap=14, pad=(12, 16, 12, 16), cross="center", h="fill",
                 fill=PRIMARY if selected else None, opacity=0.07)
    radio(row, selected)
    texts = vstack(row, "texts", gap=3, h="fill")
    title = hstack(texts, "title", gap=8, cross="center")
    text(title, name, 15, ON, "Medium", line=20)
    for label, tone in tags:
        tag(title, label, tone)
    if intent:
        text(texts, intent, 13, VARIANT, width=CARD_INNER_W - 20 - 14 - (54 if more else 0), line=19)
    mono(texts, spec, 11)
    if more:
        b = hstack(row, "more", gap=3, main="center", cross="center", h="fixed", v="fixed", width=40, height=40,
                   radius=12, stroke=OUTLINE)
        for _ in range(3):
            ellipse(b, "dot", 4, ON)
    return row


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
page_caption(PAGE, "ADR-034 · ONE PROFILE LIST FOR BOTH LINKS — WHAT ACTS OVER THE CABLE, WHAT IS WI-FI ONLY · "
                   "DRAFT FOR THE PRODUCT OWNER'S REVIEW", 40, 30, 13, "Bold", ON)
COLUMN = PHONE_W + PHONE_GAP
X = [40 + i * COLUMN for i in range(6)]
for x, label in zip(X, ("E1 · EDIT — A: WI-FI ONLY TAGGED IN PLACE", "E2 · EDIT — B: ONE CARD (NOT CHOSEN)",
                        "H1 · HOME — CABLE IN: CAPTURE, BUILT IN, PICKED BY ITSELF", "H2 · ANOTHER PROFILE FOR THE CABLE",
                        "THE FIELD MAP — WI-FI vs CABLE", "DECISIONS")):
    page_caption(PAGE, label, x, 78)

# ======== E1 · A: every field where it stands, Wi-Fi-only ones tagged ========
e1 = phone(PAGE, "E1 Profile · edit, Wi-Fi only tagged", X[0], TOP, grow=True)
app_bar(e1, "Edit profile")
text(e1, "Saved as you change it. Takes effect on the next cast — over Wi-Fi or the cable.", 13, VARIANT,
     width=CONTENT_W, line=19)
picture(e1)
beh = section(e1, "Behaviour")
field(beh, "Under network pressure", [("Balanced", False), ("Keep frame rate", True)], wifi_only=True,
      hint="What the cast gives up first when Wi-Fi slows down. The cable never slows down.")
field(beh, "Automatic quality", [("On", True), ("Off", False)],
      hint="Lowers the picture one step in a thermal emergency, over Wi-Fi or the cable.")
enc = section(e1, "Encoder")
field(enc, "Encoder", [("Hardware", True), ("Software", False)])
field(enc, "Video codec", [("H.264", True), ("VP8", False)], wifi_only=True,
      hint="Over the cable the video is always H.264.")
field(enc, "H.264 profile", [("Baseline", False), ("High", True)])
field(enc, "Encoder fps limit", [("Off", True), ("30 fps", False), ("60 fps", False)])
aud = section(e1, "Audio")
field(aud, "Low-latency audio", [("Low latency", True), ("Standard", False)], wifi_only=True,
      hint="Over the cable the audio is always raw sound — the lowest delay, whatever this says.")

# ======== E2 · B: the Wi-Fi-only fields gathered at the end ========
e2 = phone(PAGE, "E2 Profile · edit, one Wi-Fi only card", X[1], TOP, grow=True)
app_bar(e2, "Edit profile")
text(e2, "Saved as you change it. Takes effect on the next cast — over Wi-Fi or the cable.", 13, VARIANT,
     width=CONTENT_W, line=19)
picture(e2)
beh2 = section(e2, "Behaviour")
field(beh2, "Automatic quality", [("On", True), ("Off", False)],
      hint="Lowers the picture one step in a thermal emergency, over Wi-Fi or the cable.")
enc2 = section(e2, "Encoder")
field(enc2, "Encoder", [("Hardware", True), ("Software", False)])
field(enc2, "H.264 profile", [("Baseline", False), ("High", True)])
field(enc2, "Encoder fps limit", [("Off", True), ("30 fps", False), ("60 fps", False)])
wifi = section(e2, "Over Wi-Fi only")
text(wifi, "These shape the cast when it travels over Wi-Fi. Over the USB cable they don't apply.", 13, VARIANT,
     width=CARD_INNER_W, line=19)
field(wifi, "Under network pressure", [("Balanced", False), ("Keep frame rate", True)])
field(wifi, "Video codec", [("H.264", True), ("VP8", False)])
field(wifi, "Low-latency audio", [("Low latency", True), ("Standard", False)])

# ======== H1 · Home with the cable in: Capture is built in and picked by itself ========
def zone(parent, label):
    z = hstack(parent, f"zone · {label}", pad=(14, 16, 8, 16), h="fill")
    mono(z, label, 11, VARIANT, "Medium", spacing=1.1, line=14)


def usb_card(parent, sub):
    c = card(parent, "card · usb link")
    row = hstack(c, "row · usb", gap=14, pad=(13, 16, 13, 16), cross="center", h="fill")
    well = hstack(row, "well-usb", main="center", cross="center", h="fixed", v="fixed", width=38, height=38,
                  fill=ON, opacity=0.10, radius=12)
    rect(well, "plug", 12, 10, radius=2, stroke=ON, stroke_weight=1.6)
    tx = vstack(row, "texts", gap=2, h="fill")
    head = hstack(tx, "title", gap=8, cross="center")
    text(head, "Connected by cable", 15, ON, "Medium", line=20)
    tag(head, "USB", "amber")
    text(tx, sub, 13, VARIANT, width=CARD_INNER_W - 52, line=19)


def home_head(parent):
    bar = hstack(parent, "app bar", gap=10, cross="center", h="fill", v="fixed", height=40)
    brand = hstack(bar, "brand", gap=8, cross="center", h="fill")
    rect(brand, "brand-mark", 24, 24, ON, opacity=0.08, radius=8)
    mono(brand, "Caster", 13, ON, "Medium", spacing=1.4, line=16)
    ann = hstack(bar, "annunciator", pad=(5, 14, 5, 14), cross="center", fill=ON, opacity=0.06, radius=13)
    mono(ann, "STANDBY", 11, VARIANT, "Bold", spacing=1.0, line=16)
    text(parent, "Gaming PC", 22, ON, "SemiBold", line=28)


CAPTURE_SPEC = "1920 px · 120 fps · 20–30 Mbps"
h1 = phone(PAGE, "H1 Home · cable in, Capture picked", X[2], TOP, grow=True)
home_head(h1)
usb_card(h1, "Using Capture while the cable is in. Unplugging goes back to Balanced.")
share = hstack(h1, "share screen", main="center", cross="center", h="fill", v="fixed", height=56, fill=PRIMARY,
               radius=16)
text(share, "Share screen", 15, ON_PRIMARY, "Bold", line=20)
profiles = card(h1, "card · profiles")
nodes[profiles]["clip"] = True
zone(profiles, "BUILT-IN")
profile_row(profiles, "Cool", "960 px · 30 fps · 2–3 Mbps", [("BALANCED", "muted")])
hairline(profiles, CONTENT_W)
profile_row(profiles, "Balanced", "1280 px · 30 fps · 4–6 Mbps", [("BALANCED", "muted")])
hairline(profiles, CONTENT_W)
profile_row(profiles, "Performance", "1280 px · 60 fps · 6–10 Mbps", [("KEEP FRAME RATE", "muted")])
hairline(profiles, CONTENT_W)
profile_row(profiles, "Sharp", "1920 px · 30 fps · 8–12 Mbps", [("KEEP RESOLUTION", "muted")])
hairline(profiles, CONTENT_W)
profile_row(profiles, "Capture", CAPTURE_SPEC, [("BEST OVER USB", "amber"), ("CABLE", "muted")],
            intent="Capture-card quality over the USB cable. Picked by itself when the cable is in.", selected=True)
hairline(profiles, CONTENT_W)
zone(profiles, "YOUR PROFILES")
profile_row(profiles, "Low Latency", "1920 px · 60 fps · 8–14 Mbps", [("KEEP FRAME RATE", "muted")], more=True)
hairline(profiles, CONTENT_W)
foot = hstack(profiles, "foot", pad=(12, 16, 14, 16), h="fill")
text(foot, "For the next cast, over Wi-Fi or the cable. CABLE marks the profile the cable picks.", 13, VARIANT,
     width=CARD_INNER_W, line=19)

# ======== H2 · your own profile can take the cable over from Capture ========
h2 = phone(PAGE, "H2 Row menu · use when the cable is in", X[3], TOP)
text(h2, "Another profile for the cable", 20, ON, "SemiBold", line=26)
text(h2, "Capture is the cable's profile until you pick another from a row's menu. Only one profile carries "
         "CABLE; Wi-Fi keeps the one you picked last.", 13, VARIANT, width=CONTENT_W, line=19)
menu_card = card(h2, "card · profile")
nodes[menu_card]["clip"] = True
profile_row(menu_card, "Low Latency", "1920 px · 60 fps · 8–14 Mbps", [("KEEP FRAME RATE", "muted")], more=True)
anchor = hstack(h2, "menu anchor", main="max", h="fill")
menu = vstack(anchor, "menu", pad=(6, 0, 6, 0), h="fixed", width=250, fill="#1C222B", radius=14, stroke=OUTLINE)
for label, color in (("Edit", ON), ("Duplicate", ON), ("Use when the cable is in", PRIMARY), ("Delete", BAD)):
    item = hstack(menu, f"item · {label}", pad=(0, 16, 0, 16), cross="center", h="fill", v="fixed", height=48)
    text(item, label, 15, color, line=20)
text(h2, "After picking it:", 13, VARIANT, line=19)
after = card(h2, "card · profile after")
nodes[after]["clip"] = True
profile_row(after, "Low Latency", "1920 px · 60 fps · 8–14 Mbps", [("CABLE", "muted")], selected=True, more=True)
hairline(after, CONTENT_W)
profile_row(after, "Capture", CAPTURE_SPEC, [("BEST OVER USB", "amber")])
usb_card(h2, "Using Low Latency while the cable is in.")

# ======== the field map ========
MAP_W = PHONE_W + 120
fm = vstack(PAGE, "field map", gap=0, pad=(18, 20, 20, 20), h="fixed", width=MAP_W, fill=SURFACE, radius=18,
            stroke=OUTLINE)
nodes[fm]["transform"][4], nodes[fm]["transform"][5] = float(X[4]), float(TOP)
nodes[fm]["child_layout"]["absolute"] = False
head = hstack(fm, "head", gap=12, pad=(0, 0, 10, 0), h="fill")
for label, w in (("FIELD", 190), ("WI-FI", 70), ("CABLE", 70), ("", 100)):
    cell = hstack(head, f"col {label}", h="fixed", width=w)
    mono(cell, label, 11, PRIMARY, "Bold", spacing=1.2, line=14)
ROWS = [
    ("Resolution", "yes", "yes", ""),
    ("Frame rate", "yes", "yes", "capped (fixed 2026-11-17)"),
    ("Bitrate — most", "yes", "yes", "the cable's fixed rate"),
    ("Bitrate — least", "yes", "—", "Wi-Fi's floor"),
    ("Under network pressure", "yes", "—", "Wi-Fi only"),
    ("Automatic quality", "yes", "to do", "this proposal"),
    ("Encoder (HW/SW)", "yes", "to do", "this proposal"),
    ("Video codec", "yes", "—", "cable is H.264"),
    ("H.264 profile", "yes", "to do", "this proposal"),
    ("Encoder fps limit", "yes", "to do", "this proposal"),
    ("Low-latency audio", "yes", "—", "cable is raw"),
    ("Audio / Microphone", "yes", "yes", "live"),
    ("Change profile mid-cast", "yes", "to do", "this proposal"),
]
for name, wifi_v, cable_v, note in ROWS:
    hairline(fm, MAP_W - 40)
    r = hstack(fm, f"row · {name}", gap=12, pad=(9, 0, 9, 0), cross="center", h="fill")
    for value, w, color in ((name, 190, ON), (wifi_v, 70, GOOD if wifi_v == "yes" else VARIANT),
                            (cable_v, 70, GOOD if cable_v == "yes" else PRIMARY if cable_v == "to do" else VARIANT),
                            (note, 100, VARIANT)):
        cell = hstack(r, "cell", h="fixed", width=w)
        text(cell, value, 13 if w > 100 else 12, color, line=18, width=w)

# ======== open questions ========
QW = PHONE_W
q = vstack(PAGE, "open questions", gap=12, pad=(18, 20, 20, 20), h="fixed", width=QW, fill=SURFACE, radius=18,
           stroke=OUTLINE)
nodes[q]["transform"][4], nodes[q]["transform"][5] = float(X[4] + MAP_W + PHONE_GAP), float(TOP)
nodes[q]["child_layout"]["absolute"] = False
mono(q, "DECIDED 2026-11-17 (PRODUCT OWNER: “TERSERAH KAMU”)", 11, PRIMARY, "Bold", spacing=1.2, line=14)
for i, line in enumerate([
    "E1: Wi-Fi-only fields keep their place and carry a WI-FI ONLY tag (no field moves). E2 is not built.",
    "120 fps is a choice on every profile, Wi-Fi included; the hint says it is smoothest over the cable.",
    "The bitrate slider reaches 40 Mbps.",
    "Capture is built in at 1920 px · 120 fps · 20–30 Mbps and is the cable's profile by default; Wi-Fi keeps the user's pick.",
    "Over the cable the encoder stays VBR; no new field.",
    "Next phase: automatic quality and changing the profile mid-cast over the cable.",
], start=1):
    text(q, f"{i}. {line}", 13.5, ON, width=QW - 40, line=20)

for f in (e1, e2, h1, h2, fm, q):
    autolayout.solve(nodes, f, text_height)
for f, title, lines in (
    (e1, "E1", ["Every field stays where it is today; four carry a WI-FI ONLY tag and say what the cable does "
                "instead.",
                "Frame rate gains 120 fps; the bitrate's most is the cable's rate, its least is Wi-Fi's floor."]),
    (e2, "E2", ["The same fields, but the four Wi-Fi-only ones move to one card at the end, so the first three cards "
                "are “what every cast does”."]),
    (h1, "H1", ["Plugging in selects Capture (the CABLE tag) by itself; unplugging returns to the profile picked "
                "for Wi-Fi (here Balanced). The cable card says which one is in use.",
                "Capture is a fifth built-in: 1920 px · 120 fps · 20–30 Mbps, tagged BEST OVER USB. It can be picked "
                "for Wi-Fi too, where the cast lowers it when the network can't keep up."]),
    (h2, "H2", ["Any profile can take the cable over from Capture with “Use when the cable is in”; only one carries "
                "CABLE at a time."]),
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


write(sys.argv[1], nodes, [PAGE], "mobile-usb-profiles")
print(sys.argv[1], len(nodes), "nodes")

if "--into" in sys.argv:
    target = sys.argv[sys.argv.index("--into") + 1]
    doc = json.loads(zipfile.ZipFile(target).read("document.json"))
    all_nodes = {int(k): v for k, v in doc["nodes"].items()}
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
