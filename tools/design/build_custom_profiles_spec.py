"""Builds the "Mobile design v2 · custom profiles" page of designs/mobile-app.figcraft
— the draft design for issue #165 (custom profiles on the phone instead of a
single Custom state), in the shipped app's amber broadcast-console tokens.

A proposal, not the shipped UI: nothing here is measured from the app. The
frames are auto layout (spec_dsl.py), placed by autolayout.solve(). The last
frame lists the issue's open questions and what this draft assumes for each.

Second draft (2026-11-11): a profile holds every cast setting (the game-audio
transport included), so the Advanced page is gone, and "Under network
pressure" and "Automatic quality" are drawn as one field, "When the cast
struggles". The app as built (ADR-024, page "Mobile · as shipped") keeps
those two apart until the rule the single field needs exists.

The output is a one-page FigCraft file that build_mobile_figcraft.py copies in
with --spec; it is not kept in designs/ by itself.

Usage: python3 -I tools/design/build_custom_profiles_spec.py OUT.figcraft
"""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import autolayout  # noqa: E402

from spec_dsl import *  # noqa: E402,F401,F403 — tokens, the frame DSL and the component ports

CARD_INNER_W = CONTENT_W - 32  # a card's content: 16 padding on each side
MENU_BG = "#1C222B"
PHONE_GAP = 40
NOTES_GAP = 24
TOP = 112
MONO_ADVANCE = 0.6  # Liberation Mono's glyph advance, in em

# What a profile gives up first when the network is short, as the tag on its row.
PRESSURE = {"framerate": "Keep frame rate", "resolution": "Keep resolution", "balanced": "Balanced"}


# ---- pieces of the console this draft adds or reuses ----

def mono(parent, chars, size, color=VARIANT, style="Regular", spacing=0.3, line=None):
    """A single monospace line, sized from the font's real advance (the DSL's estimate ignores letter spacing)."""
    t = text(parent, chars, size, color, style, family=MONO, spacing=spacing, line=line or round(size * 1.3))
    nodes[t]["width"] = float(round(len(chars) * (size * MONO_ADVANCE + spacing)))
    return t


def tag(parent, label, tone="muted"):
    """The DSL's pill, with its label sized from the font's real advance."""
    p = pill(parent, label, tone)
    t = nodes[nodes[p]["children"][0]]
    t["width"] = float(round(len(label) * (10 * MONO_ADVANCE + 0.8)))
    return p


def hairline(parent, width):
    return rect(parent, "hairline", width, 1, OUTLINE)


def radio(parent, selected):
    """The console's pick-radio: a 20dp ring, the 10dp dot when on."""
    r = hstack(parent, "radio-on" if selected else "radio-off", main="center", cross="center", h="fixed", v="fixed",
               width=20, height=20, radius=10, stroke=PRIMARY, stroke_weight=2.0)
    if selected:
        ellipse(r, "dot", 10, PRIMARY)
    else:
        nodes[r]["strokes"] = [solid(ON, 0.30)]
    return r


def more_button(parent):
    b = hstack(parent, "more", gap=3, main="center", cross="center", h="fixed", v="fixed", width=40, height=40,
               radius=12, stroke=OUTLINE)
    for _ in range(3):
        ellipse(b, "dot", 4, ON)
    return b


def profile_row(parent, name, spec, struggle, intent=None, selected=False, more=False):
    """A profile: radio, name with what it gives up in words, the built-in's intent, the real numbers."""
    row = hstack(parent, f"profile · {name}", gap=14, pad=(12, 16, 12, 16), cross="center", h="fill",
                 fill=PRIMARY if selected else None, opacity=0.07)
    radio(row, selected)
    texts = vstack(row, "texts", gap=3, h="fill")
    title = hstack(texts, "title", gap=8, cross="center")
    text(title, name, 15, ON, "Medium", line=20)
    tag(title, PRESSURE[struggle].upper(), "amber" if selected else "muted")
    if intent:
        text(texts, intent, 13, VARIANT, width=CARD_INNER_W - 20 - 14 - (54 if more else 0), line=19)
    mono(texts, spec, 11)
    if more:
        more_button(row)
    return row


def zone(parent, label):
    z = hstack(parent, f"zone · {label}", pad=(14, 16, 8, 16), h="fill")
    mono(z, label, 11, VARIANT, "Medium", spacing=1.1, line=14)
    return z


def chip(parent, label, selected=False):
    c = hstack(parent, f"chip · {label}", pad=(10, 14, 10, 14), cross="center", fill=PRIMARY if selected else None,
               opacity=0.15, radius=20, stroke=PRIMARY if selected else OUTLINE)
    text(c, label, 14, PRIMARY if selected else ON, "Medium" if selected else "Regular", line=20)
    return c


