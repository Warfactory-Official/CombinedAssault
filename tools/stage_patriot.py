#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py, then REDACTED.
"""Stage the M983 HEMTT tractor and the MIM-104 Patriot trailer into default_vehicle.

The pair is the first warty export that needs no correcting. Its builder states "Blender front -Y,
up +Z", which converts to glTF as forward +Z and up +Y; and because left = up x forward reads +X in
that frame, +X is the vehicle's port side, which is exactly the frame combinedassault uses. So unlike
the ships ([[vehicle-frame-is-port-plus-x]]) there is no mirror and no root yaw here: the geometry is
copied verbatim and only its buffer and images are re-pointed at pack resources.

The two things this does compute are the structure models (collision boxes and part bones from the
staged node bounds) and the coupling geometry, which is printed so the vehicle JSONs can carry it:
the tractor's fifth wheel, the trailer's kingpin, and the trailer's tandem axle, which is the point
it pivots about when it is being towed.

Run from the combinedassault directory:  python3 tools/stage_patriot.py
"""
import json
import os
import shutil

import numpy as np
import mqo
import strip_cubes  # noqa: E402

HOME = os.path.expanduser("~")
SRC = f"{HOME}/models/warty/patriot_m983"
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"

RIGS = {
    "m983": dict(src=f"{SRC}/M983_HEMTT/M983_HEMTT.gltf", coupling="COUPLING_FIFTH_WHEEL"),
    "mim104": dict(src=f"{SRC}/MIM104_Patriot/MIM104_Patriot.gltf", coupling="COUPLING_KINGPIN"),
}


def quat_matrix(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def local_matrix(node):
    m = np.eye(4)
    m[:3, :3] = quat_matrix(node.get("rotation", [0, 0, 0, 1])) @ np.diag(node.get("scale", [1, 1, 1]))
    m[:3, 3] = node.get("translation", [0, 0, 0])
    return m


def walk(doc):
    """World-space box of every mesh node, and the world origin of every node, mesh or not."""
    boxes = {}
    origins = {}

    def visit(index, parent):
        node = doc["nodes"][index]
        here = parent @ local_matrix(node)
        name = node.get("name", f"node{index}")
        if name.endswith("_mesh"):
            name = name[:-5]
        origins[name] = here[:3, 3].tolist()
        if "mesh" in node:
            lo = np.full(3, np.inf)
            hi = np.full(3, -np.inf)
            for prim in doc["meshes"][node["mesh"]]["primitives"]:
                acc = doc["accessors"][prim["attributes"]["POSITION"]]
                for cx in (acc["min"][0], acc["max"][0]):
                    for cy in (acc["min"][1], acc["max"][1]):
                        for cz in (acc["min"][2], acc["max"][2]):
                            w = here @ np.array([cx, cy, cz, 1.0])
                            lo = np.minimum(lo, w[:3])
                            hi = np.maximum(hi, w[:3])
            boxes[name] = (lo.tolist(), hi.tolist())
        for child in node.get("children", []):
            visit(child, here)

    for root in doc["scenes"][doc.get("scene", 0)]["nodes"]:
        visit(root, np.eye(4))
    return boxes, origins


def stage(rig, spec):
    src_dir = os.path.dirname(spec["src"])
    doc = json.load(open(spec["src"]))
    if len(doc["buffers"]) != 1:
        raise SystemExit(f"{rig}: expected one buffer")
    blob = open(os.path.join(src_dir, doc["buffers"][0]["uri"]), "rb").read()

    models = os.path.join(PACK, "assets", NS, "models", "ground", rig)
    textures = os.path.join(PACK, "assets", NS, "textures", "ground", rig)
    os.makedirs(models, exist_ok=True)
    os.makedirs(textures, exist_ok=True)

    doc["buffers"][0] = {"byteLength": len(blob),
                         "extras": {"resourceLocation": f"{NS}:models/ground/{rig}/{rig}.bin"}}
    for image in doc.get("images", []):
        base = os.path.basename(image["uri"]).lower()
        shutil.copyfile(os.path.join(src_dir, image["uri"]), os.path.join(textures, base))
        image.pop("uri")
        image["extras"] = {"resourceLocation": f"{NS}:textures/ground/{rig}/{base}"}
    doc.setdefault("asset", {})["extras"] = {"staged_by": "tools/stage_patriot.py"}
    gltf, binary = os.path.join(models, f"{rig}.gltf"), os.path.join(models, f"{rig}.bin")
    kept = mqo.read_markers(gltf, binary)
    with open(binary, "wb") as f:
        f.write(blob)
    with open(gltf, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    mqo.append_nodes(gltf, binary, kept)
    return walk(doc)


# ---------------------------------------------------------------- structure models

def union(boxes, names):
    lo = np.full(3, np.inf)
    hi = np.full(3, -np.inf)
    for name in names:
        b = boxes[name]
        lo = np.minimum(lo, b[0])
        hi = np.maximum(hi, b[1])
    return lo, hi


def cube(lo, hi):
    lo = np.asarray(lo, dtype=float)
    hi = np.asarray(hi, dtype=float)
    size = np.maximum(hi - lo, 0.05)
    # SBM mirrors Bedrock X on load (origin.x -> -(origin.x + size.x)): author it mirrored.
    return {"origin": [round(float(v) * 16, 3) for v in (-hi[0], lo[1], lo[2])],
            "size": [round(float(v) * 16, 3) for v in size],
            "uv": [0, 0]}


def bone(name, cubes, parent=None, pivot=(0, 0, 0)):
    out = {"name": name, "pivot": [round(float(v) * 16, 3) for v in (-pivot[0], pivot[1], pivot[2])],
           "cubes": cubes}
    if parent:
        out["parent"] = parent
    return out


def structure(bones):
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16},
        "bones": bones}]}


