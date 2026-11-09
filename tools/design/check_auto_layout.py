"""Re-runs a FigCraft file's auto layout from its layout parameters alone and
compares the result with the positions stored in the file.

FigCraft keeps stored positions until something is edited, so opening a file
does not prove its auto-layout parameters are right; this does: for
build_mobile_figcraft.py output the stored positions are the ones Compose
measured, so a clean run means editing the design in FigCraft keeps the app's
layout. Text heights are taken as stored (no font metrics here).

Usage: python3 -I tools/design/check_auto_layout.py FILE.figcraft [--page N] [--tolerance DP]
Exit status 1 when any layer moves by more than the tolerance.
"""
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import autolayout  # noqa: E402

doc = json.loads(zipfile.ZipFile(sys.argv[1]).read("document.json"))
N = {int(k): v for k, v in doc["nodes"].items()}
PAGE = doc["pages"][int(sys.argv[sys.argv.index("--page") + 1]) if "--page" in sys.argv else 0]
TOL = float(sys.argv[sys.argv.index("--tolerance") + 1]) if "--tolerance" in sys.argv else 1.5


def stored_abs(i):
    x = y = 0.0
    while N[i]["type"] != "page":
        x += N[i]["transform"][4]
        y += N[i]["transform"][5]
        i = N[i]["parent"]
    return x, y


bad, checked = {}, 0
engine = autolayout.Engine(N)
for fid in N[PAGE].get("children", []):
    f = N[fid]
    if f["type"] != "frame":
        continue
    engine.layout(f, f["transform"][4], f["transform"][5], f["width"], f["height"])
    stack = [f]
    while stack:
        n = stack.pop()
        if n["type"] in ("text", "vector", "rectangle", "ellipse") and n["id"] in engine.placed:
            checked += 1
            cx, cy, _, _ = engine.placed[n["id"]]
            sx, sy = stored_abs(n["id"])
            if abs(cx - sx) > TOL or abs(cy - sy) > TOL:
                bad.setdefault(f["name"], []).append((n["name"][:28], round(cx - sx, 1), round(cy - sy, 1)))
        stack.extend(engine.kids(n))

print(f"{checked} layers re-laid out; {sum(len(v) for v in bad.values())} moved by more than {TOL}dp")
for name, v in bad.items():
    print(f"  {name}: {len(v)}  {v[:6]}")
sys.exit(1 if bad else 0)