def field(parent, label, options, hint=None, width=CARD_INNER_W):
    """A sub-label over a chip row, with an optional hint (the Advanced card's vocabulary)."""
    f = vstack(parent, f"field · {label}", gap=8, h="fill")
    text(f, label, 13, VARIANT, line=18)
    for line_options in options if isinstance(options[0], list) else [options]:
        chips = hstack(f, "chips", gap=8, h="fill")
        for chip_label, selected in line_options:
            chip(chips, chip_label, selected)
    if hint:
        text(f, hint, 13, VARIANT, width=width, line=19)
    return f


def name_field(parent, value, focused=False):
    f = vstack(parent, "field · Name", gap=8, h="fill")
    text(f, "Name", 13, VARIANT, line=18)
    box = hstack(f, "input", main="spaceBetween", cross="center", pad=(0, 14, 0, 14), h="fill", v="fixed",
                 height=52, radius=12, stroke=PRIMARY if focused else OUTLINE, stroke_weight=2.0 if focused else 1.0)
    typed = hstack(box, "typed", gap=2, cross="center")
    text(typed, value, 15, ON, line=20)
    if focused:
        rect(typed, "caret", 2, 20, PRIMARY)
    mono(box, f"{len(value)} / 20", 11)
    return f


def readout(parent, lines):
    """The console's read-only strip."""
    r = vstack(parent, "readout", gap=4, pad=(12, 14, 12, 14), h="fill", fill=ON, opacity=0.04, radius=10)
    for line in lines:
        mono(r, line, 12, VARIANT, spacing=0.2, line=17)
    return r


def app_bar(parent, title):
    bar = hstack(parent, "app bar", gap=10, cross="center", h="fill")
    back = hstack(bar, "back", main="center", cross="center", h="fixed", v="fixed", width=40, height=40, radius=12,
                  stroke=OUTLINE)
    rect(back, "chevron", 8, 14, radius=1, stroke=ON, stroke_weight=1.6)
    text(bar, title, 17, ON, "Medium", line=22)
    return bar


def home_top(parent, annunciator="STANDBY"):
    """Home's app bar and desktop name. No gear: it only led to Advanced."""
    bar = hstack(parent, "app bar", gap=10, cross="center", h="fill", v="fixed", height=40)
    brand = hstack(bar, "brand", gap=8, cross="center", h="fill")
    rect(brand, "brand-mark", 24, 24, ON, opacity=0.08, radius=8)
    mono(brand, "Caster", 13, ON, "Medium", spacing=1.4, line=16)
    ann = hstack(bar, "annunciator", pad=(5, 14, 5, 14), cross="center", fill=ON, opacity=0.06, radius=13)
    mono(ann, annunciator, 11, VARIANT, "Bold", spacing=1.0, line=16)
    desktop = hstack(parent, "desktop", main="spaceBetween", cross="center", h="fill")
    text(desktop, "Gaming PC", 22, ON, "SemiBold", line=28)
    actions = hstack(desktop, "actions", cross="center")
    text_button(actions, "Disconnect", ON)
    text_button(actions, "Terminate", ON)


def audio_card(parent):
    """Home's Audio card: one switch per track, nothing else (how the game audio travels is the profile's)."""
    mono(parent, "AUDIO", 11, VARIANT, "Medium", spacing=1.1, line=14)
    audio = card(parent, "card · audio")
    switch_row(audio, "Game audio", "Off silences the game audio, but its track stays part of the cast.", True,
               glyph="gamepad")
    hairline(audio, CONTENT_W)
    switch_row(audio, "Microphone", "Off removes the microphone from the cast and frees it for the game's own "
                                    "voice chat.", False)
    return audio


def notes(page, title, lines, x, y, width=PHONE_W):
    n = vstack(page, f"notes · {title}", gap=8, h="fixed", width=width)
    nodes[n]["transform"][4], nodes[n]["transform"][5] = float(x), float(y)
    nodes[n]["child_layout"]["absolute"] = False
    mono(n, "NOTES", 11, PRIMARY, "Bold", spacing=1.2, line=14)
    for line in lines:
        text(n, "• " + line, 12.5, VARIANT, width=width, line=18)
    return n


# ---- document ----
PAGE = nid()
nodes[PAGE] = {"id": PAGE, "name": "Mobile design v2 · custom profiles (draft, #165)", "type": "page",
               "background": hexc(PAGE_BG), "flows": [],
               "prototype": {"device": "None", "background": hexc("#1E1E1E"), "rotation": "portrait"},
               "children": [], "width": 0.0, "height": 0.0}
page_caption(PAGE, "ISSUE #165 · CUSTOM PROFILES — DRAFT 4 (AMBER CONSOLE TOKENS) · TWO BEHAVIOUR SETTINGS AGAIN; AUTOMATIC QUALITY ON A THERMAL EMERGENCY ONLY", 40, 30,
             13, "Bold", ON)
