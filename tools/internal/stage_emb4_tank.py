#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage EMB4 tanks (MCHeli 1.0.3) as glTF displays + structure, hull, track link.

    python3 tools/stage_emb4_tank.py [m1a2 t84 ...]      (from combinedassault/)
    python3 tools/stage_emb4_tank.py carve m1a2          role glTF in place: loader_mg cut from the turret mesh

MCHeli frame == vehicle frame (+X port, +Z forward); pivots, rollers, crawler loops from EMB4's <tank>.txt.
MCHeli blocks -> ours: (p + (0, ground, 0)) * scale; scale puts the hull at real length.
"""
import hashlib
import json
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mqo  # noqa: E402

SRC = os.path.expanduser("~/projects/mcheli-gigarework/1.7.10-original/mcheli/assets/mcheli")
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"

M1A2_LOOP = [(1.14, -4.62), (1.10, -4.88), (0.80, -4.90), (0.54, -4.84)]
M1A2_TOP = [(0.50, 4.04), (0.78, 4.12), (1.12, 4.02), (1.22, 3.73)]
T84_LOOP = [(0.81, -3.65), (0.53, -3.54), (-0.09, -2.43), (-0.21, -2.13), (-0.21, 3.01), (0.53, 4.42),
            (0.81, 4.53), (1.09, 4.42), (1.20, 4.14), (1.20, -3.26), (1.09, -3.54)]
MERKAVA_LOOP = [(1.03, -3.41), (0.72, -3.57), (0.37, -3.42), (-0.15, -2.55), (-0.25, -2.16), (-0.25, 3.88),
                (-0.13, 4.21), (0.52, 5.29), (0.78, 5.39), (1.03, 5.28), (1.10, 5.04), (1.15, -3.12)]
T90_LOOP = [(-0.30, 3.13), (-0.17, 3.46), (0.34, 4.31), (0.61, 4.42), (0.87, 4.31), (0.98, 4.04), (0.98, -3.32),
            (0.87, -3.59), (0.61, -3.70), (0.34, -3.59), (-0.31, -2.12)]
TANKS = {
    # hull 10.35 -> 7.97 (real 7.93)
    "m1a2": dict(
        sha1="7a7ef4fdbc15cdfa9f0fa9a0e33a17e5d1edb6c6", texture="m1a2", scale=0.77, ground=0.23, pitch=0.37,
        turret=(0.0, 2.10, 0.0), cannon=(0.0, 2.10, 2.02), bore_from_barrel=False,
        turret_parts=["$weapon0"], cannon_part="$weapon0_0",
        # EMB4 ships the lid twice, closed and open => one hinged node; hinge + swing solved from the pair.
        hatch=dict(part="$▼ハッチ_動", closed=["HNA1", "HNA2"], open=["HNB1", "HNB2"]),
        barrel_part="$weapon0_1", mg=dict(pivot=(-0.846, 3.68, 0.373), yaw="$weapon2", pitch="$weapon1"),
        # Loader's M240 on the port hatch ring; eye = hatch centre, chest out of the roof.
        loader_mg=dict(pivot=(1.243, 3.24, -0.77), yaw="$weapon4", pitch="$weapon3", gun="M240",
                       eye=(0.424, 2.85, -0.739), x_rot=(-50, 15), y_rot=(-220, 30)),
        crawler={"right": M1A2_LOOP + [(-0.16, -4.07), (-0.23, -3.45), (-0.23, 2.17), (-0.11, 2.73)] + M1A2_TOP,
                 "left": M1A2_LOOP + [(-0.16, -3.74), (-0.23, -3.78), (-0.23, 2.49), (-0.11, 2.86)] + M1A2_TOP},
        rollers={"wheel_left": range(9, 18), "wheel_right": range(0, 9)},
        turret_box=("$weapon0", ["HBD1", "HBD2", "HBD3", "HBD4", "HBD5", "HKC1", "HKC2", "HKS", "HSB1", "HSB2",
                                 "HBSS", "HHA2", "HHB1", "HCA1", "HCB1"]),
        barrel_box=("$weapon0_1", ["HTB1", "HTB2", "HTB3", "HTB4"]), coax=("$weapon0_0", "HTG"),
        smoke=True, decor=[],
        # Fenders/lights (BFL*) FORBIDDEN in the profile: 0.3-tall vertical nose => glacis contact reads square.
        shell=["body1", "body2", "body3", "body4", "body5"]),
    # T-84 Oplot-M; hull 9.16 -> 7.05 (real 7.08)
    "t84": dict(
        sha1="bc1951cd9f9cb6429ca11f65ec5443b2f894bc93", texture="t84_2", scale=0.77, ground=0.21, pitch=0.37,
        turret=(0.0, 1.52, 0.25), cannon=(0.0, 2.01, 1.62), bore_from_barrel=True,
        turret_parts=["$weapon0"], cannon_part="$weapon0_0", barrel_part=None, mg=None, hatch=None,
        crawler={"right": T84_LOOP, "left": T84_LOOP},
        rollers={"wheel_left": range(8, 16), "wheel_right": range(0, 8)},
        turret_box=("$weapon0", ["$weapon0", "追加パーツ"]), barrel_box=("$weapon0_0", ["砲身1"]),
        coax=(-0.17, 2.25, 2.10), smoke=False, shell=["車体下部", "上部"],
        # Front skirt = decoration.
        decor=["スカート"]),
    # Merkava Mk4; hull 9.52 -> 7.62 (real 7.60)
    "merkava": dict(
        src="merkava_mk4", sha1="0f61b28013e4cf2e9bcb0415892e2ea892714cbf", texture="merkava_mk4", scale=0.80,
        ground=0.25, pitch=0.37, turret=(0.0, 1.52, 0.25), cannon=(0.0, 2.01, 1.62), bore_from_barrel=True,
        turret_parts=["$weapon0"], cannon_part="$weapon0_0", barrel_part="$weapon0_1", hatch=None,
        mg=dict(pivot=(-0.83, 3.39, -0.57), yaw="$weapon1", pitch=None),
        crawler={side: MERKAVA_LOOP for side in ("right", "left")},
        rollers={"wheel_left": range(8, 16), "wheel_right": range(0, 8)},
        turret_box=("$weapon0", ["$weapon0", "obj1", "obj3"]), barrel_box=("$weapon0_1", ["$weapon0_1", "obj5"]),
        coax=(0.0, 2.92, 2.70), smoke=False, shell=["$body"], decor=[]),
    # T-90; hull 9.50 -> 6.84 (real 6.86)
    "t90": dict(
        src="t-90", sha1="7a50fff6c26d06751a17e66516e5323ceffb625d", texture="t-90", scale=0.72, ground=0.31,
        pitch=0.23, turret=(0.0, 1.58, 0.44), cannon=(0.0, 1.60, 1.81), bore_from_barrel=True,
        turret_parts=["$weapon0"], cannon_part="$weapon0_0", barrel_part="$weapon0_1", hatch=None, mg=None,
        crawler={side: T90_LOOP for side in ("right", "left")},
        rollers={"wheel_left": range(8, 16), "wheel_right": range(0, 8)},
        turret_box=("$weapon0", ["$weapon0", "ERA-T", "ERA-TF", "obj1", "obj2", "obj3", "obj4"]),
        barrel_box=("$weapon0_1", ["$weapon0_1"]), coax=(-0.25, 1.60, 1.81), smoke=True,
        shell=["$body", "ERA-B", "obj5"], decor=[]),
}


class Tank:
    def __init__(self, tank_id, spec):
        self.id, self.spec = tank_id, spec
        path = os.path.join(SRC, "models", "tanks", f"{spec.get('src', tank_id)}.mqo")
        digest = hashlib.sha1(open(path, "rb").read()).hexdigest()
        if digest != spec["sha1"]:
            raise SystemExit(f"{path}: sha1 {digest} != EMB4 1.0.3 release")
        self.parts = mqo.parts(mqo.read(path))

    def T(self, p):
        return (np.asarray(p, dtype=float) + [0.0, self.spec["ground"], 0.0]) * self.spec["scale"]

    def bounds(self, objs):
        pts = np.vstack([self.T(o.verts) for o in objs if len(o.verts)])
        return pts.min(axis=0), pts.max(axis=0)

    def named(self, part, names):
        return [o for o in self.parts[part] if o.name in names]

    def hinge(self):
        """Closed -> open rigid fit => (pivot, frame with +X = hinge axis, swing degrees about +X, closed objs)."""
        h = self.spec["hatch"]
        closed, opened = self.named(h["part"], h["closed"]), self.named(h["part"], h["open"])
        a = self.T(np.vstack([o.verts for o in closed]))
        b = self.T(np.vstack([o.verts for o in opened]))
        ca, cb = a.mean(axis=0), b.mean(axis=0)
        u, _, vt = np.linalg.svd((a - ca).T @ (b - cb))
        r = vt.T @ np.diag([1, 1, np.sign(np.linalg.det(vt.T @ u.T))]) @ u.T
        shift = cb - r @ ca
        err = np.abs(a @ r.T + shift - b).max()
        assert err < 1e-4, f"{self.id}: hatch copies not rigid ({err})"
        swing = np.arccos(np.clip((np.trace(r) - 1) / 2, -1, 1))
        axis = np.array([r[2, 1] - r[1, 2], r[0, 2] - r[2, 0], r[1, 0] - r[0, 1]]) / (2 * np.sin(swing))
        point = np.linalg.lstsq(np.eye(3) - r, shift, rcond=None)[0]
        pivot = point + axis * ((ca - point) @ axis)
        y = np.array([0.0, 1.0, 0.0]) - axis * axis[1]
        y /= np.linalg.norm(y)
        return pivot, np.column_stack([axis, y, np.cross(axis, y)]), float(np.degrees(swing)), closed


def quaternion(m):
    """xyzw of a proper rotation matrix."""
    w = np.sqrt(max(0.0, 1 + np.trace(m))) / 2
    x = np.copysign(np.sqrt(max(0.0, 1 + m[0, 0] - m[1, 1] - m[2, 2])) / 2, m[2, 1] - m[1, 2])
    y = np.copysign(np.sqrt(max(0.0, 1 - m[0, 0] + m[1, 1] - m[2, 2])) / 2, m[0, 2] - m[2, 0])
    z = np.copysign(np.sqrt(max(0.0, 1 - m[0, 0] - m[1, 1] + m[2, 2])) / 2, m[1, 0] - m[0, 1])
    return [round(float(q), 6) for q in (x, y, z, w)]


# ---------------------------------------------------------------- display

def display(t):
    s = t.spec
    w = mqo.GltfWriter(f"{NS}:models/ground/{t.id}/{t.id}.bin", f"{NS}:textures/ground/{t.id}/{t.id}.png",
                       "tools/stage_emb4_tank.py")

    def mesh_of(name, objs, pivot, frame=np.eye(3), material="paint"):
        pos, nrm, uv = [], [], []
        for o in objs:
            if o.tris:
                p, n, u = mqo.triangles(o, t.T)
                pos.append((p - pivot) @ frame)
                nrm.append(n @ frame)
                uv.append(u)
        return w.mesh(name, np.vstack(pos), np.vstack(nrm), np.vstack(uv), material)

    turret_p, cannon_p = t.T(s["turret"]), t.T(s["cannon"])
    root = w.node(t.id)
    decor = t.named("$body", s["decor"])
    w.node("body", mesh=mesh_of("body", [o for o in t.parts["$body"] if o not in decor], np.zeros(3)), parent=root)
    if decor:
        w.node("decor", mesh=mesh_of("decor", decor, np.zeros(3), material="paint_decor"), parent=root)
    turret_objs = [o for p in s["turret_parts"] for o in t.parts[p]]
    turret = w.node("turret", turret_p, mesh_of("turret", turret_objs, turret_p), root)
    cannon = w.node("cannon", cannon_p - turret_p, mesh_of("cannon", t.parts[s["cannon_part"]], cannon_p), turret)
    if s["barrel_part"]:
        w.node("barrel", mesh=mesh_of("barrel", t.parts[s["barrel_part"]], cannon_p), parent=cannon)
    if s["mg"]:
        mg_p = t.T(s["mg"]["pivot"])
        mg = w.node("mg", mg_p - turret_p, mesh_of("mg", t.parts[s["mg"]["yaw"]], mg_p), turret)
        if s["mg"]["pitch"]:
            w.node("mg_up", mesh=mesh_of("mg_up", t.parts[s["mg"]["pitch"]], mg_p), parent=mg)
    if s.get("loader_mg"):
        lm_p = t.T(s["loader_mg"]["pivot"])
        lm = w.node("loader_mg", lm_p - turret_p, mesh_of("loader_mg", t.parts[s["loader_mg"]["yaw"]], lm_p), turret)
        w.node("loader_mg_up", mesh=mesh_of("loader_mg_up", t.parts[s["loader_mg"]["pitch"]], lm_p), parent=lm)
    if s["hatch"]:
        pivot, frame, _, closed = t.hinge()
        hinge = w.node("hatch_hinge", pivot - turret_p, parent=turret, rotation=quaternion(frame))
        w.node("hatch", mesh=mesh_of("hatch", closed, pivot, frame), parent=hinge)
    # Controller: wheel0-9 left (+X), wheel10-19 right.
    wheels = {}
    for side, rollers in s["rollers"].items():
        group = w.node(side, parent=root)
        for k, r in enumerate(rollers):
            objs = t.parts[f"$track_roller{r}"]
            lo, hi = t.bounds(objs)
            centre = (lo + hi) / 2
            name = f"wheel{k + (0 if side == 'wheel_left' else 10)}"
            w.node(name, centre, mesh_of(name, objs, centre), group)
            wheels[name] = (centre, (hi[1] - lo[1]) / 2)
    models = os.path.join(PACK, "assets", NS, "models", "ground", t.id)
    os.makedirs(models, exist_ok=True)
    w.write(os.path.join(models, f"{t.id}.gltf"), os.path.join(models, f"{t.id}.bin"),
            {"staged_by": "tools/stage_emb4_tank.py", "source": f"EMB4 MCHeli 1.0.3 tanks/{t.id}",
             "scale": s["scale"]})
    return wheels


# ---------------------------------------------------------------- track link (TrackUnit draws Bedrock)

def link_model(t, tex_size):
    """One cube per EMB4 $crawler_track0 link: x across, y inward, z along; per-face UV from the MQO quads."""
    link = t.parts["$crawler_track0"][0]
    lo, hi = t.bounds([link])
    size = (hi - lo) * 16
    faces = {}
    v = t.T(link.verts)
    centre = v.mean(axis=0)
    for n in range(len(link.tris) // 2):
        idx = link.tris[2 * n][0]
        normal = np.cross(v[idx[1]] - v[idx[0]], v[idx[2]] - v[idx[0]])
        axis = int(np.argmax(np.abs(normal)))
        sign = int(np.sign(normal[axis]))
        # Bedrock faces; X mirrored on load, link local +y = inward = mesh +y, +z = along.
        name = {(0, 1): "west", (0, -1): "east", (1, 1): "up", (1, -1): "down",
                (2, 1): "south", (2, -1): "north"}[(axis, sign)]
        corners = link.tris[2 * n][1] + link.tris[2 * n + 1][1]
        us, vs = [u for u, _ in corners], [q for _, q in corners]
        faces[name] = {"uv": [round(min(us) * tex_size[0], 3), round(min(vs) * tex_size[1], 3)],
                       "uv_size": [round((max(us) - min(us)) * tex_size[0], 3),
                                   round((max(vs) - min(vs)) * tex_size[1], 3)]}
    cube_ = {"origin": [round(-size[0] / 2, 3), 0.0, round(-size[2] / 2, 3)],
             "size": [round(float(x), 3) for x in size], "uv": faces}
    model = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": tex_size[0],
                        "texture_height": tex_size[1], "visible_bounds_width": 2, "visible_bounds_height": 1,
                        "visible_bounds_offset": [0, 0.25, 0]},
        "bones": [{"name": "link", "pivot": [0, 0, 0], "cubes": [cube_]}]}]}
    path = os.path.join(PACK, "assets", NS, "models", "bedrock", "effect", f"{t.id}_track_link.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(model, f, indent=1)
    return float(centre[0]), (hi - lo)


# ---------------------------------------------------------------- structure + hull

def cube(lo, hi, **extra):
    lo, hi = np.asarray(lo, float), np.asarray(hi, float)
    size = np.maximum(hi - lo, 0.05)
    # SBM mirrors Bedrock X on load: author it mirrored.
    c = {"origin": [round(float(v) * 16, 3) for v in (-hi[0], lo[1], lo[2])],
         "size": [round(float(v) * 16, 3) for v in size], "uv": [0, 0]}
    c.update(extra)
    return c


def bone(name, cubes, parent=None, pivot=(0, 0, 0)):
    out = {"name": name, "pivot": [round(float(v) * 16, 3) for v in (-pivot[0], pivot[1], pivot[2])],
           "cubes": cubes}
    if parent:
        out["parent"] = parent
    return out


def hull2d(pts):
    """Monotone chain, CCW."""
    pts = sorted(set(map(tuple, np.round(pts, 4))))

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 1e-9:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 1e-9:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def fit_hull(pts, dirs, tol, keep=()):
    """Convex piece enclosing `pts`: support planes along `dirs` (unit), greedily added worst-first from the bounding
    box + `keep` until no vertex overshoots any direction's support by >= tol -> (points, faces CCW from outside)."""
    pts = np.asarray(pts, float)
    dirs = np.vstack([np.asarray(dirs, float), np.eye(3), -np.eye(3)] + [np.asarray(k, float)[None] for k in keep])
    support = (pts @ dirs.T).max(axis=0)
    chosen = list(range(len(dirs) - 6 - len(keep), len(dirs)))
    while True:
        verts = mqo.polytope(dirs[chosen], support[chosen])
        dev = verts @ dirs.T - support
        worst = np.unravel_index(np.argmax(dev), dev.shape)
        if dev[worst] < tol:
            break
        chosen.append(int(worst[1]))
    fixed = set(range(len(dirs) - len(keep), len(dirs)))
    return mqo.plane_hull(dirs, support, chosen, fixed)


