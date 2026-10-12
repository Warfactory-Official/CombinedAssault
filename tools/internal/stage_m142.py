#!/usr/bin/env python3
"""Stage warty's M142 HIMARS (empty six-cell pod, remesh of MCHeli m142.mqo) as role-layout glTF.

    python3 tools/internal/stage_m142.py      (from CombinedAssault/)

Overwrites the glTF (colliders included) => then tools/damage_textures.py, tools/model_icon.py.
Source glTF forward -X, up +Y, MCHeli scale (12.1 long) => ours: yaw +90 (forward +Z, +X port), x0.578 = real
7.0 m. Transforms baked into vertices: every frame unrotated.
Divergences: door hinges and steering pivots static (no part); pod stations = front 1.0 block of each cell (launch
from the station tail => clear of the pod).
"""
import hashlib
import io
import json
import os
import struct
import sys
import zipfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import convert_pack  # noqa: E402
import mqo  # noqa: E402
from stage_emb4_ce import DATA, HIDDEN, IFACE, NS, PACK, ROLE  # noqa: E402

ZIP = os.path.expanduser("~/models/warty/M142_Empty_Blender_glTF.zip")
SHA1 = "38b28553540bd0ded69b6d66e0d503c31128fea9"
SCALE = 0.578
VID = "m142"
STATION_DEPTH = 1.0
# Cab: eye height under the 2.41 roof; driver port, operator starboard, chief centre.
SEATS = {"driver": (0.48, 2.2, 2.62), "launcher": (-0.48, 2.2, 2.62), "chief": (0.0, 2.2, 2.45)}
WHEELS = {"WHEEL_0_SPIN": "wheel_0_l", "WHEEL_3_SPIN": "wheel_0_r", "WHEEL_1_SPIN": "wheel_1_l",
          "WHEEL_4_SPIN": "wheel_1_r", "WHEEL_2_SPIN": "wheel_2_l", "WHEEL_5_SPIN": "wheel_2_r"}
YAW_MESHES = ["LAUNCHER_BASE_LAUNCHER_YAW"]
PITCH_MESHES = ["LAUNCHER_CRADLE_LAUNCHER_ELEVATION", "EMPTY_POD_EMPTY_POD_ROOT"]


class Source:
    def __init__(self):
        if hashlib.sha1(open(ZIP, "rb").read()).hexdigest() != SHA1:
            raise SystemExit(f"{ZIP}: sha1 != pinned")
        self.zip = zipfile.ZipFile(ZIP)
        self.doc = json.loads(self.zip.read("m142.gltf"))
        self.blob = self.zip.read("m142.bin")
        self.index = {n["name"]: i for i, n in enumerate(self.doc["nodes"])}
        self.parent = {c: i for i, n in enumerate(self.doc["nodes"]) for c in n.get("children", [])}
        c = np.zeros((4, 4))
        c[0, 2], c[1, 1], c[2, 0], c[3, 3] = 1.0, 1.0, -1.0, 1.0
        c[:3, :3] *= SCALE
        self.ours = c

    def world(self, name):
        i = self.index[name]
        m = convert_pack.trs(self.doc["nodes"][i])
        while i in self.parent:
            i = self.parent[i]
            m = convert_pack.trs(self.doc["nodes"][i]) @ m
        return self.ours @ m

    def origin(self, name):
        return self.world(name)[:3, 3]

    def accessor(self, i):
        a = self.doc["accessors"][i]
        v = self.doc["bufferViews"][a["bufferView"]]
        width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3}[a["type"]]
        dtype = {5126: np.float32, 5125: np.uint32, 5123: np.uint16}[a["componentType"]]
        stride = v.get("byteStride", 0)
        assert not stride or stride == np.dtype(dtype).itemsize * width, "interleaved"
        return np.frombuffer(self.blob, dtype=dtype, count=a["count"] * width,
                             offset=v.get("byteOffset", 0) + a.get("byteOffset", 0)).reshape(-1, width)

    def geometry(self, names, pivot):
        """Unindexed (pos, nrm, uv) of mesh nodes `names`, ours, relative to `pivot`."""
        pos, nrm, uv = [], [], []
        for name in names:
            m = self.world(name)
            rot = m[:3, :3] / SCALE
            for prim in self.doc["meshes"][self.doc["nodes"][self.index[name]]["mesh"]]["primitives"]:
                idx = self.accessor(prim["indices"]).reshape(-1)
                p = self.accessor(prim["attributes"]["POSITION"])[idx].astype(float)
                pos.append((p @ m[:3, :3].T + m[:3, 3]) - pivot)
                nrm.append(self.accessor(prim["attributes"]["NORMAL"])[idx].astype(float) @ rot.T)
                uv.append(self.accessor(prim["attributes"]["TEXCOORD_0"])[idx].astype(float))
        return np.vstack(pos), np.vstack(nrm), np.vstack(uv)

    def bounds(self, names):
        pos = self.geometry(names, np.zeros(3))[0]
        return pos.min(axis=0), pos.max(axis=0)


