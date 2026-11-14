"""Flan's Mod 1.12.2 plane / helicopter -> CA fixed_wing / rotary_wing optional pack entry.

Per-aircraft overrides: tools/flans/aircraft.json keyed by Flan ShortName.
Flan plane frames: model faces -x (drawn rotated 180 about Y); config points are x-forward (flan.ca as is) =>
model = flan.ca(dump) then (x, z) negated; config points need no flip.
"""
import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import convert  # noqa: E402
import flan  # noqa: E402
import mqo  # noqa: E402
from convert import BUILTIN, EYE_ABOVE_SEAT, drawn, frame, hidden  # noqa: E402

FLIP = np.array([-1.0, 1.0, -1.0])
FUSELAGE = ("bodyModel", "noseModel", "tailModel", "bayModel")
BODY = FUSELAGE + ("leftWingModel", "rightWingModel", "topWingModel", "yawFlapModel", "pitchFlapLeftModel",
                   "pitchFlapRightModel", "pitchFlapLeftWingModel", "pitchFlapRightWingModel", "bodyDoorCloseModel",
                   "tailDoorCloseModel", "rightWingPos2Model", "leftWingPos2Model", "hudModel", "planeModeParts")
WING_GROUPS = ("leftWingModel", "rightWingModel", "topWingModel", "pitchFlapLeftWingModel", "pitchFlapRightWingModel",
               "leftWingPos2Model", "rightWingPos2Model")
WING_WHEELS = ("leftWingWheelModel", "rightWingWheelModel")
GEAR = ("bodyWheelModel", "tailWheelModel", "leftWingWheelModel", "rightWingWheelModel", "skidsModel")
TABLE = json.loads((HERE / "aircraft.json").read_text())


KIT_BY_WEAPON = {"m134": "combinedassault:m134", "kord": "combinedassault:kord", "gun_12.7mm": "combinedassault:m2",
                 "ma_pkm": "combinedassault:pkm", "gun_7.62mm": "combinedassault:pkm"}
KIT_RIFLE_CALIBRE = {"browning_303", "lmg08_15", "vickers_303", "mg34", "mg17", "m1919a4", "dp28", "bren", "type97_mg"}


def kit_for(weapon_id):
    name = weapon_id.split(":")[1]
    if name in KIT_RIFLE_CALIBRE:
        return "combinedassault:pkm"
    return KIT_BY_WEAPON.get(name)


class AirModel(flan.Model):
    """flan.Model turned to the plane's forward (+Z); boxes carry their array index `i`."""

    def __init__(self, path, scale):
        super().__init__(path, scale)
        self.scale = scale
        self.vectors = {k: v * FLIP for k, v in self.vectors.items()}
        raw = json.loads(path.read_text())["groups"]
        for name, arrays in self.groups.items():
            for ai, boxes in enumerate(arrays):
                for bi, b in enumerate(boxes):
                    b["pivot"] = b["pivot"] * FLIP
                    b["polys"] = [(p * FLIP, t) for p, t in b["polys"]]
                    b["i"] = raw[name][ai][bi]["i"]

    def point(self, flan_units):
        """Dump-space point (1/16 block, flan.ca'd by the loader's convention) -> CA blocks."""
        return flan.ca(flan_units) * self.scale / 16 * FLIP


def rotated(boxes, hub, degrees, axis=2):
    """Boxes turned `degrees` about the CA axis through hub."""
    a = np.radians(degrees)
    c, s = np.cos(a), np.sin(a)
    i, j = [(1, 2), (2, 0), (0, 1)][axis]
    out = []
    for b in boxes:
        polys = []
        for p, t in b["polys"]:
            q = p - hub
            r = q.copy()
            r[:, i] = c * q[:, i] - s * q[:, j]
            r[:, j] = s * q[:, i] + c * q[:, j]
            polys.append((r + hub, t))
        out.append(dict(b, polys=polys))
    return out


def extent(boxes):
    return flan.bounds(boxes)