def simplify(poly, tol):
    """Drop vertices within `tol` of the chord of their neighbours (keeps the piece's SAT axis count low)."""
    poly = list(poly)
    changed = True
    while changed and len(poly) > 4:
        changed = False
        for i in range(len(poly)):
            a, b, c = np.array(poly[i - 1]), np.array(poly[i]), np.array(poly[(i + 1) % len(poly)])
            d = c - a
            if abs(d[0] * (b - a)[1] - d[1] * (b - a)[0]) / max(np.linalg.norm(d), 1e-9) < tol:
                del poly[i]
                changed = True
                break
    return poly


def prism(poly, axis, lo, hi):
    """Extrude a CCW 2-D polygon in the plane normal to `axis` between lo and hi; faces wound outward."""
    n = len(poly)
    other = [a for a in range(3) if a != axis]

    def point(p, h):
        q = [0.0, 0.0, 0.0]
        q[axis] = h
        q[other[0]], q[other[1]] = p
        return [round(float(x), 4) for x in q]

    points = [point(p, lo) for p in poly] + [point(p, hi) for p in poly]
    faces = [list(range(n))[::-1], list(range(n, 2 * n))]
    faces += [[i, (i + 1) % n, n + (i + 1) % n, n + i] for i in range(n)]
    return points, faces


