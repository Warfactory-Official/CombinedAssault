#!/usr/bin/env python3
"""Vehicle def geometry -> glTF sockets / frame keys (pack doc §6.7), then def keys the loader reports repeated -> deleted.

    python3 tools/def_sockets.py move [vehicle ...]     sockets written; defs untouched
    (load the game: VehicleDataManager logs "def repeats its glTF sockets, delete: [...]")
    python3 tools/def_sockets.py prune <log>            deletes exactly those keys (byte-preserving)
    python3 tools/def_sockets.py stations [vehicle ...] holder cubes (collider i of the holder frame) -> station sockets:
                                                        origin = box centre, Z scale = box depth; def capacity dropped.
                                                        Box = cell mouth, not round envelope (m142, mim104) => move by hand

    python3 tools/def_sockets.py sights [model:target ...] gun geometry -> `sight` socket (ADS eye) in the bolt 0 frame:
                                                        height = top of the gun (|x| < SIGHT_HALF_WIDTH) + SIGHT_CLEAR,
                                                        SIGHT_EYE_BACK behind its rearmost point; sight line || bore.
                                                        target "" = kit model (all meshes), else the part subtree, at rest
    python3 tools/def_sockets.py lens                   LENSES -> drawn `sight_lens` disc nodes (station `optic`)
    python3 tools/def_sockets.py plates <vehicle> [--pack DIR]
                                                        armor:<id> binding report: ids mesh <-> def `armor.plates`, per plate faces / area /
                                                        declared (or fitted) normal vs mesh normals; > PLATE_DEVIATION deg => exit 1
    python3 tools/def_sockets.py plates <vehicle> --assign ID --node N (--box x0,y0,z0,x1,y1,z1 | --faces i,j,..) [--dry]
                                                        faces of node N (model frame, parts at rest = F3 `Model x y z`) -> material
                                                        `<base>_armor:ID`, primitive split in place; ID `-` unbinds

Model shared by several defs: a value moves only if every def has it equal. A socket the model already has for that
field + target + index: left alone (def value is an override).
"""
import argparse
import collections
import glob
import json
import math
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import convert_pack  # noqa: E402
import jsonedit  # noqa: E402

PACK = "src/main/resources/combinedassault_packs/combinedassault"
WEAPON = "combinedassault:weapon"
LIMITS = ("x_rot_min", "x_rot_max", "y_rot_min", "y_rot_max")
REPEATS = re.compile(r"(\S+): def repeats its glTF sockets, delete: \[(.*)\]")


def asset(rl):
    ns, path = rl.split(":", 1)
    return os.path.join(PACK, "assets", ns, path)


def defs():
    out = {}
    for f in sorted(glob.glob(os.path.join(PACK, "combinedassault", "*", "vehicles", "**", "*.json"), recursive=True)):
        rel = os.path.relpath(f, os.path.join(PACK, "combinedassault"))
        ns, _, path = rel.split(os.sep, 2)
        out[ns + ":" + path[:-5].replace(os.sep, "/")] = f
    return out


def translation(v):
    m = np.eye(4)
    m[:3, 3] = v[:3]
    return m


def rot_y(deg):
    a = math.radians(deg)
    m = np.eye(4)
    m[0, 0], m[0, 2], m[2, 0], m[2, 2] = math.cos(a), math.sin(a), -math.sin(a), math.cos(a)
    return m


def rot_x(deg):
    a = math.radians(deg)
    m = np.eye(4)
    m[1, 1], m[1, 2], m[2, 1], m[2, 2] = math.cos(a), -math.sin(a), math.sin(a), math.cos(a)
    return m


def scale(x, y, z):
    return np.diag([x, y, z, 1.0])


def rigid(world):
    """Frame as the game binds it (VehicleCubeGroup): normalized rotation + origin, scale dropped."""
    m = np.eye(4)
    m[:3, :3] = world[:3, :3] / np.linalg.norm(world[:3, :3], axis=0)
    m[:3, 3] = world[:3, 3]
    return m


