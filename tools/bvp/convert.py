#!/usr/bin/env python3
"""Bert's Vehicle Pack -> CA optional pack `bvp` (glb + def + controller/script + sounds + lang + recipe + icon).

    python3 -u tools/bvp/convert.py <bvp root> <pack out dir> [--dry] <id ...|all>     (--dry: no colliders / icon)

Per-vehicle real-world data + overrides: tools/bvp/vehicles.json (BVP id). Weapon / store mapping: tools/bvp/weapons.json.
Then colliders (tools/gen_hulls.py, CoACD venv) and the slot icon (tools/model_icon.py), as tools/flans.
Build into a scratch dir; rsync finished vehicles into optional_packs/bvp.
"""
import fcntl
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import bvp  # noqa: E402
import glb  # noqa: E402
import mqo  # noqa: E402
from mqo import GltfWriter  # noqa: E402

NS = "bvp"
CA = "combinedassault:"
BUILTIN = ROOT / "src/main/resources/combinedassault_packs/combinedassault"
VEHICLES = json.loads((HERE / "vehicles.json").read_text())
WEAPONS = json.loads((HERE / "weapons.json").read_text())
G = 9.8
EYE_ABOVE_SEAT = 0.75
HEAD_OUT = 0.35
STATIC_SAG = 0.10
# SBW defaults left in the defs: not real masses
PLACEHOLDER_MASS = {("Helicopter", 11.5), ("Defense", 44.5)}
COM_BELOW_HUB = 0.15
ZETA = 0.3
# Never drawn: track links (CA lays display.track links), fallback / broken belts, spent-ERA overlays, store visuals
# (CA draws loaded rounds), cockpit occluders.
SKIP = re.compile(r"^(Track[LR]|crudeTrack[LR]|brokenTrack[LR]|bvpEraSpent_.*|suspended_.*|pilot_view_occluder)$")
WRECK = re.compile(r"^wreck_(wing_left|wing_right|fuselage_\d+)__(.+)$")


def quat_from_matrix(m):
    w = np.sqrt(max(0.0, 1 + m[0, 0] + m[1, 1] + m[2, 2])) / 2
    x = np.sqrt(max(0.0, 1 + m[0, 0] - m[1, 1] - m[2, 2])) / 2
    y = np.sqrt(max(0.0, 1 - m[0, 0] + m[1, 1] - m[2, 2])) / 2
    z = np.sqrt(max(0.0, 1 - m[0, 0] - m[1, 1] + m[2, 2])) / 2
    x = np.copysign(x, m[2, 1] - m[1, 2])
    y = np.copysign(y, m[0, 2] - m[2, 0])
    z = np.copysign(z, m[1, 0] - m[0, 1])
    q = np.array([x, y, z, w])
    return q / np.linalg.norm(q)


def basis_x(axis):
    """Rotation whose local +X = axis (unit)."""
    x = np.asarray(axis, float) / np.linalg.norm(axis)
    ref = np.array([0.0, 1.0, 0.0]) if abs(x[1]) < 0.9 else np.array([0.0, 0.0, 1.0])
    z = np.cross(x, ref)
    z /= np.linalg.norm(z)
    y = np.cross(z, x)
    return np.column_stack([x, y, z])


class Rig:
    """glTF node tree in CA blocks: keyed nodes with world origin + rotation; triangles bucketed per node."""

    def __init__(self, vid):
        self.w = GltfWriter(None, f"{NS}:textures/vehicle/{vid}/{vid}.png", "CombinedAssault tools/bvp")
        del self.w.doc["buffers"][0]["extras"]
        root = self.w.node(vid)
        self.w.doc["nodes"][root]["extras"] = {"combinedassaultRole": "body"}
        self.nodes = {"body": (root, np.zeros(3), np.eye(3))}
        self.tris = {}

    def node(self, key, parent, origin, role=None, iface=None, extra=None, rot=None, hidden=False, scale=None):
        pi, po, pr = self.nodes[parent]
        rot = np.eye(3) if rot is None else rot
        local_rot = pr.T @ rot
        i = self.w.node(key, pr.T @ (np.asarray(origin, float) - po), parent=pi,
                        rotation=None if np.allclose(local_rot, np.eye(3)) else quat_from_matrix(local_rot))
        n = self.w.doc["nodes"][i]
        ex = {}
        if role:
            ex["combinedassaultRole"] = role
        if iface:
            ex["combinedassaultInterface"] = iface
        if extra:
            ex["combinedassault"] = extra
        if hidden:
            ex["gemrenderHidden"] = True
        if ex:
            n["extras"] = ex
        if scale is not None:
            n["scale"] = [float(s) for s in scale]
        self.nodes[key] = (i, np.asarray(origin, float), rot)
        return i

    def socket(self, key, parent, role, iface, origin, extra=None, scale=None, rot=None):
        return self.node(key, parent, origin, role, iface, extra, rot=rot, hidden=True, scale=scale)

    def add(self, key, bone):
        if bone.has_mesh():
            self.tris.setdefault(key, []).append((bone.pos, bone.nrm, bone.uv))

    def world_tris(self, keys):
        return np.concatenate([p for k in keys for p, _, _ in self.tris.get(k, [])] or [np.zeros((0, 3))])

    def flush(self):
        for key, parts in self.tris.items():
            i, origin, rot = self.nodes[key]
            pos = np.concatenate([p for p, _, _ in parts])
            nrm = np.concatenate([n for _, n, _ in parts])
            uv = np.concatenate([u for _, _, u in parts])
            self.w.doc["nodes"][i]["mesh"] = self.w.mesh(key, (pos - origin) @ rot, nrm @ rot, uv)

    def mesh_node(self, key, parent, pts, faces, role, iface=None, extra=None):
        """Hidden helper mesh (collider / socket outline) in the parent's frame."""
        i = self.socket(key, parent, role, iface, self.nodes[parent][1], extra)
        _, origin, rot = self.nodes[parent]
        append_mesh(self.w, i, (np.asarray(pts, float) - origin) @ rot, faces)
        return i


def append_mesh(w, node, pts, faces):
    pos = np.ascontiguousarray(pts, dtype=np.float32)
    idx = np.ascontiguousarray([[f[0], f[k], f[k + 1]] for f in faces for k in range(1, len(f) - 1)],
                               dtype=np.uint32).reshape(-1)
    ia = len(w.doc["accessors"])
    w.doc["accessors"].append({"bufferView": w._view(pos.tobytes(), 34962), "componentType": 5126,
                               "count": len(pos), "type": "VEC3", "min": pos.min(0).tolist(), "max": pos.max(0).tolist()})
    w.doc["accessors"].append({"bufferView": w._view(idx.tobytes(), 34963), "componentType": 5125,
                               "count": len(idx), "type": "SCALAR"})
    w.doc["meshes"].append({"name": w.doc["nodes"][node]["name"], "primitives": [{"attributes": {"POSITION": ia},
                                                                                  "indices": ia + 1}]})
    w.doc["nodes"][node]["mesh"] = len(w.doc["meshes"]) - 1


def inward(loop, i):
    """CA TrackUnit inward at loop point i: toward the loop centroid, normal to the tangent."""
    tangent = loop[(i + 1) % len(loop)] - loop[i - 1]
    tangent = tangent / np.linalg.norm(tangent)
    d = loop.mean(axis=0) - loop[i]
    d -= tangent * d.dot(tangent)
    return d / np.linalg.norm(d)


def prism(radius, half_width, sides=12):
    """Wheel probe prism about the origin, axis X."""
    pts = [[sx, radius * np.cos(2 * np.pi * k / sides), radius * np.sin(2 * np.pi * k / sides)]
           for sx in (-half_width, half_width) for k in range(sides)]
    faces = [list(range(sides))[::-1], list(range(sides, 2 * sides))]
    faces += [[k, (k + 1) % sides, sides + (k + 1) % sides, sides + k] for k in range(sides)]
    return np.array(pts), faces


def box_mesh(lo, hi):
    (x0, y0, z0), (x1, y1, z1) = lo, hi
    pts = [[x, y, z] for z in (z0, z1) for y in (y0, y1) for x in (x0, x1)]
    faces = [[0, 2, 3, 1], [4, 5, 7, 6], [0, 1, 5, 4], [2, 6, 7, 3], [0, 4, 6, 2], [1, 3, 7, 5]]
    return pts, faces


def bounds(pts):
    return pts.min(axis=0), pts.max(axis=0)


def x_rot(pitch_range):
    """BVP [min, max] (+ = up) -> CA x_rot limits (- = up)."""
    lo, hi = pitch_range
    return {"x_rot_min": -float(hi), "x_rot_max": -float(lo)}


def yaw_limits(yaw_range):
    lo, hi = yaw_range
    return {} if hi - lo >= 359 else {"y_rot_min": float(lo), "y_rot_max": float(hi)}


