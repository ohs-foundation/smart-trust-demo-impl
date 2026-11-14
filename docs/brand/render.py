"""Regenerate every icon from mark.svg: `python3 docs/brand/render.py` (needs Inkscape 1.x on PATH)."""

import re
import struct
import subprocess
from pathlib import Path

BRAND = Path(__file__).resolve().parent
ROOT = BRAND.parents[1]
TEAL, AMBER = "#0f766e", "#b45309"
SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">{}</svg>\n'
FOREGROUND = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:scaleX="1.125"
        android:scaleY="1.125"
        android:translateX="27"
        android:translateY="27">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="{}" />
    </group>
</vector>
"""


def mark():
    text = (BRAND / "mark.svg").read_text()
    return "".join(re.search(f'id="{half}"[^>]* d="([^"]+)"', text)[1] for half in ("upper", "lower"))


def badge(colour, d):
    # The white disc keeps the seam white on dark tabs and docks.
    return SVG.format(f'<circle cx="24" cy="24" r="21" fill="#fff"/><path fill="{colour}" d="{d}"/>')


def tile(colour, d, radius):
    shape = f'<rect width="48" height="48" rx="{radius}" fill="{colour}"/>'
    return SVG.format(f'{shape}<path fill="#fff" transform="translate(9 9) scale(.625)" d="{d}"/>')


def png(svg, size):
    command = ["inkscape", "--pipe", "--export-type=png", "--export-filename=-", f"--export-width={size}", f"--export-height={size}"]
    return subprocess.run(command, input=svg.encode(), capture_output=True, check=True).stdout


def ico(images):
    entries, data, offset = b"", b"", 6 + 16 * len(images)
    for size, image in images:
        entries += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(image), offset + len(data))
        data += image
    return struct.pack("<HHH", 0, 1, len(images)) + entries + data


def icns(images):
    body = b"".join(kind + struct.pack(">I", 8 + len(image)) + image for kind, image in images)
    return b"icns" + struct.pack(">I", 8 + len(body)) + body


def main():
    d = mark()
    app = png(tile(TEAL, d, 10.5), 512)
    outputs = {
        "apps/onesa/shared/src/androidMain/res/drawable/ic_launcher_foreground.xml": FOREGROUND.format(d),
        "apps/onesa/shared/src/commonMain/composeResources/drawable/app_logo.png": app,
        "apps/onesa/shared/desktop-icons/app-icon.png": app,
        "apps/onesa/shared/desktop-icons/app-icon.ico": ico(
            [(16, png(badge(TEAL, d), 16)), (32, png(badge(TEAL, d), 32))]
            + [(size, png(tile(TEAL, d, 10.5), size)) for size in (48, 256)]
        ),
        "apps/onesa/shared/desktop-icons/app-icon.icns": icns(
            [(kind, png(tile(TEAL, d, 10.5), size)) for kind, size in ((b"ic07", 128), (b"ic08", 256), (b"ic09", 512), (b"ic10", 1024))]
        ),
        "apps/taifafeki-client/public/favicon.svg": badge(AMBER, d),
        "apps/taifafeki-client/public/favicon.ico": ico([(size, png(badge(AMBER, d), size)) for size in (16, 32, 48)]),
        "apps/taifafeki-client/public/apple-touch-icon.png": png(tile(AMBER, d, 0), 180),
        "stack/onesa/keycloak/themes/onesa/login/resources/img/favicon.svg": badge(TEAL, d),
    }
    for name, content in outputs.items():
        path = ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, str):
            path.write_text(content)
        else:
            path.write_bytes(content)
        print(name)


if __name__ == "__main__":
    main()