COLUMN = PHONE_W + PHONE_GAP
X = [40 + i * COLUMN for i in range(6)]
for x, label in zip(X, ("P1 · HOME — PICK A PROFILE", "P2 · EDIT A PROFILE", "P3 · ADD A PROFILE",
                        "P4 · ROW MENU & DELETE", "P5 · WHERE ADVANCED WENT",
                        "OPEN QUESTIONS — WHAT THIS DRAFT ASSUMES")):
    page_caption(PAGE, label, x, 78)
page_caption(PAGE, "THE RULE — WHO LOWERS THE PICTURE, AND WHEN", X[5] + 560 + PHONE_GAP, 78)

# ======== P1 · Home: the profile list ========
p1 = phone(PAGE, "P1 Home · profile list", X[0], TOP, grow=True)
home_top(p1)
share = hstack(p1, "share screen", main="center", cross="center", h="fill", v="fixed", height=56, fill=PRIMARY,
               radius=16)
text(share, "Share screen", 15, ON_PRIMARY, "Bold", line=20)
profiles = card(p1, "card · profiles")
nodes[profiles]["clip"] = True
zone(profiles, "BUILT-IN")
BUILT_IN = [
    ("Cool", "960 px · 30 fps · 2–3 Mbps", "balanced", "Lowest heat — best for long sessions and warm devices."),
    ("Balanced", "1280 px · 30 fps · 4–6 Mbps", "balanced", "The default — good for most games."),
    ("Performance", "1280 px · 60 fps · 6–10 Mbps", "framerate", "Smoother motion for fast games; more heat."),
    ("Sharp", "1920 px · 30 fps · 8–12 Mbps", "resolution", "Highest detail; the most heat."),
]
for i, (name, spec, struggle, intent) in enumerate(BUILT_IN):
    if i:
        hairline(profiles, CONTENT_W)
    profile_row(profiles, name, spec, struggle, intent)
hairline(profiles, CONTENT_W)
zone(profiles, "YOUR PROFILES")
profile_row(profiles, "Stream 60", "1920 px · 60 fps · 4–12 Mbps", "framerate", selected=True, more=True)
hairline(profiles, CONTENT_W)
profile_row(profiles, "Custom 1", "1920 px · 60 fps · 8–12 Mbps", "resolution", more=True)
hairline(profiles, CONTENT_W)
add_row = hstack(profiles, "add profile", gap=14, pad=(14, 16, 14, 16), cross="center", h="fill")
plus = hstack(add_row, "plus", main="center", cross="center", h="fixed", v="fixed", width=20, height=20)
text(plus, "+", 20, PRIMARY, "Medium", line=20)
text(add_row, "Add profile", 15, PRIMARY, "Medium", line=20)
hairline(profiles, CONTENT_W)
foot = hstack(profiles, "foot", pad=(12, 16, 14, 16), h="fill")
text(foot, "For the next cast. A profile is the whole answer to what a cast will do: there is no Advanced page.",
     13, VARIANT, width=CARD_INNER_W, line=19)
audio_card(p1)

# ======== P2 · Edit a profile ========
p2 = phone(PAGE, "P2 Profile · edit", X[1], TOP, grow=True)
app_bar(p2, "Edit profile")
text(p2, "Saved as you change it. Takes effect on the next cast.", 13, VARIANT, width=CONTENT_W, line=19)
name_field(p2, "Stream 60")
picture = card(p2, "card · picture")
pic = vstack(picture, "picture", gap=16, pad=(14, 16, 16, 16), h="fill")
mono(pic, "PICTURE", 11, VARIANT, "Medium", spacing=1.1, line=14)
field(pic, "Resolution", [("1920 px", True), ("1280 px", False), ("960 px", False)])
field(pic, "Frame rate", [("60 fps", True), ("30 fps", False)])
bitrate = vstack(pic, "field · Bitrate", gap=8, h="fill")
text(bitrate, "Bitrate — 4 to 12 Mbps", 13, VARIANT, line=18)
# One slider, two thumbs: the least and the most the video may use (1–20 Mbps).
slider = hstack(bitrate, "range slider", cross="center", h="fill", v="fixed", height=32)
TRACK_W = CARD_INNER_W - 2 * 20  # the track between and beside the two 20dp thumbs
SLIDER_LOW_W = round(TRACK_W * (4 - 1) / (20 - 1))
SLIDER_ON_W = round(TRACK_W * (12 - 4) / (20 - 1))
rect(slider, "track-below", SLIDER_LOW_W, 4, ON, opacity=0.14, radius=2)
ellipse(slider, "thumb · least", 20, PRIMARY)
rect(slider, "track-on", SLIDER_ON_W, 4, PRIMARY, radius=2)
ellipse(slider, "thumb · most", 20, PRIMARY)
rect(slider, "track-above", TRACK_W - SLIDER_LOW_W - SLIDER_ON_W, 4, ON, opacity=0.14, radius=2)
text(bitrate, "The cast stays inside this range, 1 to 20 Mbps. It moves within it by itself, second by second.",
     13, VARIANT, width=CARD_INNER_W, line=19)