class Vehicle:
    def __init__(self, src, out_root):
        self.src = src
        self.vid = src.vid
        self.out = out_root
        self.info = VEHICLES.get(self.vid, {})
        self.s = src.server
        self.c = src.client
        self.geo = src.geo
        self.parts = []
        self.sounds = {}
        self.bindings = []
        self.clips = {}
        self.unclassified = []
        self.rig = Rig(self.vid)
        self.assigned = {}

    # ---- geometry assignment -------------------------------------------------------------------------------
    def assign_tree(self, rules):
        """rules(bone) -> node key | None (inherit) | False (skip subtree). Root inherits `body`."""
        def walk(b, inherited):
            r = rules(b)
            if r is False:
                return
            key = inherited if r is None else r
            if r is None and b.has_mesh() and inherited == "body" and b.name != "hull":
                self.unclassified.append(b.name)
            self.assigned[b.name] = key
            self.rig.add(key, b)
            for c in b.children:
                walk(c, key)
        for b in self.geo.bones.values():
            if b.parent is None:
                walk(b, "body")

    def frame_pos(self, parent):
        """Attachment parent frame -> CA pivot. `fitted_<bone>` = that bone; a server block {Parent, Pivot} (RoofCoaxPitch,
        Ags30Pitch) = its parent + Pivot; VehicleCustomPitch (driver cameras only) = hull."""
        fixed = {"Vehicle": None, "VehicleCustomPitch": None, "Turret": "turret", "Barrel": "barell",
                 "WeaponStation": "passengerWeaponStationYaw", "WeaponStationBarrel": "passengerWeaponStationPitch"}
        if parent in fixed:
            return np.zeros(3) if fixed[parent] is None else self.geo.bones[fixed[parent]].pivot
        if parent.startswith("fitted_"):
            return self.geo.bones[parent[len("fitted_"):]].pivot
        block = self.s[parent]
        return self.frame_pos(block["Parent"]) + np.asarray(block["Pivot"], float)

    def station_ranges(self, seat_key):
        """Passenger station (yaw range, pitch range); absent => free yaw, the firing seat's pitch limits."""
        seat = next((x for x in self.s["Seats"] if seat_key in (x.get("Weapons") or [])), {})
        return (self.s.get("PassengerWeaponStationYawRange") or [-180, 180],
                self.s.get("PassengerWeaponStationPitchRange") or [seat.get("MinPitch", -10), seat.get("MaxPitch", 60)])

    def attachment(self, name):
        parent, off = self.src.attachment(name)
        if parent in ("WeaponStation", "WeaponStationBarrel"):
            off = self.station_rot() @ off
        return self.frame_pos(parent) + off

    def station_rot(self):
        """Passenger station rest heading: binding BaseYawDegrees about +Y (tail turrets 180)."""
        a = np.radians(self.s.get("PassengerWeaponStationBinding", {}).get("BaseYawDegrees", 0))
        return np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]])

    def bolt(self, name, frame, iface, index, pivot, muzzle, rot):
        """Bolt socket in a (possibly rest-rotated) frame: origin = breech level with the muzzle, +Z = bore."""
        local = rot.T @ (np.asarray(muzzle, float) - pivot)
        self.rig.socket(name, frame, "bolt", iface, pivot + rot @ np.array([local[0], local[1], 0.0]), {"index": index},
                        scale=(1, 1, max(float(local[2]), 0.3)), rot=rot)

    def station_weapons(self, names, part_id):
        """Station seat: bolts = every muzzle of its weapons; weapon ids deduplicated, binding weapon first, rest secondary."""
        key = self.s["PassengerWeaponStationBinding"]["WeaponId"]
        order = [key] + [k for k in names if k != key]
        pivot = self.geo.bones["passengerWeaponStationPitch"].pivot
        muzzles = [mz for k in order for mz in self.muzzles(k)]
        for i, mz in enumerate(muzzles):
            self.bolt(f"socket_bolt_{part_id}_{i}", f"{part_id}_pitch", part_id, i, pivot, mz, self.station_rot())
        ids = list(dict.fromkeys(self.weapon_id(k) for k in order))
        return [ids[0]] + [{"id": w, "part_unit_id": part_id, "secondary": True} for w in ids[1:]]

    # ---- output ---------------------------------------------------------------------------------------------
    def write_model(self):
        self.rig.flush()
        rel = f"models/vehicle/{self.vid}/{self.vid}.glb"
        path = self.out / "assets" / NS / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        self.rig.w.write(str(path), None)
        for clip, channels in self.clips.items():
            mqo.append_clip(str(path), None, clip, channels)
        tex = self.out / "assets" / NS / "textures/vehicle" / self.vid
        tex.mkdir(parents=True, exist_ok=True)
        shutil.copy(self.src.texture, tex / f"{self.vid}.png")
        if self.src.wreck_texture.exists():
            shutil.copy(self.src.wreck_texture, tex / f"{self.vid}_wrecked.png")
        (self.out / "assets" / NS / "textures/slot").mkdir(parents=True, exist_ok=True)
        return f"{NS}:{rel}"

    def sound(self, event, rng, **extra):
        """BVP sound event (ns:path) -> pack event bvp:<path>; ogg copied by write_sounds."""
        ns, path = event.split(":", 1)
        self.sounds[path] = (ns, path)
        return dict({"event": f"{NS}:{path}", "range": rng}, **extra)

    def write_sounds(self):
        if not self.sounds:
            return
        base = self.out / "assets" / NS
        with locked(base / "sounds.json") as path:
            doc = json.loads(path.read_text()) if path.exists() else {}
            for path_key, (ns, p) in self.sounds.items():
                src_events = json.loads((self.src.root / "assets" / ns / "sounds.json").read_text())
                entry = src_events[p]
                sounds = []
                for s in entry["sounds"]:
                    s = dict(s) if isinstance(s, dict) else {"name": s}
                    sns, spath = s["name"].split(":", 1)
                    dst = base / "sounds" / f"{spath}.ogg"
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy(self.src.root / "assets" / sns / "sounds" / f"{spath}.ogg", dst)
                    s["name"] = f"{NS}:{spath}"
                    sounds.append(s)
                doc[path_key] = {"sounds": sounds, **({"category": entry["category"]} if "category" in entry else {})}
            path.write_text(json.dumps(dict(sorted(doc.items())), indent=2) + "\n")

    def write_lang(self, extra=None):
        with locked(self.out / "assets" / NS / "lang/en_us.json") as path:
            lang = json.loads(path.read_text()) if path.exists() else {}
            lang[f"entity.{NS}.{self.vid}"] = self.src.name()
            for p in self.parts:
                if p.get("name", "").startswith(f"part.{NS}."):
                    lang[p["name"]] = p["id"].replace("_", " ").title()
            lang.update(extra or {})
            path.write_text(json.dumps(dict(sorted(lang.items())), indent=2, ensure_ascii=False) + "\n")

    def write_recipe(self, template):
        r = json.loads((BUILTIN / "data/combinedassault/recipe" / f"{template}.json").read_text())
        r["result"]["components"]["minecraft:custom_data"]["CombinedAssaultId"] = f"{NS}:{self.vid}"
        path = self.out / "data" / NS / "recipe" / f"{self.vid}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(r, indent=2) + "\n")

    def write_controller(self, script=None):
        name = f"{self.vid}_controller"
        inputs = []
        if script:
            (self.out / "assets" / NS / "scripts").mkdir(parents=True, exist_ok=True)
            (self.out / "assets" / NS / "scripts" / f"{self.vid}.js").write_text(script)
            inputs.append({"type": "script", "function": "updateBones"})
        inputs.append({"type": "bone_binding", "special_bindings": self.bindings})
        doc = {"name": name, "parameters": {}, "graph": {"type": "merge", "inputs": inputs}}
        if script:
            doc["script"] = f"{NS}:{self.vid}"
        path = self.out / "assets" / NS / "animation_controllers" / f"{name}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(doc, indent=2) + "\n")
        return f"{NS}:{name}"

    def write_def(self, d):
        for k, v in self.info.get("def", {}).items():
            d[k] = v
        path = self.out / "combinedassault" / NS / "vehicles" / f"{self.vid}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(d, indent=2) + "\n")

    def health(self):
        """BVP MaxHealth / its unconditional `All * k` modifier = hits absorbed before death."""
        return round(self.s["MaxHealth"] / self.src.health_scale())

    def mass(self):
        """Real mass kg: vehicles.json `mass`, else flight reference base mass, else BVP `Mass` (tonnes)."""
        if "mass" in self.info:
            return float(self.info["mass"])
        if self.src.flight and self.src.flight["reference"].get("baseMassKg"):
            return float(self.src.flight["reference"]["baseMassKg"])
        if (self.s.get("Type"), self.s.get("Mass")) in PLACEHOLDER_MASS:
            raise SystemExit(f"{self.vid}: vehicles.json needs `mass` (kg; BVP Mass {self.s['Mass']} is a placeholder)")
        return float(self.s["Mass"]) * 1000

    def fuel(self):
        """Litres = kg (1 unit = 1 kg). vehicles.json `fuel`, else aircraft main fuel mass."""
        if "fuel" in self.info:
            return float(self.info["fuel"])
        if self.src.flight and self.src.flight["reference"].get("mainFuelMassKg"):
            return float(self.src.flight["reference"]["mainFuelMassKg"])
        raise SystemExit(f"{self.vid}: vehicles.json needs `fuel` (litres)")

    def weapon_id(self, key):
        """BVP weapon -> CA weapon id via weapons.json patterns over its English name (lang) and key."""
        w = self.s["Weapons"][key]
        text = f"{self.src.text(w.get('Name', ''))} | {w.get('Name', '')} | {key}"
        over = self.info.get("weapons", {}).get(key)
        if over:
            return over
        for rule in WEAPONS["weapons"]:
            if re.search(rule["match"], text, re.I):
                return rule["id"]
        raise SystemExit(f"{self.vid}: no weapons.json rule for {key!r} ({text})")

    def muzzles(self, key):
        """CA muzzle points: MuzzleAttachments, else ShootPos Positions (barrel/turret frame: z negated, as T-72 attachments
        vs positions)."""
        shoot = self.s["Weapons"][key]["ShootPos"]
        if shoot.get("MuzzleAttachments"):
            return [self.attachment(m) for m in shoot["MuzzleAttachments"]]
        tf = shoot.get("Transform", "Vehicle")
        flip = np.array([1.0, 1.0, 1.0 if tf == "Vehicle" else -1.0])
        return [self.frame_pos(tf) + np.asarray(p, float) * flip for p in shoot["Positions"]]

    def fixed_gun(self, key):
        """Fixed station (part + pitch frame + bolt per muzzle) for a vehicle-frame weapon; one per BVP key."""
        sid = f"gun_{re.sub(r'[^a-z0-9]+', '_', key.lower()).strip('_')}"
        if sid in self.rig.nodes:
            return sid
        self.rig.node(sid, "body", (0, 0, 0), "part", sid)
        self.rig.node(f"{sid}_barrel", sid, (0, 0, 0), "pitch", sid, {"system": "barrel"})
        for k, mz in enumerate(self.muzzles(key)):
            self.rig.socket(f"socket_bolt_{sid}_{k}", f"{sid}_barrel", "bolt", sid, mz - [0, 0, 0.5], {"index": k},
                            scale=(1, 1, 0.5))
        part = {"id": sid, "name": sid, "type": CA + "weapon", "is_seat": False, "crosshair_style": "circle",
                "rot_info": {"x_rot_speed": 0, "y_rot_speed": 0}}
        if self.s["Weapons"][key].get("Magazine"):
            part["ammo_capacity"] = int(self.s["Weapons"][key]["Magazine"])
        self.parts.append(part)
        return sid

    def seat_socket(self, seat, part_id, frame_key):
        """Seat + view sockets. Hidden rider: seat at BVP point + sitting eye, view at its camera attachment.
        Visible rider: both at the camera attachment."""
        pos = self.frame_pos(seat.get("Transform", "Vehicle")) + np.asarray(seat["Position"], float)
        cam = seat.get("CameraPos", {}).get("EyeAttachment")
        view = self.attachment(cam) if cam else None
        if seat.get("HidePassenger"):
            eye = pos + [0, EYE_ABOVE_SEAT, 0]
            if view is None:
                top = self.roof(pos)
                view = np.array([pos[0], top + HEAD_OUT, pos[2]]) if top is not None else eye
        else:
            eye = view if view is not None else pos + [0, EYE_ABOVE_SEAT, 0]
            view = eye
        self.rig.socket(f"socket_seat_{part_id}", frame_key, "seat", part_id, eye)
        self.rig.socket(f"socket_view_{part_id}", frame_key, "view", part_id, view)
        dm = seat.get("DismountInfo")
        return [round(float(v), 3) for v in dm["Position"]] if dm and dm.get("Transform") == "Vehicle" else None

    def roof(self, p):
        """Highest drawn surface over (x, z) within 0.15, else None."""
        pts = self.rig.world_tris(list(self.rig.tris))
        near = pts[(np.abs(pts[:, 0] - p[0]) < 0.15) & (np.abs(pts[:, 2] - p[2]) < 0.15)]
        return float(near[:, 1].max()) if len(near) else None

    def hit_modules(self):
        """BVP armor internals -> CA hit modules (engine, ammo)."""
        if not self.src.armor:
            return
        for kind, system in (("engines", "engine"), ("ammo_racks", "ammo")):
            for k, box in enumerate(self.src.armor.get(kind, [])):
                c = np.asarray(box["center"], float) * bvp.PROFILE_TO_CA
                h = np.abs(np.asarray(box["half_size"], float))
                frame = "turret" if box.get("frame") == "turret" and "turret" in self.rig.nodes else "body"
                pts, faces = box_mesh(c - h, c + h)
                self.rig.mesh_node(f"hit_{system}_{k}", frame, pts, faces, "hit", extra={"system": system})

    def armor(self):
        a = self.src.armor
        if not a:
            return None
        hull = bvp.aspect_armor([p for p in a["plates"] if p["frame"] == "hull"])
        if not hull or not any(hull.values()):
            return None
        out = dict(hull)
        turret = bvp.aspect_armor([p for p in a["plates"] if p["frame"] == "turret"])
        if turret and "turret" in self.rig.nodes and any(turret.values()):
            out["parts"] = {"turret": turret}
        return out


