#!/usr/bin/env python3
"""Generates the Controller's logo and app icons from the MSST logo.

    scripts/app-icons.py path/to/msst-logo.png

The source is the square MSST logo (1024 x 1024, radial-gradient background).
Writes, under compose/: the in-app logo (Compose resource), the desktop
installer icons (ICO, ICNS, PNG), the web favicon and the Android launcher
icons.  Needs Pillow (with libimagequant and WebP) and NumPy.
"""

import io
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw

if len(sys.argv) != 2:
    sys.exit(__doc__)
src = sys.argv[1]
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "compose")
logo = Image.open(src).convert("RGB")
W = logo.width  # 1024
a = np.asarray(logo).astype(float)
c = W // 2

# Background is a radial gradient: sample it where no text sits
# (horizontal centre row up to r=512, then the diagonal to the corner).
radii = np.arange(0, int(c * 1.4143) + 1)
prof = np.zeros((len(radii), 3))
for r in radii:
    if r <= c - 1:
        prof[r] = a[c, c - 1 - r] if r else a[c, c]
    else:
        d = min(int(r / 1.41421), c - 1)
        prof[r] = a[c - 1 - d, c - 1 - d]


def extended(scale):
    """The logo at `scale` of a square canvas, the gradient continued outward."""
    n = int(round(W / scale))
    yy, xx = np.mgrid[0:n, 0:n]
    r = np.hypot(xx - n / 2, yy - n / 2).astype(int)
    img = np.where(r[..., None] < len(prof), prof[np.minimum(r, len(prof) - 1)], 255.0)
    canvas = Image.fromarray(img.astype(np.uint8))
    canvas.paste(logo, ((n - W) // 2, (n - W) // 2))
    return canvas


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
write_icns(out("desktopApp/icons/msst-net.icns"), {n: png_bytes(sized(logo, n)) for n in (16, 32, 64, 128, 256, 512)})

# Web favicon.
png(sized(logo, 64), "webApp/src/wasmJsMain/resources/favicon.png")

# Android. Adaptive foreground: 108dp layer whose 66dp visible circle must
# hold the text, so the logo takes 64 % of the layer.
fg = extended(0.64)
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
png(sized(logo, 512), "androidApp/src/main/ic_launcher-playstore.png")
