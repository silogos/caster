#!/usr/bin/env python3
"""Generate the cast-glyph template tray icon (ADR-010).

Draws the classic cast mark on a transparent RGBA canvas: a rounded-rect
screen outline, a bottom-left dot, and two quarter arcs emanating from it.
All pixels are black with anti-aliased alpha — a macOS template image
(setTemplateImage) that follows the menu bar's light/dark mode. 4x
supersampling for clean edges; written as a minimal valid PNG (filter 0,
zlib, CRC-correct chunks — the first embedded base64 was corrupt: a valid
PNG header over a broken IDAT, which nativeImage loaded as empty, making
the tray icon invisible).

Usage: python3 gen-tray-icon.py [outdir]   # writes tray-icon-22.png / -44.png
       and prints the base64 to paste into src/main/tray/tray.ts.
"""
import math
import struct
import sys
import zlib


def hit(x: float, y: float) -> bool:
    """Geometry in the 44-unit design grid — the Google-Cast mark: a complete
    rounded-rect screen, dot bottom-left, two quarter arcs sweeping up-right
    to touch the screen's bottom-left corner."""
    cx, cy = 22.0, 17.0
    half_w, half_h, rad = 12.0, 7.0, 3.5
    stroke = 3.2
    qx = abs(x - cx) - (half_w - rad)
    qy = abs(y - cy) - (half_h - rad)
    dist = math.hypot(max(qx, 0.0), max(qy, 0.0)) - rad
    if abs(dist) <= stroke / 2:
        return True
    ax, ay = 13.0, 33.0
    dx, dy = x - ax, y - ay
    d = math.hypot(dx, dy)
    if d <= 2.6:
        return True
    if dx >= 0 and dy <= 0:
        arc_stroke = 2.8
        for r in (7.0, 14.0):
            if abs(d - r) <= arc_stroke / 2:
                return True
    return False


def render(size: int) -> list[list[int]]:
    ss = 4  # supersample factor
    alpha = [[0] * size for _ in range(size)]
    scale = (size * ss) / 44.0
    for py in range(size * ss):
        y = (py + 0.5) / scale
        row = alpha[py // ss]
        for pxx in range(size * ss):
            if hit((pxx + 0.5) / scale, y):
                row[pxx // ss] += 1
    for y in range(size):
        for x in range(size):
            alpha[y][x] = round(255 * alpha[y][x] / (ss * ss))
    return alpha


def write_png(path: str, alpha: list[list[int]]) -> None:
    size = len(alpha)
    # Filter 0 per scanline; every pixel is black with the coverage as alpha.
    raw = b''.join(b'\x00' + b''.join(bytes((0, 0, 0, a)) for a in row) for row in alpha)
    def chunk(tag: bytes, body: bytes) -> bytes:
        return struct.pack('>I', len(body)) + tag + body + struct.pack('>I', zlib.crc32(tag + body) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


def b64(path: str) -> str:
    import base64
    with open(path, 'rb') as f:
        return base64.b64encode(f.read()).decode()


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else '/tmp'
    for size in (22, 44):
        p = f'{out}/tray-icon-{size}.png'
        write_png(p, render(size))
        print(f'{p} written, base64:\n{b64(p)}')