def matching(boxes, prefix):
    return sorted(n for n in boxes if n.startswith(prefix))


def m983_structure(b, o):
    bones = [bone("vehicle_body", [])]
    # Root bones: part groups exist only for root bones => a parented wheel binds no suspension.
    for name in matching(b, "02_WHEEL_"):
        bones.append(bone(name, [cube(*b[name])], pivot=o[name]))
    return structure(bones)


def mim104_structure(b, o):
    bones = [bone("vehicle_body", [])]
    for name in matching(b, "04_WHEEL_"):
        bones.append(bone(name, [], parent="vehicle_body", pivot=o[name]))
    bones.append(bone("launcher", [], parent="vehicle_body", pivot=o["09_LAUNCHER_YAW"]))
    bones.append(bone("launcher_yaw", [], parent="launcher", pivot=o["09_LAUNCHER_YAW"]))
    bones.append(bone("launcher_elevation", [], parent="launcher_yaw", pivot=o["10_LAUNCHER_ELEVATION"]))
    return structure(bones)


STRUCTURES = {"m983": m983_structure, "mim104": mim104_structure}


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    for rig, spec in RIGS.items():
        boxes, origins = stage(rig, spec)
        lo, hi = union(boxes, list(boxes))
        print(f"{rig}: staged, whole model {np.round(lo, 2)} .. {np.round(hi, 2)}")
        print(f"   coupling {spec['coupling']:22s} {np.round(origins[spec['coupling']], 3)}")
        for name in sorted(n for n in origins if "WHEEL" in n or "LAUNCHER" in n):
            print(f"   {name:24s} origin {np.round(origins[name], 3)}")
        axles = sorted(n for n in origins if "_WHEEL_" in n)
        if axles:
            z = sorted({round(origins[n][2], 3) for n in axles})
            print(f"   axle lines z = {z}")
        model = STRUCTURES[rig](boxes, origins)
        path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", f"{rig}.structure.json")
        with open(path, "w") as f:
            json.dump(model, f, indent=1)
        strip_cubes.split(PACK, rig)
        print(f"   -> {path}: {len(model['minecraft:geometry'][0]['bones'])} bones")


if __name__ == "__main__":
    main()
