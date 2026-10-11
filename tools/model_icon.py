#!/usr/bin/env python3
"""Render a staged glTF to a slot icon, offline.

A vehicle's inventory picture is the one asset a pack cannot do without and the one there is no
in-game way to author. This is a small orthographic rasteriser over the staged model itself: the same
triangles the game will draw, from a three-quarter view, textured from the model's own base colour
maps (each primitive its own material's, times its factor) and shaded by a single headlight. Drawing the staged file rather than the source is deliberate;
an icon that comes out mirrored or nose-down is the staging being wrong, and this is the cheapest
place to find that out.

  python3 tools/model_icon.py m983 mim104
"""
import base64
import glob
import json
import os
import sys

import numpy as np
from PIL import Image

PACK = "src/main/resources/combinedassault_packs/combinedassault"
NS = "combinedassault"
SIZE = 256
SUPERSAMPLE = 3
# Looking down and along, from the vehicle's port bow: the angle a slot icon reads best at.
YAW = 145.0
PITCH = 22.0
EXPOSURE_FLOOR = 0.3
COMPONENT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def resource(location):
    return os.path.join(PACK, "assets", location.split(":")[0], location.split(":")[1])


def accessor(doc, blob, index):
    acc = doc["accessors"][index]
    bv = doc["bufferViews"][acc["bufferView"]]
    dtype = COMPONENT[acc["componentType"]]
    width = WIDTH[acc["type"]]
    start = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = bv.get("byteStride") or width * np.dtype(dtype).itemsize
    raw = np.frombuffer(blob, dtype=np.uint8, count=stride * acc["count"], offset=start)
    rows = raw.reshape(acc["count"], stride)[:, :width * np.dtype(dtype).itemsize]
    return np.ascontiguousarray(rows).view(dtype).reshape(acc["count"], width)