def structure(t, link_x, link_size):
    s = t.spec
    body_lo, body_hi = t.bounds(t.parts["$body"])
    turret_objs = t.named(*s["turret_box"])
    tur_lo, tur_hi = t.bounds(turret_objs)
    barrel_lo, barrel_hi = t.bounds(t.named(*s["barrel_box"]))
    pivot = t.T(s["cannon"])
    bore = (barrel_lo + barrel_hi) / 2 if s["bore_from_barrel"] else pivot
    radius = (barrel_hi[0] - barrel_lo[0]) / 2
    crawler = [t.T((0, y, z)) for loop in s["crawler"].values() for y, z in loop]
    track_z = (min(p[2] for p in crawler), max(p[2] for p in crawler))
    track_top = max(p[1] for p in crawler) + link_size[1]
    half = link_size[0] / 2
    tracks = {side: [[round(sign * abs(link_x), 4), round(float(p[1]), 4), round(float(p[2]), 4)]
                     for p in (t.T((0, y, z)) for y, z in s["crawler"][side])]
              for side, sign in (("left", 1), ("right", -1))}
    turret_pivot = t.T(s["turret"])
    bones = [bone("vehicle_body", []), bone("turret", [], pivot=turret_pivot),
             bone("turret_barrel", [], parent="turret", pivot=pivot)]
    if isinstance(s["coax"], tuple) and isinstance(s["coax"][0], str):
        coax_lo, coax_hi = t.bounds(t.named(s["coax"][0], [s["coax"][1]]))
        coax_x = (coax_lo[0] + coax_hi[0]) / 2
    else:
        coax_x = t.T(s["coax"])[0]
    bones.append(bone("turret_machine_gun_barrel", [], parent="turret", pivot=[coax_x, pivot[1], pivot[2]]))
    if s["smoke"]:
        bones.append(bone("turret_smoke_grenade_barrel", [], parent="turret", pivot=turret_pivot))
    if s["mg"]:
        mg_p = t.T(s["mg"]["pivot"])
        bones.append(bone("anti_aircraft_machine_gun", [], parent="turret", pivot=mg_p))
        bones.append(bone("anti_aircraft_machine_gun_barrel", [], parent="anti_aircraft_machine_gun", pivot=mg_p))
    if s["hatch"]:
        bones.append(bone("commander_hatch", [], parent="turret", pivot=t.hinge()[0]))
    for side in ("left", "right"):
        bones.append(bone(f"suspension_{side}", []))
    model = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16},
        "bones": bones}]}
    path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", f"{t.id}.structure.json")
    with open(path, "w") as f:
        json.dump(model, f, indent=1)

    # Hull: side profile of the shell (grounded), extruded across the body; turret: plan view, extruded.
    body_pts = t.T(np.vstack([o.verts for o in t.named("$body", s["shell"])]))
    profile = [(p[2], p[1]) for p in body_pts] + [(track_z[0], 0.0), (track_z[1], 0.0)]
    side = simplify(hull2d(profile), 0.05)
    top = [(z, y) for z, y in side if y > max(y for _, y in side) - 0.1]
    deck, roof = [z for z, _ in top], min(y for _, y in top)
    deck = [min(deck), min(max(deck), float(turret_pivot[2]))]  # engine aft of the turret ring
    assert max(deck) - min(deck) > 0.3, f"{t.id}: no flat roof for the engine deck"
    hull_points, hull_faces = prism([(y, z) for z, y in side][::-1], 0, body_lo[0], body_hi[0])
    tur_pts = t.T(np.vstack([o.verts for o in turret_objs]))
    plan = simplify(hull2d([(p[0], p[2]) for p in tur_pts]), 0.05)
    tur_points, tur_faces = prism(plan, 1, tur_lo[1], tur_hi[1])
    pieces = [
        {"bone": "vehicle_body", "name": "hull", "points": hull_points, "faces": hull_faces},
        {"bone": "turret", "name": "turret", "points": tur_points, "faces": tur_faces},
        {"bone": "turret_barrel", "name": "main gun",
         "from": [round(bore[0] - radius, 4), round(bore[1] - radius, 4), round(float(barrel_lo[2]), 4)],
         "to": [round(bore[0] + radius, 4), round(bore[1] + radius, 4), round(float(barrel_hi[2]), 4)]},
    ]
    # Systems inside the skin: hidden hit_* nodes in the display glTF (drawn by nothing, traced by the mod).
    r4 = lambda v: round(float(v), 4)  # noqa: E731
    modules = [
        ("hit_track_left", *mqo.box([r4(abs(link_x) - half), 0.0, r4(track_z[0])],
                                    [r4(body_hi[0]), r4(track_top), r4(track_z[1])])),
        ("hit_track_right", *mqo.box([r4(body_lo[0]), 0.0, r4(track_z[0])],
                                     [r4(-(abs(link_x) - half)), r4(track_top), r4(track_z[1])])),
        # Engine compartment: under the aft deck, above the belly.
        ("hit_engine", *mqo.box([r4(body_lo[0] * 0.8), r4(roof * 0.35), r4(min(deck))],
                                [r4(body_hi[0] * 0.8), r4(roof - 0.05), r4(max(deck))])),
    ]
    models = os.path.join(PACK, "assets", NS, "models", "ground", t.id)
    mqo.append_hit_nodes(os.path.join(models, f"{t.id}.gltf"), os.path.join(models, f"{t.id}.bin"), modules)
    path = os.path.join(PACK, "data", NS, "models", "hull", "vehicle", f"{t.id}.hull.json")
    with open(path, "w") as f:
        json.dump({"format": 1, "pieces": pieces}, f, indent=1)
    import strip_cubes  # deleted with Bedrock: see TODO
    strip_cubes.split(PACK, t.id)
    return tracks, dict(body=(body_lo, body_hi), turret=(tur_lo, tur_hi), bore=bore, turret_pivot=turret_pivot,
                        barrel_tip=float(barrel_hi[2]), track_top=float(track_top))


