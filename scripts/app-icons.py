#!/usr/bin/env python3
"""Generates the Controller's logo and app icons from the MSST-Net app icon.

    scripts/app-icons.py path/to/msst_net_icon.png

The source is the MSST-Net 3.1.x app icon artwork: a rounded blue square on a
pale backdrop (square canvas, any size).  The rounded square is cut out onto
transparency for the desktop, tray, web and in-app icons; Android gets a
full-bleed adaptive foreground.  Writes, under compose/: the in-app logo
(Compose resource), the desktop installer icons (ICO, ICNS, PNG), the tray
icon, the web favicon and the Android launcher icons.  Needs Pillow (with
libimagequant and WebP) and NumPy.
"""

import io
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

if len(sys.argv) != 2:
    sys.exit(__doc__)
src = sys.argv[1]
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "compose")
art = Image.open(src).convert("RGB")
a = np.asarray(art).astype(int)
H, W = a.shape[:2]


def edge(line):
    """Index of the sharpest brightness change in the outer fifth of `line` from each end."""
    d = np.abs(np.diff(line.sum(axis=1)))
    k = len(line) // 5
    return int(np.argmax(d[:k])) + 1, len(line) - k + int(np.argmax(d[-k:]))


# The artwork is a rounded square on a pale backdrop: find its edges and
# corner radius, then cut it out with an anti-aliased mask.
# (The bottom edge is soft behind the waves, so take the size from the sides.)
left, right = edge(a[H // 2])
top, _ = edge(a[:, W // 2])
side = right - left
diag = np.array([a[top + i, left + i] for i in range(side // 3)])
# Corner arc: the first sharp change walking in from the corner along the diagonal.
inset = int(np.argmax(np.abs(np.diff(diag.sum(axis=1))) > 40)) + 1
radius = inset / (1 - 2 ** -0.5)
shave = 3  # skip the soft glow along the edge
box = (left + shave, top + shave, left + side - shave, top + side - shave)
S = box[2] - box[0]
R = radius - shave
ss = 4
mask = Image.new("L", (S * ss, S * ss), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, S * ss - 1, S * ss - 1), radius=R * ss, fill=255)
mask = mask.resize((S, S), Image.Resampling.LANCZOS)
square = art.crop(box)
logo = square.convert("RGBA")
logo.putalpha(mask)  # the icon on transparency


def full_bleed(scale):
    """The square at `scale` of a canvas, rounded corners and the margin filled
    by extending the square's interior outward (Android's mask picks the shape)."""
    n = int(round(S / scale))
    m = int(np.ceil(R * (1 - 2 ** -0.5))) + 4  # interior crop clear of the corners
    core = np.asarray(square)[m:S - m, m:S - m]
    o = (n - S) // 2 + m
    pad = ((o, n - o - core.shape[0]), (o, n - o - core.shape[1]), (0, 0))
    canvas = Image.fromarray(np.pad(core, pad, mode="edge"))
    # Smear the replicated edges so they read as a soft continuation, not stripes.
    canvas = canvas.filter(ImageFilter.GaussianBlur(n * 0.04)).convert("RGBA")
    canvas.alpha_composite(logo, ((n - S) // 2, (n - S) // 2))
    return canvas.convert("RGB")


def out(rel):
    p = os.path.join(root, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    return p


def png(img, rel, colors=256):
    img.quantize(colors, method=Image.Quantize.LIBIMAGEQUANT, dither=Image.Dither.FLOYDSTEINBERG) \
        .save(out(rel), optimize=True)


def png_bytes(img):
    buf = io.BytesIO()
    img.quantize(256, method=Image.Quantize.LIBIMAGEQUANT, dither=Image.Dither.FLOYDSTEINBERG) \
        .save(buf, "PNG", optimize=True)
    return buf.getvalue()


def write_ico(path, pngs):
    # PNG-compressed ICO entries (Vista+), quantized to keep the file small.
    head = struct.pack("<HHH", 0, 1, len(pngs))
    offset = 6 + 16 * len(pngs)
    dirs, data = b"", b""
    for n, b in pngs.items():
        dirs += struct.pack("<BBBBHHII", n % 256, n % 256, 0, 0, 1, 32, len(b), offset + len(data))
        data += b
    open(path, "wb").write(head + dirs + data)


def write_icns(path, pngs):
    # Types holding PNG data, by pixel size (1x and @2x variants share bytes).
    types = {16: [b"icp4"], 32: [b"icp5", b"ic11"], 64: [b"icp6", b"ic12"],
             128: [b"ic07"], 256: [b"ic08"], 512: [b"ic09"]}
    body = b"".join(t + struct.pack(">I", 8 + len(b)) + b for n, b in pngs.items() for t in types[n])
    open(path, "wb").write(b"icns" + struct.pack(">I", 8 + len(body)) + body)


def webp(img, rel, q=88):
    img.save(out(rel), "WEBP", quality=q, method=6)


def sized(img, n):
    return img.resize((n, n), Image.Resampling.LANCZOS)


# In-app logo, window and tray icon (Compose resource, all targets).
webp(sized(logo, 256), "shared/src/commonMain/composeResources/drawable/msst_logo.webp")

# Desktop installers (jpackage): Linux PNG, Windows ICO, macOS ICNS.
png(sized(logo, 512), "desktopApp/icons/msst-net.png")
write_ico(out("desktopApp/icons/msst-net.ico"), {n: png_bytes(sized(logo, n)) for n in (16, 24, 32, 48, 64, 128, 256)})
# macOS icon grid: the shape takes 824 of 1024, centred.
mac = Image.new("RGBA", (S * 1024 // 824,) * 2, (0, 0, 0, 0))
mac.alpha_composite(logo, ((mac.width - S) // 2, (mac.height - S) // 2))
write_icns(out("desktopApp/icons/msst-net.icns"), {n: png_bytes(sized(mac, n)) for n in (16, 32, 64, 128, 256, 512)})

# Tray icon (both the native Linux tray and the JVM tray load it).
png(sized(logo, 64), "desktopApp/src/main/resources/tray-icon.png")

# Web favicon.
png(sized(logo, 64), "webApp/src/wasmJsMain/resources/favicon.png")

# Android. Adaptive foreground: 108dp layer whose 66dp visible circle must
# hold the text and badge, so the square takes 64 % of the layer.
fg = full_bleed(0.64)
ldpi = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for name, k in ldpi.items():
    d = f"androidApp/src/main/res/mipmap-{name}/"
    webp(sized(fg, int(108 * k)), d + "ic_launcher_foreground.webp")
    # Legacy icons (unused at minSdk 26, kept so the set stays consistent).
    n = int(48 * k)
    webp(sized(logo, n), d + "ic_launcher.webp")
    rnd = sized(fg.crop((int(fg.width * 0.18), int(fg.width * 0.18), int(fg.width * 0.82), int(fg.width * 0.82))), n * 4)
    mask = Image.new("L", rnd.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, rnd.width - 1, rnd.height - 1), fill=255)
    rnd.putalpha(mask)
    webp(rnd.resize((n, n), Image.Resampling.LANCZOS), d + "ic_launcher_round.webp")
# Play Store: opaque square, the store applies its own mask.
c = fg.crop((int(fg.width * 0.18), int(fg.width * 0.18), int(fg.width * 0.82), int(fg.width * 0.82)))
png(sized(c, 512), "androidApp/src/main/ic_launcher-playstore.png")
