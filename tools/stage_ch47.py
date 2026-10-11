#!/usr/bin/env python3
"""Stage EMB4 CH-47 (MCHeli 1.0.3 helicopters/ch47) as role-layout glTF with an internal cargo hold.

    python3 REDACTED      (from CombinedAssault/)

Overwrites the glTF (colliders included) => then tools/damage_textures.py, tools/model_icon.py.
Scale 0.97 = real hub spacing 11.94 m / MCHeli 12.31. Colliders hand-made (hollow cabin; CoACD would fill it).
Divergences: ramp opens 42 deg (MCHeli 34) => lip on the ground; troop seats moved to the cabin walls (MCHeli centre
line = inside a stowed vehicle); sideways/rearward guns turned to +Z about their pivot; spinning m134 barrels merged.
"""
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import convert_pack  # noqa: E402
from stage_emb4_ce import DATA, HIDDEN, IFACE, NS, PACK, ROLE, Rig, Source, eye, yaw  # noqa: E402

SPEC = dict(root=os.path.expanduser("~/projects/mcheli-gigarework/1.7.10-original/mcheli/assets/mcheli"),
            src="helicopters/ch47", sha1="82283521db7038be6cc97cdcba58830b647d6fcb", texture="helicopters/ch47",
            kind="aircraft", scale=0.97, ground=0.15)
HINGE = (0.0, 0.8578, -11.5485)
# Cabin, MCHeli units: walls |x| 1.36, floor top 0.86, ceiling 3.1, ramp hinge -> front bulkhead.
CABIN = dict(x=1.36, floor=0.86, ceiling=3.1, aft=-11.55, fore=-1.9)
SKIN = 1.75


def stage(src):
    T = src.T
    rig = Rig(src, "aircraft")
    src.used.add("$draftingtable_blade_dummy")

    def rotor(name, part, hub, system):
        hub = T(hub)
        pos, nrm, uv = [], [], []
        for b in range(3):
            p, n, t = src.geometry([part], hub, yaw(120 * b))
            pos.append(p)
            nrm.append(n)
            uv.append(t)
        rig.node(name, rig.root, hub, rig.w.mesh(name, np.vstack(pos), np.vstack(nrm), np.vstack(uv)),
                 {DATA: {"system": system}})

    rotor("rotor", "$blade0", (0.0, 4.6510, -0.2981), "rotor")
    rotor("rotor_aft", "$blade1", (0.0, 6.0486, -12.6138), "rotor")
    rig.drawn("ramp", rig.root, ["$hatch0"], T(HINGE), {ROLE: "part", IFACE: "ramp"})

    def gun(part, mount, barrel, at, degrees, seat):
        at = T(at)
        turn = yaw(degrees)
        mesh = rig.w.mesh(part, *src.geometry(mount, at, turn)) if mount else None
        frame = rig.node(part, rig.root, at, mesh, {ROLE: "part", IFACE: part, DATA: {"system": "barrel"}})
        up = rig.drawn(f"{part}_up", frame, barrel, at, {ROLE: "pitch", IFACE: part}, turn)
        tip = at + turn @ (src.muzzle(barrel, turn.T @ (0, 0, 1)) - at)
        breech = np.array([tip[0], tip[1], at[2]])
        rig.socket("bolt", part, up, breech, index=0, length=float(tip[2] - breech[2]))
        rig.socket("seat", part, rig.root, eye(src, seat))
        rig.socket("view", part, frame, at + [0.0, 0.35, -0.3])

    # MCHeli +X = port: m134_l50 (x < 0) is the starboard gun.
    gun("door_gun_r", ["$weapon1"], ["$weapon2", "$weapon3"], (-1.2859, 1.941, -1.5192), -90, (-0.68, 1.3, -1.5))
    gun("door_gun_l", ["$weapon4"], ["$weapon5", "$weapon6"], (1.2859, 1.941, -1.5192), 90, (0.68, 1.3, -1.5))
    gun("ramp_gun", None, ["$weapon0"], (-0.7, 1.6, -11.15), 180, (-0.82, 1.2, -10.1))

    pilot = eye(src, (0.79, 1.5, -0.5))
    rig.socket("seat", "pilot", rig.root, pilot)
    rig.socket("view", "pilot", rig.root, pilot)
    rig.socket("seat", "copilot", rig.root, eye(src, (-0.79, 1.5, -0.5)))
    rig.socket("view", "copilot", rig.root, eye(src, (-0.79, 1.5, -0.5)))
    flare = rig.node("decoy_flare", rig.root, T((0.0, 1.0, -9.0)), extras={ROLE: "part", IFACE: "decoy_flare"})
    flare_pitch = rig.node("decoy_flare_barrel", flare, T((0.0, 1.0, -9.0)),
                           extras={ROLE: "pitch", IFACE: "decoy_flare", DATA: {"system": "barrel"}})
    for i, x in enumerate((1.8, -1.8)):
        rig.socket("bolt", "decoy_flare", flare_pitch, T((x, 1.0, -9.0)), index=i, length=0.1)
    for i, z in enumerate((-3.5, -5.0, -6.5, -8.0)):
        for j, x in enumerate((1.05, -1.05)):
            seat = eye(src, (x, 1.2, z))
            rig.socket("seat", f"troop_{2 * i + j}", rig.root, seat)
            rig.socket("view", f"troop_{2 * i + j}", rig.root, seat)
    rig.socket("camera", None, rig.root, (0.0, 7.0, -6.0))
    rig.socket("camera_zoomed", None, rig.root, (0.0, 8.0, -6.0))
    rig.socket("light", None, rig.root, T((0.0, 0.5, 5.5)), extras={"kind": "search"})
    rig.w.doc["nodes"][rig.index["socket_light"]]["rotation"] = [math.sin(math.radians(10)), 0.0, 0.0,
                                                                 math.cos(math.radians(10))]
    lo = T((-CABIN["x"], CABIN["floor"], CABIN["aft"])) - [0.0, 0.3, 0.0]
    hi = T((CABIN["x"], CABIN["ceiling"], CABIN["fore"]))
    rig.node("socket_cargo_bay", rig.root, (lo + hi) / 2, extras={ROLE: "cargo_bay", HIDDEN: True}, scale=hi - lo)
    body = rig.body()
    rig.write("aircraft")
    colliders(rig)
    lo, hi = src.bounds(body)
    ramp = src.bounds(["$hatch0"])
    print(f"== ch47: body {len(body)} parts, bounds {np.round(lo, 3).tolist()} {np.round(hi, 3).tolist()}, "
          f"ramp {np.round(ramp[0], 3).tolist()} {np.round(ramp[1], 3).tolist()}")


