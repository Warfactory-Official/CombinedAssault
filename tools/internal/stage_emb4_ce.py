#!/usr/bin/env python3
"""Stage McHeliCE stock EMB4 models absent from MCHeli 1.0.3 (CE commit 5af67efb) as role-layout glTF.

    python3 tools/internal/stage_emb4_ce.py [w3 oto127 krupp_c34]      (from CombinedAssault/)

Overwrites the glTF (colliders included) => then per vehicle: tools/gen_hulls.py, tools/damage_textures.py,
tools/model_icon.py.
MCHeli blocks -> ours: (p + (0, ground, 0)) * scale. Seat = hip => eye = seat + (0, 1.2, 0).
Divergences: sideways-modelled guns (yml DefaultYaw) turned to +Z about their pivot => bolt/view local +Z = bore.
Recoil = rotation about +X of a node SLIDE blocks below the barrel (GemRender poses rotation only).
"""
import hashlib
import math
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mqo  # noqa: E402

SRC = os.path.expanduser("~/projects/mcheli-gigarework/McHeliCE/src/main/resources/assets/mcheli")
PACK = "src/main/resources/combinedassault_packs/combinedassault"
NS = "combinedassault"
SLIDE = 20.0
ROLE, IFACE, DATA, HIDDEN = "combinedassaultRole", "combinedassaultInterface", "combinedassault", "gemrenderHidden"


def emplacement(src, sha1, ground, trunnion, view, camera):
    return dict(src=f"vehicles/{src}", sha1=sha1, texture=f"vehicles/{src}", kind="emplacement", scale=1.0,
                ground=ground, trunnion=trunnion, view=view, camera=camera)


VEHICLES = {
    "oto127": emplacement("oto127mm", "0ecdb90df5c9f69e6f311c2d2fffaa6eba5ca386", 0.35, (0.0, 2.24, -0.68),
                          (0.0, 4.0, 0.0), (0.0, 2.0, 0.0)),
    "krupp_c34": emplacement("krupp_c_34", "e35411d72b9b2016be26149fffc73db03c356e5f", 0.31, (0.0, 2.84, -0.06),
                             (0.0, 5.0, 0.0), (0.0, 2.5, -2.0)),
    # rotor 16.6 -> 15.6 (real 15.7)
    "w3": dict(src="helicopters/w3", sha1="508e20f118ec2ea93c2b2b46c3915e16ff01227d", texture="helicopters/w3",
               kind="aircraft", scale=0.94, ground=0.31),
}


class Source:
    def __init__(self, vid, spec):
        self.id, self.spec = vid, spec
        path = os.path.join(spec.get("root", SRC), "models", f"{spec['src']}.mqo")
        digest = hashlib.sha1(open(path, "rb").read()).hexdigest()
        if digest != spec["sha1"]:
            raise SystemExit(f"{path}: sha1 {digest} != pinned")
        self.parts = mqo.parts(mqo.read(path))
        self.used = set()

    def T(self, p):
        return (np.asarray(p, dtype=float) + [0.0, self.spec["ground"], 0.0]) * self.spec["scale"]

    def geometry(self, parts, pivot, turn=None):
        """(pos, nrm, uv) of `parts` relative to `pivot` (ours); `turn` rotates about it."""
        pos, nrm, uv = [], [], []
        for p in parts:
            self.used.add(p)
            for o in self.parts[p]:
                if o.tris:
                    a, b, c = mqo.triangles(o, self.T)
                    pos.append(a - pivot)
                    nrm.append(b)
                    uv.append(c)
        pos, nrm = np.vstack(pos), np.vstack(nrm)
        if turn is not None:
            pos, nrm = pos @ turn.T, nrm @ turn.T
        return pos, nrm, np.vstack(uv)

    def bounds(self, parts):
        pts = self.T(np.vstack([o.verts for p in parts for o in self.parts[p] if len(o.verts)]))
        return pts.min(axis=0), pts.max(axis=0)

    def muzzle(self, parts, axis):
        """Centre of the vertices within 0.2 of the furthest along `axis` (unit, ours)."""
        pts = self.T(np.vstack([o.verts for p in parts for o in self.parts[p] if len(o.verts)]))
        d = pts @ axis
        ring = pts[d > d.max() - 0.2]
        return (ring.min(axis=0) + ring.max(axis=0)) / 2


def yaw(degrees):
    """Our yaw (+ => starboard, -X) as a glTF matrix: +Z turned towards -X."""
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, -s], [0, 1, 0], [s, 0, c]])