behaviour = card(p2, "card · behaviour")
beh = vstack(behaviour, "behaviour", gap=16, pad=(14, 16, 16, 16), h="fill")
mono(beh, "BEHAVIOUR", 11, VARIANT, "Medium", spacing=1.1, line=14)
field(beh, "Under network pressure", [("Balanced", False), ("Keep frame rate", True)],
      hint="What the cast gives up first when Wi-Fi slows down. It is WebRTC that decides, second by second; "
           "“Keep frame rate” drops detail, “Keep resolution” (the third choice, scrolled off) drops frames.")
field(beh, "Automatic quality", [("On", True), ("Off", False)],
      hint="Lowers the picture one step if the phone reports a thermal emergency, and brings it back once it "
           "cools. Ordinary game heat does not trigger it.")
encoder = card(p2, "card · encoder")
enc = vstack(encoder, "encoder", gap=16, pad=(14, 16, 16, 16), h="fill")
mono(enc, "ENCODER", 11, VARIANT, "Medium", spacing=1.1, line=14)
field(enc, "Encoder", [("Hardware", True), ("Software", False)])
field(enc, "Video codec", [("H.264", True), ("VP8", False)])
field(enc, "H.264 profile", [("Baseline", False), ("High", True)])
field(enc, "Encoder fps limit", [("Off", True), ("15 fps", False), ("30 fps", False), ("60 fps", False)],
      hint="Off follows the frame rate above.")
audio = card(p2, "card · audio")
aud = vstack(audio, "audio", gap=16, pad=(14, 16, 16, 16), h="fill")
mono(aud, "AUDIO", 11, VARIANT, "Medium", spacing=1.1, line=14)
field(aud, "Low-latency game audio", [("Low latency", False), ("Standard", True)],
      hint="Low latency sends raw sound: the smallest delay for headphones, at a higher Wi-Fi bitrate.")
actions = hstack(p2, "actions", main="spaceBetween", cross="center", h="fill")
text_button(actions, "Duplicate")
text_button(actions, "Delete profile", BAD)

# ======== P3 · Add a profile (a sheet over Home) ========
p3 = phone(PAGE, "P3 Profile · add", X[2], TOP)
nodes[p3]["layout"]["padding"] = [16.0, 0.0, 0.0, 0.0]
nodes[nodes[p3]["children"][0]]["layout"]["padding"] = [0.0, 24.0, 0.0, 24.0]  # the status bar keeps its inset
behind = vstack(p3, "home (dimmed)", main="center", cross="center", h="fill", v="fill")
mono(behind, "HOME, DIMMED BEHIND THE SHEET", 11, CAPTION, spacing=1.2, line=14)
sheet = vstack(p3, "sheet · new profile", gap=16, pad=(10, 24, 24, 24), h="fill", fill=SURFACE, stroke=OUTLINE)
nodes[sheet]["corners"] = {"radii": [28.0, 28.0, 0.0, 0.0], "smoothing": 0.0}
grab = hstack(sheet, "grabber row", main="center", h="fill")
rect(grab, "grabber", 36, 4, ON, opacity=0.30, radius=2)
text(sheet, "New profile", 20, ON, "SemiBold", line=26)
name_field(sheet, "Stream 60", focused=True)
field(sheet, "Start from", [[("Current values", True), ("Cool", False), ("Balanced", False)],
                            [("Performance", False), ("Sharp", False), ("Custom 1", False)]], width=CONTENT_W)
readout(sheet, ["1920 px · 60 fps · 4–12 Mbps", "Keep frame rate · Hardware · H.264 High"])
buttons = hstack(sheet, "buttons", gap=12, main="max", cross="center", h="fill")
text_button(buttons, "Cancel", ON)
create = hstack(buttons, "button · Create", pad=(0, 24, 0, 24), main="center", cross="center", v="fixed", height=48,
                fill=PRIMARY, radius=14)
text(create, "Create", 15, ON_PRIMARY, "Bold", line=20)

# ======== P4 · Row menu and delete ========
p4 = phone(PAGE, "P4 Profile · menu and delete", X[3], TOP)
head = vstack(p4, "heading", gap=8, h="fill")
text(head, "Row menu", 20, ON, "SemiBold", line=26)
text(head, "Only your own profiles have one. Built-in profiles are read-only: copy one with Add profile.", 13,
     VARIANT, width=CONTENT_W, line=19)
