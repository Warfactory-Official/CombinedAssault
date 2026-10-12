#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage EMB4's B-2A (MCHeli 1.0.3) as b2a: glTF display + structure.

    python3 REDACTED      (from combinedassault/)

Pivots/angles: EMB4 b-2a.txt AddPartLG* (line order == $lgN) and AddPartWeaponBay (== $wbN).
MCHeli blocks -> ours: (p + (0, GROUND, 0)) * SCALE. SCALE: span 60.3 -> 52.4 (real 52.4).
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
import stage_emb4_jet  # noqa: E402
import strip_cubes  # noqa: E402
from stage_emb4_tank import bone, cube, fit_hull  # noqa: E402

SRC = os.path.expanduser("~/projects/mcheli-gigarework/1.7.10-original/mcheli/assets/mcheli")
MODEL_SHA1 = "e9efbcb4aec87491e39c248da7388868164ccd83"  # == MC1.7.10_mcheli_1.0.3.zip entry
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"
SCALE = 0.87
GROUND = 0.33  # wheel bottom

# node: ($parts, pivot); rotations in CLIPS.
NODES = {
    "gear_nose": (["$lg0"], (0.00, 2.96, 2.79)),
    "nose_door_front": (["$lg1"], (0.00, 2.87, 3.29)),
    "nose_door_side": (["$lg2"], (-0.65, 1.75, 0.25)),
    "gear_main": (["$lg3", "$lg4"], (0.00, 3.05, -7.96)),
    "main_door_r": (["$lg5"], (-7.74, 2.23, -7.02)),
    "main_door_l": (["$lg6"], (7.74, 2.23, -7.02)),
    "inlet_door_0": (["$lg7"], (-5.06, 4.38, -5.57)),
    "inlet_door_1": (["$lg8"], (-4.06, 4.41, -5.57)),
    "inlet_door_2": (["$lg9"], (5.06, 4.38, -5.57)),
    "inlet_door_3": (["$lg10"], (4.06, 4.41, -5.57)),
    "bay_door_0": (["$wb0"], (-2.79, 1.86, -6.56)),
    "bay_door_1": (["$wb1"], (-0.15, 1.72, -6.56)),
    "bay_door_2": (["$wb2"], (2.79, 1.86, -6.56)),
    "bay_door_3": (["$wb3"], (0.15, 1.72, -6.56)),
}
# script_clips.clips, angles EMB4 b-2a.txt: LG turns when folded; LGRev (doors) while down; bay doors while open.
CLIPS = {"landing_gear": ({"gear_nose": (75, 0, 0), "gear_main": (-90, 0, 0)},
                          {"nose_door_front": (-60, 0, 0), "main_door_r": (0, 0, -90), "main_door_l": (0, 0, 90)}),
         "bay_doors": ({"bay_door_0": (0, 0, -90), "bay_door_1": (0, 0, 90), "bay_door_2": (0, 0, 90),
                        "bay_door_3": (0, 0, -90)}, {})}
TYRES = ("wheel", "wheel2")  # split off the legs: spun by the controller's wheel_rotation binding
BOMBS = [(1.84, 2.80, -4.27), (-1.84, 2.80, -4.27), (1.84, 2.80, -8.57), (-1.84, 2.80, -8.57)]  # mk82_b2
SEATS = {"pilot": (0.80, 3.40, 0.28), "commander": (-0.80, 3.40, 0.28)}
# Hull pieces: centre |x| <= 2.3, then trailing-edge kinks (4.6 fwd, 8.2 aft, 15.5 fwd, 25.7 aft), tip 30.15.
HULL_BANDS = [2.3, 4.6, 8.2, 15.5, 25.7, 30.2]
HULL_TOL = 0.1  # blocks a piece may stand proud of the skin
SKIN = ("body1", "body2", "E1(M)", "E2(M)", "glass")


def T(p):
    return (np.asarray(p, dtype=float) + [0.0, GROUND, 0.0]) * SCALE


def bounds(objs):
    pts = np.vstack([T(o.verts) for o in objs if len(o.verts)])
    return pts.min(axis=0), pts.max(axis=0)


def sides(objs):
    """One leg marker per side: one box across both mains = one centreline gear point."""
    pts = np.vstack([T(o.verts) for o in objs if len(o.verts)])
    return [(q.min(axis=0), q.max(axis=0)) for q in (pts[pts[:, 0] > 0], pts[pts[:, 0] < 0])]


def display(parts):
    w = mqo.GltfWriter(f"{NS}:models/aircraft/b2a/b2a.bin", f"{NS}:textures/aircraft/b2a/b2a.png",
                       "REDACTED")

    def mesh_of(name, objs, pivot):
        pos, nrm, uv = [], [], []
        for o in objs:
            if o.tris:
                p, n, t = mqo.triangles(o, T)
                pos.append(p - pivot)
                nrm.append(n)
                uv.append(t)
        return w.mesh(name, np.vstack(pos), np.vstack(nrm), np.vstack(uv))

    root = w.node("b2a")
    w.node("body", mesh=mesh_of("body", parts["$body"], np.zeros(3)), parent=root)
    wheels = {}
    for name, (sources, pivot) in NODES.items():
        objs = [o for s in sources for o in parts[s]]
        tyres = [o for o in objs if o.name in TYRES]
        node = w.node(name, T(pivot), mesh_of(name, [o for o in objs if o not in tyres], T(pivot)), root)
        for k, (centre, radius, axle) in enumerate(mqo.axles(tyres, T)):
            wheels[f"{name}_wheel{k}"] = radius
            w.node(f"{name}_wheel{k}", centre - T(pivot), mesh_of(f"{name}_wheel{k}", axle, centre), node)
    models = os.path.join(PACK, "assets", NS, "models", "aircraft", "b2a")
    os.makedirs(models, exist_ok=True)
    w.write(os.path.join(models, "b2a.gltf"), os.path.join(models, "b2a.bin"),
            {"staged_by": "REDACTED", "source": "EMB4 MCHeli 1.0.3 planes/b-2a", "scale": SCALE})
    return wheels


