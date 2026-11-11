#!/usr/bin/env python3
"""The product's icon as a FigCraft file: Caster's mark (the screen with two
waves that sits beside the receiver's nameplate) on the orange tile.

  python3 -I tools/design/build_app_icon.py designs/app-icon.figcraft

Frames, rendered to PNG through FigCraft's control port (`ui.render`, see
README.md "The app icon"):
  - "mac icon 1024":  the Mac app icon on Apple's grid (an 824 px tile in a
                      1024 px canvas) → apps/desktop-native/src-tauri/icons/
  - "tray template":  the mark alone, black on nothing, 22 pt → the menu-bar
                      template image
The phone's launcher icon is the same mark as a vector drawable
(ic_launcher_foreground.xml) on the same orange.
"""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from spec_dsl import PAGE_BG, frame, glyph, hexc, next_id, nid, nodes  # noqa: E402

ICON_ORANGE = "#F0603F"
MARK_PATHS = (  # viewBox 24, stroke 2, round caps — apps/desktop-native/ui/index.html .brand-tile
    "M5,5 H19 A2,2 0 0 1 21,7 V17 A2,2 0 0 1 19,19 H5 A2,2 0 0 1 3,17 V7 A2,2 0 0 1 5,5 Z",
    "M3,15 a4,4 0 0 1 4,4",
    "M3,11 a8,8 0 0 1 8,8",
)
MAC_CANVAS, MAC_TILE, MAC_RADIUS = 1024, 824, 185
MARK_SHARE = 22 / 40  # the mark is 22 px in the receiver's 40 px tile
TRAY = 22

PAGE = nid()
nodes[PAGE] = {"id": PAGE, "name": "App icon", "type": "page", "background": hexc(PAGE_BG), "flows": [],
               "prototype": {"device": "None", "background": hexc("#1E1E1E"), "rotation": "portrait"},
               "children": [], "width": 0.0, "height": 0.0}


def mark(parent, size, at, colour):
    for j, d in enumerate(MARK_PATHS):
        glyph(parent, f"mark-{j}", d, size, 24, stroke=colour, stroke_weight=2.0, absolute_at=at)


mac = frame(PAGE, "mac icon 1024", h="fixed", v="fixed", width=MAC_CANVAS, height=MAC_CANVAS, absolute_at=(0, 0))
inset = (MAC_CANVAS - MAC_TILE) / 2
tile = frame(mac, "tile", h="fixed", v="fixed", width=MAC_TILE, height=MAC_TILE, fill=ICON_ORANGE,
             radius=MAC_RADIUS, absolute_at=(inset, inset))
mark_size = MAC_TILE * MARK_SHARE
mark(tile, mark_size, ((MAC_TILE - mark_size) / 2, (MAC_TILE - mark_size) / 2), "#FFFFFF")

tray = frame(PAGE, "tray template", h="fixed", v="fixed", width=TRAY, height=TRAY, absolute_at=(MAC_CANVAS + 80, 0))
mark(tray, TRAY - 2, (1, 1), "#000000")

doc = {"format": "figcraft", "version": 1, "imageIndex": [], "name": "app-icon", "pages": [PAGE],
       "nodes": {str(k): v for k, v in nodes.items()}, "next_id": next_id[0], "styles": [],
       "variables": {"collections": [], "variables": []}, "current_page": PAGE, "document_colors": [],
       "comments": []}
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("document.json", json.dumps(doc))
    z.writestr("versions/index.json", json.dumps({"versions": []}))
print(sys.argv[1], {n["name"]: n["id"] for n in nodes.values() if n["type"] == "frame" and n["parent"] == PAGE})