def stage(tank_id):
    t = Tank(tank_id, TANKS[tank_id])
    s = t.spec
    textures = os.path.join(PACK, "assets", NS, "textures", "ground", tank_id)
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(SRC, "textures", "tanks", f"{s['texture']}.png"),
                    os.path.join(textures, f"{tank_id}.png"))
    models = os.path.join(PACK, "assets", NS, "models", "ground", tank_id)
    gltf, binary = os.path.join(models, f"{tank_id}.gltf"), os.path.join(models, f"{tank_id}.bin")
    kept = mqo.read_markers(gltf, binary)
    wheels = display(t)
    link_x, link_size = link_model(t, (1024, 1024))
    tracks, dims = structure(t, link_x, link_size)
    mqo.append_nodes(gltf, binary, kept)
    if s["hatch"]:
        mqo.append_clip(gltf, binary, "commander_hatch",
                        [("hatch", "rotation", *mqo.hinge_keys("x", round(t.hinge()[2], 3), 0.8))])
    print(f"== {tank_id}")
    print("wheels", {k: (np.round(c, 3).tolist(), round(float(r), 3)) for k, (c, r) in wheels.items()})
    print("link x", round(link_x, 3), "size", np.round(link_size, 3), "pitch", round(s["pitch"] * s["scale"], 4))
    print("tracks", json.dumps(tracks))
    for k, v in dims.items():
        print(k, np.round(v, 3).tolist() if not isinstance(v, float) else round(v, 3))