def hexa(corner):
    """corner(ix, iy, iz) -> point, each coordinate increasing with its index => (points, faces), outward winding."""
    order = [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0), (0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)]
    return [[float(v) for v in corner(*i)] for i in order], convert_pack.box((0, 0, 0), (1, 1, 1))[1]


def cuboid(src, lo, hi):
    lo, hi = src.T(lo), src.T(hi)
    return convert_pack.box(lo.tolist(), hi.tolist())


def colliders(rig):
    src, c, s = rig.src, CABIN, SPEC["scale"]
    path = os.path.join(PACK, "assets", NS, "models", "aircraft", src.id, f"{src.id}.gltf")
    gltf = convert_pack.Gltf(path, lambda rl: open(os.path.join(os.path.dirname(path), f"{src.id}.bin"), "rb").read())
    x, w = c["x"], SKIN
    ramp_hi = src.bounds(["$hatch0"])[1]
    # Ramp: top surface hinge -> lip, 0.12 thick, in the ramp frame (rest = closed).
    lip = np.array([0.0, 1.62, -13.6]) - HINGE
    rise = lip * s
    half = float(ramp_hi[0])
    ramp = hexa(lambda ix, iy, iz: ((-half, half)[ix], (rise[1], 0.0)[iz] + (-0.12, 0.0)[iy], (rise[2], 0.0)[iz]))
    tail_floor = (3.1, 2.33)
    pieces = [
        ("hold floor", None, True, cuboid(src, (-w, 0.57, c["aft"]), (w, c["floor"], c["fore"]))),
        ("hold wall l", None, False, cuboid(src, (x, c["floor"], c["aft"]), (w, c["ceiling"], c["fore"]))),
        ("hold wall r", None, False, cuboid(src, (-w, c["floor"], c["aft"]), (-x, c["ceiling"], c["fore"]))),
        ("hold roof", None, False, cuboid(src, (-w, c["ceiling"], c["aft"]), (w, 3.5, c["fore"]))),
        ("cockpit", None, False, cuboid(src, (-w, 0.67, c["fore"]), (w, 3.14, 6.46))),
        ("forward pylon", None, False, cuboid(src, (-0.8, 3.1, -2.0), (0.8, 4.3, 1.2))),
        ("tail", None, False, hexa(lambda ix, iy, iz: src.T(((-1.5, 1.5)[ix],
                                                              ((tail_floor[1], tail_floor[0])[iz], 5.8)[iy],
                                                              (-14.69, c["aft"])[iz])))),
        ("ramp", "ramp", True, ramp),
        ("front wheel l", None, False, cuboid(src, (1.5, -0.15, -4.79), (1.97, 0.82, -4.16))),
        ("front wheel r", None, False, cuboid(src, (-1.97, -0.15, -4.79), (-1.5, 0.82, -4.16))),
        ("rear wheel l", None, False, cuboid(src, (1.5, 0.18, -11.93), (1.95, 1.14, -11.31))),
        ("rear wheel r", None, False, cuboid(src, (-1.95, 0.18, -11.93), (-1.5, 1.14, -11.31))),
    ]
    for i, (name, frame, deck, (points, faces)) in enumerate(pieces):
        parent = gltf.by_name(frame) if frame else gltf.scene_roots()[0]
        positions, indices = convert_pack.triangulate(points, faces)
        node = {"name": f"collider_{(frame or 'body')}_{i}",
                "mesh": gltf.add_mesh(name, positions, indices, [len(f) - 2 for f in faces]),
                "extras": {HIDDEN: True}}
        data = {"name": name}
        if deck:
            data["deck"] = True
        convert_pack.set_role(node, "collider", None, **data)
        if frame:
            node["translation"] = [0.0, 0.0, 0.0]
        gltf.add_node(node, parent)
    gltf.save(path, lambda rl, data: open(os.path.join(os.path.dirname(path), f"{src.id}.bin"), "wb").write(data))


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    import shutil
    src = Source("ch47", SPEC)
    textures = os.path.join(PACK, "assets", NS, "textures", "aircraft", "ch47")
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(SPEC["root"], "textures", f"{SPEC['texture']}.png"), os.path.join(textures, "ch47.png"))
    stage(src)


if __name__ == "__main__":
    main()
