#!/usr/bin/env python3
"""Synthetic box vehicles for PhysicsBench: box_<L>_sprung / box_<L>_rigid, L = hull length in blocks.
usage: tools/REDACTED <pack dir>   (a CombinedAssault pack: <gamedir>/combinedassault/packs/bench)"""
import base64
import json
import math
import os
import shutil
import struct
import sys

NS = "combinedassaultbench"
LENGTHS = [4, 8, 16, 32, 64]
G = 9.81


class Model:
    def __init__(self, name):
        self.buf = bytearray()
        self.doc = {"asset": {"version": "2.0"}, "scene": 0, "scenes": [{"nodes": [0]}], "buffers": [],
                    "bufferViews": [], "accessors": [], "meshes": [],
                    "nodes": [{"name": name, "children": [], "extras": {"combinedassaultRole": "body"}}]}

    def node(self, parent, node):
        self.doc["nodes"].append(node)
        i = len(self.doc["nodes"]) - 1
        self.doc["nodes"][parent].setdefault("children", []).append(i)
        return i

    def box(self, parent, name, lo, hi, offset=(0, 0, 0), **flags):
        """Collider box [lo, hi] in vehicle frame, under a node whose world origin is offset."""
        x0, y0, z0 = (lo[k] - offset[k] for k in range(3))
        x1, y1, z1 = (hi[k] - offset[k] for k in range(3))
        pts = [x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1]
        quads = [[0, 3, 2, 1], [4, 5, 6, 7], [0, 1, 5, 4], [2, 3, 7, 6], [1, 2, 6, 5], [0, 4, 7, 3]]
        idx = [q[k] for q in quads for k in (0, 1, 2, 0, 2, 3)]
        p = len(self.buf)
        self.buf += struct.pack("<24f", *pts)
        i = len(self.buf)
        self.buf += struct.pack("<36I", *idx)
        views = self.doc["bufferViews"]
        acc = self.doc["accessors"]
        views.append({"buffer": 0, "byteOffset": p, "byteLength": 96})
        acc.append({"bufferView": len(views) - 1, "componentType": 5126, "count": 8, "type": "VEC3",
                    "min": [x0, y0, z0], "max": [x1, y1, z1]})
        views.append({"buffer": 0, "byteOffset": i, "byteLength": 144})
        acc.append({"bufferView": len(views) - 1, "componentType": 5125, "count": 36, "type": "SCALAR"})
        self.doc["meshes"].append({"primitives": [{"attributes": {"POSITION": len(acc) - 2}, "indices": len(acc) - 1}]})
        extras = {"combinedassaultRole": "collider", "gemrenderHidden": True}
        if flags:
            extras["combinedassault"] = flags
        self.node(parent, {"name": name, "mesh": len(self.doc["meshes"]) - 1, "extras": extras})

    def save(self, path):
        self.doc["buffers"] = [{"byteLength": len(self.buf),
                                "uri": "data:application/octet-stream;base64," + base64.b64encode(self.buf).decode()}]
        json.dump(self.doc, open(path, "w"))


def vehicle(length, sprung, name):
    width = max(2.0, length * 0.4)
    height = 2.0 if length < 16 else 3.0
    mass = 1500.0 * (length / 4) ** 2
    hw, hl = width / 2, length / 2
    model = Model(name)
    model.box(0, "hull", (-hw, 0, -hl), (hw, height, hl), main=True)
    parts = []
    if sprung:
        radius = 0.4
        k = mass * G / 4 / 0.1
        c = 2 * 0.3 * math.sqrt(k * mass / 4)
        for i, (sx, sz) in enumerate([(1, 1), (-1, 1), (1, -1), (-1, -1)]):
            x, z = sx * (hw - 0.3), sz * (hl - 0.6)
            wheel = f"wheel_{i}_{'l' if sx > 0 else 'r'}"
            n = model.node(0, {"name": wheel, "translation": [x, radius, z],
                               "extras": {"combinedassaultRole": "part", "combinedassaultInterface": wheel}})
            model.box(n, wheel + "_marker", (x - 0.2, 0, z - radius), (x + 0.2, 2 * radius, z + radius),
                      (x, radius, z), solid=False)
            parts.append({"id": wheel, "name": wheel, "type": "combinedassault:suspension", "is_seat": False,
                          "spring_stiffness": round(k), "damping": round(c),
                          "rest_length": 0.3, "max_compression": 0.3, "max_extension": 0.2})
    parts.append({"id": "seat0", "name": "seat0", "type": "combinedassault:generic", "is_seat": True,
                  "seat_offset": [0, height, 0]})
    force = mass * 4
    data = {"type": "combinedassault:wheeled_vehicle",
            "attributes": {"brake_force": force, "forward_force": force, "backward_force": force / 2,
                           "max_speed_forward": 0.3, "max_speed_backward": 0.1, "turn_step": 0.2, "max_turn": 2},
            "max_health": 1000,
            "energy_info": {"energy_capacity": 1000, "energy_consumption_per_tick": 0.001},
            "physics_info": {"mass": mass},
            "model": f"{NS}:models/{name}.gltf",
            "parts": parts}
    return model, data


def main(out):
    shutil.rmtree(out, ignore_errors=True)
    defs = os.path.join(out, "combinedassault", NS, "vehicles")
    models = os.path.join(out, "assets", NS, "models")
    os.makedirs(defs)
    os.makedirs(models)
    # Generated tests are filtered by template namespace => template must be combinedassaultbench:empty.
    structure = os.path.join(out, "data", NS, "structure")
    os.makedirs(structure)
    shutil.copy(os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "combinedassault",
                             "structure", "empty.nbt"), os.path.join(structure, "empty.nbt"))
    with open(os.path.join(out, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"pack_format": 34, "description": "[CA] bench"}, "combinedassault": {"id": "bench"}}, f)
    for length in LENGTHS:
        for sprung in (True, False):
            name = f"box_{length}_{'sprung' if sprung else 'rigid'}"
            model, data = vehicle(length, sprung, name)
            model.save(os.path.join(models, name + ".gltf"))
            with open(os.path.join(defs, name + ".json"), "w") as f:
                json.dump(data, f, indent=1)


if __name__ == "__main__":
    main(sys.argv[1])