menu_card = card(p4, "card · profile")
nodes[menu_card]["clip"] = True
profile_row(menu_card, "Stream 60", "1920 px · 60 fps · 4–12 Mbps", "framerate", selected=True, more=True)
anchor = hstack(p4, "menu anchor", main="max", h="fill")
menu = vstack(anchor, "menu", pad=(6, 0, 6, 0), h="fixed", width=200, fill=MENU_BG, radius=14, stroke=OUTLINE)
for label, color in (("Edit", ON), ("Duplicate", ON), ("Delete", BAD)):
    item = hstack(menu, f"item · {label}", pad=(0, 16, 0, 16), cross="center", h="fill", v="fixed", height=48)
    text(item, label, 15, color, line=20)
head2 = vstack(p4, "heading", gap=8, h="fill")
text(head2, "Delete", 20, ON, "SemiBold", line=26)
text(head2, "Asks first. Deleting the selected profile needs somewhere to land.", 13, VARIANT, width=CONTENT_W,
     line=19)
dialog = vstack(p4, "dialog · delete", gap=12, pad=(20, 20, 12, 20), h="fill", fill=MENU_BG, radius=24,
                stroke=OUTLINE)
text(dialog, "Delete “Stream 60”?", 18, ON, "SemiBold", line=24)
text(dialog, "It is the selected profile. The next cast uses Balanced instead.", 13, VARIANT, width=CONTENT_W - 40,
     line=19)
dialog_buttons = hstack(dialog, "buttons", gap=4, main="max", cross="center", h="fill")
text_button(dialog_buttons, "Cancel", ON)
text_button(dialog_buttons, "Delete", BAD)

# ======== P5 · Where Advanced went (Home while casting) ========
p5 = phone(PAGE, "P5 Home · on air, no Advanced", X[4], TOP, grow=True)
home_top(p5, "ON AIR")
status = card(p5, "card · status")
head_row = hstack(status, "status head", main="spaceBetween", cross="center", pad=(14, 16, 8, 16), h="fill")
mono(head_row, "STATUS", 11, VARIANT, "Medium", spacing=1.1, line=14)
live = hstack(head_row, "live readout", gap=8, cross="center")
mono(live, "00:12:41", 12, PRIMARY, "Medium", spacing=0.2, line=16)
mono(live, "720p60 · 6.2M", 12, VARIANT, "Medium", spacing=0.2, line=16)
lamps = vstack(status, "lamps", gap=10, pad=(4, 16, 14, 16), h="fill")
lowered = hstack(lamps, "lamp · lowered", gap=10, h="fill")
ellipse(lowered, "led", 9, PRIMARY)
lowered_text = vstack(lowered, "texts", gap=2, h="fill")
text(lowered_text, "Lowered to 1280 px: the phone is too hot.", 13, ON, width=CARD_INNER_W - 19, line=19)
text(lowered_text, "Comes back once it cools.", 13, VARIANT, width=CARD_INNER_W - 19, line=19)
restore = hstack(lowered_text, "restore", pad=(2, 0, 0, 0))
text_button(restore, "Restore now")
for lamp, tone in (("Phone temperature: critical.", PRIMARY), ("Encoder: c2.qti.avc.encoder", VARIANT)):
    row = hstack(lamps, f"lamp · {lamp}", gap=10, cross="center", h="fill")
    ellipse(row, "led", 9, tone, opacity=1.0 if tone == PRIMARY else 0.5)
    text(row, lamp, 13, ON, line=19)
audio_card(p5)
moved = vstack(p5, "where each setting went", gap=10, h="fill")
text(moved, "Where each Advanced setting went", 15, ON, "SemiBold", line=20)
for setting, home in (
    ("Automatic quality, Under network pressure", "The profile's Behaviour card, still two settings (P2)"),
    ("Encoder, H.264 profile, encoder fps limit", "The profile's Encoder card (P2)"),
    ("Preferred video codec", "The profile's Encoder card (P2)"),
    ("Low-latency game audio", "The profile's Audio card (P2)"),
    ("Encoder in use", "Status, while casting (above)"),
    ("Bitrate mode", "Removed"),
):
    entry = vstack(moved, f"moved · {setting}", gap=1, h="fill")
    text(entry, setting, 13, ON, "Medium", width=CONTENT_W, line=18)
    text(entry, "→ " + home, 12.5, BAD if home == "Removed" else PRIMARY, width=CONTENT_W, line=18)

# ======== Open questions ========
QUESTIONS_W = 560
questions = vstack(PAGE, "Open questions · assumed", gap=14, pad=(20, 20, 20, 20), h="fixed", width=QUESTIONS_W,
                   fill=SURFACE, radius=18, stroke=OUTLINE)