class Model:
    def __init__(self, rl):
        self.path = asset(rl)
        self.gltf = convert_pack.Gltf(self.path, lambda r: open(asset(r), "rb").read())
        self.frames = {}
        self.sockets = set()
        self.body = None
        for i, n in enumerate(self.gltf.nodes):
            extras = n.get("extras", {})
            role = extras.get(convert_pack.ROLE)
            if role == "body":
                self.body = i
            elif role in ("part", "pitch"):
                self.frames[(role, extras.get(convert_pack.IFACE, ""))] = i
            elif role:
                self.sockets.add((role, extras.get(convert_pack.IFACE, ""),
                                  extras.get(convert_pack.DATA, {}).get("index", -1)))
        self.added = 0

    def has(self, role, target="", index=-1):
        return (role, target, index) in self.sockets or index == 0 and (role, target, -1) in self.sockets

    def socket(self, role, target, world, parent=None, index=-1, mesh=None):
        parent = self.body if parent is None else parent
        local = np.linalg.inv(self.gltf.world(parent)) @ world
        name = "socket_" + role + ("_" + target if target else "") + ("_%d" % index if index >= 0 else "")
        node = {"name": name, "matrix": [float(round(v, 7)) for v in local.T.reshape(-1)],
                "extras": {convert_pack.HIDDEN: True}}
        if mesh is not None:
            node["mesh"] = mesh
        convert_pack.set_role(node, role, target or None, **({"index": index} if index >= 0 else {}))
        self.gltf.add_node(node, parent)
        self.added += 1


def agreed(jsons, get):
    """Value every def has, equal; else None."""
    values = [get(j) for j in jsons]
    return values[0] if values[0] is not None and all(v == values[0] for v in values) else None


def part_of(d, pid):
    return next((p for p in d.get("parts", []) if p.get("id") == pid), None)


def move(model, jsons, log):
    first = jsons[0]

    def vehicle_vec(field, getter, role):
        v = agreed(jsons, getter)
        if v is not None and not model.has(role):
            model.socket(role, "", translation(v))
            log.append(field)

    vehicle_vec("physics_info.center", lambda d: d.get("physics_info", {}).get("center"), "center_of_mass")
    vehicle_vec("center_offset", lambda d: d.get("center_offset"), "pivot")
    vehicle_vec("view_info.third_person_center_offset",
                lambda d: d.get("view_info", {}).get("third_person_center_offset"), "camera")
    vehicle_vec("view_info.third_person_center_offset_zoomed",
                lambda d: d.get("view_info", {}).get("third_person_center_offset_zoomed"), "camera_zoomed")
    vehicle_vec("towing.towable.offset", lambda d: d.get("towing", {}).get("towable", {}).get("offset"), "towable")
    vehicle_vec("towing.towable.axle", lambda d: d.get("towing", {}).get("towable", {}).get("axle"), "axle")

    for field, getter, role in (
            ("energy_info.engine_particle_offsets", lambda d: d.get("energy_info", {}).get("engine_particle_offsets"),
             "exhaust"),
            ("attributes.vortex_offsets", lambda d: d.get("attributes", {}).get("vortex_offsets"), "vortex"),
            ("attributes.aerobatic_smoke_offsets", lambda d: d.get("attributes", {}).get("aerobatic_smoke_offsets"),
             "smoke"),
            ("attributes.exhausts", lambda d: [e["offset"] + [e.get("radius", 0.5)]
                                               for e in d.get("attributes", {}).get("exhausts", [])] or None,
             "afterburner")):
        values = agreed(jsons, getter)
        if values and not any(model.has(role, "", k) for k in range(len(values))):
            for k, v in enumerate(values):
                s = v[3] if len(v) > 3 else 1.0
                model.socket(role, "", translation(v) @ scale(s, s, s), index=k)
            log.append(field)

    for hitch in first.get("towing", {}).get("hitches", []):
        hid = hitch["id"]
        v = agreed(jsons, lambda d: next((h.get("offset") for h in d.get("towing", {}).get("hitches", [])
                                          if h["id"] == hid), None))
        if v is not None and not model.has("hitch", hid):
            model.socket("hitch", hid, translation(v))
            log.append("hitches.%s.offset" % hid)

    for part in first.get("parts", []):
        pid = part["id"]
        weapon = part.get("type") == WEAPON
        part_frame = model.frames.get(("part", pid))
        pitch_frame = model.frames.get(("pitch", pid))

        def value(key):
            return agreed(jsons, lambda d: (part_of(d, pid) or {}).get(key))

        seat = value("seat_offset")
        if seat is not None and not model.has("seat", pid):
            rot = value("seat_rot") or 0.0
            model.socket("seat", pid, translation(seat) @ rot_y(-rot), part_frame)
            log.append(pid + ".seat_offset")
        dismount = value("dismount_offset")
        if dismount is not None and not model.has("dismount", pid):
            model.socket("dismount", pid, translation(dismount), part_frame)
            log.append(pid + ".dismount_offset")
        pivot = np.zeros(3) if part_frame is None else rigid(model.gltf.world(part_frame))[:3, 3]
        if weapon:
            view = value("operator_view_offset")
            if view is not None and not model.has("view", pid):
                model.socket("view", pid, translation(np.add(view, pivot)), part_frame)
                log.append(pid + ".operator_view_offset")
            sight = value("optical_sight_offset")
            if sight is not None and not model.has("sight", pid):
                model.socket("sight", pid, translation(np.add(sight, pivot)), part_frame)
                log.append(pid + ".optical_sight_offset")
            bolts = value("bolts")
            bolt_frame = pitch_frame if pitch_frame is not None else part_frame
            if bolts and bolt_frame is not None and not any(model.has("bolt", pid, k) for k in range(len(bolts))):
                at = rigid(model.gltf.world(bolt_frame))
                for k, b in enumerate(bolts):
                    world = at @ translation(b["offset"]) @ rot_y(-b.get("y_rot", 0.0)) @ rot_x(b.get("x_rot", 0.0)) \
                        @ scale(1, 1, b["barrel_length"])
                    model.socket("bolt", pid, world, bolt_frame, index=k)
                log.append(pid + ".bolts")
        else:
            view = value("owner_view_offset")
            if view is not None and not model.has("view", pid):
                model.socket("view", pid, translation(view), part_frame)
                log.append(pid + ".owner_view_offset")
        tracks = value("tracks")
        if tracks and not any(model.has("track", pid, k) for k in range(len(tracks))):
            for k, loop in enumerate(tracks):
                fan = [[0, i, i + 1] for i in range(1, len(loop) - 1)]
                positions, indices = convert_pack.triangulate(loop, fan)
                mesh = model.gltf.add_mesh("track_%s_%d" % (pid, k), positions, indices)
                model.socket("track", pid, np.eye(4), part_frame, index=k, mesh=mesh)
            log.append(pid + ".tracks")
        for key in LIMITS:
            limit = agreed(jsons, lambda d: (part_of(d, pid) or {}).get("rot_info", {}).get(key))
            frame = pitch_frame if key.startswith("x") and pitch_frame is not None else part_frame
            if limit is None or frame is None or abs(limit) > 1e30:
                continue
            meta = model.gltf.nodes[frame].setdefault("extras", {}).setdefault(convert_pack.DATA, {})
            if key not in meta:
                meta[key] = limit
                log.append(pid + ".rot_info." + key)


