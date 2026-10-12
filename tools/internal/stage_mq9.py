#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage the MQ-9 Reaper into default_vehicle from ~/models/warty/MQ9_Reaper_tweaked.blend.

tools/internal/export_mq9.py exports it already in the vehicle frame with posable pivots; this re-points the
buffer and atlas at pack resources and writes the structure model. Stations: one box per rail, under it,
sized for the largest pylon airframe (wflib atgm: 1.63 long, 0.33 fin span); order L1 R1 L2 R2 L3 R3.

  python3 tools/internal/stage_mq9.py            (BLENDER overrides the flatpak default)
"""
import json
import os
import shlex
import subprocess
import tempfile

import numpy as np
from PIL import Image

from stage_emb4_jet import controller
from stage_emb4_tank import quaternion
from stage_patriot import bone, cube, structure, union, walk
import mqo
import strip_cubes  # noqa: E402

HOME = os.path.expanduser("~")
BLEND = f"{HOME}/models/warty/MQ9_Reaper_tweaked.blend"
BLENDER = os.environ.get("BLENDER", "flatpak run --filesystem=home --filesystem=/tmp org.blender.Blender")
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"
RIG = "mq9"
ATLAS_SIZE = 1024

# Tyre nodes (export names; main right is "NOSE tyre.002"); two sit at the scene root.
TYRES = ["NOSE tyre", "LEFT wheel", "NOSE tyre.002"]
RAILS = {"L1": "Empty modular mounting rail", "L2": "Empty modular mounting rail.001",
         "L3": "Empty modular mounting rail.002", "R1": "Empty modular mounting rail.003",
         "R2": "Empty modular mounting rail.004", "R3": "Empty modular mounting rail.005"}
STATION_ORDER = ["L1", "R1", "L2", "R2", "L3", "R3"]
STATION_WIDTH = 0.36
STATION_LENGTH = 1.9


def export(out):
    script = os.path.abspath("tools/internal/export_mq9.py")
    subprocess.run(shlex.split(BLENDER) + ["-b", BLEND, "--python", script, "--", out],
                   check=True, stdout=subprocess.DEVNULL)


def stage(src):
    src_dir = os.path.dirname(src)
    doc = json.load(open(src))
    if len(doc["buffers"]) != 1 or len(doc["images"]) != 1:
        raise SystemExit("expected one buffer and one image")
    blob = open(os.path.join(src_dir, doc["buffers"][0]["uri"]), "rb").read()
    models = os.path.join(PACK, "assets", NS, "models", "aircraft", RIG)
    textures = os.path.join(PACK, "assets", NS, "textures", "aircraft", RIG)
    os.makedirs(models, exist_ok=True)
    os.makedirs(textures, exist_ok=True)
    doc["buffers"][0] = {"byteLength": len(blob),
                         "extras": {"resourceLocation": f"{NS}:models/aircraft/{RIG}/{RIG}.bin"}}
    image = doc["images"][0]
    atlas = f"{RIG}_atlas.png"
    Image.open(os.path.join(src_dir, image["uri"])).convert("RGBA") \
        .resize((ATLAS_SIZE, ATLAS_SIZE), Image.LANCZOS).save(os.path.join(textures, atlas))
    image.pop("uri")
    image["extras"] = {"resourceLocation": f"{NS}:textures/aircraft/{RIG}/{atlas}"}
    doc.setdefault("asset", {})["extras"] = {"staged_by": "tools/internal/stage_mq9.py"}
    controller(RIG, spin_tyres(doc))
    flap_hinges(doc)
    gltf, binary = os.path.join(models, f"{RIG}.gltf"), os.path.join(models, f"{RIG}.bin")
    kept = mqo.read_markers(gltf, binary)
    with open(binary, "wb") as f:
        f.write(blob)
    with open(gltf, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    mqo.append_nodes(gltf, binary, kept)
    mqo.append_clip(gltf, binary, "flaps", flap_channels())
    return doc


def flap_hinges(doc):
    """`<side> flap` (scene root) -> `<side>_flap_mount` (hinge at mid-thickness leading edge) -> `<side>_flap`."""
    nodes = doc["nodes"]
    roots = doc["scenes"][doc.get("scene", 0)]["nodes"]
    for name, side in (("LEFT flap", "left"), ("RIGHT flap", "right")):
        i = next(k for k, n in enumerate(nodes) if n.get("name") == name)
        node = nodes[i]
        assert i in roots and "children" not in node, name
        box = doc["accessors"][doc["meshes"][node["mesh"]]["primitives"][0]["attributes"]["POSITION"]]
        lo, hi = box["min"], box["max"]
        rot = quaternion_matrix(node["rotation"])
        edges = [rot @ [x, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2] + node["translation"] for x in (lo[0], hi[0])]
        lead = max(edges, key=lambda p: p[2])
        hinge = [node["translation"][0], float(lead[1]), float(lead[2])]
        node["translation"] = [round(a - b, 6) for a, b in zip(node["translation"], hinge)]
        roots[roots.index(i)] = len(nodes)
        nodes.append({"name": f"{side}_flap_mount", "translation": [round(v, 6) for v in hinge],
                      "children": [len(nodes) + 1]})
        nodes.append({"name": f"{side}_flap", "children": [i]})


def flap_channels():
    """Trailing edge down (-X about the hinge) to the vehicle json's deepest notch: clip end == progress 1."""
    with open(os.path.join(PACK, "data", NS, "vehicles", f"{RIG}.json")) as f:
        vehicle = json.load(f)
    full = max(abs(s) for s in next(p for p in vehicle["parts"] if p["id"] == "flaps")["stages"])
    keys = mqo.hinge_keys("x", -full, 1.0)
    return [(f"{side}_flap", "rotation", *keys) for side in ("left", "right")]


