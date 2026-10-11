#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage the three naval glTF assets into default_vehicle and derive their structure models.

Per ship: mirror the mesh about its athwartships axis when the export is left-handed (the Nimitz and
Kilo builders put starboard on +X with the bow on -Y, which is a mirror image), put the forward axis on
+Z through a root rotation, re-point the buffer and images at pack resources, and write a Bedrock
structure model from the staged node bounds through a per-ship bone table.

Run from the combinedassault directory:  python3 tools/stage_ships.py
"""
import json
import math
import os
import shutil

import numpy as np
import mqo
import strip_cubes  # noqa: E402

HOME = os.path.expanduser("~")
# FIXME: dir deleted; riverine gltf = Blender export of ~/models/warty/Riverine_Blender_glTF.zip. Stager unrunnable until re-exported.
SCRATCH_SHIPS = "/tmp/claude-1000/-home-rawhav0kk-projects-wf2-21/e84cf69d-f002-4e49-918b-4940d920fb19/scratchpad/ships"
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"

SHIPS = {
    "nimitz": dict(src=f"{HOME}/models/warty/nimitz_cvn68/Nimitz_CVN68.gltf", mirror="x", yaw=0.0),
    "kilo": dict(src=f"{HOME}/models/warty/kilo_class/Kilo_Class.gltf", mirror="x", yaw=0.0),
    "riverine": dict(src=f"{SCRATCH_SHIPS}/riverine/riverine_patrol_boat.gltf", mirror=None, yaw=90.0),
}

AXIS = {"x": 0, "y": 1, "z": 2}
COMPONENT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def accessor_view(doc, blob, index):
    acc = doc["accessors"][index]
    bv = doc["bufferViews"][acc["bufferView"]]
    if bv.get("byteStride"):
        raise SystemExit("strided buffer views are not handled")
    dtype = COMPONENT[acc["componentType"]]
    width = WIDTH[acc["type"]]
    start = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
    count = acc["count"] * width
    view = np.frombuffer(blob, dtype=dtype, count=count, offset=start)
    return acc, view.reshape(acc["count"], width)


def mirror_mesh(doc, blob, axis):
    """Negate one axis of every position, normal and tangent, and flip triangle winding."""
    a = AXIS[axis]
    done = set()
    for mesh in doc["meshes"]:
        for prim in mesh["primitives"]:
            for name, index in prim["attributes"].items():
                if name not in ("POSITION", "NORMAL", "TANGENT") or index in done:
                    continue
                done.add(index)
                acc, view = accessor_view(doc, blob, index)
                view[:, a] *= -1
                if name == "POSITION":
                    lo, hi = acc["min"], acc["max"]
                    lo[a], hi[a] = -hi[a], -lo[a]
            if "indices" in prim and prim["indices"] not in done:
                done.add(prim["indices"])
                acc, view = accessor_view(doc, blob, prim["indices"])
                tris = view.reshape(-1, 3)
                tris[:, [1, 2]] = tris[:, [2, 1]]
    for node in doc["nodes"]:
        if "translation" in node:
            node["translation"][a] = -node["translation"][a]
        if "rotation" in node:
            q = node["rotation"]
            for other in range(3):
                if other != a:
                    q[other] = -q[other]
        if "matrix" in node:
            raise SystemExit("matrix nodes are not handled")


def yaw_quaternion(degrees):
    half = math.radians(degrees) / 2
    return [0.0, math.sin(half), 0.0, math.cos(half)]


def quat_matrix(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def local_matrix(node):
    m = np.eye(4)
    t = node.get("translation", [0, 0, 0])
    r = node.get("rotation", [0, 0, 0, 1])
    s = node.get("scale", [1, 1, 1])
    m[:3, :3] = quat_matrix(r) @ np.diag(s)
    m[:3, 3] = t
    return m


def node_bounds(doc):
    """World-space box of every mesh node, walking the scene the way GltfBounds does."""
    out = {}

    def walk(index, parent):
        node = doc["nodes"][index]
        here = parent @ local_matrix(node)
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
            name = node.get("name", f"node{index}")
            if name.endswith("_mesh"):
                name = name[:-5]
            out[name] = (lo.tolist(), hi.tolist())
        for child in node.get("children", []):
            walk(child, here)

    for root in doc["scenes"][doc.get("scene", 0)]["nodes"]:
        walk(root, np.eye(4))
    return out


def stage(ship, spec):
    src = spec["src"]
    src_dir = os.path.dirname(src)
    doc = json.load(open(src))
    if len(doc["buffers"]) != 1:
        raise SystemExit(f"{ship}: expected one buffer")
    blob = bytearray(open(os.path.join(src_dir, doc["buffers"][0]["uri"]), "rb").read())

    if spec["mirror"]:
        mirror_mesh(doc, blob, spec["mirror"])
    roots = doc["scenes"][doc.get("scene", 0)]["nodes"]
    if spec["yaw"]:
        if len(roots) != 1:
            raise SystemExit(f"{ship}: a yaw needs a single root node")
        root = doc["nodes"][roots[0]]
        if "rotation" in root:
            raise SystemExit(f"{ship}: root already rotated")
        root["rotation"] = yaw_quaternion(spec["yaw"])

    models = os.path.join(PACK, "assets", NS, "models", "ships", ship)
    textures = os.path.join(PACK, "assets", NS, "textures", "ships", ship)
    os.makedirs(models, exist_ok=True)
    os.makedirs(textures, exist_ok=True)

    doc["buffers"][0] = {"byteLength": len(blob),
                         "extras": {"resourceLocation": f"{NS}:models/ships/{ship}/{ship}.bin"}}
    for image in doc.get("images", []):
        base = os.path.basename(image["uri"]).lower()
        shutil.copyfile(os.path.join(src_dir, image["uri"]), os.path.join(textures, base))
        image.pop("uri")
        image["extras"] = {"resourceLocation": f"{NS}:textures/ships/{ship}/{base}"}
    doc.setdefault("asset", {})["extras"] = {"staged_by": "tools/stage_ships.py",
                                            "mirror": spec["mirror"], "yaw": spec["yaw"]}
    gltf, binary = os.path.join(models, f"{ship}.gltf"), os.path.join(models, f"{ship}.bin")
    kept = mqo.read_markers(gltf, binary)
    with open(binary, "wb") as f:
        f.write(blob)
    with open(gltf, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    mqo.append_nodes(gltf, binary, kept)
    bounds = node_bounds(doc)
    return bounds


# ---------------------------------------------------------------- structure models

def union(bounds, names):
    lo = np.full(3, np.inf)
    hi = np.full(3, -np.inf)
    for name in names:
        b = bounds[name]
        lo = np.minimum(lo, b[0])
        hi = np.maximum(hi, b[1])
    return lo, hi


def cube(lo, hi):
    """A Bedrock cube in pixels from a box in blocks."""
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


def structure(bones, length, height, width):
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16,
                        "visible_bounds_width": round(float(max(length, width)), 2),
                        "visible_bounds_height": round(float(height), 2),
                        "visible_bounds_offset": [0, round(float(height) / 2, 2), 0]},
        "bones": bones}]}


def matching(bounds, prefix):
    return sorted(n for n in bounds if n.startswith(prefix))


def nimitz_structure(b):
    hull = union(b, ["01_HULL_underwater", "02_HULL_bootstripe", "03_HULL_topsides"])
    body = [cube(*b["01_HULL_underwater"]), cube(*union(b, ["02_HULL_bootstripe", "03_HULL_topsides"]))]
    for name in ["07_GALLERY_PORT", "07_GALLERY_STARBOARD", "08_DECK_supports_P", "08_DECK_supports_S",
                 "16_RADAR_2"]:
        body.append(cube(*b[name]))
    body.append(cube(*union(b, ["12_ISLAND_base", "13_BRIDGE_level_1", "13_BRIDGE_level_2",
                                 "13_BRIDGE_level_3", "17_ISLAND_communications"])))
    body.append(cube(*union(b, ["15_MAIN_MAST", "16_RADAR_1"])))
    for name in matching(b, "18_EMPTY_EMPLACEMENT_"):
        body.append(cube(*b[name]))
    for name in matching(b, "09_CATWALK_edge_"):
        body.append(cube(*b[name]))
    deck = [cube(*b["06_FLIGHT_DECK"])]
    for name in matching(b, "10_ELEVATOR_"):
        lo, hi = b[name]
        # Flush with the deck; give it thickness so it is a surface rather than a film.
        deck.append(cube([lo[0], hi[1] - 0.5, lo[2]], hi))
    bones = [bone("main_structure", [cube(*hull)]),
             bone("vehicle_body", body),
             bone("deck", deck, parent="vehicle_body")]
    return structure(bones, hull[1][2] - hull[0][2], 80, hull[1][0] - hull[0][0])


def kilo_structure(b):
    hull = union(b, ["01_HULL_bow", "02_HULL_midbody", "03_HULL_stern"])
    body = [cube(*hull),
            cube(*union(b, ["05_SAIL", "06_SAIL_fixtures", "07_SAIL_markings"])),
            cube(*union(b, matching(b, "08_MAST_") + ["09_MAST_large_sensor", "10_MAST_periscope",
                                                       "11_MAST_capped_sensor"])),
            cube(*b["12_FORWARD_PLANE_P"]), cube(*b["12_FORWARD_PLANE_S"]),
            cube(*b["13_STERN_PLANE_P"]), cube(*b["13_STERN_PLANE_S"]),
            cube(*union(b, ["14_LOWER_RUDDER", "15_UPPER_RUDDER"]))]
    deck = [cube(*flush_deck(b["04_UPPER_DECK"], hull))]
    bow_z = hull[1][2]
    tubes_lo = [-2.0, -2.0, bow_z - 6.0]
    tubes_hi = [2.0, 1.0, bow_z - 0.5]
    bones = [bone("main_structure", [cube(*hull)]),
             bone("vehicle_body", body),
             bone("deck", deck, parent="vehicle_body"),
             bone("torpedo_tubes", [cube(tubes_lo, tubes_hi)], pivot=(0, -0.5, bow_z - 3.0)),
             bone("torpedo_tubes_barrel", [cube([-1.0, -1.0, bow_z - 2.0], [1.0, 0.0, bow_z - 0.5])],
                  parent="torpedo_tubes", pivot=(0, -0.5, bow_z - 1.5))]
    return structure(bones, hull[1][2] - hull[0][2], 22, hull[1][0] - hull[0][0])


def flush_deck(deck, hull):
    """Lift a deck box until its top is flush with the hull box it sits inside.

    Collision here is coarse boxes and a rider lands on whichever is highest, so a deck declared below
    the top of the hull's own box is geometry nothing ever touches: the riverine's walking surface is
    its gunwale line, not the moulded deck the model draws.
    """
    lo, hi = deck
    top = max(hi[1], hull[1][1])
    return [lo[0], top - (hi[1] - lo[1]), lo[2]], [hi[0], top, hi[2]]


def riverine_structure(b):
    hull = union(b, ["HULL", "COLLAR"])
    body = [cube(*hull), cube(*b["CABIN"]), cube(*b["HELM"]), cube(*b["PROPULSION"])]
    for name in ["MOUNT_BOW_PORT", "MOUNT_BOW_STARBOARD", "MOUNT_STERN"]:
        body.append(cube(*b[name]))
    deck = [cube(*flush_deck(b["DECK"], hull))]
    bones = [bone("main_structure", [cube(*hull)]),
             bone("vehicle_body", body),
             bone("deck", deck, parent="vehicle_body")]
    return structure(bones, hull[1][2] - hull[0][2], 4.5, hull[1][0] - hull[0][0])


STRUCTURES = {"nimitz": nimitz_structure, "kilo": kilo_structure, "riverine": riverine_structure}


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    for ship, spec in SHIPS.items():
        bounds = stage(ship, spec)
        lo, hi = union(bounds, list(bounds))
        print(f"{ship}: staged, whole model {np.round(lo, 2)} .. {np.round(hi, 2)}")
        for name in sorted(bounds):
            blo, bhi = bounds[name]
            print(f"   {name:40s} {np.round(blo, 2)} .. {np.round(bhi, 2)}")
        with open(os.path.join(SCRATCH_SHIPS, f"{ship}.bounds.json"), "w") as f:
            json.dump(bounds, f, indent=1)
        model = STRUCTURES[ship](bounds)
        path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", f"{ship}.structure.json")
        with open(path, "w") as f:
            json.dump(model, f, indent=1)
        strip_cubes.split(PACK, ship)
        print(f"   -> {path}: {sum(len(bn['cubes']) for bn in model['minecraft:geometry'][0]['bones'])} cubes")


if __name__ == "__main__":
    main()