nodes[questions]["transform"][4], nodes[questions]["transform"][5] = float(X[5]), float(TOP)
nodes[questions]["child_layout"]["absolute"] = False
Q_TEXT_W = QUESTIONS_W - 40 - 22 - 10
text(questions, "What this draft assumes", 20, ON, "SemiBold", line=26)
text(questions, "The eight open questions of issue #165, then three that this second draft raises. Each is the "
                "product owner's to answer; the screens draw one answer so there is something to point at.", 13,
     VARIANT, width=QUESTIONS_W - 40, line=19)
for number, (question, drawn) in enumerate([
    ("Built-in profiles: read-only, or editable too?",
     "Read-only: no menu, no edit screen (P1, P4). Add profile copies one (P3)."),
    ("A value changed in your own profile: saved at once, or Save / Revert?",
     "Saved at once (P2)."),
    ("A value changed while a built-in is selected?",
     "Cannot happen: a built-in has nothing to change. The Custom tab and its dials are gone (P1)."),
    ("Today's Custom values on upgrade?",
     "They arrive as a first profile, “Custom 1” (P1)."),
    ("Auto quality with a custom profile?",
     "A switch in each profile (P2). It acts only on a thermal emergency; the profile is the ceiling."),
    ("Picking another profile during a cast?",
     "Not drawn. P5 shows a cast, but not its profile list."),
    ("How many profiles, and how long a name?",
     "A name up to 20 characters (P2, P3). No limit on the number is drawn."),
    ("Should a new profile start with “Keep frame rate”?",
     "A new profile always copies something, “Current values” by default (P3), so it has no default of its own."),
    ("NEW · “Under network pressure” and “Automatic quality” as one field?",
     "No. Built as one for a day, then split again by the product owner (2026-11-11): the network is "
     "WebRTC's, and the app's own rule had no trigger that held up on the device except the temperature."),
    ("NEW · Does the Advanced page go?",
     "Drawn as gone: no gear on Home (P1), and P5 shows where each of its settings went."),
    ("NEW · Today's “Automatic quality: Off” on upgrade?",
     "Kept as it is stored: the switch is still there."),
], start=1):
    entry = hstack(questions, f"question {number}", gap=10, h="fill")
    num = hstack(entry, f"number {number}", main="center", cross="center", h="fixed", v="fixed", width=22,
                 height=22, fill=PRIMARY, radius=11)
    text(num, str(number), 12, ON_PRIMARY, "Bold", line=16)
    lines = vstack(entry, "lines", gap=2, h="fill")
    text(lines, question, 13, ON, "Medium", width=Q_TEXT_W, line=18)
    text(lines, drawn, 12.5, PRIMARY, width=Q_TEXT_W, line=18)
hairline(questions, QUESTIONS_W - 40)
removed = vstack(questions, "removed · bitrate mode", gap=6, h="fill")
tag(removed, "REMOVED · BITRATE MODE (CBR / VBR)", "bad")
text(removed, "Decided 2026-11-11: the setting goes. VBR never took effect (libwebrtc fixes the encoder to CBR), "
              "and real VBR would burst on busy scenes, against smooth frames and sound. The bitrate already "
              "follows the network through the range and “Under network pressure”. A profile has no such field "
              "(P2).", 12.5, VARIANT, width=QUESTIONS_W - 40, line=18)

# ======== Who lowers the picture, and when ========
RULE_W = 560
rule = vstack(PAGE, "The rule · who lowers the picture", gap=12, pad=(20, 20, 20, 20), h="fixed", width=RULE_W,
              fill=SURFACE, radius=18, stroke=OUTLINE)
nodes[rule]["child_layout"]["absolute"] = False
text(rule, "Who lowers the picture, and when", 20, ON, "SemiBold", line=26)
text(rule, "Decided by the product owner 2026-11-11, after a day with one merged field (ADR-025).", 13, VARIANT,
     width=RULE_W - 40, line=19)
for heading, lines in (
    ("Under network pressure: WebRTC",
     ["Bitrate, and with it resolution or frame rate, second by second, the way the profile's choice says.",
      "Never under the least of the profile's bitrate range.",
      "The app does not step in, and cannot say on screen what WebRTC did."]),
    ("Automatic quality: the app, on a thermal emergency only",
     ["Nothing below CRITICAL. A game keeps this tablet at SEVERE for a whole session at 60 fps.",
      "At CRITICAL: one step down at once; a second if it is still there a minute later; no further.",
      "The order is the profile's pressure choice: Keep frame rate gives up resolution, Keep resolution "
      "gives up 60 → 30 fps first, Balanced one of each.",
      "Out of the emergency for 30 s: one step back, then the next.",
      "“Restore now” goes straight back to the profile's values."]),
    ("Why nothing else triggers it",
     ["Measured on the TB321FU over 14,000 s of casts: the dropped-frame counter never left 0, and the "
      "encoder-load figure read highest when the cast was healthiest."]),
):
    block = vstack(rule, f"rule · {heading}", gap=3, h="fill")
    text(block, heading, 13, ON, "Medium", width=RULE_W - 40, line=18)
    for line in lines:
        text(block, "• " + line, 12.5, VARIANT, width=RULE_W - 40, line=18)

