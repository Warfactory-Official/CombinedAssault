#!/usr/bin/env python3
"""Paint layers for GemRender glTF displays.

    python3 tools/paint_layers.py mask <texture.png> [#RRGGBB[,#RRGGBB...]]   (from combinedassault/)
    python3 tools/paint_layers.py solid <texture.png> x0,y0,x1,y1 ...
    python3 tools/paint_layers.py patterns

mask: <texture>_paint.png beside the base colour. alpha = coverage (chroma within reach of the paint
colour, fading out below LUM_FLOOR), rgb = paint colour (default: most common lit texel; several => each texel its nearest, e.g. two-tone
schemes). Painted texel = base * paint / reference (GemRender: coverage-weighted mean rgb).
solid: adds rectangles (texels, x1/y1 exclusive) to <texture>_paint_solid.png: white = paint's mean colour
instead of its pattern (placards, small panels), shading kept. Inside a rectangle coverage ignores chroma
(luminance ramp only): an off-tint placard is paint too. Hand-painting the files works too.
patterns: tileable camo sheets into textures/paint/ (periodic noise via FFT, so edges wrap).
"""
import os
import sys

import numpy as np
from PIL import Image

ASSETS = "src/main/resources/combinedassault_packs/combinedassault/assets/combinedassault"
SIZE = 512

# Chroma distance (rgb / sum, L2): full coverage below NEAR, none past FAR.
NEAR, FAR = 0.018, 0.04
# Luminance ramp: black rubber, text, vents => unpainted.
LUM_FLOOR, LUM_FULL = 0.12, 0.22


def smooth(x, lo, hi):
    t = np.clip((x - lo) / (hi - lo), 0, 1)
    return t * t * (3 - 2 * t)


def dominant(rgb, alpha):
    """Most common lit colour, 8-level bins."""
    px = rgb[alpha > 0.5]
    lum = px.mean(axis=1)
    px = px[lum > np.percentile(lum, 50)]
    bins = (px * 255).astype(int) // 8
    keys, counts = np.unique(bins[:, 0] * 1024 + bins[:, 1] * 32 + bins[:, 2], return_counts=True)
    k = keys[np.argmax(counts)]
    return np.array([k // 1024, k // 32 % 32, k % 32]) * 8 + 4


def mask(path, colour=None):
    src = np.asarray(Image.open(path).convert("RGBA"), float) / 255
    rgb, alpha = src[..., :3], src[..., 3]
    paints = ([np.array([int(c.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4)]) for c in colour.split(",")] if colour
              else [dominant(rgb, alpha)])
    chroma = rgb / np.maximum(rgb.sum(axis=2, keepdims=True), 1e-6)
    distance = np.stack([np.linalg.norm(chroma - p / p.sum(), axis=2) for p in paints])
    nearest = distance.argmin(axis=0)
    coverage = ((1 - smooth(distance.min(axis=0), NEAR, FAR)) * smooth(rgb.mean(axis=2), LUM_FLOOR, LUM_FULL)
                * (alpha > 0.5))
    out = np.zeros(src.shape, np.uint8)
    out[..., :3] = np.stack(paints)[nearest]
    out[..., 3] = (coverage * 255 + 0.5).astype(np.uint8)
    target_path = path[:-4] + "_paint.png"
    Image.fromarray(out, "RGBA").save(target_path, optimize=True)
    names = ",".join(f"#{p[0]:02X}{p[1]:02X}{p[2]:02X}" for p in paints)
    print(f"{target_path}: paint {names}, coverage {coverage.mean():.3f}")


def solid(path, rects):
    target = path[:-4] + "_paint_solid.png"
    size = Image.open(path).size
    out = np.asarray(Image.open(target).convert("L")).copy() if os.path.exists(target) else np.zeros(size[::-1], np.uint8)
    src = np.asarray(Image.open(path).convert("RGBA"), float) / 255
    paint_path = path[:-4] + "_paint.png"
    paint = np.asarray(Image.open(paint_path).convert("RGBA")).copy()
    for rect in rects:
        x0, y0, x1, y1 = (int(v) for v in rect.split(","))
        out[y0:y1, x0:x1] = 255
        area = src[y0:y1, x0:x1]
        coverage = smooth(area[..., :3].mean(axis=2), LUM_FLOOR, LUM_FULL) * (area[..., 3] > 0.5)
        paint[y0:y1, x0:x1, 3] = (coverage * 255 + 0.5).astype(np.uint8)
    Image.fromarray(out, "L").save(target, optimize=True)
    Image.fromarray(paint, "RGBA").save(paint_path, optimize=True)
    print(f"{target}: {(out > 127).mean():.4f} solid")


def periodic_noise(rng, scale):
    """Tileable noise, 0..1: white noise low-passed in frequency space (wraps by construction)."""
    freq = np.fft.fftfreq(SIZE)
    radius = np.hypot(*np.meshgrid(freq, freq))
    spectrum = np.fft.fft2(rng.standard_normal((SIZE, SIZE))) * np.exp(-(radius * scale) ** 2)
    field = np.real(np.fft.ifft2(spectrum))
    return (field - field.min()) / (field.max() - field.min())


def blobs(seed, colours, scale, warp):
    """Layered threshold camo: each colour over the previous where its noise field is high."""
    rng = np.random.default_rng(seed)
    out = np.empty((SIZE, SIZE, 3))
    out[:] = colours[0][1]
    for share, colour in colours[1:]:
        field = periodic_noise(rng, scale) + warp * (periodic_noise(rng, scale / 4) - 0.5)
        out[field > np.quantile(field, 1 - share)] = colour
    return out


def hexrgb(value):
    return np.array([int(value[i:i + 2], 16) for i in (1, 3, 5)], float)


PATTERNS = {
    # (share of the sheet, colour); first entry = ground.
    "woodland": (81, 40.0, 0.35, [(1.0, "#6F7B4B"), (0.40, "#5E4A34"), (0.22, "#9E8C66"), (0.14, "#1E1E1C")]),
    "winter": (17, 48.0, 0.30, [(1.0, "#E8E8E4"), (0.35, "#B8B9B5"), (0.18, "#6E7070")]),
    "urban": (23, 36.0, 0.40, [(1.0, "#8E8F8C"), (0.38, "#5C5E5C"), (0.22, "#C4C4BE"), (0.12, "#2A2B2A")]),
}


def patterns():
    target = f"{ASSETS}/textures/paint"
    os.makedirs(target, exist_ok=True)
    for name, (seed, scale, warp, colours) in PATTERNS.items():
        sheet = blobs(seed, [(share, hexrgb(c)) for share, c in colours], scale, warp)
        Image.fromarray(sheet.astype(np.uint8), "RGB").save(f"{target}/{name}.png", optimize=True)
        print(f"{target}/{name}.png")


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    if sys.argv[1:2] == ["mask"]:
        mask(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    elif sys.argv[1:2] == ["solid"]:
        solid(sys.argv[2], sys.argv[3:])
    elif sys.argv[1:2] == ["patterns"]:
        patterns()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