class Rig:
    def __init__(self, src, folder):
        self.src = src
        self.w = mqo.GltfWriter(f"{NS}:models/{folder}/{src.id}/{src.id}.bin",
                                f"{NS}:textures/{folder}/{src.id}/{src.id}.png", "tools/internal/stage_emb4_ce.py")
        self.world = {}
        self.index = {}
        self.root = self.node(src.id, None, (0, 0, 0), extras={ROLE: "body"})

    def node(self, name, parent, at, mesh=None, extras=None, scale=None):
        """`at`: vehicle-frame rest position (ours); frames carry no rest rotation."""
        at = np.asarray(at, dtype=float)
        local = at - (self.world[parent] if parent else 0)
        i = self.w.node(name, local, mesh, self.index[parent] if parent else None)
        if extras:
            self.w.doc["nodes"][i]["extras"] = extras
        if scale is not None:
            self.w.doc["nodes"][i]["scale"] = [float(v) for v in scale]
        self.world[name], self.index[name] = at, i
        return name

    def drawn(self, name, parent, parts, pivot, extras=None, turn=None):
        return self.node(name, parent, pivot, self.w.mesh(name, *self.src.geometry(parts, pivot, turn)), extras)

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

    def body(self):
        rest = [q for q in self.src.parts if q not in self.src.used]
        self.drawn("body", self.root, rest, np.zeros(3))
        return rest

    def write(self, folder):
        models = os.path.join(PACK, "assets", NS, "models", folder, self.src.id)
        os.makedirs(models, exist_ok=True)
        self.w.write(os.path.join(models, f"{self.src.id}.gltf"), os.path.join(models, f"{self.src.id}.bin"),
                     {"staged_by": "tools/internal/stage_emb4_ce.py", "source": f"EMB4 McHeliCE stock {self.src.spec['src']}",
                      "scale": self.src.spec["scale"]})


def stage_emplacement(src):
    s, T = src.spec, src.T
    rig = Rig(src, "emplacement")
    mount = rig.drawn("mount", rig.root, ["$part0"], T((0, 0, 0)),
                      {ROLE: "part", IFACE: "gun", DATA: {"system": "turret"}})
    trunnion = T(s["trunnion"])
    cradle = rig.drawn("cradle", mount, ["$part0_0"], trunnion, {ROLE: "pitch", IFACE: "gun"})
    slide = trunnion - [0.0, SLIDE, 0.0]
    rig.drawn("barrel", cradle, ["$part0_1"], slide, {DATA: {"system": "barrel"}})
    tip = src.muzzle(["$part0_1"], (0, 0, 1))
    breech = np.array([tip[0], tip[1], trunnion[2]])
    rig.socket("bolt", "gun", cradle, breech, index=0, length=float(tip[2] - breech[2]))
    rig.socket("seat", "gun", mount, T((0.0, 0.6 + 1.2, 0.0)))
    rig.socket("view", "gun", mount, T(s["view"]))
    rig.socket("sight", "gun", mount, T(s["view"]))
    rig.socket("camera", None, rig.root, T(s["camera"]))
    rig.socket("camera_zoomed", None, rig.root, T(s["camera"]))
    rig.body()
    rig.write("emplacement")
    print(f"== {src.id}: trunnion {np.round(trunnion, 3).tolist()} muzzle {np.round(tip, 3).tolist()}")


def eye(src, p):
    return src.T(np.add(p, (0.0, 1.2, 0.0)))