def cmd_move(args):
    by_model = collections.defaultdict(list)
    for vid, path in defs().items():
        if args.vehicles and vid.split(":")[1] not in args.vehicles:
            continue
        d = json.load(open(path))
        by_model[d["model"]].append((vid, d))
    for rl, users in by_model.items():
        model = Model(rl)
        if model.body is None:
            sys.exit(rl + ": no body node")
        log = []
        move(model, [d for _, d in users], log)
        if model.added or log:
            model.gltf.save(model.path, lambda r, data: open(asset(r), "wb").write(data))
        print("%s (%s): %d sockets; %s" % (rl, ", ".join(v for v, _ in users), model.added, ", ".join(log) or "-"))


def positions(gltf, mesh):
    out = []
    for prim in gltf.doc["meshes"][mesh]["primitives"]:
        acc = gltf.doc["accessors"][prim["attributes"]["POSITION"]]
        view = gltf.doc["bufferViews"][acc["bufferView"]]
        start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
        stride = view.get("byteStride", 12)
        buf = gltf.buffers[view["buffer"]]
        for k in range(acc["count"]):
            out.append(np.frombuffer(bytes(buf[start + k * stride:start + k * stride + 12]), dtype="<f4"))
    return np.array(out, dtype=float)


def triangles(gltf, mesh):
    """(n, 3) vertex rows of every triangle, three per triangle."""
    out, base = [], 0
    p = positions(gltf, mesh)
    for prim in gltf.doc["meshes"][mesh]["primitives"]:
        count = gltf.doc["accessors"][prim["attributes"]["POSITION"]]["count"]
        if "indices" in prim:
            acc = gltf.doc["accessors"][prim["indices"]]
            view = gltf.doc["bufferViews"][acc["bufferView"]]
            start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
            dtype = {5121: "<u1", 5123: "<u2", 5125: "<u4"}[acc["componentType"]]
            size = np.dtype(dtype).itemsize
            idx = np.frombuffer(bytes(gltf.buffers[view["buffer"]][start:start + acc["count"] * size]), dtype=dtype)
        else:
            idx = np.arange(count)
        out.append(p[base + idx.astype(int)])
        base += count
    return np.concatenate(out)


