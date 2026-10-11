#!/usr/bin/env python3
"""Derive <tex>_damaged.png and <tex>_wrecked.png from each glTF display's base-colour texture.

    python3 tools/damage_textures.py [texture.png ...]      (from combinedassault/; default: every base colour below)

GemRenderModelLoader picks them up by name as variants 1 and 2; hand-painted files of the same name replace these.
Texture space: soot and scorch ignore UV islands (seams accepted). Alpha kept (MASK cut-outs).
<tex>_paint.png present => also <tex>_damaged_paint.png (coverage stripped by soot, scorch, scratches) and
<tex>_wrecked_paint.png (WRECK_PAINT of it left); <tex>_paint_solid.png copied beside each.
"""
import glob
import os
import shutil
import sys
import zlib

import numpy as np
from PIL import Image

PACK = "src/main/resources/combinedassault_packs/combinedassault/assets/combinedassault/textures"
DIRS = ("aircraft", "ground", "emplacement")
SKIP = ("_orm", "_normal", "_damaged", "_wrecked", "_paint", "_solid")
WRECK_PAINT = 0.1


def noise(shape, cell, rng):
    """Value noise: a random grid of `cell` pixels upscaled bicubic, 0..1."""
    h, w = shape
    grid = rng.random((max(2, h // cell + 2), max(2, w // cell + 2)))
    img = Image.fromarray((grid * 255).astype(np.uint8)).resize((w + 2 * cell, h + 2 * cell), Image.BICUBIC)
    return np.asarray(img, float)[cell:cell + h, cell:cell + w] / 255


def fractal(shape, rng, base):
    out = np.zeros(shape)
    amp, total = 1.0, 0.0
    for octave in range(4):
        out += amp * noise(shape, max(2, base >> octave), rng)
        total += amp
        amp *= 0.5
    return out / total


def smooth(x, lo, hi):
    t = np.clip((x - lo) / (hi - lo), 0, 1)
    return t * t * (3 - 2 * t)


def scratches(shape, rng, count):
    """Thin light streaks (bare metal)."""
    h, w = shape
    mask = Image.new("L", (w, h), 0)
    from PIL import ImageDraw
    draw = ImageDraw.Draw(mask)
    for _ in range(count):
        x, y = rng.random() * w, rng.random() * h
        angle = rng.random() * np.pi
        length = (0.02 + rng.random() * 0.06) * w
        draw.line([(x, y), (x + np.cos(angle) * length, y + np.sin(angle) * length)],
                  fill=int(120 + rng.random() * 135), width=max(1, w // 512))
    return np.asarray(mask, float) / 255


def derive(path):
    src = Image.open(path)
    alpha = src.getchannel("A") if src.mode == "RGBA" else None
    rgb = np.asarray(src.convert("RGB"), float) / 255
    shape = rgb.shape[:2]
    rng = np.random.default_rng(zlib.crc32(os.path.basename(path).encode()))
    base = max(8, shape[1] // 16)
    grey = rgb.mean(axis=2, keepdims=True)

    soot = smooth(fractal(shape, rng, base), 0.55, 0.8)[..., None]
    scorch = smooth(fractal(shape, rng, base * 2), 0.62, 0.72)[..., None]
    streak = scratches(shape, rng, 60 * max(1, shape[1] // 512))[..., None]
    damaged = rgb * (1 - 0.55 * soot)
    damaged = damaged * (1 - 0.35 * scorch) + np.array([0.12, 0.07, 0.04]) * 0.35 * scorch
    damaged = damaged * (1 - streak) + (grey * 0.3 + 0.55) * streak

    char = fractal(shape, rng, base)[..., None]
    rust = np.array([0.30, 0.16, 0.08])
    wrecked = (grey * 0.35 + rgb * 0.05) * (0.45 + 0.35 * char)
    wrecked = wrecked * (1 - 0.4 * scorch) + rust * 0.4 * scorch
    wrecked *= 1 - 0.5 * soot

    stem = path[:-4]
    for suffix, data in (("_damaged", damaged), ("_wrecked", wrecked)):
        out = Image.fromarray((np.clip(data, 0, 1) * 255 + 0.5).astype(np.uint8), "RGB")
        if alpha is not None:
            out.putalpha(alpha)
        out.save(stem + suffix + ".png", optimize=True)
        print(stem + suffix + ".png")

    if os.path.exists(stem + "_paint.png"):
        paint = np.asarray(Image.open(stem + "_paint.png").convert("RGBA"), float) / 255
        coverage = paint[..., 3]
        stripped = coverage * (1 - 0.8 * soot[..., 0]) * (1 - scorch[..., 0]) * (1 - streak[..., 0])
        for suffix, left in (("_damaged_paint", stripped), ("_wrecked_paint", coverage * WRECK_PAINT)):
            out = paint.copy()
            out[..., 3] = left
            Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGBA").save(stem + suffix + ".png", optimize=True)
            print(stem + suffix + ".png")
            if os.path.exists(stem + "_paint_solid.png"):
                shutil.copyfile(stem + "_paint_solid.png", stem + suffix + "_solid.png")


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    files = sys.argv[1:] or [f for d in DIRS for f in sorted(glob.glob(f"{PACK}/{d}/**/*.png", recursive=True))
                             if not any(s in os.path.basename(f) for s in SKIP)]
    for f in files:
        derive(f)


if __name__ == "__main__":
    main()