class Ground(Vehicle):
    """Turret (+ coax / vehicle-frame stations), commander weapon station, seats; running gear by subclass."""
    RECIPE = "t90"

    def build(self):
        g = self.geo
        r = self.rig
        self.has_turret = "turret" in g.bones and "barell" in g.bones and \
            (g.bones["turret"].has_mesh() or g.bones["barell"].has_mesh())
        if self.has_turret:
            r.node("turret", "body", g.bones["turret"].pivot, "part", "turret", {"system": "turret"})
            r.node("cannon", "turret", g.bones["barell"].pivot, "pitch", "turret",
                   dict(x_rot(self.s["TurretPitchRange"]), system="barrel"))
        self.station = "passengerWeaponStationPitch" in g.bones and "PassengerWeaponStationBinding" in self.s
        if self.station:
            parent = "turret" if self.s["PassengerWeaponStationBinding"]["Parent"] == "TURRET" and self.has_turret else "body"
            r.node("hmg", parent, g.bones["passengerWeaponStationYaw"].pivot, "part", "hmg", rot=self.station_rot())
            r.node("hmg_pitch", "hmg", g.bones["passengerWeaponStationPitch"].pivot, "pitch", "hmg",
                   x_rot(self.station_ranges(self.s["PassengerWeaponStationBinding"]["WeaponId"])[1]), rot=self.station_rot())
        self.gear_frames()

        def rules(b):
            if SKIP.match(b.name):
                return False
            own = self.gear_rule(b)
            if own is not None:
                return own
            if self.has_turret and b.name in ("turret", "barell"):
                return {"turret": "turret", "barell": "cannon"}[b.name]
            if self.station and b.name in ("passengerWeaponStation", "passengerWeaponStationYaw"):
                return "hmg"
            if self.station and b.name == "passengerWeaponStationPitch":
                return "hmg_pitch"
            return None
        self.assign_tree(rules)
        self.gear_geometry()
        self.weapons()
        self.exhausts()
        self.hit_modules()
        hull = r.world_tris(["body"])
        self.lo, self.hi = bounds(hull)
        r.socket("socket_camera", "body", "camera", None, (0, self.hi[1] + 1.0, 0))
        r.socket("socket_center_of_mass", "body", "center_of_mass", None, self.center_of_mass(hull))
        model = self.write_model()
        self.write_def(self.ground_def(model))
        self.write_lang()
        self.write_recipe(self.RECIPE)
        self.write_sounds()

    def gear_frames(self):
        pass

    def gear_rule(self, b):
        return None

    def gear_geometry(self):
        pass

    def exhausts(self):
        ex = self.c.get("EngineExhaust")
        if not ex:
            return
        assert ex["Parent"] == "HULL"
        for k, o in enumerate(ex["Origins"]):
            self.rig.socket(f"socket_exhaust_{k}", "body", "exhaust", None,
                            np.asarray(o["Position"], float) * bvp.GEO_TO_CA, {"index": k})

    def turret_sub(self, key, k):
        """Secondary weapon of the turret seat: barrel/turret-frame => own pitch frame on the turret; vehicle frame => fixed."""
        shoot = self.s["Weapons"][key]["ShootPos"]
        if shoot.get("Transform", "Barrel") == "Vehicle":
            return {"id": self.weapon_id(key), "part_unit_id": self.fixed_gun(key), "secondary": True}
        sub = f"turret_mg_{k}" if k else "turret_mg"
        muzzles = self.muzzles(key)
        breech = self.geo.bones["barell"].pivot
        self.rig.node(f"{sub}_barrel", "turret", np.array([muzzles[0][0], breech[1], breech[2]]), "pitch", sub,
                      x_rot(self.s["TurretPitchRange"]))
        for i, mz in enumerate(muzzles):
            self.rig.socket(f"socket_bolt_{sub}_{i}", f"{sub}_barrel", "bolt", sub, (mz[0], mz[1], breech[2]), {"index": i},
                            scale=(1, 1, max(mz[2] - breech[2], 0.3)))
        self.parts.append({"id": sub, "name": sub, "type": CA + "weapon", "is_seat": False,
                           "rot_info": {"x_rot_speed": 1.5, "y_rot_speed": 0}})
        return {"id": self.weapon_id(key), "part_unit_id": sub, "secondary": True}

    def turret_part(self, seat, names):
        weapons = [self.weapon_id(names[0])] if names else []
        weapons += [self.turret_sub(key, k) for k, key in enumerate(names[1:])]
        if names:
            breech = self.geo.bones["barell"].pivot
            for i, mz in enumerate(self.muzzles(names[0])):
                self.rig.socket(f"socket_bolt_turret_{i}", "cannon", "bolt", "turret", (mz[0], mz[1], breech[2]),
                                {"index": i}, scale=(1, 1, max(mz[2] - breech[2], 0.3)))
        yaw, pitch = self.s["TurretTurnSpeed"]
        return {"id": "turret", "name": "part.combinedassault.turret", "type": CA + "weapon",
                "crew_roles": ["driver", "gunner"], "max_health": round(0.4 * self.health()),
                "render_bone": "turret", "loading": "autoloader" if self.s.get("HasAutoloader") else "manual",
                "rot_info": {"x_rot_speed": round(pitch / 20, 3), "y_rot_speed": round(yaw / 20, 3),
                             **yaw_limits(self.s.get("TurretYawRange") or [-180, 180])},
                "optical_sight_type": "crt", "with_stabilizer": True,
                "with_thermal_imager": bool(seat.get("HasThermalImaging")), "zoom_max": 8,
                "weapons": weapons, "protect_passenger": True}

    def station_part(self, seat, names):
        """Station seat: binding weapon first; the seat's other weapons ride the same station as secondaries."""
        key = self.s["PassengerWeaponStationBinding"]["WeaponId"]
        weapons = self.station_weapons(names, "hmg")
        yaw, pitch = self.s["PassengerWeaponStationTurnSpeed"]
        return {"id": "hmg", "name": f"part.{NS}.hmg", "type": CA + "weapon", "crew_roles": ["commander", "gunner"],
                "max_health": 120, "render_bone": "hmg", "crosshair_style": "cross",
                "rot_info": {"x_rot_speed": round(pitch / 20, 3), "y_rot_speed": round(yaw / 20, 3),
                             **yaw_limits(self.station_ranges(key)[0])},
                "optical_sight_type": "operator", "zoom_max": 3,
                "weapons": weapons,
                "protect_passenger": bool(seat.get("HidePassenger"))}

    def weapons(self):
        driver = False
        for idx, seat in enumerate(self.s["Seats"]):
            names = seat.get("Weapons", [])
            if idx == 0 and self.has_turret:
                pid, frame = "turret", "turret"
                part = self.turret_part(seat, names)
                driver = True
            elif self.station and self.s["PassengerWeaponStationBinding"]["WeaponId"] in names:
                pid, frame = "hmg", "hmg"
                part = self.station_part(seat, names)
            else:
                assert not names, f"{self.vid}: seat {idx} weapons {names} unmapped"
                pid = f"seat_{idx}"
                frame = "turret" if seat.get("Transform") == "Turret" and self.has_turret else "body"
                part = {"id": pid, "name": f"part.{NS}.{pid}", "type": CA + "generic",
                        "protect_passenger": bool(seat.get("HidePassenger"))}
                if not driver:
                    part["crew_roles"], driver = ["driver"], True
                elif seat.get("Transform") == "Turret":
                    part["crew_roles"] = ["commander"]
            dm = self.seat_socket(seat, pid, frame)
            if dm:
                part["dismount_offset"] = dm
            self.parts.append(part)
        self.parts.sort(key=lambda p: p.get("is_seat", True) is False)

    def engine_audio(self):
        a = self.src.audio
        eng = a["engine"]
        rng = eng.get("range", 112)
        p0, p1 = eng.get("idlePitch", [1, 1])
        out = {"start": self.sound(eng["start"], rng), "loops": [
            {"sound": self.sound(eng["idle"], rng), "input": "rpm", "volume": [[0.0, eng.get("volume", 1)], [1, eng.get("volume", 1)]],
             "pitch": [[0.6, p0], [1, p1]]}]}
        if eng.get("stop"):
            out["stop"] = self.sound(eng["stop"], rng)
        t = a.get("tracks")
        if t:
            out["loops"].append({"sound": self.sound(t["loop"], t.get("range", 48)), "input": "speed",
                                 "volume": [[0.01, 0], [0.1, 0.6 * t.get("volume", 1)], [0.6, t.get("volume", 1)]],
                                 "pitch": [[0, 0.8], [1, 1.1]]})
        return out

    def common_def(self, model):
        fuel = self.fuel()
        d = {"max_health": self.health()}
        armor = self.armor()
        if armor:
            d["armor"] = armor
        d.update({"view_info": {"third_person_distance": 7},
                  "energy_info": {"energy_capacity": fuel, "energy_consumption_per_tick": round(fuel / 72000, 6)},
                  "storage": {"slots": {"Small": 9, "Medium": 27, "Large": 54}.get(self.s.get("VehicleContainerType"), 27)},
                  "parts": self.parts, "model": model,
                  "display": {"icon": f"{NS}:textures/slot/{self.vid}.png", "tab_index": 100,
                              "controller": self.write_controller(), "engine": self.engine_audio(), "cabin_muffle": 0.85}})
        if self.s.get("EngineInfo", {}).get("Buoyancy", 0) > 0:
            d["can_wade"] = True
        return d


