#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage EMB4 helicopters (MCHeli 1.0.3) as glTF displays + structure.

    python3 tools/stage_emb4_heli.py [ah64d mh60l ...]      (from combinedassault/)

Pivots, rotors, gear, weapon points: EMB4 helicopters/<src>.txt. MCHeli blocks -> ours: (p + (0, ground, 0)) * scale.
MCHeli yaw/pitch limits == ours (yaw + => starboard, pitch + => down). seat_offset = eye = T(MCHeli seat + (0, 1.2, 0)). AddRotor blade count N => blade mesh baked N times.
"""
import hashlib
import json
import math
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mqo  # noqa: E402
import script_clips  # noqa: E402
import stage_emb4_jet  # noqa: E402
import strip_cubes  # noqa: E402
from stage_emb4_tank import bone, cube  # noqa: E402

SRC = os.path.expanduser("~/projects/mcheli-gigarework/1.7.10-original/mcheli/assets/mcheli")
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"
DROP = {"$draftingtable_blade_dummy"}


def missiles(first, count):
    return [(f"missile{i}", [f"$weapon{first + i}"], None, None) for i in range(count)]


# rotors: (node, parts, pivot, axis, blades). nodes: (node, parts, pivot | None = mesh centre, parent).
# weapons: bone -> pivot (bolts: glTF socket_bolt_*). gear_boxes: landing_gear leg markers (lo, hi).
HELIS = {
    # rotor 15.2 -> 14.2 (real 14.6), length over rotors 19.3 -> 17.9 (real 17.7)
    "ah64d": dict(
        src="ah-64", sha1="9c01000bcbcb1e850bbcec65b4225547c5b59cbc", texture="ah-64", scale=0.93, ground=0.30,
        rotors=[("rotor", ["$blade0"], (0.0, 5.19, -1.95), "y", 4),
                ("tail_rotor", ["$blade1"], (0.85, 4.04, -11.94), "x", 1)],
        nodes=[("gun", ["$weapon0"], (0.0, 0.29, 0.72), None), ("gun_up", ["$weapon0_0"], (0.0, 0.29, 0.72), "gun"),
               ("pnvs", ["$camera0"], (0.0, 1.81, 3.13), None), ("tads", ["$camera1"], (0.0, 1.26, 3.31), None),
               ("tads_up", ["$camera2"], (0.0, 1.26, 3.31), "tads")] + missiles(2, 8),
        sight=(0.0, 1.26, 3.31),
        weapons={"auto_cannon": (0.0, 0.29, 0.72),
                 "missile": (0.0, 0.96, -1.64),
                 "rocket": (0.0, 0.81, -2.13)},
        flares=((0.45, 1.55, -5.5), (-0.45, 1.55, -5.5)),
        points={"pilot": (0.0, 1.50, -0.28), "cpg": (0.0, 1.00, 1.26), "exhaust_l": (1.3, 2.6, -4.2),
                "exhaust_r": (-1.3, 2.6, -4.2)}),
    # rotor 10.3 -> 8.2 (real 8.3); EMB4 oversized
    "ah_6": dict(
        src="ah-6", sha1="d88c672365ec7c2de03bcd5aeb03b550860f428f", texture="ah-6", scale=0.80, ground=0.29,
        rotors=[("rotor", ["$blade0"], (0.0, 3.35, 0.0), "y", 1), ("tail_rotor", ["$blade1"], (0.50, 1.90, -6.55), "x", 1)],
        nodes=[],
        sight=(0.0, 1.40, 1.80),
        weapons={"gun": (0.0, 0.40, 0.61),
                 "rocket": (0.0, 0.16, 0.0)},
        flares=(),
        points={"pilot": (-0.45, 0.80, 1.20), "copilot": (0.45, 0.80, 1.20), "bench_l": (0.90, 0.80, 0.20),
                "bench_r": (-0.90, 0.80, 0.20)}),
    # rotor 18.5 -> 16.3 (real 16.4)
    "mh60l": dict(
        src="mh-60l_dap", sha1="93fc57e6e4657a38f9516e2a477c24b14dae9333", texture="mh-60l_dap", scale=0.88,
        ground=0.31,
        rotors=[("rotor", ["$blade0"], (0.0, 3.98, -2.33), "y", 4),
                ("tail_rotor", ["$blade1"], (-0.60, 4.07, -14.59), "x", 1)],
        nodes=[("flir", ["$camera0"], (0.0, 0.52, 2.84), None),
               ("door_gun_r", ["$weapon2"], (-1.825, 1.475, -0.25), None),
               ("door_gun_r_up", ["$weapon3"], (-1.825, 1.475, -0.25), "door_gun_r"),
               ("door_gun_r_barrel", ["$weapon4"], (-1.825, 1.475, -0.25), "door_gun_r_up"),
               ("door_gun_l", ["$weapon5"], (1.792, 1.475, -0.25), None),
               ("door_gun_l_up", ["$weapon6"], (1.792, 1.475, -0.25), "door_gun_l"),
               ("door_gun_l_barrel", ["$weapon7"], (1.792, 1.475, -0.25), "door_gun_l_up")],
        sight=(0.0, 0.52, 2.84),
        weapons={"auto_cannon": (-2.51, 1.29, -1.51),
                 "rocket": (2.51, 1.19, -1.81),
                 "door_gun_r": (-1.825, 1.475, -0.25),
                 "door_gun_l": (1.792, 1.475, -0.25)},
        flares=((0.5, 1.2, -7.0), (-0.5, 1.2, -7.0)),
        points={"pilot": (-0.85, 0.90, 1.45), "copilot": (0.85, 0.90, 1.45), "gunner_r": (-1.05, 0.90, -0.30),
                "gunner_l": (1.05, 0.90, -0.30), "cabin_0": (0.45, 0.90, -1.4), "cabin_1": (-0.45, 0.90, -1.4),
                "cabin_2": (0.45, 0.90, -2.4), "cabin_3": (-0.45, 0.90, -2.4)}),
    # fuselage 19.7 -> 17.9 (real 17.5)
    "mi24": dict(
        src="mi-24", sha1="c1b4d8e0a13f2daef03692b4edb36fc15ed0a88e", texture="mi-24", scale=0.91, ground=0.58,
        rotors=[("rotor", ["$blade0"], (0.0, 3.00, 0.0), "y", 1), ("tail_rotor", ["$blade1"], (0.53, 4.36, -12.21), "x", 1)],
        # AddPartLG: axis, angle [, axis2, angle2]; outer node turns first-listed axis, child the second.
        nodes=[("gun", ["$weapon0"], (0.0, 0.43, 5.937), None), ("gun_up", ["$weapon0_0"], (0.0, 0.2056, 6.22), "gun"),
               ("gear_nose", ["$lg0"], (0.0, 0.60, 4.00), None),
               ("gear_l", [], (0.53, 0.59, -1.25), None), ("gear_l_fold", ["$lg1"], (0.53, 0.59, -1.25), "gear_l"),
               ("gear_r", [], (-0.53, 0.59, -1.25), None), ("gear_r_fold", ["$lg2"], (-0.53, 0.59, -1.25), "gear_r"),
               ("gear_door_l", ["$lg3"], (0.90, 0.75, -1.40), None),
               ("gear_door_r", ["$lg4"], (-0.90, 0.75, -1.40), None)],
        sight=(0.0, 0.30, 5.937),
        weapons={"gun": (0.0, 0.2056, 6.22),
                 "missile": (0.0, 0.9, -2.2),
                 "rocket": (0.0, 0.95, -2.9)},
        flares=((0.6, 2.0, -6.0), (-0.6, 2.0, -6.0)),
        tyres=("前輪", "後輪_左", "後輪_右"),  # split off the legs: wheel_rotation binding spins them
        gear_boxes=[((-0.24, -0.58, 3.09), (0.24, 0.29, 3.87)), ((0.52, -0.58, -1.64), (1.93, 0.57, -0.87)),
                    ((-1.93, -0.58, -1.64), (-0.52, 0.57, -0.87))],
        # script_clips.clips: {part: (on, off)}, landing_gear on == gear up.
        clips={"landing_gear": ({"gear_nose": (43, 0, 0), "gear_l": (0, 93, 0), "gear_l_fold": (0, 0, 36),
                                 "gear_r": (0, -93, 0), "gear_r_fold": (0, 0, -36), "gear_door_l": (0, 0, -127),
                                 "gear_door_r": (0, 0, 127)}, {})},
        points={"pilot": (0.0, 0.90, 3.73), "gunner": (0.0, 0.44, 5.08), "troop_0": (0.27, 1.08, -0.59),
                "troop_1": (0.27, 1.08, 0.08), "troop_2": (-0.27, 1.08, -0.59), "troop_3": (-0.27, 1.08, 0.08),
                "troop_4": (0.25, 0.87, 1.10), "troop_5": (-0.25, 0.87, 1.10)}),
    # rotor 14.1 (real 14.5), fuselage 13.75 (real 13.5)
    "ka52": dict(
        src="ka52", sha1="c2863d9e23d2545976127fbbe5e3495b4c32e01e", texture="ka52_camo1", scale=1.0, ground=0.30,
        rotors=[("rotor_upper", ["$blade0"], (0.0, 0.0, 0.0), "y", 1), ("rotor_lower", ["$blade1"], (0.0, 0.0, 0.0), "y", 1)],
        nodes=[("sight", ["$camera0"], (0.0, 0.20, 4.30), None), ("sight_up", ["$camera1"], (0.0, 0.20, 4.30), "sight"),
               ("gun", ["$weapon0"], (-1.04, 0.65, -0.3), None)] + missiles(1, 12),
        sight=(0.0, 0.20, 4.30),
        weapons={"auto_cannon": (-1.04, 0.65, -0.3),
                 "missile": (0.0, 0.9, -0.84),
                 "igla": (0.0, 0.35, 1.0)},
        flares=((0.3, 1.6, -4.0), (-0.3, 1.6, -4.0)),
        points={"pilot": (0.25, 0.70, 2.40), "navigator": (-0.25, 0.70, 2.40)}),
}


class Heli:
    def __init__(self, heli_id, spec):
        self.id, self.spec = heli_id, spec
        path = os.path.join(SRC, "models", "helicopters", f"{spec['src']}.mqo")
        digest = hashlib.sha1(open(path, "rb").read()).hexdigest()
        if digest != spec["sha1"]:
            raise SystemExit(f"{path}: sha1 {digest} != EMB4 1.0.3 release")
        self.parts = mqo.parts(mqo.read(path))

    def T(self, p):
        return (np.asarray(p, dtype=float) + [0.0, self.spec["ground"], 0.0]) * self.spec["scale"]


def axis_rotation(axis, angle):
    c, s = math.cos(angle), math.sin(angle)
    return {"x": np.array([[1, 0, 0], [0, c, -s], [0, s, c]]),
            "y": np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])}[axis]


def display(h):
    s = h.spec
    w = mqo.GltfWriter(f"{NS}:models/aircraft/{h.id}/{h.id}.bin", f"{NS}:textures/aircraft/{h.id}/{h.id}.png",
                       "tools/stage_emb4_heli.py")
    used = set(DROP)

    def geometry(parts, pivot, keep=lambda o: True):
        pos, nrm, uv = [], [], []
        for p in parts:
            used.add(p)
            for o in filter(keep, h.parts[p]):
                if o.tris:
                    a, b, c = mqo.triangles(o, h.T)
                    pos.append(a - pivot)
                    nrm.append(b)
                    uv.append(c)
        return np.vstack(pos), np.vstack(nrm), np.vstack(uv)

    root = w.node(h.id)
    for name, parts, pivot, axis, blades in s["rotors"]:
        p = h.T(pivot)
        pos, nrm, uv = geometry(parts, p)
        turns = [axis_rotation(axis, 2 * math.pi * k / blades) for k in range(blades)]
        w.node(name, p, w.mesh(name, np.vstack([pos @ r.T for r in turns]), np.vstack([nrm @ r.T for r in turns]),
                               np.vstack([uv] * blades)), root)
    index, origin, wheels = {}, {}, {}
    for name, parts, pivot, parent in s["nodes"]:
        if pivot is None:
            pts = np.vstack([h.T(o.verts) for q in parts for o in h.parts[q] if len(o.verts)])
            p = (pts.min(axis=0) + pts.max(axis=0)) / 2
        else:
            p = h.T(pivot)
        tyres = [o for q in parts for o in h.parts[q] if o.name in s.get("tyres", ())]
        mesh = w.mesh(name, *geometry(parts, p, lambda o: o not in tyres)) if parts else None
        up = index[parent] if parent else root
        index[name] = w.node(name, p - (origin[parent] if parent else 0), mesh, up)
        origin[name] = p
        for k, (centre, radius, objs) in enumerate(mqo.axles(tyres, h.T)):
            wheels[f"{name}_wheel{k}"] = radius
            pos, nrm, uv = [], [], []
            for o in objs:
                a, b, c = mqo.triangles(o, h.T)
                pos.append(a - centre)
                nrm.append(b)
                uv.append(c)
            w.node(f"{name}_wheel{k}", centre - p, w.mesh(f"{name}_wheel{k}", np.vstack(pos), np.vstack(nrm),
                                                           np.vstack(uv)), index[name])
    body = [q for q in h.parts if q not in used]
    w.node("body", mesh=w.mesh("body", *geometry(body, np.zeros(3))), parent=root)
    models = os.path.join(PACK, "assets", NS, "models", "aircraft", h.id)
    os.makedirs(models, exist_ok=True)
    w.write(os.path.join(models, f"{h.id}.gltf"), os.path.join(models, f"{h.id}.bin"),
            {"staged_by": "tools/stage_emb4_heli.py", "source": f"EMB4 MCHeli 1.0.3 helicopters/{s['src']}",
             "scale": s["scale"]})
    return body, wheels


def structure(h):
    s = h.spec
    bones = [bone("vehicle_body", []), bone("sighting_system", [], pivot=h.T(s["sight"]))]
    # Yaw bone on its display node's pivot, else hits traverse about the barrel's.
    nodes = {n: pv for n, _, pv, _ in s["nodes"] if pv is not None}
    for name, pivot in s["weapons"].items():
        p = h.T(pivot)
        bones.append(bone(name, [], pivot=h.T(nodes[name]) if name in nodes else p))
        bones.append(bone(f"{name}_barrel", [], parent=name, pivot=p))
    if s["flares"]:
        p = h.T(s["flares"][0])
        bones.append(bone("decoy_flare", [], pivot=p))
        bones.append(bone("decoy_flare_barrel", [], parent="decoy_flare", pivot=p))
    if s.get("gear_boxes"):
        bones.append(bone("landing_gear", [cube(h.T(lo), h.T(hi)) for lo, hi in s["gear_boxes"]]))
    model = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16},
        "bones": bones}]}
    path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", f"{h.id}.structure.json")
    with open(path, "w") as f:
        json.dump(model, f, indent=1)
    strip_cubes.split(PACK, h.id)


def stage(heli_id):
    h = Heli(heli_id, HELIS[heli_id])
    s = h.spec
    textures = os.path.join(PACK, "assets", NS, "textures", "aircraft", heli_id)
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(SRC, "textures", "helicopters", f"{s['texture']}.png"),
                    os.path.join(textures, f"{heli_id}.png"))
    models = os.path.join(PACK, "assets", NS, "models", "aircraft", heli_id)
    gltf, binary = os.path.join(models, f"{heli_id}.gltf"), os.path.join(models, f"{heli_id}.bin")
    kept = mqo.read_markers(gltf, binary)
    body, wheels = display(h)
    if wheels:
        stage_emb4_jet.controller(heli_id, wheels)
    structure(h)
    mqo.append_nodes(gltf, binary, kept)
    if s.get("clips"):
        script_clips.clips(heli_id, gltf, binary, s["clips"])
    print(f"== {heli_id} (body: {len(body)} parts)")
    for name, p in s["points"].items():
        eye = name in ("pilot", "copilot", "cpg", "gunner", "navigator") or name.startswith(("bench", "cabin", "troop", "gunner"))
        print(f"  {name}", np.round(h.T(np.add(p, (0, 1.2 if eye else 0, 0))), 3).tolist())


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    for heli_id in sys.argv[1:] or HELIS:
        stage(heli_id)


if __name__ == "__main__":
    main()