def slab(tris, half):
    """Triangle (n*3, 3) parts inside |x| <= half: clipped polygon vertices."""
    out = []
    for t in tris.reshape(-1, 3, 3):
        poly = list(t)
        for sign in (1, -1):
            clipped = []
            for k in range(len(poly)):
                a, b = poly[k], poly[(k + 1) % len(poly)]
                da, db = half - sign * a[0], half - sign * b[0]
                if da >= 0:
                    clipped.append(a)
                if da * db < 0:
                    clipped.append(a + (b - a) * (da / (da - db)))
            poly = clipped
            if not poly:
                break
        out.extend(poly)
    return np.array(out).reshape(-1, 3)


def cmd_stations(args):
    for vid, path in defs().items():
        if args.vehicles and vid.split(":")[1] not in args.vehicles:
            continue
        text = open(path).read()
        d = json.loads(text)
        model = None
        for part in d.get("parts", []):
            if part.get("type") != "combinedassault:missile_holder":
                continue
            model = model or Model(d["model"])
            pid = part["id"]
            frame = model.frames.get(("part", pid))
            if frame is None or model.has("station", pid, 0):
                print("%s %s: %s" % (vid, pid, "no frame" if frame is None else "has stations"))
                continue
            at = rigid(model.gltf.world(frame))
            to_frame = np.linalg.inv(at)
            boxes = []
            for c in model.gltf.nodes[frame].get("children", []):
                n = model.gltf.nodes[c]
                if n.get("extras", {}).get(convert_pack.ROLE) != "collider" or "mesh" not in n:
                    continue
                p = positions(model.gltf, n["mesh"])
                local = (to_frame @ model.gltf.world(c) @ np.c_[p, np.ones(len(p))].T)[:3].T
                boxes.append((local.min(0), local.max(0)))
            capacity = part.get("capacity", len(boxes))
            for k, (lo, hi) in enumerate(boxes[:capacity]):
                model.socket("station", pid, at @ translation((lo + hi) / 2) @ scale(1, 1, hi[2] - lo[2]), frame, index=k)
            if capacity == min(capacity, len(boxes)) and "capacity" in part:
                text = jsonedit.remove(text, ["parts", {"id": pid}, "capacity"])
            print("%s %s: %d stations" % (vid, pid, min(capacity, len(boxes))))
        if model is not None and model.added:
            model.gltf.save(model.path, lambda r, data: open(asset(r), "wb").write(data))
            json.loads(text)
            open(path, "w").write(text)


def json_path(d, label):
    """Loader label (MountDefaults put/flushLists/resolve) -> jsonedit path."""
    head, _, rest = label.partition(".")
    if head == "parts":
        pid, _, key = rest.partition(".")
        return ["parts", {"id": pid}, key]
    if head in {p["id"] for p in d.get("parts", [])}:
        return ["parts", {"id": head}] + rest.split(".")
    if head == "hitches":
        hid, _, key = rest.rpartition(".")
        return ["towing", "hitches", {"id": hid}, key]
    if head == "towable":
        return ["towing", "towable", rest]
    return label.split(".")


def cmd_prune(args):
    repeated = collections.OrderedDict()
    for line in open(args.log, errors="replace"):
        m = REPEATS.search(line)
        if m:
            repeated.setdefault(m.group(1), set()).update(s.strip() for s in m.group(2).split(","))
    files = defs()
    for vid, labels in repeated.items():
        path = files[vid]
        text = open(path).read()
        d = json.loads(text)
        for label in sorted(labels):
            text = jsonedit.remove(text, json_path(d, label))
        # rot_info / physics_info left empty by the removal
        d = json.loads(text)
        for part in d.get("parts", []):
            if part.get("rot_info") == {}:
                text = jsonedit.remove(text, ["parts", {"id": part["id"]}, "rot_info"])
        for key in ("physics_info", "view_info", "energy_info"):
            if d.get(key) == {}:
                text = jsonedit.remove(text, [key])
        json.loads(text)
        open(path, "w").write(text)
        print("%s: %d keys" % (vid, len(labels)))


