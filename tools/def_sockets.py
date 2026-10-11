#!/usr/bin/env python3
"""Vehicle def geometry -> glTF sockets / frame keys (pack doc §6.7), then def keys the loader reports repeated -> deleted.

    python3 tools/def_sockets.py move [vehicle ...]     sockets written; defs untouched
    (load the game: VehicleDataManager logs "def repeats its glTF sockets, delete: [...]")
    python3 tools/def_sockets.py prune <log>            deletes exactly those keys (byte-preserving)

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
            ("attributes.afterburner_offsets", lambda d: d.get("attributes", {}).get("afterburner_offsets"),
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


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("move")
    m.add_argument("vehicles", nargs="*")
    p = sub.add_parser("prune")
    p.add_argument("log")
    args = ap.parse_args()
    {"move": cmd_move, "prune": cmd_prune}[args.cmd](args)


if __name__ == "__main__":
    main()
