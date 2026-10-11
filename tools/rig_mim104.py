#!/usr/bin/env python3
"""Rig the staged MIM-104 glTF in place: launcher (traverse + elevation frames, four canister stations), wheels
(suspension part frames + probe boxes), landing legs (stow_when_towed suspension: frame at each foot, inner leg
telescoping out of the static sleeve, foot probe).

    python3 REDACTED      (from CombinedAssault/)

Each step once only (skips a rigged one). Rest pose = canisters level, muzzles forward (+Z); elevation raises the
muzzles. Stations = front STATION_DEPTH of each canister (launch from the station tail => inside the tube).
Wheels: unsprung, the towed hull dragged its belly over whole-block noise and levered the tractor off its wheels.
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import convert_pack  # noqa: E402
from stage_emb4_ce import DATA, HIDDEN, IFACE, NS, PACK, ROLE  # noqa: E402

PATH = os.path.join(PACK, "assets", NS, "models", "ground", "mim104", "mim104.gltf")
YAW = "09_LAUNCHER_YAW"
PITCH = "10_LAUNCHER_ELEVATION"
CANISTERS = ["11_CANISTER_11", "11_CANISTER_12", "11_CANISTER_21", "11_CANISTER_22"]
CANISTER_HALF = 3.23
STATION_DEPTH = 1.0
STATION_R = 0.4
YAW_COLLIDERS = ["09_LAUNCHER_YAW_mesh"]
PITCH_COLLIDERS = ["10_LAUNCHER_ELEVATION_mesh"] + [c + "_mesh" for c in CANISTERS]
SEAT = (0.0, 2.6, -5.0)
# node -> suspension part id (vehicles/mim104.json)
WHEELS = {"04_WHEEL_00_L": "wheel_0_l", "04_WHEEL_00_R": "wheel_0_r", "04_WHEEL_01_L": "wheel_1_l",
          "04_WHEEL_01_R": "wheel_1_r"}
# Probe box half extents, node frame: tyre width x radius.
WHEEL_HALF = (0.4, 0.62, 0.62)
VIEW = (0.0, 3.5, -3.0)
# Static sleeves (both legs, one mesh); split by side x for the inner legs.
LEGS = "15_FRONT_LANDING_LEGS_mesh"
# Inner leg scale: stroke to the ground (1.6) > sleeve height (0.675).
LEG_SCALE_Y, LEG_SCALE_XZ = 2.5, 0.75
FOOT_HALF = (0.14, 0.09, 0.2)


def read(rl):
    return open(os.path.join(PACK, "assets", NS, rl.split(":", 1)[1]), "rb").read()


def write(rl, data):
    open(os.path.join(PACK, "assets", NS, rl.split(":", 1)[1]), "wb").write(data)


def at(g, i):
    return g.world(i)[:3, 3]


def reparent(g, i, parent):
    """Move node i under parent, world pose unchanged (colliders: identity rotation)."""
    old = g.parent[i]
    g.nodes[old]["children"].remove(i)
    g.nodes[parent].setdefault("children", []).append(i)
    g.parent[i] = parent
    g.nodes[i]["translation"] = (-at(g, parent)).tolist()


def socket(g, role, target, parent, world, index=None, length=None):
    e = {ROLE: role, HIDDEN: True, IFACE: target}
    if index is not None:
        e[DATA] = {"index": index}
    node = {"name": f"socket_{role}_{target}" + (f"_{index}" if index is not None else ""),
            "translation": (np.asarray(world, float) - at(g, parent)).tolist(), "extras": e}
    if length is not None:
        node["scale"] = [1.0, 1.0, length]
    g.add_node(node, parent)


def wheels(g):
    for name, part in WHEELS.items():
        node = g.by_name(name)
        if ROLE in g.nodes[node].get("extras", {}):
            print(f"== mim104: {name} already rigged")
            continue
        convert_pack.set_role(g.nodes[node], "part", part)
        points, faces = convert_pack.box([-h for h in WHEEL_HALF], list(WHEEL_HALF))
        positions, indices = convert_pack.triangulate(points, faces)
        probe = {"name": f"collider_{part}", "mesh": g.add_mesh(part, positions, indices, [len(f) - 2 for f in faces]),
                 "extras": {HIDDEN: True}}
        convert_pack.set_role(probe, "collider", None, name=part, solid=False)
        g.add_node(probe, node)
        print(f"== mim104: {name} -> {part} at {np.round(at(g, node), 3).tolist()}")


def legs(g):
    if g.by_name("15_LANDING_LEG_L") is not None:
        print("== mim104: legs already rigged")
        return
    sleeve = g.nodes[g.by_name(LEGS)]
    prim = g.doc["meshes"][sleeve["mesh"]]["primitives"][0]
    positions = accessor(g, prim["attributes"]["POSITION"])
    tris = accessor(g, prim["indices"]).reshape(-1, 3)
    for side, sign in (("l", 1), ("r", -1)):
        mine = tris[positions[tris[:, 0], 0] * sign < 0]
        v = positions[mine.ravel()]
        lo, hi = v.min(0), v.max(0)
        cx, foot, cz = (lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2
        buf = g.buffers[0]
        while len(buf) % 4:
            buf.append(0)
        off = len(buf)
        buf += mine.astype("<u4").tobytes()
        g.doc["bufferViews"].append({"buffer": 0, "byteOffset": off, "byteLength": mine.size * 4, "target": 34963})
        g.doc["accessors"].append({"bufferView": len(g.doc["bufferViews"]) - 1, "componentType": 5125,
                                   "count": int(mine.size), "type": "SCALAR"})
        g.doc["meshes"].append({"name": f"landing_leg_{side}", "primitives": [dict(prim, indices=len(g.doc["accessors"]) - 1)]})
        part = f"landing_leg_{side}"
        frame = g.add_node({"name": f"15_LANDING_LEG_{side.upper()}", "translation": [float(cx), float(foot), float(cz)]},
                           g.scene_roots()[0])
        convert_pack.set_role(g.nodes[frame], "part", part)
        # world = foot + t + S v: foot and leg axis fixed under the scale.
        g.add_node({"name": f"15_LANDING_LEG_{side.upper()}_INNER", "mesh": len(g.doc["meshes"]) - 1,
                    "extras": dict(sleeve.get("extras", {})),
                    "translation": [-LEG_SCALE_XZ * float(cx), -LEG_SCALE_Y * float(foot), -LEG_SCALE_XZ * float(cz)],
                    "scale": [LEG_SCALE_XZ, LEG_SCALE_Y, LEG_SCALE_XZ]}, frame)
        points, faces = convert_pack.box([-FOOT_HALF[0], 0, -FOOT_HALF[2]], [FOOT_HALF[0], 2 * FOOT_HALF[1], FOOT_HALF[2]])
        pos, idx = convert_pack.triangulate(points, faces)
        probe = {"name": f"collider_{part}", "mesh": g.add_mesh(part, pos, idx, [len(f) - 2 for f in faces]),
                 "extras": {HIDDEN: True}}
        convert_pack.set_role(probe, "collider", None, name=part, solid=False)
        g.add_node(probe, frame)
        print(f"== mim104: {part} foot {np.round([cx, foot, cz], 3).tolist()}")


def accessor(g, i):
    a = g.doc["accessors"][i]
    view = g.doc["bufferViews"][a["bufferView"]]
    width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3}[a["type"]]
    dtype = {5126: "<f4", 5123: "<u2", 5125: "<u4"}[a["componentType"]]
    start = view.get("byteOffset", 0) + a.get("byteOffset", 0)
    out = np.frombuffer(bytes(g.buffers[0][start:start + a["count"] * width * np.dtype(dtype).itemsize]), dtype)
    return out.reshape(-1, width) if width > 1 else out.astype(np.int64)


def launcher(g):
    yaw, pitch, root = g.by_name(YAW), g.by_name(PITCH), g.scene_roots()[0]
    if ROLE in g.nodes[yaw].get("extras", {}):
        print("== mim104: launcher already rigged")
        return
    convert_pack.set_role(g.nodes[yaw], "part", "launcher", system="turret")
    convert_pack.set_role(g.nodes[pitch], "pitch", "launcher", system="barrel")
    pod = g.add_node({"name": "canisters", "extras": {ROLE: "part", IFACE: "canisters"}}, pitch)

    for i, n in enumerate(list(g.nodes)):
        source = n.get("extras", {}).get("genHulls", {}).get("node")
        if source in YAW_COLLIDERS:
            reparent(g, i, yaw)
        elif source in PITCH_COLLIDERS:
            reparent(g, i, pitch)

    base = at(g, pitch)
    for k, name in enumerate(CANISTERS):
        c = at(g, g.by_name(name)) - base
        front = c[2] + CANISTER_HALF
        points, faces = convert_pack.box([c[0] - STATION_R, c[1] - STATION_R, front - STATION_DEPTH],
                                         [c[0] + STATION_R, c[1] + STATION_R, front])
        positions, indices = convert_pack.triangulate(points, faces)
        node = {"name": f"collider_canisters_{k}",
                "mesh": g.add_mesh(f"canister {k + 1}", positions, indices, [len(f) - 2 for f in faces]),
                "extras": {HIDDEN: True}}
        convert_pack.set_role(node, "collider", None, name=f"canister {k + 1}", solid=False)
        g.add_node(node, pod)

    centres = np.array([at(g, g.by_name(n)) for n in CANISTERS])
    muzzle = [centres[:, 0].mean(), centres[:, 1].mean(), centres[:, 2].max() + CANISTER_HALF]
    socket(g, "bolt", "launcher", pitch, muzzle, index=0, length=0.1)
    socket(g, "view", "launcher", yaw, at(g, yaw) + VIEW)
    socket(g, "seat", "launcher", root, SEAT)
    print(f"== mim104: yaw {np.round(at(g, yaw), 3).tolist()} pitch {np.round(base, 3).tolist()} "
          f"muzzle {np.round(muzzle, 3).tolist()}")


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    g = convert_pack.Gltf(PATH, read)
    launcher(g)
    wheels(g)
    legs(g)
    g.save(PATH, write)


if __name__ == "__main__":
    main()
