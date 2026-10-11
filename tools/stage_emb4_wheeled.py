#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage EMB4 wheeled armour (MCHeli 1.0.3 tanks/) as glTF display + structure + controller + script.

    python3 tools/stage_emb4_wheeled.py [centauro m1129 ...]      (from combinedassault/)

Pivots from EMB4 tanks/<src>.txt; ours = (p + (0, ground, 0)) * scale. Wheels `wheel_<axle>_<l|r>` (axle 0 = front),
node origin = hub, spun by `wheel_rotation`, steered by `steering_angle` (AddPartWheel angle; MCHeli per-axle max).
Hatches: `<n>_hinge` (+X = axis) -> `<n>`, posed by the script while the bay part is on.
"""
import hashlib
import json
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mqo  # noqa: E402
import script_clips  # noqa: E402
import strip_cubes  # noqa: E402
from stage_emb4_tank import bone, cube, quaternion  # noqa: E402

SRC = os.path.expanduser("~/projects/mcheli-gigarework/1.7.10-original/mcheli/assets/mcheli")
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"

# wheels: (part, axle, side). turret: yaw part + pitch child (+ recoil barrel). mgs: yaw part (+ pitch part).
# hatches: (node, part, hinge pivot, axis, open degrees). steer: axle -> degrees.
VEHICLES = {
    # hull 9.51 -> 7.89 (real B1 7.85)
    "centauro": dict(
        src="centauro", sha1="1a0e4a5171ea886703743e06be9bd51567062e7b", texture="centauro", scale=0.83, ground=0.35,
        wheels=[(f"$wheel{i}", i % 4, "l" if i < 4 else "r") for i in range(8)], steer={0: 20, 1: 10},
        body=["$body"],
        turret=dict(part="$weapon0", pivot=(0.0, 2.01, 0.0), cannon=("$weapon0_0", (0.0, 2.36, 1.32)),
                    barrel="$weapon0_1"),
        mgs=[("mg_l", "$weapon1", None, (1.1824, 3.1640, -0.1427)), ("mg_r", "$weapon2", None, (-1.2037, 3.1640, -0.9673))],
        mortar=None, hatches=[]),
    # hull 9.64 -> 6.94 (real M1129 6.95)
    "m1129": dict(
        src="m1129", sha1="a6eaff641fd96d42b004ee41036b6f3cb1bddac5", texture="m1129", scale=0.72, ground=0.35,
        wheels=[(f"$wheel{i}", i // 2, "r" if i % 2 == 0 else "l") for i in range(8)], steer={0: 30, 1: 15},
        body=["$body"], turret=None,
        mgs=[("mg", "$weapon1", "$weapon1_0", (-0.755, 3.167, 0.324))],
        mortar=dict(part="$weapon0", tube="$weapon0_0", pivot=(0.0, 1.80, -2.67)),
        hatches=[("hatch_r", "$wb0", (-1.03, 2.61, -2.70), (0, 0, 1), 110),
                 ("hatch_l", "$wb1", (1.03, 2.61, -2.70), (0, 0, 1), -110)]),
}


def frame_x(axis):
    """Rotation whose +X is `axis`."""
    x = np.asarray(axis, float) / np.linalg.norm(axis)
    y = np.array([0.0, 1.0, 0.0]) - x * x[1]
    if np.linalg.norm(y) < 1e-6:
        y = np.array([0.0, 0.0, 1.0]) - x * x[2]
    y /= np.linalg.norm(y)
    return np.column_stack([x, y, np.cross(x, y)])


class Vehicle:
    def __init__(self, vid, spec):
        self.id, self.spec = vid, spec
        path = os.path.join(SRC, "models", "tanks", f"{spec['src']}.mqo")
        digest = hashlib.sha1(open(path, "rb").read()).hexdigest()
        if digest != spec["sha1"]:
            raise SystemExit(f"{path}: sha1 {digest} != EMB4 1.0.3 release")
        self.parts = mqo.parts(mqo.read(path))

    def T(self, p):
        return (np.asarray(p, dtype=float) + [0.0, self.spec["ground"], 0.0]) * self.spec["scale"]

    def objs(self, names):
        return [o for n in names for o in self.parts[n] if len(o.verts)]

    def bounds(self, names):
        pts = self.T(np.vstack([o.verts for o in self.objs(names)]))
        return pts.min(axis=0), pts.max(axis=0)

    def wheel(self, part):
        lo, hi = self.bounds([part])
        return (lo + hi) / 2, (hi[1] - lo[1]) / 2


def wheel_name(axle, side):
    return f"wheel_{axle}_{side}"


def display(v):
    s = v.spec
    w = mqo.GltfWriter(f"{NS}:models/ground/{v.id}/{v.id}.bin", f"{NS}:textures/ground/{v.id}/{v.id}.png",
                       "tools/stage_emb4_wheeled.py")

    def mesh_of(name, parts, pivot, frame=np.eye(3)):
        pos, nrm, uv = [], [], []
        for o in v.objs(parts):
            if o.tris:
                p, n, u = mqo.triangles(o, v.T)
                pos.append((p - pivot) @ frame)
                nrm.append(n @ frame)
                uv.append(u)
        return w.mesh(name, np.vstack(pos), np.vstack(nrm), np.vstack(uv))

    root = w.node(v.id)
    w.node("body", mesh=mesh_of("body", s["body"], np.zeros(3)), parent=root)
    for part, axle, side in s["wheels"]:
        centre, _ = v.wheel(part)
        w.node(wheel_name(axle, side), centre, mesh_of(wheel_name(axle, side), [part], centre), root)
    if s["turret"]:
        t = s["turret"]
        tp, (cpart, cpivot) = v.T(t["pivot"]), t["cannon"]
        cp = v.T(cpivot)
        turret = w.node("turret", tp, mesh_of("turret", [t["part"]], tp), root)
        cannon = w.node("cannon", cp - tp, mesh_of("cannon", [cpart], cp), turret)
        w.node("barrel", mesh=mesh_of("barrel", [t["barrel"]], cp), parent=cannon)
    else:
        turret, tp = root, np.zeros(3)
    for name, yaw, pitch, pivot in s["mgs"]:
        p = v.T(pivot)
        mg = w.node(name, p - tp, mesh_of(name, [yaw], p), turret)
        if pitch:
            w.node(f"{name}_up", mesh=mesh_of(f"{name}_up", [pitch], p), parent=mg)
    if s["mortar"]:
        m = s["mortar"]
        p = v.T(m["pivot"])
        mortar = w.node("mortar", p, mesh_of("mortar", [m["part"]], p), root)
        w.node("mortar_tube", mesh=mesh_of("mortar_tube", [m["tube"]], p), parent=mortar)
    for name, part, pivot, axis, _ in s["hatches"]:
        p, frame = v.T(pivot), frame_x(axis)
        hinge = w.node(f"{name}_hinge", p, parent=root, rotation=quaternion(frame))
        w.node(name, mesh=mesh_of(name, [part], p, frame), parent=hinge)
    models = os.path.join(PACK, "assets", NS, "models", "ground", v.id)
    os.makedirs(models, exist_ok=True)
    w.write(os.path.join(models, f"{v.id}.gltf"), os.path.join(models, f"{v.id}.bin"),
            {"staged_by": "tools/stage_emb4_wheeled.py", "source": f"EMB4 MCHeli 1.0.3 tanks/{s['src']}",
             "scale": s["scale"]})


def structure(v):
    s = v.spec
    bones = [bone("vehicle_body", [])]
    for part, axle, side in s["wheels"]:
        centre, _ = v.wheel(part)
        bones.append(bone(wheel_name(axle, side), [cube(*v.bounds([part]))], pivot=centre))
    if s["turret"]:
        t = s["turret"]
        bones.append(bone("turret", [], pivot=v.T(t["pivot"])))
        bones.append(bone("turret_barrel", [], parent="turret", pivot=v.T(t["cannon"][1])))
    for name, _, _, pivot in s["mgs"]:
        bones.append(bone(name, [], parent="turret" if s["turret"] else None, pivot=v.T(pivot)))
        bones.append(bone(f"{name}_barrel", [], parent=name, pivot=v.T(pivot)))
    if s["mortar"]:
        p = v.T(s["mortar"]["pivot"])
        bones.append(bone("mortar", [], pivot=p))
        bones.append(bone("mortar_barrel", [], parent="mortar", pivot=p))
    if s["hatches"]:
        bones.append(bone("hatches", []))
    model = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16},
        "bones": bones}]}
    path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", f"{v.id}.structure.json")
    with open(path, "w") as f:
        json.dump(model, f, indent=1)
    strip_cubes.split(PACK, v.id)


def controller(v):
    s = v.spec
    radius = round(float(np.mean([v.wheel(p)[1] for p, _, _ in s["wheels"]])), 3)
    names = [wheel_name(a, side) for _, a, side in s["wheels"]]
    special = [{"bones": names, "source": "wheel_rotation", "axis": "x", "param": radius}]
    top = max(s["steer"].values())
    for axle, degrees in sorted(s["steer"].items()):
        special.append({"bones": [wheel_name(axle, "l"), wheel_name(axle, "r")], "source": "steering_angle",
                        "axis": "y", "multiplier": round(degrees / top, 3), "min": -degrees, "max": degrees})
    parts = []
    if s["turret"]:
        parts += [{"bone": "turret", "part": "turret", "rotation_type": "y", "axis": "y", "invert": True},
                  {"bone": "cannon", "part": "turret", "rotation_type": "x", "axis": "x", "invert": False}]
    for name, _, pitch, _ in s["mgs"]:
        parts.append({"bone": name, "part": name, "rotation_type": "y", "axis": "y", "invert": True})
        if pitch:
            parts.append({"bone": f"{name}_up", "part": name, "rotation_type": "x", "axis": "x", "invert": False})
    if s["mortar"]:
        parts += [{"bone": "mortar", "part": "mortar", "rotation_type": "y", "axis": "y", "invert": True},
                  {"bone": "mortar_tube", "part": "mortar", "rotation_type": "x", "axis": "x", "invert": False}]
    graph = {"type": "bone_binding", "special_bindings": special, "part_bindings": parts}
    ctl = {"name": f"{v.id}_controller", "parameters": {}}
    if s["hatches"]:
        ctl["script"] = f"{NS}:{v.id}"
        graph = {"type": "merge", "inputs": [{"type": "script", "function": "updateBones"}, graph]}
        lines = [f"// EMB4 {s['src']} (tools/stage_emb4_wheeled.py).", "function updateBones(context) {",
                 "    var builder = createPoseBuilder();", '    if (context.isPartOn("hatches")) {']
        lines += [f'        builder.setRotation("{n}", {a}, 0, 0);' for n, _, _, _, a in s["hatches"]]
        lines += ["    }", "    return builder;", "}", ""]
        with open(os.path.join(PACK, "assets", NS, "scripts", f"{v.id}.js"), "w") as f:
            f.write("\n".join(lines))
    ctl["graph"] = graph
    with open(os.path.join(PACK, "assets", NS, "animation_controllers", f"{v.id}_controller.json"), "w") as f:
        json.dump(ctl, f, indent=4)
    return radius


def stage(vid):
    v = Vehicle(vid, VEHICLES[vid])
    s = v.spec
    textures = os.path.join(PACK, "assets", NS, "textures", "ground", vid)
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(SRC, "textures", "tanks", f"{s['texture']}.png"), os.path.join(textures, f"{vid}.png"))
    models = os.path.join(PACK, "assets", NS, "models", "ground", vid)
    gltf, binary = os.path.join(models, f"{vid}.gltf"), os.path.join(models, f"{vid}.bin")
    kept = mqo.read_markers(gltf, binary)
    display(v)
    structure(v)
    mqo.append_nodes(gltf, binary, kept)
    radius = controller(v)
    if s["hatches"]:
        script_clips.bake(vid, gltf, binary)
    body_lo, body_hi = v.bounds(s["body"])
    print(f"== {vid}: body {np.round(body_lo, 3).tolist()} .. {np.round(body_hi, 3).tolist()}, wheel r {radius}")
    for part, axle, side in s["wheels"]:
        print("  ", wheel_name(axle, side), np.round(v.wheel(part)[0], 3).tolist())
    for key in ("turret",):
        if s[key]:
            print("  turret", np.round(v.T(s[key]["pivot"]), 3).tolist(), "cannon", np.round(v.T(s[key]["cannon"][1]), 3).tolist(),
                  "barrel tip z", round(float(v.bounds([s[key]["barrel"]])[1][2]), 3))
    for name, _, _, pivot in s["mgs"]:
        print("  ", name, np.round(v.T(pivot), 3).tolist())
    if s["mortar"]:
        print("  mortar", np.round(v.T(s["mortar"]["pivot"]), 3).tolist(), "tube hi",
              np.round(v.bounds([s["mortar"]["tube"]])[1], 3).tolist())


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    for vid in sys.argv[1:] or VEHICLES:
        stage(vid)


if __name__ == "__main__":
    main()