class Rig:
    def __init__(self, src):
        self.src = src
        self.w = mqo.GltfWriter(f"{NS}:models/ground/{VID}/{VID}.bin", f"{NS}:textures/ground/{VID}/{VID}.png",
                                "tools/internal/stage_m142.py")
        self.w.doc["materials"][0]["doubleSided"] = True
        self.world = {}
        self.index = {}
        self.root = self.node(VID, None, (0, 0, 0), extras={ROLE: "body"})

    def node(self, name, parent, at, mesh=None, extras=None, scale=None):
        at = np.asarray(at, dtype=float)
        i = self.w.node(name, at - (self.world[parent] if parent else 0), mesh,
                        self.index[parent] if parent else None)
        if extras:
            self.w.doc["nodes"][i]["extras"] = extras
        if scale is not None:
            self.w.doc["nodes"][i]["scale"] = [float(v) for v in scale]
        self.world[name], self.index[name] = at, i
        return name

    def drawn(self, name, parent, meshes, pivot, extras=None):
        return self.node(name, parent, pivot, self.w.mesh(name, *self.src.geometry(meshes, pivot)), extras)

    def socket(self, role, target, parent, at, index=None, length=None, extras=None):
        data = dict(extras or {})
        if index is not None:
            data["index"] = index
        e = {ROLE: role, HIDDEN: True}
        if target:
            e[IFACE] = target
        if data:
            e[DATA] = data
        name = f"socket_{role}" + (f"_{target}" if target else "") + (f"_{index}" if index is not None else "")
        return self.node(name, parent, at, extras=e, scale=None if length is None else (1, 1, length))


def mesh_nodes(src):
    return [n["name"] for n in src.doc["nodes"] if "mesh" in n]