def stage_w3(src):
    T = src.T
    rig = Rig(src, "aircraft")
    rig.drawn("rotor", rig.root, ["$blade0"], T((0.0, 3.66, 0.0)), {DATA: {"system": "rotor"}})
    rig.drawn("tail_rotor", rig.root, ["$blade1"], T((-0.28, 3.93, -10.7)), {DATA: {"system": "tail_rotor"}})

    sight = rig.drawn("sight", rig.root, ["$camera0"], T((0.0, 2.65, 1.91)),
                      {ROLE: "part", IFACE: "sighting_system", DATA: {"system": "optics"}})
    pilot = eye(src, (-0.55, 0.75, 2.4))
    rig.socket("seat", "sighting_system", rig.root, pilot)
    rig.socket("view", "sighting_system", sight, pilot)
    rig.socket("sight", "sighting_system", sight, T((0.0, 2.65, 1.91)) + [0.0, 0.0, 0.2])
    rig.socket("bolt", "sighting_system", sight, T((0.0, 2.65, 1.91)), index=0, length=0.1)

    nose = rig.drawn("nose_gun", rig.root, ["$weapon0"], T((0.0, 0.07, 4.01)),
                     {ROLE: "part", IFACE: "nose_gun", DATA: {"system": "barrel"}})
    nose_up = rig.drawn("nose_gun_up", nose, ["$weapon0_0"], T((0.0, 0.27, 4.01)), {ROLE: "pitch", IFACE: "nose_gun"})
    tip = src.muzzle(["$weapon0"], (0, 0, 1))
    breech = np.array([tip[0], tip[1], rig.world[nose_up][2]])
    rig.socket("bolt", "nose_gun", nose_up, breech, index=0, length=float(tip[2] - breech[2]))
    copilot = eye(src, (0.55, 0.75, 2.4))
    rig.socket("seat", "nose_gun", rig.root, copilot)
    rig.socket("view", "nose_gun", nose, copilot)

    # yml DefaultYaw 90: modelled pointing starboard.
    door_at = T((-1.18, 1.41, 1.04))
    turn = yaw(-90)
    door = rig.drawn("door_gun", rig.root, ["$weapon1"], door_at,
                     {ROLE: "part", IFACE: "door_gun", DATA: {"system": "barrel", "side": "right"}}, turn)
    door_up_at = door_at + turn @ (T((-1.24, 1.24, 1.04)) - door_at)
    door_up = rig.drawn("door_gun_up", door, ["$weapon1_0"], door_up_at, {ROLE: "pitch", IFACE: "door_gun"}, turn)
    tip = door_at + turn @ (src.muzzle(["$weapon1"], (-1, 0, 0)) - door_at)
    breech = np.array([tip[0], tip[1], door_up_at[2]])
    rig.socket("bolt", "door_gun", door_up, breech, index=0, length=float(tip[2] - breech[2]))
    rig.socket("seat", "door_gun", rig.root, eye(src, (-0.78, 0.87, 1.04)))
    rig.socket("view", "door_gun", door, door_at + [0.0, 0.35, -0.3])

    def fixed(part, at, muzzles, length):
        frame = rig.node(part, rig.root, T(at), extras={ROLE: "part", IFACE: part})
        pitch = rig.node(f"{part}_barrel", frame, T(at), extras={ROLE: "pitch", IFACE: part, DATA: {"system": "barrel"}})
        for k, m in enumerate(muzzles):
            rig.socket("bolt", part, pitch, T(m), index=k, length=length)

    fixed("rocket", (0.0, 1.07, -0.45), [(-1.8, 1.07, -0.45), (1.8, 1.07, -0.45)], 1.0)
    fixed("grom", (-2.51, 1.39, 0.6), [(-2.66, 1.39, 0.6), (-2.36, 1.39, 0.6)], 1.0)
    fixed("decoy_flare", (0.0, 1.5, -5.0), [(0.6, 1.5, -5.0), (-0.6, 1.5, -5.0)], 0.1)

    for k, p in enumerate([(0.34, 0.83, -0.2), (-0.34, 0.83, -0.2), (0.34, 0.83, -0.91), (-0.34, 0.83, -0.91),
                           (0.34, 0.83, -1.7), (-0.34, 0.83, -1.7)]):
        rig.socket("seat", f"seat{k + 3}", rig.root, eye(src, p))
        rig.socket("view", f"seat{k + 3}", rig.root, eye(src, p))
    rig.socket("camera", None, rig.root, (0.0, 6.0, 0.0))
    rig.socket("camera_zoomed", None, rig.root, (0.0, 7.0, 0.0))
    rig.socket("light", None, rig.root, T((0.0, 0.2, 4.4)), extras={"kind": "search"})
    rig.w.doc["nodes"][rig.index["socket_light"]]["rotation"] = [math.sin(math.radians(10)), 0.0, 0.0,
                                                                 math.cos(math.radians(10))]
    body = rig.body()
    rig.write("aircraft")
    lo, hi = src.bounds(body)
    print(f"== w3: body {len(body)} parts, bounds {np.round(lo, 3).tolist()} {np.round(hi, 3).tolist()}")


def stage(vid):
    src = Source(vid, VEHICLES[vid])
    folder = src.spec["kind"]
    textures = os.path.join(PACK, "assets", NS, "textures", folder, vid)
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(SRC, "textures", f"{src.spec['texture']}.png"), os.path.join(textures, f"{vid}.png"))
    (stage_w3 if vid == "w3" else stage_emplacement)(src)


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    for vid in sys.argv[1:] or VEHICLES:
        stage(vid)


if __name__ == "__main__":
    main()
