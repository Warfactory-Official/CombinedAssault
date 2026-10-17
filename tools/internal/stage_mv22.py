#!/usr/bin/env python3
"""Stage MCHeli MV-22 (planes/mv-22) as role-layout glTF: tilting nacelles on the `thrust` part, gear + cargo door clips.

    python3 tools/internal/stage_mv22.py      (from CombinedAssault/)
    ~/.cache/combinedassault-hulls/bin/python tools/gen_hulls.py mv22 --main
    python3 tools/damage_textures.py; python3 tools/model_icon.py mv22

Scale 0.92: hub spacing 15.2 -> 14.0 (real 14.0), length 19.0 -> 17.5 (real 17.5). Ground 0.30 = wheel bottom.
Nacelles: body nodes (part-frame children = part hits, not `rotor` pools); script turns them by `thrust` xRot (0 cruise, -90 up).
Divergences: no walk-in hold (cabin 1.6 high) => storage + seats; skin us-2 only.
"""
import math
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import convert_pack  # noqa: E402
import mqo  # noqa: E402
from stage_emb4_ce import DATA, HIDDEN, IFACE, NS, PACK, ROLE, Rig, Source, eye  # noqa: E402

SPEC = dict(root=os.path.expanduser("~/projects/mcheli-gigarework/1.7.10-original/mcheli/assets/mcheli"),
            src="planes/mv-22", sha1="1b85b0c0654cf7ccfb107062223ee8a1617050ea", texture="planes/mv-22-us-2",
            kind="aircraft", scale=0.92, ground=0.30)
# mv-22.txt: AddPartRotor / AddBlade / AddPartHatch / AddPartLG (MCHeli +X = port).
NACELLE = (7.60, 3.83, -4.31)
HUB = (7.60, 4.29, -1.49)
HATCHES = [("ramp", "$hatch0", (0.0, 0.43, -9.06), -38), ("ramp_door", "$hatch1", (0.0, 2.48, -13.44), -27)]
# (node, part, pivot, clip axis, degrees retracted)
GEAR = [("gear_nose", "$lg0", (0.0, 0.85, 1.19), "x", 90), ("gear_main", "$lg1", (0.0, 1.00, -5.85), "x", 90),
        ("gear_door_l", "$lg2", (2.33, 0.91, -5.96), "z", -90), ("gear_door_r", "$lg3", (-2.33, 0.91, -5.96), "z", 90)]
SEATS = [(x, z) for z in (-2.87, -3.96, -5.06, -6.14, -7.17, -8.33) for x in (-0.70, 0.70)]
WHEEL_TOP = 0.35