SIGHT_HALF_WIDTH = 0.04
SIGHT_CLEAR = 0.03
SIGHT_EYE_BACK = 0.12
#: model -> station targets; "" = kit model; (target, {"x"|"y"|"z": bolt frame m}) overrides that coordinate
SIGHT_TARGETS = {
    "combinedassault:models/weapon/m2/m2.glb": [""],
    "combinedassault:models/weapon/kord/kord.glb": [""],
    "combinedassault:models/weapon/mk19/mk19.glb": [""],
    # PGO-9 eyepiece: LENSES centre - relief
    "combinedassault:models/weapon/spg9/spg9.glb": [("", {"x": -0.149, "y": 0.048, "z": 0.885})],
    "combinedassault:models/aircraft/ch47/ch47.glb": ["door_gun_r", "door_gun_l", "ramp_gun"],
    "combinedassault:models/aircraft/mh60l/mh60l.glb": ["door_gun_r", "door_gun_l"],
    "combinedassault:models/aircraft/w3/w3.glb": ["door_gun"],
    "combinedassault:models/ground/m1a2/m1a2.glb": ["loader_machine_gun"],
    "combinedassault:models/ground/merkava/merkava.glb": ["anti_aircraft_machine_gun"],
    "combinedassault:models/ground/centauro/centauro.glb": ["mg_l", "mg_r"],
}


def parent_of(gltf, index):
    for i, n in enumerate(gltf.nodes):
        if index in n.get("children", []):
            return i
    return None


def subtree(gltf, root):
    out, stack = [], [root]
    while stack:
        i = stack.pop()
        out.append(i)
        stack.extend(gltf.nodes[i].get("children", []))
    return out


def cmd_sights(args):
    targets = collections.defaultdict(list)
    if args.targets:
        for t in args.targets:
            rl, _, target = t.rpartition(":") if t.count(":") > 1 else (t, "", "")
            targets[rl].append(next((e for e in SIGHT_TARGETS.get(rl, []) if isinstance(e, tuple) and e[0] == target),
                                    target))
    else:
        targets.update(SIGHT_TARGETS)
    for rl, parts in targets.items():
        model = Model(rl)
        g = model.gltf
        for target in parts:
            target, fixed = target if isinstance(target, tuple) else (target, {})
            if model.has("sight", target):
                print("%s %r: has a sight socket" % (rl, target))
                continue
            bolt = next((i for i, n in enumerate(g.nodes) if n.get("extras", {}).get(convert_pack.ROLE) == "bolt"
                         and n["extras"].get(convert_pack.IFACE, "") == target
                         and n["extras"].get(convert_pack.DATA, {}).get("index", 0) == 0), None)
            if bolt is None:
                sys.exit("%s %r: no bolt 0" % (rl, target))
            frame = model.frames.get(("pitch", target)) if target else None
            part = model.frames.get(("part", target)) if target else None
            roots = subtree(g, part) if part is not None else range(len(g.nodes))
            at = rigid(g.world(bolt))
            to_bolt = np.linalg.inv(at)
            pts = []
            for i in roots:
                n = g.nodes[i]
                if "mesh" not in n or n.get("extras", {}).get(convert_pack.HIDDEN) \
                        or n.get("extras", {}).get(convert_pack.ROLE) == "sight_lens":
                    continue
                p = triangles(g, n["mesh"])
                pts.append((to_bolt @ g.world(i) @ np.c_[p, np.ones(len(p))].T)[:3].T)
            line = slab(np.concatenate(pts), SIGHT_HALF_WIDTH)
            height = line[:, 1].max() + SIGHT_CLEAR
            back = line[:, 2].min() - SIGHT_EYE_BACK
            parent = frame if frame is not None else parent_of(g, bolt)
            eye = [fixed.get("x", 0.0), fixed.get("y", height), fixed.get("z", back)]
            model.socket("sight", target, at @ translation(eye), parent)
            print("%s %r: eye %s (bolt frame)" % (rl, target, [round(v, 3) for v in eye]))
        if model.added:
            compact = "\n" not in open(model.path).read().strip()
            g.save(model.path, lambda r, data: open(asset(r), "wb").write(data))
            if compact:
                text = json.dumps(json.load(open(model.path)), separators=(",", ":"))
                open(model.path, "w").write(text)


LENS_SEGMENTS = 24
#: model -> (target, bolt frame centre, radius m); disc faces the eye (-Z)
LENSES = {
    "combinedassault:models/weapon/spg9/spg9.glb": [("", (-0.149, 0.048, 0.953), 0.026)],
}


