#!/usr/bin/env python3
"""Minecraft player as boxes, for size comparison. No Blender needed.

    python3 tools/blender/ca_reference.py <out dir>      writes player_reference.gltf (+ .obj)

Vanilla HumanoidModel: 32 px tall, drawn at 15/16 => 1.875 blocks; hitbox 0.6 x 1.8, eyes 1.62 (standing).
Seated = vanilla riding pose (legs -81.0 deg, splayed 18 deg; arms -36 deg); origin = entity position (seat socket).
glTF frame (+Y up, faces +Z, 1 unit = 1 block).
"""
import base64
import json
import math
import os
import struct
import sys

PX = 1.0 / 16 * 15 / 16
HITBOX = (0.6, 1.8)
EYE = 1.62
# HumanoidModel riding: leg xRot -1.4137167, yRot +-pi/10; arm xRot -pi/5.
RIDE_LEG = (-1.4137167, math.pi / 10)
RIDE_ARM = -math.pi / 5
COLOURS = {"head": (0.78, 0.6, 0.47), "body": (0.0, 0.66, 0.66), "arm": (0.78, 0.6, 0.47), "leg": (0.27, 0.22, 0.6),
           "hitbox": (1.0, 0.2, 0.2)}


def boxes(seated=False):
    """[(name, kind, size xyz, pivot xyz, euler xyz rad, offset of box centre from pivot)], blocks, Y up, face +Z."""
    out = [("head", "head", (8, 8, 8), (0, 24, 0), (0, 0, 0), (0, 4, 0)),
           ("body", "body", (8, 12, 4), (0, 24, 0), (0, 0, 0), (0, -6, 0))]
    for side, x in (("left", 1), ("right", -1)):
        arm = (RIDE_ARM if seated else 0, 0, 0)
        out.append(("arm_" + side, "arm", (4, 12, 4), (5 * x, 22, 0), arm, (x, -4, 0)))
        leg = (RIDE_LEG[0], RIDE_LEG[1] * x, 0) if seated else (0, 0, 0)
        out.append(("leg_" + side, "leg", (4, 12, 4), (2 * x, 12, 0), leg, (0, -6, 0)))
    return [(n, k, tuple(s * PX for s in size), tuple(p * PX for p in pivot), euler, tuple(o * PX for o in off))
            for n, k, size, pivot, euler, off in out]


def rotate(euler, v):
    """Minecraft ModelPart order: Z, then Y, then X (applied to the vector as X(Y(Z v)))."""
    x, y, z = v
    rx, ry, rz = euler
    x, y = x * math.cos(rz) - y * math.sin(rz), x * math.sin(rz) + y * math.cos(rz)
    x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
    y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
    return x, y, z


def corners(size, pivot, euler, offset):
    out = []
    for i in range(8):
        local = tuple(offset[a] + (size[a] / 2 if i >> a & 1 else -size[a] / 2) for a in range(3))
        r = rotate(euler, local)
        out.append(tuple(pivot[a] + r[a] for a in range(3)))
    return out


# Cube faces over corner indices (bit 0 = +x, bit 1 = +y, bit 2 = +z), outward winding.
FACES = [(0, 4, 6, 2), (1, 3, 7, 5), (0, 1, 5, 4), (2, 6, 7, 3), (0, 2, 3, 1), (4, 5, 7, 6)]


def solids(seated=False):
    """[(name, kind, 8 corners)] incl. the hitbox (standing only: riding hitbox depends on the seat)."""
    out = [(n, k, corners(s, p, e, o)) for n, k, s, p, e, o in boxes(seated)]
    if not seated:
        w, h = HITBOX
        out.append(("hitbox", "hitbox", corners((w, h, w), (0, h / 2, 0), (0, 0, 0), (0, 0, 0))))
    return out


def write(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    data, nodes, meshes, accessors, views, materials, kinds = bytearray(), [], [], [], [], [], {}
    obj = ["# Minecraft player reference, 1 unit = 1 block, +Y up, faces +Z; standing at x 0, seated at x 1.5"]
    base = 1
    for seated, shift in ((False, 0.0), (True, 1.5)):
        for name, kind, points in solids(seated):
            points = [(x + shift, y, z) for x, y, z in points]
            if kind not in kinds:
                kinds[kind] = len(materials)
                materials.append({"name": kind, "pbrMetallicRoughness": {
                    "baseColorFactor": list(COLOURS[kind]) + [0.35 if kind == "hitbox" else 1.0],
                    "metallicFactor": 0.0, "roughnessFactor": 1.0},
                    **({"alphaMode": "BLEND"} if kind == "hitbox" else {})})
            tris = [t for f in FACES for t in ((f[0], f[1], f[2]), (f[0], f[2], f[3]))]
            p_off = len(data)
            for p in points:
                data += struct.pack("<3f", *p)
            i_off = len(data)
            for t in tris:
                data += struct.pack("<3H", *t)
            views += [{"buffer": 0, "byteOffset": p_off, "byteLength": 96},
                      {"buffer": 0, "byteOffset": i_off, "byteLength": 72}]
            accessors += [{"bufferView": len(views) - 2, "componentType": 5126, "count": 8, "type": "VEC3",
                           "min": [min(p[a] for p in points) for a in range(3)],
                           "max": [max(p[a] for p in points) for a in range(3)]},
                          {"bufferView": len(views) - 1, "componentType": 5123, "count": 36, "type": "SCALAR"}]
            label = ("seated_" if seated else "standing_") + name
            meshes.append({"name": label, "primitives": [
                {"attributes": {"POSITION": len(accessors) - 2}, "indices": len(accessors) - 1,
                 "material": kinds[kind]}]})
            nodes.append({"name": label, "mesh": len(meshes) - 1})
            obj.append("o " + label)
            obj += ["v %.5f %.5f %.5f" % p for p in points]
            obj += ["f " + " ".join(str(base + i) for i in f) for f in FACES]
            base += 8
        while len(data) % 4:
            data += b"\0"
    nodes.append({"name": "eye_height", "translation": [0.0, EYE, 0.0]})
    gltf = {"asset": {"version": "2.0", "generator": "tools/blender/ca_reference.py"},
            "scene": 0, "scenes": [{"nodes": list(range(len(nodes)))}], "nodes": nodes, "meshes": meshes,
            "materials": materials, "accessors": accessors, "bufferViews": views,
            "buffers": [{"byteLength": len(data),
                         "uri": "data:application/octet-stream;base64," + base64.b64encode(bytes(data)).decode()}]}
    with open(os.path.join(out_dir, "player_reference.gltf"), "w", encoding="utf-8") as out:
        json.dump(gltf, out, indent=1)
    with open(os.path.join(out_dir, "player_reference.obj"), "w", encoding="utf-8") as out:
        out.write("\n".join(obj) + "\n")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    write(sys.argv[1])