class Tracked(Ground):
    def gear_frames(self):
        rg = self.c["RunningGear"]
        assert rg["Type"] == "TRACKED", self.vid
        self.wheels = {b: side for side, names in (("left", rg["WheelBones"]["Left"]), ("right", rg["WheelBones"]["Right"]))
                       for b in names}
        for side in ("left", "right"):
            self.rig.node(f"suspension_{side}", "body", (0, 0, 0), "part", f"suspension_{side}",
                          {"system": "track", "side": side})
        for b, side in self.wheels.items():
            self.rig.node(b, f"suspension_{side}", self.geo.bones[b].pivot,
                          extra={"road_wheel": True} if re.fullmatch(r"wheel[LR]\d+", b) else None)

    def gear_rule(self, b):
        return b.name if b.name in self.wheels else None

    def gear_geometry(self):
        self.track_paths(self.c["RunningGear"])
        groups = {}
        for b, side in self.wheels.items():
            pts = self.rig.world_tris([b])
            radius = round(float(np.ptp(pts[:, 1]) / 2), 3) if len(pts) else 0.3
            groups.setdefault((side, radius), []).append(b)
        for (side, radius), bones in sorted(groups.items()):
            self.bindings.append({"bones": bones, "source": f"{side}_wheel_rotation", "axis": "x", "param": radius})

    def center_of_mass(self, hull):
        lo, hi = self.lo, self.hi
        return (0.0, min(self.contact + 0.4 * (float(hi[1]) - self.contact), float(hull[:, 1].mean())),
                float((lo[2] + hi[2]) / 2))

    def track_paths(self, rg):
        """Track socket = BVP link pivots in order (the drawn belt); link glb = one top-run link mesh."""
        g = self.geo
        self.contact = None
        spacing = []
        for side_def in rg["TrackRender"]["Sides"]:
            side = {"L": "left", "R": "right"}[side_def["Side"]]
            prefix = side_def["LinkMovePrefix"]
            n = rg["TrackRender"]["LinkCount"]
            pivots = np.array([g.bones[f"{prefix}{i}"].pivot for i in range(n)])
            spacing += list(np.linalg.norm(np.roll(pivots, -1, axis=0) - pivots, axis=1))
            # path = belt outer surface (probe contact); link mesh shifted inward by the same half thickness
            self.half_thick = rg["TrackRender"]["LinkHalfThickness"] / 16
            pts = np.array([p - inward(pivots, i) * self.half_thick for i, p in enumerate(pivots)])
            self.rig.mesh_node(f"socket_track_suspension_{side}_0", f"suspension_{side}", pts, [list(range(n))],
                               "track", f"suspension_{side}", {"index": 0})
            low = float(pts[:, 1].min())
            self.contact = low if self.contact is None else min(self.contact, low)
            if side == "left":
                self.link_source = (side_def, pivots)
        self.module = float(np.mean(spacing))
        self.belt_w = self.link_mesh()

    def link_mesh(self):
        """Top-run link: highest pivot with level neighbours; local frame = CA TrackUnit (X right, Y inward, Z -tangent)."""
        side_def, pts = self.link_source
        n = len(pts)
        level = [i for i in range(n) if abs(pts[i - 1][1] - pts[(i + 1) % n][1]) < 0.02]
        i = max(level, key=lambda k: pts[k][1])
        rot_bone = self.geo.bones[f"{side_def['LinkRotationPrefix']}{i}"]
        tangent = pts[(i + 1) % n] - pts[i - 1]
        tangent /= np.linalg.norm(tangent)
        down = inward(pts, i)
        forward = -tangent
        right = np.cross(down, forward)
        right /= np.linalg.norm(right)
        rot = np.column_stack([right, down, forward])
        local = (rot_bone.pos - pts[i]) @ rot + [0, self.half_thick, 0]
        w = GltfWriter(None, f"{NS}:textures/vehicle/{self.vid}/{self.vid}.png", "CombinedAssault tools/bvp")
        del w.doc["buffers"][0]["extras"]
        w.node("link", mesh=w.mesh("link", local, rot_bone.nrm @ rot, rot_bone.uv))
        rel = f"models/effect/{self.vid}_track_link.glb"
        out = self.out / "assets" / NS / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        w.write(str(out), None)
        self.track = {"model": f"{NS}:{rel}", "module_length": round(self.module, 4)}
        return float(np.ptp(local[:, 0]))

    def ground_def(self, model):
        e = self.s["EngineInfo"]
        mass, fuel = self.mass(), self.fuel()
        loaded = mass + fuel
        k = loaded * G / 2 / STATIC_SAG
        c = 2 * ZETA * (k * loaded / 2) ** 0.5
        for side in ("left", "right"):
            self.parts.append({"id": f"suspension_{side}", "name": f"suspension_{side}", "type": CA + "track",
                               "is_seat": False, "spring_stiffness": round(k), "damping": round(c),
                               "rest_length": 0.4, "max_compression": 0.4, "max_extension": 0.1, "max_droop": 0.12,
                               "track_width": round(max(self.belt_w, 0.2), 3)})
        d = {"type": CA + "tracked_vehicle",
             "attributes": {"brake_acceleration": 0.025, "forward_acceleration": round(e["Increment"], 4),
                            "backward_acceleration": round(e["Increment"], 4),
                            "max_speed_forward": round(e["MaxForwardSpeedRate"], 3),
                            "max_speed_backward": round(e["MaxBackwardSpeedRate"], 3),
                            "turn_acceleration": 1, "max_turn": round(e["TrackRotSpeed"], 3),
                            "engine_power": round(self.info["engine_kw"] * loaded / mass, 1)},
             "physics_info": {"mass": mass, "friction": round(0.04 * loaded * G), "suspension_response": 4,
                              "can_destroy_block": mass > 15000}}
        d.update(self.common_def(model))
        d["display"]["track"] = self.track
        return d


# SBW legacy helicopters (no client AircraftRig): rotor bones by name; rates as BVP's AH-64D rig.
LEGACY_ROTORS = {"propeller": {"Axis": [0, -1, 0], "DegreesPerTickAtFullSpeed": 98, "Direction": 1},
                 "contraPropeller": {"Axis": [0, -1, 0], "DegreesPerTickAtFullSpeed": 98, "Direction": -1},
                 "tailPropeller": {"Axis": [1, 0, 0], "DegreesPerTickAtFullSpeed": 392, "Direction": 1}}
WHEEL = re.compile(r"^wheel([LR])(\d+|Front|Rear)$")
DEFAULT_STEER = 25.0