def cmd_lens(args):
    for rl, lenses in LENSES.items():
        model = Model(rl)
        g = model.gltf
        for target, centre, radius in lenses:
            if model.has("sight_lens", target):
                print("%s %r: has a sight_lens" % (rl, target))
                continue
            bolt = next(i for i, n in enumerate(g.nodes) if n.get("extras", {}).get(convert_pack.ROLE) == "bolt"
                        and n["extras"].get(convert_pack.IFACE, "") == target
                        and n["extras"].get(convert_pack.DATA, {}).get("index", 0) == 0)
            frame = model.frames.get(("pitch", target)) if target else None
            parent = frame if frame is not None else parent_of(g, bolt)
            positions = [0.0, 0.0, 0.0]
            for k in range(LENS_SEGMENTS):
                a = 2 * math.pi * k / LENS_SEGMENTS
                positions += [radius * math.cos(a), radius * math.sin(a), 0.0]
            # Wound toward -Z (the eye).
            indices = [v for k in range(LENS_SEGMENTS) for v in (0, 2 + k % LENS_SEGMENTS if k + 1 < LENS_SEGMENTS
                                                                 else 1, 1 + k)]
            material = next((pr.get("material") for m in g.doc["meshes"] for pr in m["primitives"]
                             if pr.get("material") is not None), None)
            mesh = g.add_mesh("sight_lens", positions, indices, material=material)
            at = rigid(g.world(bolt)) @ translation(centre)
            local = np.linalg.inv(g.world(parent)) @ at
            node = {"name": "sight_lens" + ("_" + target if target else ""), "mesh": mesh,
                    "matrix": [float(round(v, 7)) for v in local.T.reshape(-1)]}
            convert_pack.set_role(node, "sight_lens", target or None)
            g.add_node(node, parent)
            model.added += 1
            print("%s %r: lens r %.4f at %s" % (rl, target, radius, centre))
        if model.added:
            compact = "\n" not in open(model.path).read().strip()
            g.save(model.path, lambda r, data: open(asset(r), "wb").write(data))
            if compact:
                open(model.path, "w").write(json.dumps(json.load(open(model.path)), separators=(",", ":")))


PLATE = re.compile(r"(?:^|[\s_.])armor:([A-Za-z0-9_]+)(?:\.\d+)?$")
# Same bar as the load-time fit: a plate whose faces disagree with its declared normal by more is mis-declared or curved.
PLATE_DEVIATION = 10.0


def plate_of(name):
    m = PLATE.search(name or "")
    return m.group(1) if m else None


def unplated(name):
    return PLATE.sub("", name).rstrip("_. ") if PLATE.search(name or "") else name


def declared_normals(spec):
    """PlateSpec.build: (positive-x normal, negative-x normal) or None (curved / undeclared => mesh normal)."""
    facing = spec.get("facing")
    if facing is None:
        return None
    f = str(facing).strip().lower()
    if f in ("top", "bottom"):
        n = np.array([0.0, 1.0 if f == "top" else -1.0, 0.0])
        return n, n
    slope = math.radians(spec.get("slope", 0))
    if f in ("front", "rear", "side"):
        hx, hz = {"front": (0, 1), "rear": (0, -1), "side": (1, 0)}[f]
    else:
        az = math.radians(float(f))
        hx, hz = -math.sin(az), math.cos(az)
    c, u = math.cos(slope), math.sin(slope)
    pos = np.array([hx * c, u, hz * c])
    return pos, (np.array([-hx * c, u, hz * c]) if f == "side" else pos)