# ======== The name and the icon ========
# The three marks the product already draws, each in the two colours on the table.
LAUNCHER_PATHS = (  # apps/mobile ic_launcher_foreground.xml, viewport 108, drawn at its 0.75 group scale
    "M28,24 h52 a5,5 0 0 1 5,5 v30 a5,5 0 0 1 -5,5 h-52 a5,5 0 0 1 -5,-5 v-30 a5,5 0 0 1 5,-5 z",
    "M20,68 a3,3 0 1 0 6,0 a3,3 0 1 0 -6,0 z",
    "M23,64 a7,7 0 0 1 7,7 h-3 a4,4 0 0 0 -4,-4 z",
    "M23,58 a13,13 0 0 1 13,13 h-3 a10,10 0 0 0 -10,-10 z",
    "M23,52 a19,19 0 0 1 19,19 h-3 a16,16 0 0 0 -16,-16 z",
)
CAST_GLYPH_PATH = (  # apps/mobile ic_cast.xml, viewport 24: the notification and in-app glyph
    "M21,3H3c-1.1,0-2,0.9-2,2v3h2V5h18v14h-9v2h9c1.1,0,2-0.9,2-2V5c0-1.1-0.9-2-2-2z M1,18v3h3C4,19.34,2.66,18,1,18z "
    "M1,14v2c2.76,0,5,2.24,5,5h2C8,17.13,4.87,14,1,14z M1,10v2c4.97,0,9,4.03,9,9h2C12,14.92,7.07,10,1,10z"
)
DESKTOP_MARK_PATHS = (  # apps/desktop-native ui/index.html .brand-tile, viewBox 24, stroked
    "M5,5 H19 A2,2 0 0 1 21,7 V17 A2,2 0 0 1 19,19 H5 A2,2 0 0 1 3,17 V7 A2,2 0 0 1 5,5 Z",
    "M3,15 a4,4 0 0 1 4,4",
    "M3,11 a8,8 0 0 1 8,8",
)
ICON_ORANGE, ICON_BLUE, ICON_BLUE_TILE = "#F0603F", "#A8C7FA", "#3B82F6"
TILE = 96


def icon_tile(parent, kind, colour, chosen=False):
    """One candidate: the launcher tile as the phone and the Dock would show it, and what it is."""
    col = vstack(parent, f"icon · {kind} · {colour}", gap=8, cross="center")
    dark = kind != "desktop"
    tint = {"orange": PRIMARY if dark else ICON_ORANGE, "blue": ICON_BLUE if dark else ICON_BLUE_TILE}[colour]
    tile = frame(col, "tile", h="fixed", v="fixed", width=TILE, height=TILE, fill=BG if dark else tint, radius=22,
                 stroke=PRIMARY if chosen else OUTLINE, stroke_weight=2.0 if chosen else 1.0, clip=True)
    if kind == "launcher":
        for j, d in enumerate(LAUNCHER_PATHS):  # the launcher shows the centre 72 of the 108 viewport
            glyph(tile, f"path-{j}", d, TILE * 108 * 0.75 / 72, 108, fill=tint, shift=(-6.0, -6.0))
    elif kind == "glyph":
        glyph(tile, "cast", CAST_GLYPH_PATH, 56, 24, fill=tint, absolute_at=(20, 20))
    else:
        for j, d in enumerate(DESKTOP_MARK_PATHS):  # 22 px in a 40 px tile on the receiver
            glyph(tile, f"path-{j}", d, TILE * 22 / 40, 24, stroke="#FFFFFF", stroke_weight=2.0,
                  absolute_at=(TILE * 9 / 40, TILE * 9 / 40))
    mono(col, colour.upper(), 10, PRIMARY if chosen else VARIANT, "Bold", spacing=1.0, line=14)
    if chosen:
        tag(col, "PROPOSED", "amber")
    return col


NAME_W = 600
name = vstack(PAGE, "The name and the icon", gap=14, pad=(20, 20, 20, 20), h="fixed", width=NAME_W, fill=SURFACE,
              radius=18, stroke=OUTLINE)
nodes[name]["child_layout"]["absolute"] = False
text(name, "The name and the icon", 20, ON, "SemiBold", line=26)
text(name, "The name is the product owner's decision of 2026-11-11, before v0.2.0: the product is called Caster, on the phone and "
           "on the Mac, with one icon.", 13, VARIANT, width=NAME_W - 40, line=19)