class Wheeled(Ground):
    """Each wheel = suspension part (frame at the tyre centre): wheel_rotation x, steering_angle y on steered ones.
    Springs: Flan's rule (10 cm sag, zeta 0.3, loaded mass / wheels). No BVP Steering => front half of the axles steer."""
    RECIPE = "humvee"

    def gear_frames(self):
        g = self.geo
        rg = self.c.get("RunningGear", {})
        names = rg.get("WheelBones", {}).get("Left", []) + rg.get("WheelBones", {}).get("Right", []) \
            or [b for b in g.bones if WHEEL.match(b)]
        assert names, f"{self.vid}: no wheel bones"
        steer = {w["WheelBone"]: float(w["MaxAngleDegrees"]) for w in rg.get("Steering", {}).get("Wheels", [])}
        if not steer:
            axles = sorted({round(float(g.bones[b].pivot[2]), 1) for b in names}, reverse=True)
            front = set(axles[:max(1, len(axles) // 2)])
            steer = {b: DEFAULT_STEER for b in names if round(float(g.bones[b].pivot[2]), 1) in front}
        self.wheel_ids = {}
        self.steer = {}
        for b in sorted(names, key=lambda n: -g.bones[n].pivot[2]):
            side = "l" if WHEEL.match(b).group(1) == "L" else "r"
            pid = f"wheel_{side}{len([v for v in self.wheel_ids.values() if v.startswith('wheel_' + side)])}"
            pts = self.subtree_pos(b)
            centre = (pts.min(axis=0) + pts.max(axis=0)) / 2 if len(pts) else g.bones[b].pivot
            self.rig.node(pid, "body", centre, "part", pid, {"system": "wheel"})
            self.wheel_ids[b] = pid
            if b in steer:
                self.steer[pid] = steer[b]

    def subtree_pos(self, name):
        out, stack = [], [self.geo.bones[name]]
        while stack:
            b = stack.pop()
            if len(b.pos):
                out.append(b.pos)
            stack += b.children
        return np.concatenate(out) if out else np.zeros((0, 3))

    def gear_rule(self, b):
        return self.wheel_ids.get(b.name)

    def gear_geometry(self):
        mass, fuel = self.mass(), self.fuel()
        loaded = mass + fuel
        units = len(self.wheel_ids)
        k = loaded * G / units / STATIC_SAG
        c = 2 * ZETA * (k * loaded / units) ** 0.5
        self.hubs = []
        for pid in self.wheel_ids.values():
            pts = self.rig.world_tris([pid])
            lo, hi = bounds(pts)
            radius = float(max(hi[1] - lo[1], hi[2] - lo[2]) / 2)
            centre = self.rig.nodes[pid][1]
            self.hubs.append(centre)
            ring, faces = prism(radius, max(float(hi[0] - lo[0]) / 2, 0.05))
            self.rig.mesh_node(f"collider_{pid}", pid, ring + centre, faces, "collider", extra={"solid": False, "name": pid})
            self.bindings.append({"bones": [pid], "source": "wheel_rotation", "axis": "x", "param": round(radius, 3)})
            if pid in self.steer:
                a = self.steer[pid]
                self.bindings.append({"bones": [pid], "source": "steering_angle", "axis": "y", "multiplier": 1.0,
                                      "min": -a, "max": a})
            self.parts.append({"id": pid, "name": pid, "type": CA + "suspension", "max_health": 40, "is_seat": False,
                               "spring_stiffness": round(k), "damping": round(c), "rest_length": 0.25,
                               "max_compression": 0.25, "max_extension": 0.2})

    def center_of_mass(self, hull):
        h = np.array(self.hubs)
        return (float(h[:, 0].mean()), float(h[:, 1].mean()) - COM_BELOW_HUB, float(h[:, 2].mean()))

    def ground_def(self, model):
        e = self.s["EngineInfo"]
        mass, fuel = self.mass(), self.fuel()
        loaded = mass + fuel
        d = {"type": CA + "wheeled_vehicle",
             "attributes": {"brake_force": round(loaded * 5), "forward_force": round(loaded * 5.5),
                            "backward_force": round(loaded * 2.8),
                            "max_speed_forward": round(e["MaxForwardSpeedRate"], 3),
                            "max_speed_backward": round(e["MaxBackwardSpeedRate"], 3),
                            "steer_fade_speed": 0.6, "turn_step": 0.25, "max_turn": 2.5,
                            "engine_power": round(self.info["engine_kw"] * loaded / mass, 1)},
             "physics_info": {"mass": mass}}
        d.update(self.common_def(model))
        return d


class Emplacement(Ground):
    """SBW `Defense`: hull (+ tripod legs) static, turret seat = the gun. CA generic, mountable as an emplacement.
    Colliders hand-built as the built-in spg9: main = footprint box over the feet, gun box on the pitch frame
    (CoACD pieces on tripod legs tipped ags_30 / browning / milan / spg9 over on the settle pad)."""
    RECIPE = "spg9"
    FOOT = 0.15

    def gear_geometry(self):
        body = self.rig.world_tris(list(self.rig.tris))
        lo, hi = bounds(body)
        feet = body[body[:, 1] < lo[1] + self.FOOT]
        flo, fhi = bounds(feet)
        top = lo[1] + min(0.5, 0.5 * (hi[1] - lo[1]))
        self.footprint = (np.array([flo[0], lo[1], flo[2]]), np.array([fhi[0], top, fhi[2]]))
        pts, faces = box_mesh(*self.footprint)
        self.rig.mesh_node("collider_footprint", "body", pts, faces, "collider", extra={"main": True, "name": "footprint"})
        if self.has_turret:
            gun = self.rig.world_tris(["cannon"])
            if len(gun):
                glo, ghi = bounds(gun)
                pts, faces = box_mesh(glo, ghi)
                self.rig.mesh_node("collider_gun", "cannon", pts, faces, "collider", extra={"name": "gun"})

    def mass(self):
        if "mass" not in self.info:
            raise SystemExit(f"{self.vid}: vehicles.json needs `mass` (kg; BVP Defense Mass is a placeholder)")
        return float(self.info["mass"])

    def center_of_mass(self, hull):
        flo, fhi = self.footprint
        return tuple(float(v) for v in (flo + fhi) / 2)

    def turret_part(self, seat, names):
        part = super().turret_part(seat, names)
        part.update({"crew_roles": ["gunner"], "pose": self.info.get("pose", "sit"), "crosshair_style": "cross",
                     "optical_sight_type": "operator", "zoom_max": 3, "protect_passenger": bool(seat.get("HidePassenger"))})
        part["rot_info"]["need_power"] = False
        for k in ("loading", "with_stabilizer", "with_thermal_imager", "max_health"):
            part.pop(k)
        part["max_health"] = 100
        return part

    def ground_def(self, model):
        return {"type": CA + "generic", "attributes": {}, "physics_info": {"mass": self.mass()},
                "max_health": self.health(), "view_info": {"third_person_distance": 4},
                "mountable": {"enabled": True, "tags": ["emplacement"]},
                "parts": self.parts, "model": model,
                "display": {"icon": f"{NS}:textures/slot/{self.vid}.png", "tab_index": 300,
                            "controller": self.write_controller()}}


class Aircraft(Vehicle):
    def build(self):
        self.heli = self.s["Type"] == "Helicopter"
        g = self.geo
        rig = self.c.get("AircraftRig", {})
        r = self.rig
        sections = {}
        for b in g.bones.values():
            m = WRECK.match(b.name)
            if m:
                sections.setdefault(m.group(2), set()).add(m.group(1))
        wing_bones = {s: [b for b in g.bones.values() if b.name.startswith(f"wreck_{s}__") and b.has_mesh()]
                      for s in ("wing_left", "wing_right")}
        self.wings = {}
        for s, side, name in (("wing_left", "left", "wing_l"), ("wing_right", "right", "wing_r")):
            if not wing_bones[s]:
                continue
            pts = np.concatenate([b.pos for b in wing_bones[s]])
            lo, hi = bounds(pts)
            sign = 1 if side == "left" else -1
            inner = min(abs(lo[0]), abs(hi[0])) if lo[0] * hi[0] > 0 else 0.0
            origin = np.array([sign * inner, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2])
            r.node(name, "body", origin, extra={"system": "wing", "side": side, "health": self.wing_health()})
            self.wings[s] = name
        surfaces = {sf["Bone"]: sf for sf in rig.get("Surfaces", [])}
        rotors = {ro["Bone"]: ro for ro in rig.get("Rotors", []) if ro["SpeedChannel"] == "engineSpool"}
        if not rig:
            rotors = {name: dict(spec, Bone=name) for name, spec in LEGACY_ROTORS.items()
                      if name in g.bones and g.bones[name].has_mesh()}
        self.rotors = rotors
        rolling = {ro["Bone"]: ro for ro in rig.get("Rotors", []) if ro["SpeedChannel"] == "groundRoll"}
        gear = [gr["Bone"] for gr in rig.get("Gear", [])]
        steer = {ns["Bone"] for ns in rig.get("NoseSteering", [])}

        def section_of(bone_name):
            """Wing node owning a moving bone: its wreck meshes' section, else body."""
            secs = sections.get(bone_name, set())
            for s in ("wing_left", "wing_right"):
                if s in secs and s in self.wings:
                    return self.wings[s]
            return "body"

        self.gear_parts = {}
        for gb in gear:
            owner = section_of(gb)
            part = {"body": "landing_gear", "wing_l": "landing_gear_l", "wing_r": "landing_gear_r"}[owner]
            if part not in r.nodes:
                r.node(part, "body", (0, 0, 0), "part", part)
            self.gear_parts.setdefault(part, []).append(gb)
            r.node(gb, owner, g.bones[gb].pivot)
        for name in sorted(surfaces, key=lambda n: len(g.ancestors(n))):
            owner = next((a for a in g.ancestors(name) if a in surfaces), section_of(name))
            axis = bvp.ca_dir(surfaces[name]["Axis"])
            r.node(f"{name}_hinge", owner, g.bones[name].pivot, rot=basis_x(axis))
            r.node(name, f"{name}_hinge", g.bones[name].pivot, rot=basis_x(axis))
        mains = [n for n, ro in rotors.items() if abs(ro["Axis"][0]) <= 0.5]
        for name, ro in rotors.items():
            meta = {"system": "tail_rotor" if abs(ro["Axis"][0]) > 0.5 else "rotor"}
            if len(mains) > 1 and name in mains:
                # coaxial / tandem discs: own pools
                meta.update(unit=str(mains.index(name)), spin="cw" if ro.get("Direction", 1) > 0 else "ccw")
            r.node(name, "body", g.bones[name].pivot, extra=meta)
        for name in sorted(steer | set(rolling), key=lambda n: len(g.ancestors(n))):
            parent = next((a for a in g.ancestors(name) if a in r.nodes), section_of(name))
            r.node(name, parent, g.bones[name].pivot)
        station = "passengerWeaponStationPitch" in g.bones and "PassengerWeaponStationBinding" in self.s
        if station:
            r.node("gun", "body", g.bones["passengerWeaponStationYaw"].pivot, "part", "gun", {"system": "turret"},
                   rot=self.station_rot())
            r.node("gun_pitch", "gun", g.bones["passengerWeaponStationPitch"].pivot, "pitch", "gun",
                   dict(x_rot(self.station_ranges(self.s["PassengerWeaponStationBinding"]["WeaponId"])[1]), system="barrel"),
                   rot=self.station_rot())
        barrel_keys = {k for seat in self.s["Seats"] for k in seat.get("Weapons", [])
                       if (self.s["Weapons"][k].get("ShootPos") or {}).get("Transform") in ("Barrel", "Turret")}
        turret_gun = not station and barrel_keys and "turret" in g.bones and "barell" in g.bones
        if turret_gun:
            r.node("gun", "body", g.bones["turret"].pivot, "part", "gun", {"system": "turret"})
            r.node("gun_pitch", "gun", g.bones["barell"].pivot, "pitch", "gun",
                   dict(x_rot(self.s["TurretPitchRange"]), system="barrel"))
        moving = set(surfaces) | set(rotors) | set(gear) | steer | set(rolling)

        def rules(b):
            if SKIP.match(b.name):
                return False
            m = WRECK.match(b.name)
            if m:
                sec, owner = m.groups()
                if owner in moving:
                    return owner
                if sec in self.wings:
                    return self.wings[sec]
                return self.assigned.get(owner, "body")
            if b.name in moving:
                return b.name
            if station and b.name == "passengerWeaponStationYaw":
                return "gun"
            if station and b.name == "passengerWeaponStationPitch":
                return "gun_pitch"
            if turret_gun and b.name in ("turret", "barell"):
                return {"turret": "gun", "barell": "gun_pitch"}[b.name]
            return None
        self.assign_tree(rules)
        self.surface_bindings(surfaces)
        self.roll_bindings(rolling)
        self.gear_clip()
        hull = r.world_tris(["body"] + list(self.wings.values()))
        self.lo, self.hi = bounds(hull)
        r.socket("socket_camera", "body", "camera", None, (0, self.hi[1] + 2.0, 0))
        r.socket("socket_camera_zoomed", "body", "camera_zoomed", None, (0, self.hi[1] + 3.0, 0))
        self.seats_and_weapons(station, turret_gun)
        r.socket("socket_center_of_mass", "body", "center_of_mass", None, self.center_of_mass())
        script = self.rotor_script(rotors)
        model = self.write_model()
        self.write_aircraft_def(model, script)
        self.write_lang()
        self.write_recipe("ah64d" if self.heli else "a10")
        self.write_sounds()

    def wing_health(self):
        return max(20, round(self.health() * self.info.get("wing_share", 0.25)))

    def surface_bindings(self, surfaces):
        """Hinge child bone rotates about local X = BVP hinge axis. Source = dominant control weight; sign so +input moves
        the trailing edge: stick_pitch + (push) down, stick_roll + right surface up, pedals + toward -X."""
        for name, sf in surfaces.items():
            w = sf["ControlWeights"]
            src, weight = max((("stick_pitch", w.get("ElevatorUp", 0)), ("stick_roll", w.get("RightRoll", 0)),
                               ("pedals", w.get("RudderRight", 0))), key=lambda t: abs(t[1]))
            if weight == 0:
                continue
            pts = self.rig.world_tris([name])
            if not len(pts):
                continue
            pivot = self.geo.bones[name].pivot
            axis = self.rig.nodes[f"{name}_hinge"][2][:, 0]
            arm = pts.mean(axis=0) - pivot
            move = np.cross(axis, arm)  # trailing-edge motion per +rad about local X
            if src == "stick_pitch":
                want = -move[1]
            elif src == "stick_roll":
                want = move[1] * (1 if pivot[0] < 0 else -1)
            else:
                want = -move[0]
            sign = 1 if want > 0 else -1
            deg = float(sf.get("MaxDeflectionDegrees", 20))
            self.bindings.append({"bones": [name], "source": src, "axis": "x", "multiplier": sign * deg,
                                  "min": -deg, "max": deg})

    def roll_bindings(self, rolling):
        for name in rolling:
            pts = self.rig.world_tris([name])
            if not len(pts):
                continue
            radius = round(float(np.ptp(pts[:, 1]) / 2), 3)
            self.bindings.append({"bones": [name], "source": "wheel_rotation", "axis": "x", "param": radius})

    def gear_clip(self):
        """Gear legs translate up 0.85 x their height (Flan's convention); non-solid foot boxes under each gear part."""
        self.feet = []
        for part, legs in self.gear_parts.items():
            channels = []
            for k, leg in enumerate(legs):
                keys = [leg] + [b.name for b in self.geo.bones.values() if leg in self.geo.ancestors(b.name)]
                pts = self.rig.world_tris(keys)
                if not len(pts):
                    continue
                lo, hi = bounds(pts)
                i, origin, _ = self.rig.nodes[leg]
                parent_origin = self.rig.nodes[self.parent_key(leg)][1]
                rest = origin - parent_origin
                channels.append((leg, "translation", [0.0, 1.0],
                                 [rest.tolist(), (rest + [0, 0.85 * (hi[1] - lo[1]), 0]).tolist()]))
                half = np.maximum((hi - lo) / 2, [0.12, 0.0, 0.12])
                c = (lo + hi) / 2
                pts_box, faces = box_mesh(c - half, c + half)
                self.rig.mesh_node(f"collider_{part}_{k}", part, pts_box, faces, "collider", extra={"solid": False, "name": part})
                self.feet.append(c)
            if channels:
                self.clips[part] = channels

    def parent_key(self, key):
        i = self.rig.nodes[key][0]
        for k, (j, _, _) in self.rig.nodes.items():
            if i in self.rig.w.doc["nodes"][j].get("children", []):
                return k
        raise KeyError(key)

    def center_of_mass(self):
        lo, hi = self.lo, self.hi
        y = lo[1] + 0.45 * (hi[1] - lo[1])
        z = (lo[2] + hi[2]) / 2
        mains = [f for f in self.feet if abs(f[0]) >= 0.4]
        aux = [f for f in self.feet if abs(f[0]) < 0.4]
        if self.heli:
            hub = [self.geo.bones[b].pivot for b, ro in self.rotors.items() if abs(ro["Axis"][1]) > 0.5]
            if len(hub) == 1:
                z = float(hub[0][2])
        elif mains:
            z = float(np.mean([f[2] for f in mains]))
            if aux:
                z = 0.8 * z + 0.2 * float(np.mean([f[2] for f in aux]))
        return (0.0, float(y), float(z))

    def seats_and_weapons(self, station, turret_gun):
        """Pilot (seat 0): fixed stations + stores + flares; legacy turret gun => the seat firing it rides that part."""
        stores = self.stores()
        for idx, seat in enumerate(self.s["Seats"]):
            names = seat.get("Weapons", [])
            barrel = [k for k in names if (self.s["Weapons"][k].get("ShootPos") or {}).get("Transform") in ("Barrel", "Turret")]
            extra = (stores + self.flares()) if idx == 0 else []
            if station and self.s["PassengerWeaponStationBinding"]["WeaponId"] in names:
                pid = "gun"
                key = self.s["PassengerWeaponStationBinding"]["WeaponId"]
                yaw, pitch = self.s["PassengerWeaponStationTurnSpeed"]
                part = self.gun_part(yaw, pitch, self.station_ranges(key)[0], self.station_weapons(names, "gun") + extra)
                if idx == 0:
                    part["crew_roles"] = ["driver", "gunner"]
            elif turret_gun and barrel:
                pid = "gun"
                pivot = self.geo.bones["barell"].pivot
                for k, mz in enumerate(self.muzzles(barrel[0])):
                    self.rig.socket(f"socket_bolt_gun_{k}", "gun_pitch", "bolt", "gun", (mz[0], mz[1], pivot[2]),
                                    {"index": k}, scale=(1, 1, max(mz[2] - pivot[2], 0.3)))
                weapons = [self.weapon_id(k) for k in barrel]
                weapons += [{"id": self.weapon_id(k), "part_unit_id": self.fixed_gun(k)} for k in names if k not in barrel]
                yaw, pitch = self.s["TurretTurnSpeed"]
                part = self.gun_part(yaw, pitch, self.s.get("TurretYawRange") or [-180, 180], weapons + extra)
                if idx == 0:
                    part["crew_roles"] = ["driver", "gunner"]
            elif names or extra:
                pid = "pilot" if idx == 0 else f"seat_{idx}"
                weapons = [{"id": self.weapon_id(k), "part_unit_id": self.fixed_gun(k)} for k in names] + extra
                part = {"id": pid, "name": "pilot" if idx == 0 else f"part.{NS}.{pid}", "type": CA + "weapon",
                        "crew_roles": ["driver", "gunner"] if idx == 0 else ["gunner"], "operator_on_weapon_unit": False,
                        "crosshair_style": "cross", "rot_info": {"x_rot_speed": 0, "y_rot_speed": 0}, "weapons": weapons}
            else:
                pid = "pilot" if idx == 0 else f"seat_{idx}"
                part = {"id": pid, "name": "pilot" if idx == 0 else f"part.{NS}.{pid}", "type": CA + "generic"}
                if idx == 0:
                    part["crew_roles"] = ["driver"]
            dm = self.seat_socket(seat, pid, "body")
            if dm:
                part["dismount_offset"] = dm
            self.parts.insert(len([p for p in self.parts if p.get("is_seat", True)]), part)

    def gun_part(self, yaw, pitch, yaw_range, weapons):
        return {"id": "gun", "name": f"part.{NS}.gun", "type": CA + "weapon", "crew_roles": ["gunner"],
                "max_health": 120, "render_bone": "gun", "crosshair_style": "square",
                "fire_control_sensor_type": "eo", "optical_sight_type": "crt", "with_stabilizer": True,
                "with_thermal_imager": True, "zoom_max": 12,
                "rot_info": {"x_rot_speed": round(pitch / 20, 3), "y_rot_speed": round(yaw / 20, 3), **yaw_limits(yaw_range)},
                "weapons": weapons}

    def flares(self):
        """BVP Countermeasures flares -> built-in decoy_flare station, bolts at the source dispenser points."""
        cm = self.s.get("Countermeasures", {})
        if not cm.get("Flares"):
            return []
        self.rig.node("decoy_flare", "body", (0, 0, 0), "part", "decoy_flare")
        self.rig.node("decoy_flare_barrel", "decoy_flare", (0, 0, 0), "pitch", "decoy_flare")
        for k, key in enumerate(("FlareLeftPos", "FlareRightPos")):
            self.rig.socket(f"socket_bolt_decoy_flare_{k}", "decoy_flare_barrel", "bolt", "decoy_flare", cm[key],
                            {"index": k}, scale=(1, 1, 0.2))
        self.parts.append({"id": "decoy_flare", "name": "decoy_flare", "type": CA + "weapon", "is_seat": False,
                           "crosshair_style": "none", "firing_mode": "salvo", "rot_info": {"x_rot_speed": 0, "y_rot_speed": 0}})
        return [{"id": CA + "decoy_flare", "part_unit_id": "decoy_flare"}]

    def stores(self):
        """BVP pylon pairs -> per side: missile_holder `pylons_<s>` (stations) + firing part `stores_<s>` (bolts);
        weapons bvp:pylons_<s> share salvo `stores`. Wing-section stations ride that wing's pool."""
        arm = self.src.armament
        if not arm or not arm.get("Pairs"):
            return []
        sides = {"l": [], "r": []}
        sizes = set()
        for pair in arm["Pairs"]:
            allowed = pair.get("AllowedStores", [])
            pair_sizes = {self.store_size(s) for s in allowed} - {None}
            if not pair_sizes:
                continue
            sizes |= pair_sizes
            for s, key in (("l", "Left"), ("r", "Right")):
                points = []
                for st in pair.get(f"{key}Stations", []):
                    ok = [a for a in st.get("Stores", allowed) if a not in st.get("ExceptStores", [])]
                    if any(self.store_size(a) for a in ok):
                        points.append((np.asarray(st["Point"], float), st.get("Face", "bottom")))
                if not points:
                    points = [(np.asarray(pair[key], float), "bottom")]
                length = max(WEAPONS["store_length"][z] for z in pair_sizes)
                sides[s] += [(pt, face, length) for pt, face in points]
        if not sizes:
            return []
        weapons = []
        for s, points in sides.items():
            if not points:
                continue
            wing = self.wings.get("wing_left" if s == "l" else "wing_right")
            on = None
            if wing:
                wlo, whi = bounds(self.rig.world_tris([wing]))
                if all(abs(p[0]) >= min(abs(wlo[0]), abs(whi[0])) for p, _, _ in points):
                    on = "left wing" if s == "l" else "right wing"
            holder, fire = f"pylons_{s}", f"stores_{s}"
            self.rig.node(holder, "body", (0, 0, 0), "part", holder)
            self.rig.node(fire, "body", (0, 0, 0), "part", fire)
            self.rig.node(f"{fire}_barrel", fire, (0, 0, 0), "pitch", fire)
            for k, (p, face, length) in enumerate(points):
                drop = {"bottom": [0, -0.2, 0], "left": [0.15, -0.05, 0], "right": [-0.15, -0.05, 0]}[face]
                c = p + drop
                self.rig.socket(f"socket_station_{holder}_{k}", holder, "station", holder, c, {"index": k},
                                scale=(1, 1, length))
                self.rig.socket(f"socket_bolt_{fire}_{k}", f"{fire}_barrel", "bolt", fire, c + [0, 0, length / 2],
                                {"index": k}, scale=(1, 1, 0.3))
            hp = {"id": holder, "name": holder, "type": CA + "missile_holder", "is_seat": False,
                  "accepts": sorted(sizes)}
            fp = {"id": fire, "name": fire, "type": CA + "weapon", "is_seat": False, "crosshair_style": "circle",
                  "fire_control_sensor_type": "eo", "parent_weapon_unit_aim": True,
                  "rot_info": {"x_rot_speed": 0, "y_rot_speed": 0}}
            if on:
                hp["on_system"] = fp["on_system"] = on
            self.parts += [fp, hp]
            weapons.append({"id": f"{NS}:pylons_{s}", "part_unit_id": fire, "salvo": "stores"})
            self.write_pylon_weapon(s, len(points))
        if len(weapons) == 1:
            del weapons[0]["salvo"]
        return weapons

    def store_size(self, store_id):
        st = self.src.store(store_id)
        for rule in WEAPONS["stores"]:
            if re.search(rule["match"], f"{store_id} | {st.get('Category', '')} | {st.get('Name', '')}", re.I):
                return rule["size"]
        raise SystemExit(f"{self.vid}: no weapons.json store rule for {store_id} ({st.get('Category')}, {st.get('Name')})")

    def write_pylon_weapon(self, s, count):
        path = self.out / "combinedassault" / NS / "weapons" / f"pylons_{s}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        with locked(path) as p:
            cap = max(count, json.loads(p.read_text())["max_capacity"]) if p.exists() else count
            p.write_text(json.dumps({"type": CA + "missile", "name": f"weapon.{NS}.pylons", "recoil": 0,
                                     "shoot_interval": 500, "max_capacity": cap, "missile_holder": f"pylons_{s}",
                                     "holder_only": True}, indent=2) + "\n")

    def rotor_script(self, rotors):
        """Rotor spin: BVP deg/tick at full spool x power (0..100)."""
        if not rotors:
            return None
        lines = ["function updateBones(context) {", "    var power = context.getPower() / 100;",
                 "    var builder = createPoseBuilder();"]
        for i, (name, ro) in enumerate(rotors.items()):
            axis = bvp.ca_dir(ro["Axis"])
            k = int(np.argmax(np.abs(axis)))
            rate = ro["DegreesPerTickAtFullSpeed"] * ro.get("Direction", 1) * np.sign(axis[k])
            # broken pool => disc stops (built-in ah64d); getRotorLift / getRotorAntiTorque: rotary-wing context only
            gate = ("context.getRotorAntiTorque()" if abs(ro["Axis"][0]) > 0.5 else "context.getRotorLift()") if self.heli else "1"
            lines.append(f'    var a{i} = (context.getFloat("spin_{i}", 0) + ({gate} > 0 ? power * {rate:.3f} : 0)) % 360;')
            lines.append(f'    context.setFloat("spin_{i}", a{i});')
            rot = ["0", "0", "0"]
            rot[k] = f"a{i}"
            lines.append(f'    builder.setRotation("{name}", {", ".join(rot)});')
        lines += ["    return builder;", "}"]
        return "\n".join(lines) + "\n"

    def write_aircraft_def(self, model, script):
        mass, fuel = self.mass(), self.fuel()
        info = self.info
        d = {"type": CA + ("rotary_wing_vehicle" if self.heli else "fixed_wing_vehicle")}
        if self.heli:
            d["attributes"] = {"main_rotor_force": round(mass * info.get("lift", 19.0)), "ceiling": 256,
                               "x_rot_speed_acceleration": 1.5, "x_rot_speed_max": 4.0,
                               "y_rot_speed_acceleration": 1.5, "y_rot_speed_max": 4.0,
                               "z_rot_speed_acceleration": 1.5, "z_rot_speed_max": 4.0,
                               "max_air_speed": round(info["speed_kmh"] / 3.6 / 20, 2)}
            friction = 2 * mass
        else:
            d["attributes"], friction = self.flight(mass, fuel)
        d["physics_info"] = {"mass": mass, "friction": friction}
        if "landing_gear" in self.gear_parts:
            d["landing_gear_part_id"] = "landing_gear"
        d["max_health"] = self.health()
        d["view_info"] = {"third_person_distance": 14 if self.heli else 16, "third_person_distance_zoomed": 4}
        d["energy_info"] = {"energy_capacity": fuel, "energy_consumption_per_tick": round(fuel / 72000, 6)}
        d["defense_stats"] = {"damage_threshold": 10, "impact_multiplier": 3}
        cm = self.s.get("Countermeasures", {})
        if cm.get("RadarWarningReceiver"):
            d["with_warning_receiver"] = True
        for gp in self.gear_parts:
            part = {"id": gp, "name": "landing_gear", "type": CA + "landing_gear", "is_seat": False,
                    "drag_k": 0.5 if gp == "landing_gear" else 0}
            if gp != "landing_gear":
                part["on_system"] = "left wing" if gp.endswith("_l") else "right wing"
            self.parts.append(part)
        d["parts"] = self.parts
        d["model"] = model
        d["display"] = {"icon": f"{NS}:textures/slot/{self.vid}.png", "tab_index": 400 if self.heli else 500,
                        "controller": self.write_controller(script)}
        d["display"]["engine"], d["display"]["cabin_muffle"] = self.engine_audio(), 0.6 if self.heli else 0.85
        self.write_def(d)

    def flight(self, mass, fuel):
        """Flan's fixed-wing calibration (tools/flans/aircraft.py) on real data; afterburner => thrust_k."""
        ref = self.src.flight["reference"]
        eng = self.src.flight["engineering"]
        speed = ref["maximumTrueAirspeedKmh"] / 3.6
        v_top = min(max(30 + 0.25 * speed, 35.0), 85.0)
        m = mass + fuel
        k_min = m / 700.0
        ld = 4.0
        v_lo2 = 9.8 * m / ((k_min + 3 * k_min * np.sin(np.radians(10))) * (ld + 0.8))
        friction = 0.5 * mass
        needed = friction + 1.5 * k_min * 2.5 * v_lo2
        thrust = max(needed, min(k_min * v_top ** 2, 0.75 * 9.8 * m))
        big, mid = mass > 30000, mass > 10000
        a = {"thrust": round(thrust), "thrust_k": round(min(eng["afterburnerMultiplier"], 1.5), 3)
             if eng.get("afterburnerEnabled") else 1.0,
             "ceiling": 512, "x_rot_input_step": 0.12 if big else 0.2, "y_rot_input_step": 0.3 if big else 0.5,
             "z_rot_input_step": 0.12 if big else 0.2,
             "air_drag_k_min": round(k_min, 2), "air_drag_k_max": round(4 * k_min, 2), "lift_to_drag_k": ld,
             "x_rot_input_drag_k": 1.0, "y_rot_input_drag_k": 0.25, "z_rot_input_drag_k": 0.125,
             "turn_rate_by_speed": 0.4,
             "x_turn_rate": 0.8 if big else 1.5 if mid else 2.0,
             "y_turn_rate": 1.2 if big else 2.2 if mid else 3.0,
             "z_turn_rate": 2.0 if big else 4.0 if mid else 7.0}
        ab = self.c.get("AfterburnerPresentation")
        if ab:
            assert ab["Frame"] == "VEHICLE_LOCAL_BLOCKS"
            a["exhausts"] = [{"offset": [round(v, 3) for v in o["Position"]], "radius": o["NozzleRadiusBlocks"],
                              "direction": o["Direction"], "smoke": 0.35} for o in ab["Outlets"]]
        return a, round(friction)

    def engine_audio(self):
        """BVP layers: engine start/idle/stop, boost (full power), afterburner, distant, airflow."""
        a = self.src.audio
        eng = a["engine"]
        rng = eng.get("range", 256)
        inp = "power" if self.heli else "throttle"
        v0, v1 = eng.get("idleVolume", [1, 1])
        p0, p1 = eng.get("idlePitch", [1, 1])
        out = {"start": self.sound(eng["start"], rng), "loops": [
            {"sound": self.sound(eng["idle"], rng), "input": inp, "volume": [[0, v0], [1, v1]], "pitch": [[0, p0], [1, p1]],
             **({"gate": [[0.05, 0], [0.2, 1]]} if self.heli else {})}]}
        if eng.get("stop"):
            out["stop"] = self.sound(eng["stop"], rng)
        b = a.get("boost")
        if b:
            out["loops"].append({"sound": self.sound(b["loop"], b["range"]), "input": "throttle",
                                 "volume": [[b.get("from", 0.3), b["volume"][0]], [1, b["volume"][1]]],
                                 "pitch": [[b.get("from", 0.3), b["pitch"][0]], [1, b["pitch"][1]]]})
        ab = a.get("afterburner")
        if ab:
            out["loops"].append({"sound": self.sound(ab["loop"], ab["range"]), "input": "throttle",
                                 "volume": [[1.0, 0], [1.05, ab["volume"][0]], [1.5, ab["volume"][1]]],
                                 "pitch": [[1.0, ab["pitch"][0]], [1.5, ab["pitch"][1]]]})
        far = a.get("distant")
        if far:
            out["loops"].append({"sound": self.sound(far["loop"], far["range"], **{"from": far["near"]}), "input": inp,
                                 "volume": [[0.2, 0], [1, far["volume"][1]]], "pitch": [[0, far["pitch"][0]], [1, far["pitch"][1]]]})
        air = a.get("airflow")
        if air:
            full = air["fullSpeed"] * 20 / (300 if not self.heli else 100)
            out["loops"].append({"sound": self.sound(air["loop"], air["range"]), "input": "speed",
                                 "volume": [[0, air["volume"][0]], [full, air["volume"][1]]],
                                 "pitch": [[0, air["pitch"][0]], [full, air["pitch"][1]]]})
        return out


class Ship(Vehicle):
    """SBW `Boat` (carriers): CA ship. Colliders hand-built like the built-in nimitz: main = keel..deck box at waterline
    beam, body boxes = BVP OBBs above the deck (island), deck = `deck: true` slabs fitted to the BVP deck grid per Z band."""
    BANDS = 16

    def build(self):
        self.assign_tree(lambda b: False if SKIP.match(b.name) else None)
        r = self.rig
        hull = r.world_tris(["body"])
        lo, hi = bounds(hull)
        deck = self.src.deck()
        top = deck["DeckHeight"]
        water = hull[np.abs(hull[:, 1]) < 1.0]
        beam = float(np.abs(water[:, 0]).max())
        self.box("collider_main", [-beam, lo[1], lo[2]], [beam, top - 0.5, hi[2]], main=True)
        for k, o in enumerate(self.s.get("OBB", [])):
            c, h = np.asarray(o["Position"], float), np.asarray(o["Size"], float)
            if c[1] - h[1] > top - 1.0:
                self.box(f"collider_island_{k}", c - h, c + h)
        cells = bvp.deck_heights(deck)
        z0 = deck["MinZ"]
        rows = np.array_split(np.arange(cells.shape[0]), self.BANDS)
        for k, band in enumerate(rows):
            flight = np.argwhere(cells[band] >= (top - 0.5) * 16)
            if not len(flight):
                continue
            xs = deck["MinX"] + flight[:, 1] * deck["CellSize"]
            za, zb = z0 + band[0] * deck["CellSize"], z0 + (band[-1] + 1) * deck["CellSize"]
            self.box(f"collider_deck_{k}", [xs.min(), top - 0.5, za], [xs.max() + deck["CellSize"], top, zb], deck=True)
        seat = self.s["Seats"][0]
        dm = self.seat_socket(seat, "helm", "body")
        part = {"id": "helm", "name": f"part.{NS}.helm", "type": CA + "generic", "crew_roles": ["driver"],
                "protect_passenger": False}
        if dm:
            part["dismount_offset"] = dm
        self.parts.append(part)
        r.socket("socket_camera", "body", "camera", None, (0, hi[1] + 10, 0))
        model = self.write_model()
        mass = self.mass()
        self.write_def({
            "type": CA + "ship",
            "attributes": {"waterline": 0.0, "draft": round(float(-lo[1]), 2),
                           "max_speed_forward": round(self.info["speed_kmh"] / 3.6 / 20, 3),
                           "max_speed_backward": round(self.info["speed_kmh"] / 3.6 / 20 / 3, 3),
                           "acceleration_time": 60, "throttle_rate": 0.25, "rudder_rate": 0.5, "turn_rate": 2.0,
                           "turn_lag": 1.0, "low_speed_turn": 0.0, "drift": 0.15, "heel": 1.0, "trim": 0.3,
                           "heave_period": 6.0, "righting_rate": 1.0, "min_keel_clearance": 2.0, "shallow_drag": 2.0},
            "max_health": self.health(),
            "view_info": {"third_person_distance": 160, "third_person_distance_zoomed": 8},
            "energy_info": {"energy_capacity": self.fuel(), "energy_consumption_per_tick": round(self.fuel() / 72000, 4)},
            "defense_stats": {"damage_threshold": 50, "impact_multiplier": 0.05},
            "physics_info": {"mass": mass, "radar_cross_section": 40, "can_tumble": False},
            "storage": {"slots": 216}, "parts": self.parts, "model": model,
            "display": {"icon": f"{NS}:textures/slot/{self.vid}.png", "tab_index": 600,
                        "controller": self.write_controller()}})
        self.write_lang()

    def box(self, name, lo, hi, main=False, deck=False):
        pts, faces = box_mesh(lo, hi)
        extra = {"name": name.split("_")[1]}
        if main:
            extra["main"] = True
        if deck:
            extra["deck"] = True
        self.rig.mesh_node(name, "body", pts, faces, "collider", extra=extra)


class locked:
    """flock around a shared pack file (lang, sounds.json, pylon weapons): parallel converter runs."""

    def __init__(self, path):
        self.path = Path(path)

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = open(self.path.with_suffix(self.path.suffix + ".lock"), "w")
        fcntl.flock(self.lock, fcntl.LOCK_EX)
        return self.path

    def __exit__(self, *exc):
        self.lock.close()
        self.path.with_suffix(self.path.suffix + ".lock").unlink(missing_ok=True)


def kind_of(src):
    t = src.server.get("Type")
    if t in ("Helicopter", "Airplane"):
        return Aircraft
    if src.client.get("RunningGear", {}).get("Type") == "TRACKED":
        return Tracked
    if src.server.get("EngineType") == "Wheel":
        return Wheeled
    if t == "Defense":
        return Emplacement
    if t == "Boat":
        return Ship
    raise SystemExit(f"{src.vid}: unsupported (Type {t}, RunningGear {src.client.get('RunningGear', {}).get('Type')})")


def pack_meta(bvp_root, out_root):
    out_root.mkdir(parents=True, exist_ok=True)
    meta = {"pack": {"pack_format": 34, "description": "[CA] Bert's Vehicle Pack by Bert"},
            "combinedassault": {"id": NS, "requires": ["combinedassault"], "authors": ["Bert"]}}
    (out_root / "pack.mcmeta").write_text(json.dumps(meta, indent=2) + "\n")
    for doc in sorted(bvp_root.glob("THIRD_PARTY_*.md")):
        shutil.copy(doc, out_root / doc.name)
    (out_root / "CREDITS.md").write_text(
        "# Bert's Vehicle Pack for CombinedAssault\n\n"
        "Bert's Vehicle Pack by Bert (Superb Warfare add-on, Forge 1.20.1): models, textures, sounds and vehicle data.\n\n"
        "- Aircraft full-power / afterburner audio layers: Tyrants and Plebeians Flan's Mod content pack "
        "(`THIRD_PARTY_AIRCRAFT_POWER_AUDIO.md`).\n"
        "- Flan's Mod / TaP exhaust provenance (Flan's Mod by Jamio Flan): `THIRD_PARTY_AFTERBURNER.md`.\n\n"
        "Changes: Bedrock poly meshes converted to glTF; vehicle data rewritten as CombinedAssault definitions; colliders, "
        "sockets and controllers added.\n")
    lang = out_root / "assets" / NS / "lang/en_us.json"
    with locked(lang) as path:
        doc = json.loads(path.read_text()) if path.exists() else {}
        doc[f"weapon.{NS}.pylons"] = "Pylons"
        path.write_text(json.dumps(dict(sorted(doc.items())), indent=2, ensure_ascii=False) + "\n")


def fix_main(out_root, vid):
    """gen_hulls --main takes the largest piece anywhere; main must ride the body frame."""
    path = out_root / "assets" / NS / f"models/vehicle/{vid}/{vid}.glb"
    doc, blob = glb.read(path)
    nodes = doc["nodes"]
    children = {c for n in nodes for c in n.get("children", [])}
    root = next(i for i in range(len(nodes)) if i not in children)
    cands = []
    for i in nodes[root].get("children", []):
        n = nodes[i]
        ex = n.get("extras", {})
        if ex.get("combinedassaultRole") != "collider" or ex.get("combinedassault", {}).get("solid") is False or "mesh" not in n:
            continue
        acc = doc["accessors"][doc["meshes"][n["mesh"]]["primitives"][0]["attributes"]["POSITION"]]
        cands.append((float(np.prod(np.array(acc["max"]) - np.array(acc["min"]))), i))
    assert cands, f"{vid}: no solid body collider"
    for n in nodes:
        ex = n.get("extras", {})
        if ex.get("combinedassaultRole") == "collider" and ex.get("combinedassault", {}).get("main"):
            del ex["combinedassault"]["main"]
    nodes[max(cands)[1]].setdefault("extras", {}).setdefault("combinedassault", {})["main"] = True
    glb.write(path, doc, blob)


def finish(out_root, vid, hull_args):
    subprocess.run([str(Path.home() / ".cache/combinedassault-hulls/bin/python"), str(ROOT / "tools/gen_hulls.py"),
                    "--pack", str(out_root), f"{NS}:{vid}", "--main", *hull_args], check=True, stdout=subprocess.DEVNULL)
    fix_main(out_root, vid)
    check_hulls(out_root, vid)
    subprocess.run([sys.executable, str(ROOT / "tools/model_icon.py"), "--pack", str(out_root), f"{NS}:{vid}"], check=True,
                   stdout=subprocess.DEVNULL)


def check_hulls(out_root, vid):
    """Colliders the game would refuse (load error => gametest JVM hangs) rebuilt exactly, then re-checked: exit if any."""
    import hullfix
    path = out_root / "assets" / NS / f"models/vehicle/{vid}/{vid}.glb"
    hullfix.fix_model(path)
    bad = hullfix.check_model(path)
    if bad:
        raise SystemExit(f"{vid}: colliders refused by ConvexHull.of: {bad}")


def main():
    bvp_root, out_root = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    ids = sys.argv[3:]
    dry = ids[:1] == ["--dry"]
    ids = ids[1:] if dry else ids
    if ids == ["all"]:
        ids = sorted(p.stem for p in (bvp_root / "data" / bvp.NS_SRC / "sbw/vehicles").glob("*.json"))
    pack_meta(bvp_root, out_root)
    for vid in ids:
        src = bvp.Source(bvp_root, vid)
        v = kind_of(src)(src, out_root)
        v.build()
        if dry:
            print(f"{vid}: built (dry)")
            continue
        hulls = v.info.get("hulls")
        if isinstance(v, (Ship, Emplacement)):
            check_hulls(out_root, vid)
            subprocess.run([sys.executable, str(ROOT / "tools/model_icon.py"), "--pack", str(out_root), f"{NS}:{vid}"],
                           check=True, stdout=subprocess.DEVNULL)
            print(f"{vid} -> {NS}:{vid} ({type(v).__name__})")
            continue
        if hulls is None and isinstance(v, Aircraft) and v.wings:
            hulls = ["--merge", "body", "--nodes", ",".join([vid, *v.wings.values()])]
        finish(out_root, vid, hulls or [])
        print(f"{vid} -> {NS}:{vid} ({type(v).__name__}); unclassified drawn bones -> body: {v.unclassified or 'none'}")


if __name__ == "__main__":
    main()