ROLE_PACK = "src/main/resources/combinedassault_packs/combinedassault"


def carve(tank_id):
    """Role glTF: spec `loader_mg` triangles out of the turret mesh => `loader_mg` (part) / `loader_mg_up` (pitch)
    + bolt, seat, view sockets; interface `loader_machine_gun`. Match: exact corner positions (3 dp)."""
    t = Tank(tank_id, TANKS[tank_id])
    spec = t.spec["loader_mg"]
    base = os.path.join(ROLE_PACK, "assets", NS, "models", "ground", tank_id, tank_id)
    with open(base + ".gltf") as f:
        doc = json.load(f)
    with open(base + ".bin", "rb") as f:
        blob = bytearray(f.read())
    names = [n.get("name") for n in doc["nodes"]]
    assert "loader_mg" not in names, f"{tank_id}: carved already"
    turret_i = names.index("turret")
    turret = doc["nodes"][turret_i]
    turret_p = np.array(turret["translation"])
    pivot = t.T(spec["pivot"])

    def array(i, width):
        a = doc["accessors"][i]
        v = doc["bufferViews"][a["bufferView"]]
        dtype = np.uint32 if a["componentType"] == 5125 else np.float32
        return np.frombuffer(bytes(blob), dtype, a["count"] * width,
                             v.get("byteOffset", 0) + a.get("byteOffset", 0)).reshape(-1, width)

    def view(data, target):
        while len(blob) % 4:
            blob.append(0)
        doc["bufferViews"].append({"buffer": 0, "byteOffset": len(blob), "byteLength": len(data), "target": target})
        blob.extend(data)
        return len(doc["bufferViews"]) - 1

    def primitive(pos, nrm, uv, idx, material):
        attributes = {}
        for key, data, kind in (("POSITION", pos, "VEC3"), ("NORMAL", nrm, "VEC3"), ("TEXCOORD_0", uv, "VEC2")):
            data = np.ascontiguousarray(data, dtype=np.float32)
            accessor = {"bufferView": view(data.tobytes(), 34962), "componentType": 5126, "count": len(data),
                        "type": kind}
            if key == "POSITION":
                accessor.update(min=data.min(axis=0).tolist(), max=data.max(axis=0).tolist())
            doc["accessors"].append(accessor)
            attributes[key] = len(doc["accessors"]) - 1
        idx = np.ascontiguousarray(idx, dtype=np.uint32).reshape(-1)
        doc["accessors"].append({"bufferView": view(idx.tobytes(), 34963), "componentType": 5125, "count": len(idx),
                                 "type": "SCALAR"})
        return {"attributes": attributes, "indices": len(doc["accessors"]) - 1, "material": material}

    prim = doc["meshes"][turret["mesh"]]["primitives"][0]
    pos, nrm, uv = (array(prim["attributes"][k], w) for k, w in (("POSITION", 3), ("NORMAL", 3), ("TEXCOORD_0", 2)))
    tris = array(prim["indices"], 1).reshape(-1, 3)
    key = lambda c: tuple(round(float(x), 3) for x in c)  # noqa: E731
    owner = np.full(len(tris), -1)
    for k, part in enumerate((spec["yaw"], spec["pitch"])):
        corners = {key(c) for o in t.parts[part] if len(o.verts) for c in t.T(o.verts) - turret_p}
        hit = np.array([all(key(pos[i]) in corners for i in tri) for tri in tris])
        assert hit.sum() == sum(len(o.tris) for o in t.parts[part]), f"{part}: {hit.sum()} triangles matched"
        owner[hit] = k

    def sub(selected, origin):
        used, local = np.unique(selected, return_inverse=True)
        return pos[used] + turret_p - origin, nrm[used], uv[used], local

    p, n, u, i = sub(tris[owner == -1], turret_p)
    doc["meshes"][turret["mesh"]]["primitives"] = [primitive(p, n, u, i, prim["material"])]
    meshes = []
    for k, name in enumerate(("loader_mg", "loader_mg_up")):
        p, n, u, i = sub(tris[owner == k], pivot)
        doc["meshes"].append({"name": name, "primitives": [primitive(p, n, u, i, prim["material"])]})
        meshes.append(len(doc["meshes"]) - 1)

    gun = t.named(spec["pitch"], [spec["gun"]])[0]
    gun_v = t.T(gun.verts) - pivot
    muzzle = gun_v[gun_v[:, 2] > gun_v[:, 2].max() - 0.02]
    bore = [float(muzzle[:, 0].mean()), float(muzzle[:, 1].mean())]
    station = "loader_machine_gun"
    hidden = lambda role, **extra: {"combinedassaultRole": role, "combinedassaultInterface": station,  # noqa: E731
                                    **extra, "gemrenderHidden": True}

    def add(name, parent, translation=None, mesh=None, extras=None, scale=None):
        node = {"name": name}
        if translation is not None:
            node["translation"] = [round(float(v), 6) for v in translation]
        if scale is not None:
            node["scale"] = scale
        if mesh is not None:
            node["mesh"] = mesh
        if extras:
            node["extras"] = extras
        doc["nodes"].append(node)
        doc["nodes"][parent].setdefault("children", []).append(len(doc["nodes"]) - 1)
        return len(doc["nodes"]) - 1

    eye = np.array(spec["eye"])
    yaw = add("loader_mg", turret_i, pivot - turret_p, meshes[0],
              {"combinedassaultRole": "part", "combinedassaultInterface": station,
               "combinedassault": {"y_rot_min": spec["y_rot"][0], "y_rot_max": spec["y_rot"][1]}})
    pitch = add("loader_mg_up", yaw, None, meshes[1],
                {"combinedassaultRole": "pitch", "combinedassaultInterface": station,
                 "combinedassault": {"x_rot_min": spec["x_rot"][0], "x_rot_max": spec["x_rot"][1]}})
    add(f"socket_bolt_{station}_0", pitch, [bore[0], bore[1], 0.0],
        extras=hidden("bolt", combinedassault={"index": 0}), scale=[1.0, 1.0, round(float(gun_v[:, 2].max()), 6)])
    add(f"socket_seat_{station}", yaw, eye - pivot, extras=hidden("seat"))
    add(f"socket_view_{station}", yaw, eye - pivot, extras=hidden("view"))
    blob = mqo.repack(doc, blob)
    with open(base + ".bin", "wb") as f:
        f.write(blob)
    with open(base + ".gltf", "w") as f:
        json.dump(doc, f, indent=1)
    print(f"{tank_id}: turret {int((owner == -1).sum())} tris, loader_mg {int((owner == 0).sum())}, "
          f"loader_mg_up {int((owner == 1).sum())}, bore {np.round(bore, 3).tolist()}")


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    if sys.argv[1:2] == ["carve"]:
        for tank_id in sys.argv[2:]:
            carve(tank_id)
        return
    for tank_id in sys.argv[1:] or TANKS:
        stage(tank_id)


if __name__ == "__main__":
    main()