def quat_matrix(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def local(node):
    m = np.eye(4)
    m[:3, :3] = quat_matrix(node.get("rotation", [0, 0, 0, 1])) @ np.diag(node.get("scale", [1, 1, 1]))
    m[:3, 3] = node.get("translation", [0, 0, 0])
    return m


def gather(doc, blob):
    """Every triangle in the scene, in model space, with its texture coordinates and material index (-1: none)."""
    tris, uvs, mats = [], [], []

    def visit(index, parent):
        node = doc["nodes"][index]
        # = GemRender GltfImporter.HIDDEN_PREFIXES + extras.gemrenderHidden: subtree not drawn.
        if node.get("name", "").startswith(("hit_", "socket_")) or (node.get("extras") or {}).get("gemrenderHidden"):
            return
        here = parent @ local(node)
        if "mesh" in node:
            for prim in doc["meshes"][node["mesh"]]["primitives"]:
                pos = accessor(doc, blob, prim["attributes"]["POSITION"]).astype(np.float64)
                pos = pos @ here[:3, :3].T + here[:3, 3]
                uv = (accessor(doc, blob, prim["attributes"]["TEXCOORD_0"]).astype(np.float64)
                      if "TEXCOORD_0" in prim["attributes"] else np.zeros((len(pos), 2)))
                idx = accessor(doc, blob, prim["indices"]).astype(np.int64).reshape(-1, 3)
                tris.append(pos[idx])
                uvs.append(uv[idx])
                mats.append(np.full(len(idx), prim.get("material", -1)))
        for child in node.get("children", []):
            visit(child, here)

    for root in doc["scenes"][doc.get("scene", 0)]["nodes"]:
        visit(root, np.eye(4))
    return np.concatenate(tris), np.concatenate(uvs), np.concatenate(mats)


def sheets(doc):
    """Material index -> RGB sheet (base colour texture x factor; flat factor without one). -1: white."""
    out = {-1: np.ones((1, 1, 3))}
    for index, material in enumerate(doc.get("materials", [])):
        pbr = material.get("pbrMetallicRoughness", {})
        factor = np.asarray(pbr.get("baseColorFactor", [1, 1, 1, 1])[:3], dtype=np.float64)
        sheet = np.ones((1, 1, 3))
        if "baseColorTexture" in pbr:
            image = doc["images"][doc["textures"][pbr["baseColorTexture"]["index"]]["source"]]
            location = image["extras"]["resourceLocation"]
            sheet = np.asarray(Image.open(resource(location)).convert("RGB"), dtype=np.float64) / 255
        out[index] = sheet * factor
    return out


def render(rig):
    (path,) = glob.glob(os.path.join(PACK, "assets", NS, "models", "*", rig, f"{rig}.gltf"))
    doc = json.load(open(path))
    blob = open(os.path.join(os.path.dirname(path), f"{rig}.bin"), "rb").read()
    tris, uvs, mats = gather(doc, blob)
    maps = sheets(doc)

    yaw, pitch = np.radians(YAW), np.radians(PITCH)
    right = np.array([np.cos(yaw), 0, -np.sin(yaw)])
    up = np.array([np.sin(pitch) * np.sin(yaw), np.cos(pitch), np.sin(pitch) * np.cos(yaw)])
    forward = np.cross(right, up)

    flat = tris.reshape(-1, 3)
    centre = (flat.min(0) + flat.max(0)) / 2
    view = (flat - centre) @ np.stack([right, up, forward], axis=1)
    span = max(view[:, 0].max() - view[:, 0].min(), view[:, 1].max() - view[:, 1].min()) * 1.06
    px = SIZE * SUPERSAMPLE
    screen = np.empty_like(view)
    screen[:, 0] = (view[:, 0] / span + 0.5) * px
    screen[:, 1] = (0.5 - view[:, 1] / span) * px
    screen[:, 2] = view[:, 2]
    screen = screen.reshape(-1, 3, 3)

    normals = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
    lengths = np.linalg.norm(normals, axis=1, keepdims=True)
    normals = normals / np.where(lengths == 0, 1, lengths)
    light = np.array([0.45, 0.78, -0.44])
    shade = 0.45 + 0.55 * np.clip(normals @ light, 0, 1)

    colour = np.zeros((px, px, 3))
    alpha = np.zeros((px, px), dtype=bool)
    depth = np.full((px, px), np.inf)
    for i in range(len(screen)):
        raster(screen[i], uvs[i], shade[i], maps[mats[i]], colour, alpha, depth)

    # Dark hull (charcoal submarine) vanishes on a dark slot: lift until median covered luminance >= EXPOSURE_FLOOR.
    median = np.median(colour[alpha] @ [0.2126, 0.7152, 0.0722]) if alpha.any() else 1
    if median < EXPOSURE_FLOOR:
        colour *= EXPOSURE_FLOOR / max(median, 1e-3)
    rgba = np.zeros((px, px, 4), dtype=np.uint8)
    rgba[..., :3] = np.clip(colour * 255, 0, 255).astype(np.uint8)
    rgba[..., 3] = np.where(alpha, 255, 0)
    icon = Image.fromarray(rgba, "RGBA").resize((SIZE, SIZE), Image.LANCZOS)
    out = os.path.join(PACK, "assets", NS, "textures", "slot", f"{rig}.png")
    icon.save(out)
    covered = int(alpha.sum()) / (px * px)
    print(f"{rig}: {len(tris)} triangles -> {out} ({covered * 100:.1f}% of the icon covered)")


def raster(tri, uv, shade, sheet, colour, alpha, depth):
    lo = np.floor(tri[:, :2].min(0)).astype(int)
    hi = np.ceil(tri[:, :2].max(0)).astype(int)
    lo = np.maximum(lo, 0)
    hi = np.minimum(hi, colour.shape[0] - 1)
    if lo[0] > hi[0] or lo[1] > hi[1]:
        return
    ys, xs = np.mgrid[lo[1]:hi[1] + 1, lo[0]:hi[0] + 1]
    px = xs + 0.5
    py = ys + 0.5
    (x0, y0), (x1, y1), (x2, y2) = tri[0, :2], tri[1, :2], tri[2, :2]
    area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
    if abs(area) < 1e-9:
        return
    w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area
    w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area
    w2 = 1 - w0 - w1
    inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
    if not inside.any():
        return
    z = w0 * tri[0, 2] + w1 * tri[1, 2] + w2 * tri[2, 2]
    window = depth[lo[1]:hi[1] + 1, lo[0]:hi[0] + 1]
    winner = inside & (z < window)
    if not winner.any():
        return
    u = (w0 * uv[0, 0] + w1 * uv[1, 0] + w2 * uv[2, 0]) % 1.0
    v = (w0 * uv[0, 1] + w1 * uv[1, 1] + w2 * uv[2, 1]) % 1.0
    tx = np.clip((u * sheet.shape[1]).astype(int), 0, sheet.shape[1] - 1)
    ty = np.clip((v * sheet.shape[0]).astype(int), 0, sheet.shape[0] - 1)
    texel = sheet[ty, tx] * shade
    window[winner] = z[winner]
    colour[lo[1]:hi[1] + 1, lo[0]:hi[0] + 1][winner] = texel[winner]
    alpha[lo[1]:hi[1] + 1, lo[0]:hi[0] + 1][winner] = True


if __name__ == "__main__":
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    for name in sys.argv[1:] or ["m983", "mim104"]:
        render(name)