def structure(parts):
    bay = T((0.0, 2.80, -6.42))
    bones = [
        bone("vehicle_body", []),
        bone("landing_gear", [cube(*bounds(parts["$lg0"]))] + [cube(*side) for side in sides(parts["$lg4"])]),
        bone("sighting_system", [], pivot=T((0.0, 1.7, 3.5))),
        bone("bomb_bay", [], pivot=bay),
        bone("bomb_bay_barrel", [], parent="bomb_bay", pivot=bay),
        bone("guided_bay", [], pivot=bay),
        bone("guided_bay_barrel", [], parent="guided_bay", pivot=bay),
        bone("bay_doors", []),
    ]
    model = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16},
        "bones": bones}]}
    path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", "b2a.structure.json")
    with open(path, "w") as f:
        json.dump(model, f, indent=1)
    hull(parts)


def hull(parts):
    """vehicle_body as convex span slabs of the skin; slab faces cut where the planform is piecewise linear."""
    tris = np.array([T(o.verts)[list(idx)] for o in parts["$body"] if o.name in SKIN for idx, _ in o.tris])
    verts = tris.reshape(-1, 3)
    planes = [0.0] + [c * SCALE for c in HULL_BANDS]
    cuts = []
    for i, j in ((0, 1), (1, 2), (2, 0)):
        a, b = tris[:, i], tris[:, j]
        for x in planes:
            hit = (a[:, 0] - x) * (b[:, 0] - x) < 0
            k = (x - a[hit, 0]) / (b[hit, 0] - a[hit, 0])
            cuts.append(a[hit] + (b[hit] - a[hit]) * k[:, None])
    pts = np.vstack([verts] + cuts)
    pts[:, 0] = np.abs(pts[:, 0])  # skin mirror-symmetric; port half built, starboard mirrored
    normals = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
    normals = normals[np.linalg.norm(normals, axis=1) > 1e-9]
    normals /= np.linalg.norm(normals, axis=1)[:, None]
    dirs = np.unique(np.round(np.vstack([normals, -normals, normals * [-1, 1, 1], normals * [1, -1, -1]]), 4), axis=0)
    dirs /= np.linalg.norm(dirs, axis=1)[:, None]
    pieces = []
    modules = []
    for band, (lo_x, hi_x) in enumerate(zip(planes, planes[1:])):
        sel = pts[(pts[:, 0] >= lo_x - 1e-6) & (pts[:, 0] <= hi_x + 1e-6)]
        points, faces = fit_hull(sel, dirs, HULL_TOL, keep=((1, 0, 0), (-1, 0, 0)))
        name = "centre body" if lo_x == 0 else f"wing {round((lo_x + hi_x) / 2 / SCALE, 1)}"
        mirrored = [[-x, y, z] for x, y, z in points]
        pieces.append({"bone": "vehicle_body", "name": f"{name} left", "points": points, "faces": faces})
        pieces.append({"bone": "vehicle_body", "name": f"{name} right", "points": mirrored,
                       "faces": [f[::-1] for f in faces]})
        if lo_x:  # outboard slabs = the wing system
            modules.append((f"hit_wing_left_{band}", points, faces))
            modules.append((f"hit_wing_right_{band}", mirrored, [f[::-1] for f in faces]))
        print("hull", name, len(points), "points", len(faces), "faces")
    models = os.path.join(PACK, "assets", NS, "models", "aircraft", "b2a")
    mqo.append_hit_nodes(os.path.join(models, "b2a.gltf"), os.path.join(models, "b2a.bin"), modules)
    path = os.path.join(PACK, "data", NS, "models", "hull", "vehicle", "b2a.hull.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump({"format": 1, "pieces": pieces}, f, indent=1)
    strip_cubes.split(PACK, "b2a")


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    model_path = os.path.join(SRC, "models", "planes", "b-2a.mqo")
    digest = hashlib.sha1(open(model_path, "rb").read()).hexdigest()
    if digest != MODEL_SHA1:
        raise SystemExit(f"{model_path}: sha1 {digest} != EMB4 1.0.3 release")
    parts = mqo.parts(mqo.read(model_path))
    textures = os.path.join(PACK, "assets", NS, "textures", "aircraft", "b2a")
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(SRC, "textures", "planes", "b-2a.png"), os.path.join(textures, "b2a.png"))
    models = os.path.join(PACK, "assets", NS, "models", "aircraft", "b2a")
    gltf, binary = os.path.join(models, "b2a.gltf"), os.path.join(models, "b2a.bin")
    kept = mqo.read_markers(gltf, binary)
    wheels = display(parts)
    stage_emb4_jet.controller("b2a", wheels)
    structure(parts)
    mqo.append_nodes(gltf, binary, kept)
    script_clips.clips("b2a", gltf, binary, CLIPS)
    for name, p in SEATS.items():
        print("seat", name, np.round(T(p), 3).tolist())
    for p in BOMBS:
        print("bomb", np.round(T(p), 3).tolist())
    tip = T((30.15, 3.0, -19.0))
    print("vortex", [np.round(tip, 2).tolist(), np.round(tip * [-1, 1, 1], 2).tolist()])


if __name__ == "__main__":
    main()