def clusters(boxes, tol=0.1):
    """Boxes whose inflated bounding boxes touch."""
    lo = [flan.bounds([b])[0] - tol for b in boxes]
    hi = [flan.bounds([b])[1] + tol for b in boxes]
    parent = list(range(len(boxes)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(len(boxes)):
        for j in range(i):
            if np.all(lo[i] <= hi[j]) and np.all(lo[j] <= hi[i]):
                parent[find(i)] = find(j)
    groups = {}
    for i, b in enumerate(boxes):
        groups.setdefault(find(i), []).append(b)
    return list(groups.values())


class Aircraft(convert.Vehicle):
    def __init__(self, flans, dump, pack_dir, cfg_path, info):
        self.cfg = flan.Config(cfg_path)
        self.info, self.pack_dir, self.flans = info, pack_dir, flans
        self.short = self.cfg.str("ShortName")
        self.vid = info.get("id", flan.slug(self.short))
        self.ns = flan.FLAN_PACKS[pack_dir.name]
        self.spec = TABLE.get(self.short, {})
        self.heli = self.cfg.str("Mode") == "Heli"
        self.jet = pack_dir.name == "Modern Weapons Pack" and not self.heli
        self.kind = "combinedassault:rotary_wing_vehicle" if self.heli else "combinedassault:fixed_wing_vehicle"
        model_scale = self.cfg.float("ModelScale", 1.0)
        cls, _ = flan.model_class(self.cfg.str("Model"))
        self.dump_path = dump / f"{cls}.json"
        raw = AirModel(self.dump_path, model_scale)
        self.moving = self.rotor_specs(raw)
        hidden_boxes = {(g, i) for r in self.moving for g, i in r["taken"]}
        lo, hi = extent([b for g in FUSELAGE for b in raw.boxes(g) if (g, b["i"]) not in hidden_boxes])
        length = self.spec.get("length", info.get("length"))
        self.k = (length / (hi[2] - lo[2]) if length else 1.0 / 1.5) * info.get("fit_scale", 1.0)
        self.model = AirModel(self.dump_path, model_scale * self.k)
        self.cfg_k = self.k * model_scale
        self.moving = self.rotor_specs(self.model)
        self.taken = {(g, i) for r in self.moving for g, i in r["taken"]}
        self.parts, self.kits = [], {}
        self.wheel_bindings = []

    def pos(self, key):
        v = self.cfg.vec(key)
        return None if v is None else v * self.cfg_k

    def cfg_point(self, args):
        return flan.ca([float(x) for x in args[:3]]) / 16 * self.cfg_k

    def rotor_specs(self, m):
        """Spinning assemblies: {name, kind main|tail|prop, boxes, hub, taken, unit, spin}."""
        out = []
        spec = self.spec
        for kind, group, hubs in (("main", "heliMainRotorModels", spec.get("main_hubs", [])),
                                  ("tail", "heliTailRotorModels", spec.get("tail_hubs", []))):
            for i, boxes in enumerate(m.groups.get(group, [])):
                boxes = [b for b in boxes if not b["hidden"]]
                if boxes:
                    hub = m.point(hubs[i]) if i < len(hubs) else None
                    out.append({"kind": kind, "boxes": boxes, "hub": hub, "taken": [(group, b["i"]) for b in boxes]})
        for s in spec.get("split", []):
            group = s["group"]
            lo, hi = s["range"]
            boxes = [b for b in m.boxes(group) if lo <= b["i"] <= hi]
            out.append({"kind": s["kind"], "boxes": boxes, "hub": m.point(s["hub"]), "unit": s.get("unit"),
                        "spin": s.get("spin"), "taken": [(group, b["i"]) for b in boxes]})
        if not self.heli and not self.jet:
            for i, blades in enumerate(m.groups.get("propellerModels", [])):
                blades = [b for b in blades if not b["hidden"]]
                if blades:
                    out.append({"kind": "prop", "blades": blades, "hub": blades[0]["pivot"], "index": i,
                                "taken": [("propellerModels", b["i"]) for b in blades]})
        return out

    def split_boxes(self):
        """(body boxes, {side: wing boxes}); wings only for sides with a SetupPart and boxes off the centreline.
        Side from box centre x (CA +X = left): Flan's left/right group names disagree with the geometry on several models."""
        body, wings = [], {"left": [], "right": []}
        votes = {"left": {}, "right": {}}
        for g in BODY:
            for b in self.model.boxes(g):
                if (g, b["i"]) in self.taken:
                    continue
                if g in WING_GROUPS:
                    lo, hi = flan.bounds([b])
                    cx = (lo[0] + hi[0]) / 2
                    fin = hi[0] - lo[0] < 0.25 and hi[1] - lo[1] > 0.8
                    if abs(cx) > 0.1 and not fin:
                        side = "left" if cx > 0 else "right"
                        wings[side].append(b)
                        votes[side][g] = votes[side].get(g, 0) + 1
                        continue
                body.append(b)
        if self.jet:
            body += self.model.boxes("propellerModels")
        if not self.heli and self.cfg.str("Mode") == "VTOL":
            body += self.model.boxes("planeModeParts")
        self.wing_part = {}
        for side in ("left", "right"):
            counts = votes[side]
            part = "leftWing" if counts.get("leftWingModel", 0) + counts.get("pitchFlapLeftWingModel", 0) \
                + counts.get("leftWingPos2Model", 0) >= counts.get("rightWingModel", 0) \
                + counts.get("pitchFlapRightWingModel", 0) + counts.get("rightWingPos2Model", 0) else "rightWing"
            if counts and self.setup_hp(part) is None:
                part = "rightWing" if part == "leftWing" else "leftWing"
            if wings[side] and self.setup_hp(part) is not None:
                self.wing_part[side] = part
            else:
                body += wings[side]
                wings[side] = []
        return body, wings

    def setup_hp(self, part):
        for a in self.cfg.all("SetupPart"):
            if a[0] == part:
                return float(a[1])
        return None

    def wing_health(self, side):
        core = self.setup_hp("core") or 1000.0
        return max(20, round(self.info.get("health", 500) * self.setup_hp(self.wing_part[side]) / core))

    def body_boxes(self):
        """Every drawn airframe box (body + wings)."""
        body, wings = self.split_boxes()
        return body + wings["left"] + wings["right"]

    def build(self, out_root):
        ns, vid = self.ns, self.vid
        self.out_root = out_root
        m = self.model
        w = convert.GltfWriter(None, f"{ns}:textures/vehicle/{vid}/{vid}.png", "CombinedAssault tools/flans")
        del w.doc["buffers"][0]["extras"]
        w.doc["materials"][0]["doubleSided"] = True
        root = w.node(vid)
        w.doc["nodes"][root]["extras"] = {"combinedassaultRole": "body"}
        body, wings = self.split_boxes()
        drawn(w, "body", body, root, (0, 0, 0))
        lo, hi = extent(body + wings["left"] + wings["right"])
        self.root, self.lo, self.hi = root, lo, hi
        self.wing_nodes = {}
        for side, boxes in wings.items():
            if not boxes:
                continue
            blo, bhi = extent(boxes)
            sign = 1.0 if side == "left" else -1.0
            inner = max(0.0, min(blo[0] * sign, bhi[0] * sign)) if blo[0] * bhi[0] >= 0 else 0.0
            origin = np.array([sign * inner, (blo[1] + bhi[1]) / 2, (blo[2] + bhi[2]) / 2])
            name = "wing_l" if side == "left" else "wing_r"
            node = drawn(w, name, boxes, root, origin)
            w.doc["nodes"][node]["translation"] = [float(x) for x in origin]
            w.doc["nodes"][node]["extras"] = {"combinedassault": {"system": "wing", "side": side,
                                                                 "health": self.wing_health(side)}}
            self.wing_nodes[side] = (node, origin, name)
        if self.wing_nodes:
            self.info["hulls"] = ["--merge", "body", "--nodes", ",".join(["body"] + [n[2] for n in self.wing_nodes.values()])]
        self.spinners = self.draw_rotors(w, root)
        self.gear_clip = self.gear(w, root)
        self.pilot_weapons = self.stations(w, root)
        self.seats_air(w, root)
        hidden(w, "socket_camera", root, "camera", translation=(0, hi[1] + 2.0, 0))
        hidden(w, "socket_camera_zoomed", root, "camera_zoomed", translation=(0, hi[1] + 3.0, 0))
        hidden(w, "socket_center_of_mass", root, "center_of_mass", translation=self.center_of_mass())
        rel = f"models/vehicle/{vid}/{vid}.glb"
        path = out_root / "assets" / ns / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        w.write(str(path), None)
        for clip, channels in (self.gear_clip or {}).items():
            mqo.append_clip(str(path), None, clip, channels)
        self.copy_textures(out_root)
        self.write_aircraft_files(out_root, f"{ns}:{rel}")

    def draw_rotors(self, w, root):
        """Names and spin directions the script drives."""
        spins = []
        mains = [r for r in self.moving if r["kind"] == "main"]
        tails = [r for r in self.moving if r["kind"] == "tail"]
        props = [r for r in self.moving if r["kind"] == "prop"]
        for i, r in enumerate(mains + tails + props):
            kind = r["kind"]
            hub = r["hub"]
            if hub is None:
                lo, hi = extent(r["boxes"])
                hub = np.array([0.0, (lo[1] + hi[1]) / 2, 0.0]) if kind == "main" else (lo + hi) / 2
            group = mains if kind == "main" else tails if kind == "tail" else props
            many = len(group) > 1
            idx = next(n for n, x in enumerate(group) if x is r)
            name = {"main": "rotor", "tail": "tail_rotor", "prop": "prop"}[kind] + (f"_{idx}" if many or kind == "prop" else "")
            spin = r.get("spin") or ("cw" if idx % 2 == 0 else "ccw")
            sign = 1 if spin == "cw" else -1
            system = {"main": "rotor", "tail": "tail_rotor", "prop": "rotor"}[kind]
            if kind == "prop":
                n = len(r["blades"])
                boxes = []
                for j, b in enumerate(r["blades"]):
                    boxes += rotated([b], hub, 360.0 * j / n)
            else:
                boxes = r["boxes"]
            node = drawn(w, name, boxes, root, hub)
            if node is None:
                continue
            w.doc["nodes"][node]["translation"] = [float(x) for x in hub]
            meta = {"system": system}
            if many:
                meta["unit"] = r.get("unit") or str(idx)
                meta["spin"] = spin
            w.doc["nodes"][node]["extras"] = {"combinedassault": meta}
            spins.append({"name": name, "kind": kind, "sign": sign})
        return spins

    def gear(self, w, root):
        """Gear legs: drawn `gear_k` nodes + non-solid foot boxes under a landing_gear frame; returns {clip: channels}.
        Wing-wheel clusters on a side with a wing node => part `landing_gear_l` / `_r` (on_system wing pool, drag_k 0),
        the rest (and every drag_k / state reference) stays `landing_gear`."""
        boxes = [b for g in GEAR for b in self.model.boxes(g)]
        if not boxes:
            return None
        self.feet = []
        self.contacts = []
        self.wheel_bindings = []
        wing_wheel_ids = {id(b) for g in WING_WHEELS for b in self.model.boxes(g)}
        plan = []
        for group in clusters(boxes):
            lo, hi = extent(group)
            top = np.array([(lo[0] + hi[0]) / 2, hi[1], (lo[2] + hi[2]) / 2])
            side = "left" if top[0] > 0 else "right"
            owner = side if any(id(b) in wing_wheel_ids for b in group) and abs(top[0]) > 0.4 \
                and side in self.wing_nodes else None
            plan.append((group, lo, hi, top, owner))
        if all(o is not None for *_, o in plan):
            plan = [(g, lo, hi, top, None) for g, lo, hi, top, _ in plan]
        frames = {None: ("landing_gear", frame(w, "landing_gear", root, "part", "landing_gear", (0, 0, 0)))}
        for owner in ("left", "right"):
            if any(o == owner for *_, o in plan):
                part = "landing_gear_" + owner[0]
                frames[owner] = (part, frame(w, part, root, "part", part, (0, 0, 0)))
        channels = {name: [] for name, _ in frames.values()}
        for k, (group, lo, hi, top, owner) in enumerate(plan):
            part_name, frame_node = frames[owner]
            name = f"gear_{k}"
            parent, parent_origin = frame_node, np.zeros(3)
            if owner:
                parent, parent_origin = self.wing_nodes[owner][0], self.wing_nodes[owner][1]
            wheels, rest = self.wheel_boxes(group)
            node = (drawn(w, name, rest, parent, top) if rest else None) or w.node(name, parent=parent)
            w.doc["nodes"][node]["translation"] = [float(x) for x in top - parent_origin]
            if owner:
                # under the wing node for the clip / shed subtree, but no wing structure (feet touch the ground)
                w.doc["nodes"][node]["extras"] = {"combinedassault": {"system": "none"}}
            for j, tyre in enumerate(wheels):
                wlo, whi = extent(tyre)
                centre = (wlo + whi) / 2
                wname = f"{name}_wheel{j}"
                wn = drawn(w, wname, tyre, node, centre)
                w.doc["nodes"][wn]["translation"] = [float(x) for x in centre - top]
                radius = float(max(whi[1] - wlo[1], whi[2] - wlo[2]) / 2)
                self.wheel_bindings.append({"bones": [wname], "source": "wheel_rotation", "axis": "x",
                                            "param": round(radius, 3)})
            up = top + np.array([0, (hi[1] - lo[1]) * 0.85, 0])
            channels[part_name].append((name, "translation", [0.0, 1.0],
                                        [(top - parent_origin).tolist(), (up - parent_origin).tolist()]))
            half = np.array([max((hi[0] - lo[0]) / 2, 0.12), 0.0, max((hi[2] - lo[2]) / 2, 0.12)])
            c = (lo + hi) / 2
            pts = np.array([[c[0] + sx * half[0], y, c[2] + sz * half[2]]
                            for y in (lo[1], hi[1]) for sx in (-1, 1) for sz in (-1, 1)])
            faces = [[0, 1, 3, 2][::-1], [4, 5, 7, 6], [0, 1, 5, 4], [2, 3, 7, 6], [0, 2, 6, 4], [1, 3, 7, 5]]
            col = hidden(w, f"collider_gear_{k}", frame_node, "collider", extra={"solid": False, "name": part_name})
            self.append_mesh(w, col, pts, faces)
            self.feet.append(c)
            self.contacts.append((float(lo[1]), float(c[2])))
        for owner, (part_name, _) in frames.items():
            part = {"id": part_name, "name": "landing_gear", "type": "combinedassault:landing_gear", "is_seat": False,
                    "drag_k": 0.5 if owner is None else 0}
            if owner:
                part["on_system"] = f"{owner} wing"
            self.parts.append(part)
        return {n: c for n, c in channels.items() if c}

    def ground_pitch(self):
        """Degrees nose up at rest: pivot on the lowest gear foot toward the foot farthest from it along z; the first
        airframe point to meet the ground on that side sets the angle (a tail cone under the tail wheel wins)."""
        if self.heli or len(getattr(self, "contacts", [])) < 2:
            return 0.0
        main = min(self.contacts)
        aux = max(self.contacts, key=lambda f: abs(f[1] - main[1]))
        sign = 1.0 if aux[1] < main[1] else -1.0
        pts = flan.triangles(self.body_boxes() + [b for g in GEAR for b in self.model.boxes(g)])[0]
        dz = (main[1] - pts[:, 2]) * sign
        far = pts[dz > abs(aux[1] - main[1]) * 0.25]
        pitch = sign * float(np.degrees(np.min(np.arctan2(far[:, 1] - main[0], (main[1] - far[:, 2]) * sign))))
        return round(pitch, 2) if abs(pitch) > 0.5 else 0.0

    @staticmethod
    def wheel_boxes(group):
        """Tyres: boxes sharing a pivot x,z whose union is round in (y, z) and thin along the axle."""
        by_pivot = []
        for b in group:
            for g in by_pivot:
                if np.linalg.norm((g[0]["pivot"] - b["pivot"])[[0, 2]]) < 0.02:
                    g.append(b)
                    break
            else:
                by_pivot.append([b])
        tyres, taken = [], set()
        for g in by_pivot:
            lo, hi = flan.bounds(g)
            d = hi - lo
            big = max(d[1], d[2])
            if len(g) > 1 and big > 0.15 and abs(d[1] - d[2]) <= 0.3 * big and d[0] <= 0.9 * big:
                tyres.append(g)
                taken.update(id(b) for b in g)
        return tyres, [b for b in group if id(b) not in taken]

    def center_of_mass(self):
        lo, hi = self.lo, self.hi
        y = lo[1] + 0.45 * (hi[1] - lo[1])
        z = (lo[2] + hi[2]) / 2
        mains = [f for f in getattr(self, "feet", []) if abs(f[0]) >= 0.4]
        aux = [f for f in getattr(self, "feet", []) if abs(f[0]) < 0.4]
        if self.heli:
            hubs = [r["hub"] for r in self.moving if r["kind"] == "main" and r["hub"] is not None]
            z = float(np.mean([h[2] for h in hubs])) if len(hubs) == 1 else z
            if mains:
                z = float(np.clip(z, min(f[2] for f in mains) - 0.5, max(f[2] for f in mains) + 0.5))
        elif mains:
            z = float(np.mean([f[2] for f in mains]))
            if aux:
                z = 0.8 * z + 0.2 * float(np.mean([f[2] for f in aux]))
        return (0.0, float(y), float(z))

    def stations(self, w, root):
        """Pilot-fired weapon stations from aircraft.json `stations`; returns the pilot's weapons list.
        Wing-mounted points (Flan part leftWing/rightWing on a side with a wing node) => own `<id>_l` / `<id>_r` part with
        on_system `left wing` / `right wing`; the parts of one station share weapon entry `salvo` group = station id."""
        weapons = []
        for st in self.spec.get("stations", []):
            args = self.cfg.all(st["points"])
            args = args[slice(*st.get("slice", (0, len(args))))]
            groups = {"": [], "left wing": [], "right wing": []}
            for a in args:
                p = self.cfg_point(a)
                side = "left" if p[0] > 0 else "right"
                wing = len(a) > 3 and a[3] in ("leftWing", "rightWing") and side in self.wing_nodes
                groups[f"{side} wing" if wing else ""].append(p)
            if not args:
                groups[""] = [np.array([0.0, float(self.lo[1]), float(self.hi[2] * 0.5)])]
            total = sum(len(v) for v in groups.values())
            first = len(weapons)
            for key, pts in groups.items():
                if not pts:
                    continue
                sid = st["id"] + {"": "", "left wing": "_l", "right wing": "_r"}[key]
                unit = frame(w, sid, root, "part", sid, (0, 0, 0))
                barrel = frame(w, f"{sid}_barrel", unit, "pitch", sid, (0, 0, 0), {"system": "barrel"})
                for k, p in enumerate(pts):
                    hidden(w, f"socket_bolt_{sid}_{k}", barrel, "bolt", sid, p, {"index": k}, scale=(1, 1, 0.5))
                part = {"id": sid, "name": sid, "type": "combinedassault:weapon", "is_seat": False,
                        "crosshair_style": st.get("crosshair", "circle"), "rot_info": {"x_rot_speed": 0, "y_rot_speed": 0}}
                if key:
                    part["on_system"] = key
                if st.get("ammo"):
                    part["ammo_capacity"] = max(1, round(st["ammo"] * len(pts) / total))
                if st.get("follow"):
                    part["parent_weapon_unit_aim"] = True
                self.parts.append(part)
                entry = {"id": st["weapon"], "part_unit_id": sid}
                if st.get("secondary"):
                    entry["secondary"] = True
                weapons.append(entry)
            if len(weapons) - first > 1:
                for entry in weapons[first:]:
                    entry["salvo"] = st["id"]
        return weapons

    def seats_air(self, w, root):
        eye = np.array([0, EYE_ABOVE_SEAT, 0])
        seat_guns = {int(k): v for k, v in self.spec.get("seat_guns", {}).items()}
        pilot = None
        rest = []
        for s in flan.seats(self.cfg):
            pos = (s.pos + s.rotated_offset) * self.cfg_k
            if s.id == 0:
                pid = "pilot"
                hidden(w, f"socket_seat_{pid}", root, "seat", pid, pos + eye)
                hidden(w, f"socket_view_{pid}", root, "view", pid, pos + eye)
                part = {"id": pid, "name": "pilot", "type": "combinedassault:generic", "crew_roles": ["driver"]}
                if self.pilot_weapons:
                    part.update({"type": "combinedassault:weapon", "crew_roles": ["driver", "gunner"],
                                 "operator_on_weapon_unit": False, "crosshair_style": "cross",
                                 "rot_info": {"x_rot_speed": 0, "y_rot_speed": 0}, "weapons": self.pilot_weapons})

                pilot = part
                continue
            pid = f"seat_{s.id}"
            hidden(w, f"socket_seat_{pid}", root, "seat", pid, pos + eye)
            hidden(w, f"socket_view_{pid}", root, "view", pid, pos + eye)
            gun, yaw, pitch = s.gun, s.yaw, s.pitch
            if s.id in seat_guns:
                sg = seat_guns[s.id]
                gun, yaw, pitch = sg["weapon"], tuple(sg.get("yaw", (-110, 110))), tuple(sg.get("pitch", (-60, 20)))
                weapon_id = sg["weapon"]
            else:
                weapon_id = self.gun_weapon(gun) if gun else None
            part = {"id": pid, "name": "passenger_seat", "type": "combinedassault:generic", "protect_passenger": False}
            if gun:
                part.update(self.gun_seat(w, root, s, pid, pos, yaw, pitch, weapon_id))
            rest.append(part)
        self.parts[:0] = [pilot] if pilot else []
        self.parts += rest

    def gun_seat(self, w, root, s, pid, pos, yaw, pitch, weapon_id):
        door_gun = s.id in {int(k) for k in self.spec.get("seat_guns", {})}
        if s.gun_origin is not None:
            origin = s.gun_origin * self.cfg_k
        else:
            origin = pos + (np.array([0.4 * np.sign(pos[0]), 0.25, 0.0]) if door_gun else 0)
        rig = self.model.groups.get(f"gunModels:{s.gun_name}") if s.gun_name else None
        boxes = [[b for b in arr if not b["hidden"]] for arr in rig] if rig else []
        flat = [b for arr in boxes for b in arr]
        pivot = flat[0]["pivot"] if flat else origin
        kit = kit_for(weapon_id)
        forward = 1.0
        if flat and not kit:
            lo, hi = extent(flat)
            forward = 1.0 if hi[2] - pivot[2] >= pivot[2] - lo[2] else -1.0
        mid = 0.5 * (yaw[0] + yaw[1])
        g = frame(w, f"{pid}_gun", root, "part", pid, pivot)
        pf = frame(w, f"{pid}_gun_pitch", g, "pitch", pid, (0, 0, 0),
                   {"x_rot_min": max(pitch[0], -89.0), "x_rot_max": min(pitch[1], 89.0)})
        rot = {"x_rot_speed": 4, "y_rot_speed": 4, "y_rot_min": yaw[0], "y_rot_max": yaw[1]}
        if kit:
            rot.update({"x_rot_speed": 5, "y_rot_speed": 5, "y_rot": mid, "need_power": False})
            return {"type": "combinedassault:weapon", "crew_roles": ["gunner"], "render_bone": f"{pid}_gun",
                    "handling": "manual", "operator_on_weapon_unit": False, "rot_info": rot,
                    "optical_sight_type": "operator", "zoom_max": 2, "crosshair_style": "cross",
                    "hardpoint": {"node": f"{pid}_gun_pitch", "accepts": ["door", "pintle"], "fitted": kit}}
        reach = 0.6
        for k, arr in enumerate(boxes):
            if not arr:
                continue
            if forward < 0:
                arr = rotated(arr, pivot, 180.0, axis=1)
            drawn(w, f"{pid}_gun_{k}", arr, g if k == 0 else pf, pivot)
        if flat:
            lo, hi = extent(flat)
            reach = max(hi[2] - pivot[2], pivot[2] - lo[2], 0.3)
        hidden(w, f"socket_bolt_{pid}_0", pf, "bolt", pid, (0, 0, reach), {"index": 0}, scale=(1, 1, 0.5))
        rot["y_rot"] = mid if forward < 0 else 0.0
        return {"type": "combinedassault:weapon", "crosshair_style": "cross", "operator_on_weapon_unit": True,
                "rot_info": rot, "weapons": [weapon_id]}

    def flight(self, mass, fuel):
        """Fixed-wing attributes; pack doc 3.10 Takeoff: ground cap v^2 = (thrust-friction)/(k_min*1.5)
        vs liftoff v^2 = 9.8 m / (k(10 deg) (L/D + 0.8))."""
        speed = self.info.get("speed_kmh", 300) / 3.6
        v_top = min(max(30 + 0.25 * speed, 35.0), 85.0)
        m = mass + fuel
        k_min = m / 700.0
        ld = 4.0 if self.jet else 6.0
        v_lo2 = 9.8 * m / ((k_min + 3 * k_min * np.sin(np.radians(10))) * (ld + 0.8))
        friction = 0.5 * mass
        needed = friction + 1.5 * k_min * 2.5 * v_lo2
        thrust = max(needed, min(k_min * v_top ** 2, 0.75 * 9.8 * m))
        big = mass > 30000
        mid = mass > 10000
        a = {"thrust": round(thrust), "thrust_k": 1.0, "ceiling": 512 if self.jet else 400,
             "x_rot_input_step": 0.12 if big else 0.2, "y_rot_input_step": 0.3 if big else 0.5,
             "z_rot_input_step": 0.12 if big else 0.2,
             "air_drag_k_min": round(k_min, 2), "air_drag_k_max": round(4 * k_min, 2), "lift_to_drag_k": ld,
             "x_rot_input_drag_k": 1.0, "y_rot_input_drag_k": 0.25, "z_rot_input_drag_k": 0.125,
             "turn_rate_by_speed": 0.4,
             "x_turn_rate": 0.8 if big else 1.5 if mid else 2.0,
             "y_turn_rate": 1.2 if big else 2.2 if mid else 3.0,
             "z_turn_rate": 2.0 if big else 4.0 if mid else 7.0}
        return a, round(friction)

    def write_aircraft_files(self, out_root, model_rl):
        info, cfg = self.info, self.cfg
        mass = info.get("mass", 5000)
        fuel = round(mass * 0.2)
        d = {"type": self.kind}
        if self.heli:
            d["attributes"] = {"main_rotor_force": round(mass * self.spec.get("lift", 19.0), 2), "ceiling": 256,
                               "x_rot_speed_acceleration": 1.5, "x_rot_speed_max": 4.0,
                               "y_rot_speed_acceleration": 1.5, "y_rot_speed_max": 4.0,
                               "z_rot_speed_acceleration": 1.5, "z_rot_speed_max": 4.0,
                               "max_air_speed": round(info.get("speed_kmh", 280) / 3.6 / 20, 2)}
            friction = 2 * mass
        else:
            d["attributes"], friction = self.flight(mass, fuel)
        d["physics_info"] = {"mass": mass, "friction": friction}
        pitch = self.ground_pitch()
        if pitch:
            d["attributes"]["ground_pitch"] = pitch
        if any(p["id"] == "landing_gear" for p in self.parts):
            d["landing_gear_part_id"] = "landing_gear"
        d["max_health"] = info.get("health", 500)
        d["view_info"] = {"third_person_distance": cfg.float("CameraDistance", 12), "third_person_distance_zoomed": 4}
        d["energy_info"] = {"energy_capacity": fuel, "energy_consumption_per_tick": round(fuel / 160000, 9)}
        d["defense_stats"] = {"damage_threshold": 10, "impact_multiplier": 3}
        d["parts"] = self.parts
        d["model"] = model_rl
        d["display"] = {"icon": f"{self.ns}:textures/slot/{self.vid}.png", "tab_index": 910,
                        "controller": f"{self.ns}:{self.vid}_controller"}
        d["display"]["engine"], d["display"]["cabin_muffle"] = self.engine_audio()
        desc = cfg.str("Description")
        if desc:
            d["display"]["description"] = desc
        for k, v in self.spec.get("def", {}).items():
            d[k] = v
        path = out_root / "combinedassault" / self.ns / "vehicles" / f"{self.vid}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        self.write_lang(out_root)
        path.write_text(json.dumps(d, indent=2) + "\n")
        self.write_script(out_root)
        self.write_recipe(out_root)

    def engine_audio(self):
        """Existing events only: z10 (helicopters), piston (propellers), jet (jets); curves as the shipped defs."""
        if self.heli:
            return ({"start": {"event": "combinedassault:z10_engine_start", "range": 360},
                     "stop": {"event": "combinedassault:z10_engine_stop", "range": 360},
                     "loops": [{"sound": {"event": "combinedassault:z10_engine_run", "range": 600}, "input": "power",
                                "volume": [[0.2, 0], [0.5, 1]], "pitch": [[0.5, 0.8], [1, 1]]}]}, 0.5)
        big = self.info.get("mass", 0) > 30000
        if self.jet:
            return ({"start": {"event": "combinedassault:jet_engine_start", "range": 720 if big else 540},
                     "stop": {"event": "combinedassault:jet_engine_stop", "range": 540},
                     "loops": [{"sound": {"event": "combinedassault:jet_engine_run", "range": 1200 if big else 900},
                                "input": "throttle", "volume": [[0, 0.55], [1, 1]],
                                "pitch": [[0, 0.85], [1, 1.05], [1.5, 1.15]], "aspect": [[0, 0.55], [120, 1]],
                                "gate": [[0.2, 0], [0.5, 1]]}]}, 0.85)
        return ({"start": {"event": "combinedassault:piston_engine_start", "range": 400},
                 "stop": {"event": "combinedassault:piston_engine_stop", "range": 400},
                 "loops": [{"sound": {"event": "combinedassault:piston_engine_run", "range": 700}, "input": "throttle",
                            "volume": [[0, 0.5], [1, 1]], "pitch": [[0, 0.8], [1, 1.2]],
                            "gate": [[0.1, 0], [0.3, 1]]}]}, 0.5)

    def write_script(self, out_root):
        lines = ["function updateBones(context) {",
                 "    var step = context.getPower() / 5;",
                 "    var builder = createPoseBuilder();"]
        for i, s in enumerate(self.spinners):
            key = f"spin_{i}"
            mult = " * 5" if s["kind"] == "tail" else ""
            lines.append(f'    var {key} = (context.getFloat("{key}", 0) + step{mult}) % 360;')
            lines.append(f'    context.setFloat("{key}", {key});')
            sign = "" if s["sign"] > 0 else "-"
            rot = {"main": f"0, {sign}{key}, 0", "tail": f"{sign}{key}, 0, 0", "prop": f"0, 0, {sign}{key}"}[s["kind"]]
            lines.append(f'    builder.setRotation("{s["name"]}", {rot});')
        lines += ["    return builder;", "}"]
        base = out_root / "assets" / self.ns
        (base / "scripts").mkdir(parents=True, exist_ok=True)
        (base / "scripts" / f"{self.vid}.js").write_text("\n".join(lines) + "\n")
        (base / "animation_controllers").mkdir(parents=True, exist_ok=True)
        (base / "animation_controllers" / f"{self.vid}_controller.json").write_text(json.dumps(
            {"name": f"{self.vid}_controller", "script": f"{self.ns}:{self.vid}", "parameters": {},
             "graph": {"type": "merge", "inputs": [
                 {"type": "script", "function": "updateBones"},
                 {"type": "bone_binding", "special_bindings": self.wheel_bindings}]}}, indent=2) + "\n")

    def write_recipe(self, out_root):
        tpl = "ah64d" if self.heli else "a10"
        r = json.loads((BUILTIN / "data/combinedassault/recipe" / f"{tpl}.json").read_text())
        r["result"]["components"]["minecraft:custom_data"]["CombinedAssaultId"] = f"{self.ns}:{self.vid}"
        path = out_root / "data" / self.ns / "recipe" / f"{self.vid}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(r, indent=2) + "\n")
