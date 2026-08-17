# -*- coding: utf-8 -*-
"""Generate TimeRecorder launcher icon PNGs (legacy, for API 24-25).

Design matches drawable/ic_launcher_foreground.xml:
  brand-blue rounded-square background,
  4-quadrant dial (orange/green/blue/purple) + white hands at 10:10.
Pure stdlib (zlib/struct), no PIL needed.
Run:  python tools/gen_icon.py
"""
import math
import os
import struct
import zlib

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RES_DIR = os.path.normpath(os.path.join(BASE_DIR, "..", "app", "src", "main", "res"))

BG = (0x3D, 0x5A, 0xFE)
ORANGE = (0xFF, 0x70, 0x43)
GREEN = (0x66, 0xBB, 0x6A)
BLUE = (0x29, 0xB6, 0xF6)
PURPLE = (0xAB, 0x47, 0xBC)
WHITE = (255, 255, 255)


def dist_point_seg(px_, py_, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px_ - ax, py_ - ay
    c1 = vx * wx + vy * wy
    if c1 <= 0:
        return math.hypot(px_ - ax, py_ - ay)
    c2 = vx * vx + vy * vy
    if c2 <= c1:
        return math.hypot(px_ - bx, py_ - by)
    t = c1 / c2
    return math.hypot(px_ - (ax + t * vx), py_ - (ay + t * vy))


def render(size):
    cx = cy = size / 2.0
    r_dial = size * 0.42
    r_corner = size * 0.20
    half_min = size * 0.028
    half_hour = size * 0.042
    r_center = size * 0.055

    # hand endpoints: minute at 2 o'clock (60 deg), hour at 10 o'clock (-60 deg)
    rad_min = math.radians(60)
    rad_hour = math.radians(-60)
    mx2 = cx + r_dial * math.sin(rad_min)
    my2 = cy - r_dial * math.cos(rad_min)
    hx2 = cx + r_dial * math.sin(rad_hour)
    hy2 = cy - r_dial * math.cos(rad_hour)

    buf = bytearray(size * size * 4)

    def set_px(col, row, rgb):
        i = (row * size + col) * 4
        buf[i] = rgb[0]
        buf[i + 1] = rgb[1]
        buf[i + 2] = rgb[2]
        buf[i + 3] = 255

    def in_rounded_rect(x, y):
        left = top = r_corner
        right = bot = size - r_corner
        if left <= x <= right or top <= y <= bot:
            return True
        ccx = left if x < left else right
        ccy = top if y < top else bot
        return (x - ccx) ** 2 + (y - ccy) ** 2 <= r_corner ** 2

    for row in range(size):
        y = row + 0.5
        for col in range(size):
            x = col + 0.5
            if not in_rounded_rect(x, y):
                continue
            dx = x - cx
            dy = y - cy
            d = math.hypot(dx, dy)
            if d <= r_dial:
                ang = math.degrees(math.atan2(dx, -dy)) % 360.0
                if ang < 90:
                    color = ORANGE     # top-right
                elif ang < 180:
                    color = GREEN      # bottom-right
                elif ang < 270:
                    color = BLUE       # bottom-left
                else:
                    color = PURPLE     # top-left
                if (dist_point_seg(x, y, cx, cy, hx2, hy2) <= half_hour
                        or dist_point_seg(x, y, cx, cy, mx2, my2) <= half_min
                        or d <= r_center):
                    color = WHITE
            else:
                color = BG
            set_px(col, row, color)

    raw = bytearray()
    for row in range(size):
        raw.append(0)  # filter: None
        raw.extend(buf[row * size * 4:(row + 1) * size * 4])

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff))

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


def main():
    for folder, size in SIZES.items():
        path = os.path.join(RES_DIR, folder, "ic_launcher.png")
        with open(path, "wb") as f:
            f.write(render(size))
        print("wrote", path, size, "px")


if __name__ == "__main__":
    main()