def quaternion_matrix(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def spin_tyres(doc):
    """Each tyre's mesh onto a new identity-rest child `wheel<k>` (the tyre node keeps its pose, meshless), hub
    details within its radius re-parented onto it, all under `gear` => {wheel<k>: (radius, axis, sign)}."""
    nodes = doc["nodes"]
    parent = {c: i for i, n in enumerate(nodes) for c in n.get("children", [])}
    roots = doc["scenes"][doc.get("scene", 0)]["nodes"]

    def local(n):
        m = np.eye(4)
        x, y, z, w = n.get("rotation", [0, 0, 0, 1])
        m[:3, :3] = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]
        m[:3, :3] *= n.get("scale", [1, 1, 1])
        m[:3, 3] = n.get("translation", [0, 0, 0])
        return m

    def world(i):
        return (world(parent[i]) if i in parent else np.eye(4)) @ local(nodes[i])

    def detach(i):
        if i in parent:
            nodes[parent[i]]["children"].remove(i)
            if not nodes[parent[i]]["children"]:
                del nodes[parent[i]]["children"]
            del parent[i]
        else:
            roots.remove(i)

    def attach(i, to, frame):
        m = np.linalg.inv(world(to)) @ frame
        scale = np.linalg.norm(m[:3, :3], axis=0)
        r = m[:3, :3] / scale
        for key in ("translation", "rotation", "scale"):
            nodes[i].pop(key, None)
        nodes[i]["translation"] = m[:3, 3].tolist()
        nodes[i]["rotation"] = quaternion(r)
        if not np.allclose(scale, 1, atol=1e-6):
            nodes[i]["scale"] = scale.tolist()
        nodes[to].setdefault("children", []).append(i)
        parent[i] = to

    gear = next(i for i, n in enumerate(nodes) if n.get("name") == "gear")
    wheels = {}
    for k, name in enumerate(TYRES):
        tyre = next(i for i, n in enumerate(nodes) if n.get("name") == name)
        acc = doc["accessors"][doc["meshes"][nodes[tyre]["mesh"]]["primitives"][0]["attributes"]["POSITION"]]
        lo, hi = np.array(acc["min"]), np.array(acc["max"])
        axis = int(np.argmin(hi - lo))
        pose = world(tyre)
        centre = pose @ np.append((lo + hi) / 2, 1)
        radius = max(float(hi[a] - lo[a]) / 2 * np.linalg.norm(pose[:3, a]) for a in range(3) if a != axis)
        near = [i for i, n in enumerate(nodes) if i != tyre and "mesh" in n and "hub" in n.get("name", "").lower()
                and np.linalg.norm(world(i)[:3, 3] - centre[:3]) < radius]
        frame = world(tyre)
        detach(tyre)
        attach(tyre, gear, frame)
        spin = len(nodes)
        nodes.append({"name": f"wheel{k}", "mesh": nodes[tyre].pop("mesh"),
                      "translation": [0.0, 0.0, 0.0]})
        nodes[tyre]["name"] = f"wheel{k}_mount"
        nodes[tyre].setdefault("children", []).append(spin)
        parent[spin] = tyre
        for i in near:
            frame = world(i)
            detach(i)
            attach(i, spin, frame)
        wheels[f"wheel{k}"] = (radius, "xyz"[axis], 1 if pose[0, axis] > 0 else -1)
    return wheels