def stage(src):
    T = src.T
    rig = Rig(src, "aircraft")
    rig.node("thrust", rig.root, T((0.0, NACELLE[1], NACELLE[2])),
             extras={ROLE: "part", IFACE: "thrust", DATA: {"x_rot_min": -97, "x_rot_max": 0}})
    for side, sign, part, blade, spin in (("l", 1, "$rotor0", "$blade0", "ccw"), ("r", -1, "$rotor1", "$blade1", "cw")):
        nacelle = rig.drawn(f"nacelle_{side}", rig.root, [part], T((sign * NACELLE[0], NACELLE[1], NACELLE[2])))
        rig.drawn(f"prop_{side}", nacelle, [blade], T((sign * HUB[0], HUB[1], HUB[2])),
                  {DATA: {"system": "rotor", "unit": side.upper(), "spin": spin}})
    gear = rig.node("landing_gear", rig.root, np.zeros(3), extras={ROLE: "part", IFACE: "landing_gear"})
    for name, part, pivot, _, _ in GEAR:
        rig.drawn(name, gear, [part], T(pivot))
    doors = rig.node("cargo_doors", rig.root, np.zeros(3), extras={ROLE: "part", IFACE: "cargo_doors"})
    for name, part, pivot, _ in HATCHES:
        rig.drawn(name, doors, [part], T(pivot))

    for name, x in (("pilot", -0.60), ("copilot", 0.60)):
        rig.socket("seat", name, rig.root, eye(src, (x, 1.20, 0.0)))
        rig.socket("view", name, rig.root, eye(src, (x, 1.20, 0.0)))
    for i, (x, z) in enumerate(SEATS):
        rig.socket("seat", f"troop_{i}", rig.root, eye(src, (x, 1.10, z)))
        rig.socket("view", f"troop_{i}", rig.root, eye(src, (x, 1.10, z)))
    flare = rig.node("decoy_flare", rig.root, T((0.0, 1.2, -9.0)), extras={ROLE: "part", IFACE: "decoy_flare"})
    flare_pitch = rig.node("decoy_flare_barrel", flare, T((0.0, 1.2, -9.0)),
                           extras={ROLE: "pitch", IFACE: "decoy_flare", DATA: {"system": "barrel"}})
    for i, x in enumerate((1.6, -1.6)):
        rig.socket("bolt", "decoy_flare", flare_pitch, T((x, 1.2, -9.0)), index=i, length=0.1)
    rig.socket("center_of_mass", None, rig.root, T((0.0, 2.0, -4.0)))
    rig.socket("hitch", "cargo_hook", rig.root, T((0.0, 0.35, -4.3)))
    rig.socket("camera", None, rig.root, T((0.0, 7.5, -5.0)))
    rig.socket("camera_zoomed", None, rig.root, T((0.0, 8.5, -5.0)))
    rig.socket("light", None, rig.root, T((0.0, 0.6, 1.8)), extras={"kind": "search"})
    rig.w.doc["nodes"][rig.index["socket_light"]]["rotation"] = [math.sin(math.radians(10)), 0.0, 0.0,
                                                                 math.cos(math.radians(10))]
    body = rig.body()
    gltf, binary = paths(src)
    os.makedirs(os.path.dirname(gltf), exist_ok=True)
    rig.w.write(gltf, binary, {"staged_by": "tools/internal/stage_mv22.py", "source": f"MCHeli {SPEC['src']}",
                               "scale": SPEC["scale"]})
    mqo.append_clip(gltf, binary, "landing_gear",
                    [(name, "rotation", *mqo.hinge_keys(axis, deg, 1.5)) for name, _, _, axis, deg in GEAR])
    mqo.append_clip(gltf, binary, "cargo_doors",
                    [(name, "rotation", *mqo.hinge_keys("x", deg, 2.0)) for name, _, _, deg in HATCHES])
    wheels(src)
    lo, hi = src.bounds(body)
    print(f"== mv22: body {body}, bounds {np.round(lo, 3).tolist()} {np.round(hi, 3).tolist()}")


def paths(src):
    models = os.path.join(PACK, "assets", NS, "models", "aircraft", src.id)
    return os.path.join(models, f"{src.id}.gltf"), os.path.join(models, f"{src.id}.bin")


def wheels(src):
    """Solid wheel boxes in the body frame (gear legs carry no colliders): tyre vertices below WHEEL_TOP, per side."""
    path, binary = paths(src)
    gltf = convert_pack.Gltf(path, lambda rl: open(binary, "rb").read())
    pieces = []
    for name, part in (("nose wheel", "$lg0"), ("main wheel", "$lg1")):
        pts = np.vstack([o.verts for o in src.parts[part] if len(o.verts)])
        pts = pts[pts[:, 1] < WHEEL_TOP]
        for label, keep in (("l", pts[:, 0] > 0.05), ("r", pts[:, 0] < -0.05)) if name == "main wheel" \
                else (("", np.ones(len(pts), bool)),):
            side = pts[keep]
            lo, hi = src.T(side.min(axis=0)), src.T(side.max(axis=0))
            pieces.append((f"{name} {label}".strip(), convert_pack.box(lo.tolist(), hi.tolist())))
    for i, (name, (points, faces)) in enumerate(pieces):
        positions, indices = convert_pack.triangulate(points, faces)
        node = {"name": f"collider_body_wheel_{i}",
                "mesh": gltf.add_mesh(name, positions, indices, [len(f) - 2 for f in faces]),
                "extras": {HIDDEN: True}}
        convert_pack.set_role(node, "collider", None, name=name)
        gltf.add_node(node, gltf.scene_roots()[0])
    gltf.save(path, lambda rl, data: open(binary, "wb").write(data))


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    src = Source("mv22", SPEC)
    textures = os.path.join(PACK, "assets", NS, "textures", "aircraft", "mv22")
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(SPEC["root"], "textures", f"{SPEC['texture']}.png"), os.path.join(textures, "mv22.png"))
    stage(src)


if __name__ == "__main__":
    main()