names = vstack(name, "name · where it shows", gap=3, h="fill")
text(names, "Where the name shows", 13, ON, "Medium", width=NAME_W - 40, line=18)
for line in (
    "Phone: the launcher label “Caster”, the app bar's “Caster”, the notification.",
    "Mac: the app “Caster” (was “Zero-Friction Local Cast Native”), the window and tray, the nameplate “CASTER”, "
    "the video window, the Syphon source in OBS (pick it again once).",
    "Unchanged: the app's ids, the signing key and the receiver's data folder, so updates install and saved "
    "pairings stay.",
):
    text(names, "• " + line, 12.5, VARIANT, width=NAME_W - 40, line=18)
for label, kind, why in (
    ("A · the phone's launcher icon today", "launcher", "The waves run into the screen; drawn as a placeholder."),
    ("B · the phone's cast glyph (notification, app bar)", "glyph", "The stock Material glyph: every cast app's."),
    ("C · the mark beside the Mac's nameplate", "desktop", "Already the product's own mark, and reads at 16 px."),
):
    block = vstack(name, f"candidate · {kind}", gap=8, h="fill")
    text(block, label, 13, ON, "Medium", width=NAME_W - 40, line=18)
    row = hstack(block, "tiles", gap=20, cross="min")
    icon_tile(row, kind, "orange", chosen=kind == "desktop")
    icon_tile(row, kind, "blue")
    text(row, why, 12.5, VARIANT, width=NAME_W - 40 - 2 * (TILE + 20), line=18)
text(name, "Proposed: C in orange, the Mac's own tile, on both apps (launcher, Dock, disk image). The phone's "
           "notification keeps a one-colour glyph, as Android requires.", 12.5, ON, width=NAME_W - 40, line=18)

for f in (p1, p2, p3, p4, p5, questions):
    autolayout.solve(nodes, f, text_height)
nodes[rule]["transform"][4] = float(X[5] + QUESTIONS_W + PHONE_GAP)
nodes[rule]["transform"][5] = float(TOP)
autolayout.solve(nodes, rule, text_height)
nodes[name]["transform"][4] = float(X[5] + QUESTIONS_W + PHONE_GAP + RULE_W + PHONE_GAP)
nodes[name]["transform"][5] = float(TOP)
autolayout.solve(nodes, name, text_height)

# Notes sit under each phone, so they are placed once the phones' heights are known.
for f, title, lines in (
    (p1, "P1", ["The Preset | Custom tabs are gone: one list, built-in first, then your own.",
                "Every row says in words what it gives up when the cast struggles.",
                "No gear in the app bar: it only led to Advanced.",
                "The Audio card is only its switches."]),
    (p2, "P2", ["Picture, behaviour and encoder live together: a profile is the whole answer to “what will this "
                "cast do”.",
                "The bitrate is one slider with two thumbs: the least and the most. The sender never goes under "
                "the least, whatever the choice below (there was a hidden 1 Mbps floor).",
                "Behaviour keeps two settings: what WebRTC gives up under network pressure, and whether the app "
                "may lower the picture in a thermal emergency.",
                "The codec joins the H.264 profile it belongs with. No Bitrate mode: removed.",
                "How the game audio travels (Low latency / Standard) is the profile's too.",
                "The fps choices are the app's own (30 / 60)."]),
    (p3, "P3", ["Starts from the values on screen, or from any profile.",
                "The strip shows what will be copied before it is created."]),
    (p4, "P4", ["Rename is the Name field in Edit, not a separate item.",
                "Deleting the selected profile falls back to Balanced, the app's default."]),
    (p5, "P5", ["Home while casting. The encoder readout moves to Status, where the cast's other facts are.",
                "A thermal step says what was lowered and that it comes back once the phone cools. “Restore "
                "now” is for not waiting.",
                "The list under the cards is an annotation, not part of the screen."]),
):
    n = notes(PAGE, title, lines, nodes[f]["transform"][4], TOP + nodes[f]["height"] + NOTES_GAP)
    autolayout.solve(nodes, n, text_height)

doc = {"format": "figcraft", "version": 1, "imageIndex": [], "name": "mobile-custom-profiles", "pages": [PAGE],
       "nodes": {str(k): v for k, v in nodes.items()}, "next_id": next_id[0], "styles": [],
       "variables": {"collections": [], "variables": []}, "current_page": PAGE, "document_colors": [],
       "comments": []}
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("document.json", json.dumps(doc))
    z.writestr("versions/index.json", json.dumps({"versions": []}))
frames = [n for n in nodes.values() if n["type"] == "frame"]
print(sys.argv[1], len(nodes), "nodes,", len(frames), "frames,", sum(1 for n in frames if n.get("layout")), "auto layout")