def plate_faces(g, node):
    """Per triangle of node's mesh, primitive order (the order the game reads): (primitive, material, vertex rows in
    the model frame, true outward normal * 2 x area). A mirroring world matrix reverses winding."""
    world = g.world(node)
    flip = np.linalg.det(world[:3, :3]) < 0
    out = []
    for k, prim in enumerate(g.doc["meshes"][g.nodes[node]["mesh"]]["primitives"]):
        if prim.get("mode", 4) != 4:
            continue
        pos = accessor_array(g, prim["attributes"]["POSITION"])
        pos = (world @ np.c_[pos, np.ones(len(pos))].T).T[:, :3]
        idx = primitive_indices(g, prim, len(pos))
        for tri in pos[idx[: len(idx) // 3 * 3].reshape(-1, 3)]:
            n = np.cross(tri[1] - tri[0], tri[2] - tri[0])
            out.append((k, prim.get("material"), tri, -n if flip else n))
    return out


def accessor_array(g, i):
    acc = g.doc["accessors"][i]
    view = g.doc["bufferViews"][acc["bufferView"]]
    dtype = {5126: "<f4", 5125: "<u4", 5123: "<u2", 5121: "<u1"}[acc["componentType"]]
    width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3}[acc["type"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = view.get("byteStride", 0)
    blob = bytes(g.buffers[view["buffer"]])
    item = np.dtype(dtype).itemsize
    if stride and stride != width * item:
        return np.array([np.frombuffer(blob, dtype, width, start + r * stride) for r in range(acc["count"])], dtype=float)
    return np.frombuffer(blob, dtype, acc["count"] * width, start).reshape(acc["count"], width).astype(float)


def primitive_indices(g, prim, count):
    if "indices" not in prim:
        return np.arange(count)
    return accessor_array(g, prim["indices"]).reshape(-1).astype(int)


def cmd_plates(args):
    global PACK
    if args.pack:
        PACK = args.pack
    vid = args.vehicle if ":" in args.vehicle else "combinedassault:" + args.vehicle
    path = defs().get(vid)
    if path is None:
        sys.exit(vid + ": no def under " + PACK)
    d = json.load(open(path))
    model = Model(d["model"])
    g = model.gltf
    if args.assign:
        return assign_plate(model, args)
    specs = (d.get("armor") or {}).get("plates", {})
    names = [m.get("name", "") for m in g.doc.get("materials", [])]
    area, normal, count, worst, far, mean = {}, {}, {}, {}, {}, {}
    nodes = [i for i, n in enumerate(g.nodes) if "mesh" in n]
    for i in nodes:
        for _, m, tri, n in plate_faces(g, i):
            pid = plate_of(names[m]) if m is not None else None
            if pid is None:
                continue
            a = np.linalg.norm(n) / 2
            if a <= 0:
                continue
            count[pid] = count.get(pid, 0) + 1
            area[pid] = area.get(pid, 0) + a
            normal[pid] = normal.get(pid, 0) + n / 2
    bad = False
    for pid in sorted(set(count) | set(specs)):
        if pid not in specs:
            print("%s: mesh binds '%s' (%d faces), def has no armor.plates entry" % (vid, pid, count[pid]))
            bad = True
        elif pid not in count:
            print("%s: armor.plates '%s' is bound by no face" % (vid, pid))
            bad = True
    for pid in sorted(count):
        spec = specs.get(pid, {})
        fit = normal[pid] / np.linalg.norm(normal[pid]) if np.linalg.norm(normal[pid]) > 0 else np.zeros(3)
        coherence = np.linalg.norm(normal[pid]) / area[pid]
        declared = declared_normals(spec)
        label = "curved" if spec.get("curved") else "declared" if declared else "fitted"
        dev_max = dev_mean = 0.0
        far_faces = 0
        if not spec.get("curved"):
            for i in nodes:
                for _, m, tri, n in plate_faces(g, i):
                    if m is None or plate_of(names[m]) != pid or np.linalg.norm(n) == 0:
                        continue
                    want = fit if declared is None else declared[0 if tri[:, 0].mean() >= 0 else 1]
                    dev = math.degrees(math.acos(max(-1.0, min(1.0, abs(float(np.dot(n / np.linalg.norm(n), want)))))))
                    dev_max = max(dev_max, dev)
                    dev_mean += dev * np.linalg.norm(n) / 2 / area[pid]
                    far_faces += dev > PLATE_DEVIATION
        print("%s plate %-14s %4d faces %8.3f m2  %-8s n=(%.3f, %.3f, %.3f)  mesh vs normal: mean %.1f max %.1f deg, %d > %g%s"
              % (vid, pid, count[pid], area[pid], label, *(declared[0] if declared else fit), dev_mean, dev_max,
                 far_faces, PLATE_DEVIATION, "" if declared or spec.get("curved") else "  (coherence %.2f)" % coherence))
        if far_faces:
            print("  => fix the declaration or mark `curved`")
            bad = True
    if bad:
        sys.exit(1)


def assign_plate(model, args):
    g = model.gltf
    hits = [i for i, n in enumerate(g.nodes) if n.get("name") == args.node and "mesh" in n]
    if len(hits) != 1:
        sys.exit("node %s: %d mesh nodes of that name" % (args.node, len(hits)))
    node = hits[0]
    mesh = g.nodes[node]["mesh"]
    if sum(1 for n in g.nodes if n.get("mesh") == mesh) > 1:
        sys.exit("node %s shares its mesh with another node: assigning would bind both" % args.node)
    if bool(args.box) == bool(args.faces):
        sys.exit("--assign needs exactly one of --box, --faces")
    plate = None if args.assign == "-" else args.assign
    if plate is not None and not re.fullmatch(r"[A-Za-z0-9_]+", plate):
        sys.exit("plate id %r: [A-Za-z0-9_]+" % plate)
    faces = plate_faces(g, node)
    if args.box:
        lo_hi = [float(v) for v in args.box.split(",")]
        if len(lo_hi) != 6:
            sys.exit("--box x0,y0,z0,x1,y1,z1")
        lo, hi = np.array(lo_hi[:3]), np.array(lo_hi[3:])
        picked = {t for t, (_, _, tri, _) in enumerate(faces)
                  if np.all(tri.mean(0) >= np.minimum(lo, hi)) and np.all(tri.mean(0) <= np.maximum(lo, hi))}
    else:
        picked = {int(v) for v in args.faces.split(",")}
        if max(picked) >= len(faces):
            sys.exit("--faces: node has %d triangles" % len(faces))
    if not picked:
        sys.exit("no face selected")
    materials = g.doc.setdefault("materials", [])
    by_name = {m.get("name", ""): i for i, m in enumerate(materials)}
    prims = g.doc["meshes"][mesh]["primitives"]
    first = {}
    t = 0
    for k, prim in enumerate(prims):
        if prim.get("mode", 4) != 4:
            continue
        first[k] = t
        t += len(primitive_indices(g, prim, g.doc["accessors"][prim["attributes"]["POSITION"]]["count"])) // 3
    moved = 0
    for k in sorted(first, reverse=True):
        prim = prims[k]
        base = first[k]
        pos_count = g.doc["accessors"][prim["attributes"]["POSITION"]]["count"]
        idx = primitive_indices(g, prim, pos_count)
        tris = idx[: len(idx) // 3 * 3].reshape(-1, 3)
        sel = np.array([base + r in picked for r in range(len(tris))])
        if not sel.any():
            continue
        material = prim.get("material")
        name = materials[material].get("name", "") if material is not None else "plate"
        target = unplated(name) + ("_armor:" + plate if plate else "")
        if plate is not None and plate_of(name) == plate:
            continue
        if target not in by_name:
            copy = json.loads(json.dumps(materials[material])) if material is not None else {}
            copy["name"] = target
            materials.append(copy)
            by_name[target] = len(materials) - 1
        moved += int(sel.sum())
        if args.dry:
            continue
        kept = tris[~sel].reshape(-1)
        new = {key: value for key, value in prim.items() if key != "indices"}
        new["material"] = by_name[target]
        new["indices"] = add_indices(g, tris[sel].reshape(-1))
        if len(kept):
            if "indices" in prim:
                rewrite_indices(g, prim["indices"], kept)
            else:
                prim["indices"] = add_indices(g, kept)
            prims.insert(k + 1, new)
        else:
            prims[k] = new
    print("%s: %d of %d faces -> %s%s" % (args.node, moved, len(picked), plate or "unbound", " (dry)" if args.dry else ""))
    if moved and not args.dry:
        compact = g.path.lower().endswith(".gltf") and "\n" not in open(g.path).read().strip()
        g.save(g.path, lambda r, data: open(asset(r), "wb").write(data))
        if compact:
            open(g.path, "w").write(json.dumps(json.load(open(g.path)), separators=(",", ":")))


def add_indices(g, values):
    buf = g.buffers[0]
    while len(buf) % 4:
        buf.append(0)
    offset = len(buf)
    buf += np.asarray(values, dtype="<u4").tobytes()
    views = g.doc["bufferViews"]
    views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(values) * 4, "target": 34963})
    g.doc["accessors"].append({"bufferView": len(views) - 1, "componentType": 5125, "count": len(values), "type": "SCALAR"})
    return len(g.doc["accessors"]) - 1


def rewrite_indices(g, accessor, values):
    """Shrink an index accessor in place (same view, same component type); the tail of its range goes unread."""
    acc = g.doc["accessors"][accessor]
    view = g.doc["bufferViews"][acc["bufferView"]]
    dtype = {5121: "<u1", 5123: "<u2", 5125: "<u4"}[acc["componentType"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    data = np.asarray(values, dtype=dtype).tobytes()
    g.buffers[view["buffer"]][start:start + len(data)] = data
    acc["count"] = len(values)
    acc.pop("min", None)
    acc.pop("max", None)


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("move")
    m.add_argument("vehicles", nargs="*")
    p = sub.add_parser("prune")
    p.add_argument("log")
    st = sub.add_parser("stations")
    st.add_argument("vehicles", nargs="*")
    si = sub.add_parser("sights")
    si.add_argument("targets", nargs="*")
    sub.add_parser("lens")
    pl = sub.add_parser("plates")
    pl.add_argument("vehicle")
    pl.add_argument("--pack")
    pl.add_argument("--assign")
    pl.add_argument("--node")
    pl.add_argument("--box")
    pl.add_argument("--faces")
    pl.add_argument("--dry", action="store_true")
    args = ap.parse_args()
    {"move": cmd_move, "prune": cmd_prune, "stations": cmd_stations, "sights": cmd_sights, "lens": cmd_lens, "plates": cmd_plates}[args.cmd](args)


if __name__ == "__main__":
    main()