def stage(src):
    rig = Rig(src)
    wheel_meshes = {pivot: f"WHEELS_{pivot}" for pivot in WHEELS}
    used = set(YAW_MESHES) | set(PITCH_MESHES) | set(wheel_meshes.values())
    rig.drawn("hull", rig.root, [n for n in mesh_nodes(src) if n not in used], np.zeros(3))

    yaw_at = src.origin("LAUNCHER_YAW")
    pitch_at = src.origin("LAUNCHER_ELEVATION")
    rig.drawn("launcher", rig.root, YAW_MESHES, yaw_at,
              {ROLE: "part", IFACE: "launcher", DATA: {"system": "turret"}})
    rig.drawn("launcher_pitch", "launcher", PITCH_MESHES, pitch_at,
              {ROLE: "pitch", IFACE: "launcher", DATA: {"system": "barrel"}})
    rig.node("pod", "launcher_pitch", pitch_at, extras={ROLE: "part", IFACE: "pod"})

    for pivot, part in WHEELS.items():
        side = "left" if part.endswith("_l") else "right"
        rig.drawn(part, rig.root, [wheel_meshes[pivot]], src.origin(pivot),
                  {ROLE: "part", IFACE: part, DATA: {"system": "wheel", "side": side}})

    pod_lo, pod_hi = src.bounds(["EMPTY_POD_EMPTY_POD_ROOT"])
    muzzle = np.array([(pod_lo[0] + pod_hi[0]) / 2, (pod_lo[1] + pod_hi[1]) / 2, pod_hi[2]])
    rig.socket("bolt", "launcher", "launcher_pitch", muzzle, index=0, length=0.1)
    rig.socket("view", "launcher", "launcher", yaw_at + [0.0, 1.8, -0.4])
    for part, eye in SEATS.items():
        rig.socket("seat", part, rig.root, eye)
        if part != "launcher":
            rig.socket("view", part, rig.root, eye)
    rig.socket("camera", None, rig.root, (0.0, 2.0, 0.0))
    rig.socket("exhaust", None, rig.root, (-1.0, 2.3, 1.6), index=0)
    rig.socket("towable", None, rig.root, (0.0, 0.85, 3.45))
    rig.socket("hitch", "pintle", rig.root, (0.0, 0.8, -3.15))
    for side, x in (("l", 0.85), ("r", -0.85)):
        rig.node(f"socket_light_head_{side}", rig.root, (x, 1.0, 3.45),
                 extras={ROLE: "light", HIDDEN: True, DATA: {"kind": "head"}})

    models = os.path.join(PACK, "assets", NS, "models", "ground", VID)
    os.makedirs(models, exist_ok=True)
    path = os.path.join(models, f"{VID}.gltf")
    rig.w.write(path, os.path.join(models, f"{VID}.bin"),
                {"staged_by": "tools/internal/stage_m142.py", "source": "warty M142_Empty_Blender_glTF", "scale": SCALE})
    colliders(src, path, pitch_at)
    textures = os.path.join(PACK, "assets", NS, "textures", "ground", VID)
    os.makedirs(textures, exist_ok=True)
    with open(os.path.join(textures, f"{VID}.png"), "wb") as f:
        f.write(src.zip.read("textures/m142_atlas.png"))
    print(f"== m142: yaw {np.round(yaw_at, 3).tolist()} pitch {np.round(pitch_at, 3).tolist()} "
          f"muzzle {np.round(muzzle, 3).tolist()} pod {np.round(pod_lo, 3).tolist()} {np.round(pod_hi, 3).tolist()}")


def colliders(src, path, pitch_at):
    gltf = convert_pack.Gltf(path, lambda rl: open(path[:-5] + ".bin", "rb").read())
    chassis = src.bounds(["CHASSIS"])
    cab = src.bounds(["CAB"])
    cradle = src.bounds(["LAUNCHER_CRADLE_LAUNCHER_ELEVATION", "EMPTY_POD_EMPTY_POD_ROOT"])
    deck = chassis[1][1]
    pieces = [
        ("chassis", None, True, convert_pack.box([chassis[0][0], 0.55, chassis[0][2]],
                                                 [chassis[1][0], deck, chassis[1][2]])),
        ("cab", None, False, convert_pack.box([cab[0][0], deck, cab[0][2]], cab[1].tolist())),
        ("launcher", None, False, convert_pack.box([cradle[0][0], deck, cradle[0][2]], cradle[1].tolist())),
    ]
    for pivot, part in WHEELS.items():
        at = src.origin(pivot)
        lo, hi = src.bounds([f"WHEELS_{pivot}"])
        pieces.append((part, part, False, convert_pack.box((lo - at).tolist(), (hi - at).tolist())))
    r = 0.22 * SCALE
    for i in range(6):
        cell = src.origin(f"ATTACH_CELL_{i + 1:02d}") - pitch_at
        pieces.append((f"cell {i + 1}", "pod", False, convert_pack.box(
            [cell[0] - r, cell[1] - r, cell[2] - STATION_DEPTH], [cell[0] + r, cell[1] + r, cell[2]])))
    for i, (name, frame, main, (points, faces)) in enumerate(pieces):
        positions, indices = convert_pack.triangulate(points, faces)
        node = {"name": f"collider_{frame or 'body'}_{i}",
                "mesh": gltf.add_mesh(name, positions, indices, [len(f) - 2 for f in faces]),
                "extras": {HIDDEN: True}}
        data = {"name": name}
        if main:
            data["main"] = True
        if frame:
            data["solid"] = False
        convert_pack.set_role(node, "collider", None, **data)
        gltf.add_node(node, gltf.by_name(frame) if frame else gltf.scene_roots()[0])
    gltf.save(path, lambda rl, data: open(path[:-5] + ".bin", "wb").write(data))


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    stage(Source())


if __name__ == "__main__":
    main()