def stations(boxes):
    out = []
    for key in STATION_ORDER:
        lo, hi = boxes[RAILS[key]]
        x = (lo[0] + hi[0]) / 2
        z = (lo[2] + hi[2]) / 2
        top = lo[1]
        out.append(([x - STATION_WIDTH / 2, top - STATION_WIDTH, z - STATION_LENGTH / 2],
                    [x + STATION_WIDTH / 2, top, z + STATION_LENGTH / 2]))
    return out


def children(doc, name):
    index = next(i for i, n in enumerate(doc["nodes"]) if n.get("name") == name)
    return [doc["nodes"][c]["name"] for c in doc["nodes"][index].get("children", [])]


def mq9_structure(doc, b, o):
    gear = children(doc, "gear")
    nose_gear = [n for n in gear if b.get(n) and b[n][0][2] > 2.0]
    port_gear = [n for n in gear if b.get(n) and b[n][0][2] <= 2.0 and b[n][0][0] > 0]
    starboard_gear = [n for n in gear if b.get(n) and b[n][0][2] <= 2.0 and b[n][1][0] < 0]
    ball = b["Cylinder"]
    ball_centre = [(ball[0][i] + ball[1][i]) / 2 for i in range(3)]
    racks = stations(b)
    rack_lo, rack_hi = union({i: r for i, r in enumerate(racks)}, range(len(racks)))
    centre = [(rack_lo[0] + rack_hi[0]) / 2, rack_hi[1], (rack_lo[2] + rack_hi[2]) / 2]
    bones = [bone("vehicle_body", []),
             bone("sighting_system", [], pivot=ball_centre),
             bone("landing_gear", [cube(*union(b, g)) for g in (nose_gear, port_gear, starboard_gear)]),
             bone("pylons", [cube(*r) for r in racks]),
             bone("hardpoints", [], pivot=centre),
             bone("hardpoints_barrel", [], parent="hardpoints", pivot=centre)]
    return structure(bones), racks


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    with tempfile.TemporaryDirectory() as scratch:
        src = os.path.join(scratch, f"{RIG}.gltf")
        export(src)
        doc = stage(src)
    boxes, origins = walk(doc)
    lo, hi = union(boxes, list(boxes))
    print(f"{RIG}: {len(doc['nodes'])} nodes, whole model {np.round(lo, 2)} .. {np.round(hi, 2)}")
    model, racks = mq9_structure(doc, boxes, origins)
    for key, (rlo, rhi) in zip(STATION_ORDER, racks):
        print(f"   station {key}: {np.round(rlo, 3)} .. {np.round(rhi, 3)}")
    path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", f"{RIG}.structure.json")
    with open(path, "w") as f:
        json.dump(model, f, indent=1)
    strip_cubes.split(PACK, RIG)
    print(f"   -> {path}: {len(model['minecraft:geometry'][0]['bones'])} bones")


if __name__ == "__main__":
    main()
