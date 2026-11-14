#!/usr/bin/env python3
"""Camera sightline metric + fix ladder on a staged CA vehicle (glb + def): same rules as Java `Sightlines`
(`SightlineGameTest` = oracle). Clip states not swept here (no clip evaluation).

    python3 tools/sightline.py <pack dir> <ns:vehicle>... [--fix]

Ladder per failing camera (--fix rewrites glb + def in place):
  1. eye moved, bounded: sight / periscope along bore and up <= MOVE; seat / gunner eye up <= MOVE;
  2. occluding triangle islands drawn wholly within VIEW_HIDE of the eye split into `<node>_vh_<part>` -> `view_hide`;
  3. flag (printed, eye at the least-bad bounded move).
"""
import json
import math
import os
import sys
from dataclasses import dataclass, field

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glb  # noqa: E402
import model_icon as mi  # noqa: E402

NEAR = 0.05
REACH = 2.0
W, H = 64, 36
FOV = 70.0
CENTRE = math.sqrt(0.1)
BAND = 10.0
EYE = 1.62
YAW_STEP = 30.0
LIMIT = {"sight": 0.05, "driver": 0.05, "crew": 0.15, "passenger": 1.0}
MOVE = 0.3
MOVE_STEP = 0.05
VIEW_HIDE = 1.5
SELF = 4.0  # bore from the station's pitch frame meets its own vehicle within this => pose dropped
BUILTIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                       "src/main/resources/combinedassault_packs/combinedassault")
UNDRAWN = ("hit_", "socket_")


def rot_y(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])


def rot_x(deg):
    a = math.radians(deg)
    return np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])


@dataclass
class Node:
    name: str
    parent: int
    local: np.ndarray
    role: str = ""
    iface: str = ""
    meta: dict = field(default_factory=dict)
    drawn: bool = True
    base: bool = True  # in the vehicle glb (not grafted)
    gltf_index: int = -1


@dataclass
class Piece:
    """One primitive of one drawn node: triangles in that node's frame."""
    node: int
    tris: np.ndarray  # (n, 3, 3)
    culled: np.ndarray  # (n,) single-sided
    island: np.ndarray  # (n,) island id within the piece
    mesh: int = -1
    prim: int = -1
    order: np.ndarray = None  # piece triangle -> index triangle in the primitive
    hide: np.ndarray = None  # per triangle, ladder trial only


def resource(location, packs):
    ns, path = location.split(":", 1)
    for root in packs:
        candidate = os.path.join(root, "assets", ns, path)
        if os.path.exists(candidate):
            return candidate
    raise FileNotFoundError(location)


def def_path(location, kind, packs):
    ns, path = location.split(":", 1)
    for root in packs:
        candidate = os.path.join(root, "combinedassault", ns, kind, path + ".json")
        if os.path.exists(candidate):
            return candidate
    raise FileNotFoundError(f"{kind} {location}")


def islands(idx, count):
    """Triangle -> component id over shared vertex indices (Flan box: one per face)."""
    parent = list(range(count))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for a, b, c in idx:
        ra = find(a)
        parent[find(b)] = ra
        parent[find(c)] = ra
    return np.array([find(t[0]) for t in idx])


class Scene:
    def __init__(self, pack, vehicle):
        self.pack = pack
        self.packs = [pack, BUILTIN]
        ns, vid = vehicle.split(":")
        self.vehicle = vehicle
        self.def_file = os.path.join(pack, "combinedassault", ns, "vehicles", vid + ".json")
        self.defn = json.load(open(self.def_file))
        self.glb_file = resource(self.defn["model"], self.packs)
        self.doc, self.blob = glb.read(self.glb_file)
        self.nodes, self.pieces = [], []
        self.parts = {p["id"]: dict(p) for p in self.defn.get("parts", [])}
        mounts = {}
        for p in self.defn.get("parts", []):
            for m in p.get("mounts", []):
                mounts.setdefault(m["node"], []).append((p["id"], m["model"]))
            hp = p.get("hardpoint")
            if hp and hp.get("fitted"):
                kit = json.load(open(def_path(hp["fitted"], "hardware", self.packs)))
                self.parts[p["id"]].update(kit.get("station", {}))
                mounts.setdefault(hp["node"], []).append((p["id"], kit["model"]))
        self.load(self.doc, self.blob, -1, True, mounts)
        self.byname = {}
        for i, n in enumerate(self.nodes):
            self.byname.setdefault(n.name, i)

    def load(self, doc, blob, attach, base, mounts, part=None):
        def visit(index, parent, drawn):
            n = doc["nodes"][index]
            ex = n.get("extras") or {}
            name = n.get("name", f"node{index}")
            drawn = drawn and not name.lower().startswith(UNDRAWN) and not ex.get("gemrenderHidden")
            iface = ex.get("combinedassaultInterface", "")
            role = ex.get("combinedassaultRole", "")
            if not base and not iface and role in ("seat", "view", "sight", "sight_lens"):
                iface = part
            self.nodes.append(Node(name, parent, mi.local(n), role, iface, ex.get("combinedassault", {}), drawn,
                                   base, index if base else -1))
            me = len(self.nodes) - 1
            if drawn and "mesh" in n:
                for k, prim in enumerate(doc["meshes"][n["mesh"]]["primitives"]):
                    if prim.get("mode", 4) != 4:
                        continue
                    mat = doc["materials"][prim["material"]] if "material" in prim else {}
                    if mat.get("alphaMode") == "BLEND":
                        continue
                    pos = mi.accessor(doc, blob, prim["attributes"]["POSITION"]).astype(np.float64)
                    idx = (mi.accessor(doc, blob, prim["indices"]).astype(np.int64).reshape(-1, 3)
                           if "indices" in prim else np.arange(len(pos)).reshape(-1, 3))
                    tri = pos[idx]
                    self.pieces.append(Piece(me, tri, np.full(len(idx), not mat.get("doubleSided", False)),
                                             islands(idx, len(pos)), n["mesh"] if base else -1, k if base else -1,
                                             np.arange(len(idx))))
            for c in n.get("children", []):
                visit(c, me, drawn)
            for pid, model in mounts.get(name, []):
                mdoc, mblob = glb.read(resource(model, self.packs))
                self.load(mdoc, mblob, me, False, {}, pid)

        first = len(self.pieces)
        for root in doc["scenes"][doc.get("scene", 0)]["nodes"]:
            visit(root, attach, True)
        if attach < 0:
            rest = self.world()
            for pc in self.pieces[first:]:
                if np.linalg.det(rest[pc.node][:3, :3]) < 0:  # glTF: negative determinant => clockwise front
                    pc.tris = pc.tris[:, [0, 2, 1]]

    def world(self, extra=None):
        extra = extra or {}
        out = []
        for i, n in enumerate(self.nodes):
            m = n.local if n.parent < 0 else out[n.parent] @ n.local
            if i in extra:
                m = m.copy()
                m[:3, :3] = m[:3, :3] @ extra[i]
            out.append(m)
        return out

    def find(self, role, iface):
        for i, n in enumerate(self.nodes):
            if n.role == role and n.iface == iface:
                return i
        return -1

    def under(self, i, root):
        while i >= 0:
            if i == root:
                return True
            i = self.nodes[i].parent
        return False


@dataclass
class Station:
    part: int  # part frame node
    pitch: int  # pitch frame node or -1
    yaws: list
    pitches: list


@dataclass
class Cam:
    part: str
    kind: str
    role: str
    attach: int  # frame node the eye rides
    eye_local: np.ndarray  # in attach frame, rest
    look: int  # frame whose +Z is the view (-1: body)
    zooms: list
    station: Station = None
    socket: int = -1  # socket node the eye comes from (-1: derived)
    objective: float = 0.0


def euler_self(m):
    """CA selfRot of a frame (YXZ euler of its rest rotation): (x deg, y deg)."""
    r = m[:3, :3]
    x = math.degrees(math.asin(max(-1.0, min(1.0, -r[1, 2]))))
    y = math.degrees(math.atan2(r[0, 2], r[2, 2]))
    return x, -y


def station(scene, pid, rest):
    p = scene.parts[pid]
    part = scene.find("part", pid)
    if part < 0:
        return None
    pitch = scene.find("pitch", pid)
    info = dict(scene.nodes[part].meta)
    if pitch >= 0:
        info.update({k: v for k, v in scene.nodes[pitch].meta.items() if k.startswith("x_rot")})
    info.update(p.get("rot_info", {}))
    xs, ys = euler_self(rest[part])
    xs = 0.0  # pitch self rot rides the pitch frame; limits authored about rest
    ylo, yhi = info.get("y_rot_min", -1e30) - ys, info.get("y_rot_max", 1e30) - ys
    xlo, xhi = info.get("x_rot_min", -90) - xs, info.get("x_rot_max", 90) - xs
    if info.get("y_rot_speed", 0) <= 0:
        yaws = [0.0]
    elif not yhi - ylo < 360:
        yaws = [i * YAW_STEP - 180 + YAW_STEP for i in range(int(360 / YAW_STEP))]
    else:
        n = max(1, math.ceil((yhi - ylo) / YAW_STEP))
        yaws = [ylo + (yhi - ylo) * i / n for i in range(n + 1)]
    if info.get("x_rot_speed", 0) <= 0:
        pitches = [0.0]
    else:
        pitches = [xlo, 0.0, xhi] if xlo < 0 < xhi else [xlo, xhi]
    return Station(part, pitch, yaws, pitches)


def cameras(scene):
    rest = scene.world()
    out = []
    seats = [p for p in scene.defn.get("parts", []) if p.get("is_seat", True)]
    body = next(i for i, n in enumerate(scene.nodes) if n.role == "body")

    def local_of(frame, point):
        return (np.linalg.inv(rest[frame]) @ np.append(point, 1))[:3]

    def frame_of(i):
        while i >= 0 and scene.nodes[i].role not in ("body", "part", "pitch"):
            i = scene.nodes[i].parent
        return i

    for index, seat in enumerate(seats):
        pid = seat["id"]
        p = scene.parts[pid]
        roles = p.get("crew_roles") or (["driver"] if index == 0 else []) + (
            ["gunner"] if p.get("type") == "combinedassault:weapon" else ["commander"])
        drives = "driver" in roles
        view = scene.find("view", pid)
        sight = scene.find("sight", pid)
        if p.get("type") == "combinedassault:weapon":
            st = station(scene, pid, rest)
            part = st.part if st else body
            gun = st.pitch if st and st.pitch >= 0 else part
            kind = p.get("optical_sight_type", "none")
            traverses = st is not None and (len(st.yaws) > 1 or len(st.pitches) > 1)
            on_unit = p.get("operator_on_weapon_unit", True)
            pivot = rest[part][:3, 3]

            def view_eye(scope):
                if view < 0:
                    return part, local_of(part, pivot + [0, EYE, 0]), -1
                if not on_unit and not scope:
                    base = frame_of(scene.nodes[part].parent)
                    return base, local_of(base, rest[view][:3, 3]), view
                frame = gun if kind == "operator" else part
                return frame, local_of(frame, rest[view][:3, 3]), view

            aircraft = scene.defn.get("type", "").endswith(("fixed_wing", "rotary_wing", "helicopter", "plane"))
            gun_look = traverses and not (drives and aircraft)
            attach, eye, sock = view_eye(False)
            role = "passenger" if kind == "crt" else "driver" if drives else "crew"
            out.append(Cam(pid, "view", role, attach, eye, gun if gun_look else -1, [1.0],
                           st if traverses else None, sock))
            if kind != "none":
                optic = p.get("optic")
                lens = scene.find("sight_lens", pid) >= 0
                ads = p.get("handling") == "manual" and sight >= 0 and (kind == "operator" or optic and lens)
                if ads:
                    attach, eye, sock = gun, local_of(gun, rest[sight][:3, 3]), sight
                elif kind != "operator" and sight >= 0:
                    attach, eye, sock = part, local_of(part, rest[sight][:3, 3]), sight
                else:
                    attach, eye, sock = view_eye(True)
                if ads and optic and lens:
                    zooms = list(optic["zoom"])
                else:
                    zooms, z, top = [], max(1.0, p.get("zoom_min", 1)), p.get("zoom_max", 8)
                    while True:
                        zooms.append(z)
                        if z >= top:
                            break
                        z = min(z * 2, top)
                out.append(Cam(pid, "sight", "sight", attach, eye, gun, zooms, st if traverses else None, sock,
                               optic.get("objective", 0) if ads and optic and lens else 0))
            continue
        seat_node = scene.find("seat", pid)
        src = view if view >= 0 else seat_node
        if src < 0:
            continue
        frame = frame_of(scene.nodes[seat_node if seat_node >= 0 else src].parent)
        ride = frame if frame != body else -1
        st = None
        if ride >= 0:
            owner = frame
            while owner >= 0 and scene.nodes[owner].role != "part":
                owner = frame_of(scene.nodes[owner].parent)
            if owner >= 0 and scene.nodes[owner].iface in scene.parts:
                st = station(scene, scene.nodes[owner].iface, rest)
        role = "passenger" if sight >= 0 else "driver" if drives else "crew" if p.get("hatch") else "passenger"
        out.append(Cam(pid, "view", role, frame, local_of(frame, rest[src][:3, 3]), ride, [1.0], st, src))
        if sight >= 0:
            out.append(Cam(pid, "periscope", "driver" if drives else "sight", frame, local_of(frame, rest[sight][:3, 3]), ride, [1.0], st,
                           sight))
    return out


@dataclass
class Score:
    centre: int = 0
    frac: float = 0.0
    bad: float = -1.0
    pose: str = ""
    self_aim: int = 0
    occluders: dict = field(default_factory=dict)  # (piece, tri) -> count
    nodes: dict = field(default_factory=dict)

    def passed(self, role):
        if role == "sight":
            return self.centre == 0 and self.frac <= LIMIT[role]
        return role == "passenger" or self.frac <= LIMIT[role]


_NDC = np.stack(np.meshgrid((np.arange(W) + 0.5) / W * 2 - 1, 1 - (np.arange(H) + 0.5) / H * 2), -1).reshape(-1, 2)


def rays(fov):
    tv = math.tan(math.radians(fov / 2))
    th = tv * 16 / 9
    d = np.stack([-_NDC[:, 0] * th, _NDC[:, 1] * tv, np.ones(len(_NDC))], 1)
    return d / np.linalg.norm(d, axis=1, keepdims=True), tv, th


def look_basis(world, frame):
    r = world[frame][:3, :3] if frame >= 0 else np.eye(3)
    fwd = r @ [0, 0, 1.0]
    left = np.cross([0, 1.0, 0], fwd)
    if np.linalg.norm(left) < 1e-6:
        left = r @ [1.0, 0, 0]
    left /= np.linalg.norm(left)
    up = np.cross(fwd, left)
    return np.stack([left, up, fwd])  # rows: camera axes in model frame


def trace(scene, world, eye, basis, fov, hidden):
    """depth (P,), who piece (P,), tri (P,)."""
    d, tv, th = rays(fov)
    depth = np.full(len(d), REACH)
    who = np.full(len(d), -1)
    tri_of = np.full(len(d), -1)
    for k, pc in enumerate(scene.pieces):
        if hidden[pc.node] or not scene.nodes[pc.node].drawn:
            continue
        m = world[pc.node]
        t = pc.tris @ m[:3, :3].T + m[:3, 3] - eye
        centre = t.mean(1)
        radius = np.max(np.linalg.norm(t - centre[:, None], axis=2), axis=1)
        close = np.linalg.norm(centre, axis=1) - radius < REACH
        if pc.hide is not None:
            close &= ~pc.hide
        keep = np.nonzero(close)[0]
        if not len(keep):
            continue
        c = t[keep] @ basis.T  # camera local: x left, y up, z ahead
        z = c[:, :, 2]
        ok = z.max(1) >= NEAR
        e1, e2 = c[:, 1] - c[:, 0], c[:, 2] - c[:, 0]
        n = np.cross(e1, e2)
        ok &= ~(pc.culled[keep] & (np.einsum("ij,ij->i", n, c[:, 0]) >= 0))
        keep, c, e1, e2 = keep[ok], c[ok], e1[ok], e2[ok]
        if not len(keep):
            continue
        # screen bbox; any corner behind the near plane => whole frame
        zc = np.maximum(c[:, :, 2], NEAR)
        sx = (-c[:, :, 0] / zc / th + 1) / 2 * W
        sy = (1 - c[:, :, 1] / zc / tv) / 2 * H
        behind = c[:, :, 2].min(1) < NEAR
        i0 = np.where(behind, 0, np.clip(np.floor(sx.min(1)), 0, W - 1)).astype(int)
        i1 = np.where(behind, W - 1, np.clip(np.ceil(sx.max(1)), 0, W - 1)).astype(int)
        j0 = np.where(behind, 0, np.clip(np.floor(sy.min(1)), 0, H - 1)).astype(int)
        j1 = np.where(behind, H - 1, np.clip(np.ceil(sy.max(1)), 0, H - 1)).astype(int)
        valid = (sx.max(1) >= 0) & (sx.min(1) <= W) & (sy.max(1) >= 0) & (sy.min(1) <= H) | behind
        tis, pis = [], []
        for ti in np.nonzero(valid)[0]:
            jj, ii = np.mgrid[j0[ti]:j1[ti] + 1, i0[ti]:i1[ti] + 1]
            p = (jj * W + ii).ravel()
            tis.append(np.full(len(p), ti))
            pis.append(p)
        if not tis:
            continue
        ti, pi = np.concatenate(tis), np.concatenate(pis)
        a, ea, eb, dd = c[ti, 0], e1[ti], e2[ti], d[pi]
        h = np.cross(dd, eb)
        det = np.einsum("ij,ij->i", ea, h)
        good = np.abs(det) > 1e-12
        inv = np.where(good, 1 / np.where(good, det, 1), 0)
        s = -a
        u = np.einsum("ij,ij->i", s, h) * inv
        q = np.cross(s, ea)
        v = np.einsum("ij,ij->i", dd, q) * inv
        dist = np.einsum("ij,ij->i", eb, q) * inv
        hit = good & (u >= 0) & (u <= 1) & (v >= 0) & (u + v <= 1) & (dist > 0) & (dist * dd[:, 2] >= NEAR)
        for idx in np.nonzero(hit)[0]:
            p = pi[idx]
            if dist[idx] < depth[p]:
                depth[p] = dist[idx]
                who[p] = k
                tri_of[p] = keep[ti[idx]]
    return depth, who, tri_of


def score(cam, depth, who, tri_of, basis, fov):
    d, _, _ = rays(fov)
    blocked = depth < REACH
    if cam.role == "sight":
        mid = (np.abs(_NDC[:, 0]) <= CENTRE) & (np.abs(_NDC[:, 1]) <= CENTRE)
        centre = int((blocked & mid).sum())
        region = ~mid
        frac = float(blocked[region].mean())
        bad = 1 + centre / mid.sum() if centre else frac
        counted = np.ones(len(d), bool)
    else:
        centre = 0
        if cam.role == "driver":
            elev = np.degrees(np.arcsin(np.clip((d @ basis)[:, 1], -1, 1)))
            region = np.abs(elev) <= BAND
        else:
            region = np.ones(len(d), bool)
        frac = float(blocked[region].mean()) if region.any() else 0.0
        bad = frac
        counted = region
    s = Score(centre, frac, bad)
    sel = blocked & counted
    for p in np.nonzero(sel)[0]:
        key = (int(who[p]), int(tri_of[p]))
        s.occluders[key] = s.occluders.get(key, 0) + 1
    s.region = int(counted.sum())
    return s


def poses(cam):
    st = cam.station
    if st is None:
        return [(None, 0.0, 0.0)]
    return [(st, y, x) for y in st.yaws for x in st.pitches]


def extra_for(st, yaw, pitch):
    if st is None:
        return {}
    if st.pitch >= 0:
        return {st.part: rot_y(-yaw), st.pitch: rot_x(pitch)}
    return {st.part: rot_y(-yaw) @ rot_x(pitch)}


def evaluate(scene, cam, delta=np.zeros(3), hidden=None, only=None):
    """Worst Score over the sweep; delta = eye move, model frame at rest; only = [(yaw, pitch)] subset."""
    hidden = hidden if hidden is not None else hide_mask(scene, cam.part, set())
    worst = Score()
    worst.yaw = worst.pitch = 0.0
    rest = scene.world()
    eye_rest = (rest[cam.attach] @ np.append(cam.eye_local, 1))[:3] + delta
    eye_local = (np.linalg.inv(rest[cam.attach]) @ np.append(eye_rest, 1))[:3]
    for st, yaw, pitch in poses(cam):
        if only is not None and (yaw, pitch) not in only:
            continue
        world = scene.world(extra_for(st, yaw, pitch))
        eye = (world[cam.attach] @ np.append(eye_local, 1))[:3]
        basis = look_basis(world, cam.look)
        if st is not None and into_hull(scene, world, st, basis[2]):
            worst.self_aim += 1
            continue
        eye = eye + basis[2] * cam.objective
        for z in cam.zooms:
            fov = math.degrees(2 * math.atan(math.tan(math.radians(FOV / 2)) / z))
            depth, who, tri_of = trace(scene, world, eye, basis, fov, hidden)
            s = score(cam, depth, who, tri_of, basis, fov)
            if s.bad > worst.bad:
                s.pose = f"yaw {yaw:.0f} pitch {pitch:.0f} zoom {z:g}"
                s.yaw, s.pitch = yaw, pitch
                s.self_aim = worst.self_aim
                worst = s
    return worst


def into_hull(scene, world, st, fwd):
    """Bore segment (pitch frame origin + SELF * fwd) meets drawn geometry outside the station's part frame."""
    frame = st.pitch if st.pitch >= 0 else st.part
    o = world[frame][:3, 3]
    d = fwd * SELF
    for pc in scene.pieces:
        if scene.under(pc.node, st.part):
            continue
        m = world[pc.node]
        t = pc.tris @ m[:3, :3].T + m[:3, 3] - o
        e1, e2 = t[:, 1] - t[:, 0], t[:, 2] - t[:, 0]
        h = np.cross(d, e2)
        det = np.einsum("ij,ij->i", e1, h)
        ok = np.abs(det) > 1e-12
        inv = np.where(ok, 1 / np.where(ok, det, 1), 0)
        sv = -t[:, 0]
        u = np.einsum("ij,ij->i", sv, h) * inv
        q = np.cross(sv, e1)
        v = (q @ d) * inv
        k = np.einsum("ij,ij->i", e2, q) * inv
        if np.any(ok & (u >= 0) & (u <= 1) & (v >= 0) & (u + v <= 1) & (k > 0) & (k < 1)):
            return True
    return False


def hide_mask(scene, pid, extra):
    names = set(scene.parts[pid].get("view_hide", [])) | extra
    roots = [i for i, n in enumerate(scene.nodes) if n.name in names and n.base]
    return np.array([any(scene.under(i, r) for r in roots) for i in range(len(scene.nodes))])


def moves(cam, scene):
    rest = scene.world()
    if cam.kind == "view":
        return [np.array([0, s, 0.0]) for s in np.arange(MOVE_STEP, MOVE + 1e-9, MOVE_STEP)]
    fwd = look_basis(rest, cam.look)[2]
    grid = [(f, u) for f in np.arange(0, MOVE + 1e-9, MOVE_STEP) for u in np.arange(0, MOVE + 1e-9, MOVE_STEP)
            if f or u]
    grid.sort(key=lambda fu: math.hypot(*fu))
    return [f * fwd + np.array([0, u, 0.0]) for f, u in grid]


def island_vertices(scene, piece, island, world):
    pc = scene.pieces[piece]
    t = pc.tris[pc.island == island]
    m = world[pc.node]
    return (t.reshape(-1, 3) @ m[:3, :3].T + m[:3, 3])


def ladder(scene, cam):
    """-> (status, delta, islands {(piece, island)}, before Score, after Score)."""
    before = evaluate(scene, cam)
    if before.passed(cam.role):
        return "ok", np.zeros(3), set(), before, before
    best, best_delta = before, np.zeros(3)
    if cam.socket >= 0:
        for delta in moves(cam, scene):
            quick = evaluate(scene, cam, delta, only=[(before.yaw, before.pitch)])
            if not quick.passed(cam.role):
                if quick.bad < best.bad:
                    best, best_delta = quick, delta
                continue
            full = evaluate(scene, cam, delta)
            if full.passed(cam.role):
                return "moved", delta, set(), before, full
            if full.bad < best.bad:
                best, best_delta = full, delta
    if cam.socket < 0:
        return "flag", best_delta, set(), before, best
    rest = scene.world()
    eye = rest[cam.socket][:3, 3] + best_delta
    chosen = set()
    current = best if np.linalg.norm(best_delta) > 0 else before
    for _ in range(8):
        found = set()
        for (piece, tri) in current.occluders:
            pc = scene.pieces[piece]
            if not scene.nodes[pc.node].base or pc.mesh < 0:
                continue
            key = (piece, int(pc.island[tri]))
            if key in chosen or key in found:
                continue
            if np.linalg.norm(island_vertices(scene, piece, key[1], rest) - eye, axis=1).max() <= VIEW_HIDE:
                found.add(key)
        if not found:
            break
        chosen |= found
        current = evaluate(scene, cam, best_delta, hidden=hide_mask(scene, cam.part, set()) | island_mask(scene, chosen))
        if current.passed(cam.role):
            island_mask(scene, set())
            return "view_hide", best_delta, chosen, before, current
    island_mask(scene, set())
    return "flag", best_delta, set(), before, best


def island_mask(scene, chosen):
    """Islands are hidden by splitting; for evaluation, a per-triangle mask on the pieces."""
    for pc in scene.pieces:
        pc.hide = np.zeros(len(pc.tris), bool) if chosen else None
    for piece, island in chosen:
        pc = scene.pieces[piece]
        pc.hide |= pc.island == island
    return np.zeros(len(scene.nodes), bool)


def apply(scene, cam, delta, chosen):
    """Writes the eye move into the socket node / the island split + view_hide into glb and def."""
    doc = scene.doc
    if np.linalg.norm(delta) > 0:
        node = scene.nodes[cam.socket]
        rest = scene.world()
        parent_rot = rest[node.parent][:3, :3] if node.parent >= 0 else np.eye(3)
        local = np.linalg.inv(parent_rot) @ delta
        g = doc["nodes"][node.gltf_index]
        g["translation"] = [round(float(a + b), 5) for a, b in zip(g.get("translation", [0, 0, 0]), local)]
    if chosen:
        names = split(scene, chosen, cam.part)
        part = next(p for p in scene.defn["parts"] if p["id"] == cam.part)
        part["view_hide"] = sorted(set(part.get("view_hide", [])) | names)


def split(scene, chosen, pid):
    """Islands -> child node `<node>_vh_<pid>` (identity) sharing the vertex accessors; parent keeps the rest."""
    doc = scene.doc
    blob = bytearray(scene.blob)
    by_prim = {}
    for piece, island in chosen:
        by_prim.setdefault(piece, set()).add(island)
    by_node = {}
    for piece, isl in by_prim.items():
        pc = scene.pieces[piece]
        by_node.setdefault(pc.node, []).append((pc, isl))
    names = set()
    for node, items in by_node.items():
        g = doc["nodes"][scene.nodes[node].gltf_index]
        users = sum(1 for n in doc["nodes"] if n.get("mesh") == g["mesh"])
        assert users == 1, f"{scene.vehicle}: mesh of {g.get('name')} shared; island split would hit every user"
        prims = []
        for pc, isl in items:
            prim = doc["meshes"][pc.mesh]["primitives"][pc.prim]
            idx = (mi.accessor(doc, bytes(blob), prim["indices"]).astype(np.uint32).reshape(-1, 3)
                   if "indices" in prim else np.arange(doc["accessors"][prim["attributes"]["POSITION"]]["count"],
                                                       dtype=np.uint32).reshape(-1, 3))
            take = np.isin(pc.island, list(isl))
            keep_idx, take_idx = idx[~take], idx[take]
            prim["indices"] = index_accessor(doc, blob, keep_idx)
            moved = {k: v for k, v in prim.items() if k != "indices"}
            moved["indices"] = index_accessor(doc, blob, take_idx)
            prims.append(moved)
        name = f"{g.get('name', 'node')}_vh_{pid}"
        doc["meshes"].append({"name": name, "primitives": prims})
        doc["nodes"].append({"name": name, "mesh": len(doc["meshes"]) - 1})
        g.setdefault("children", []).append(len(doc["nodes"]) - 1)
        names.add(name)
    scene.blob = bytes(blob)
    return names


def index_accessor(doc, blob, idx):
    data = idx.astype(np.uint32).ravel().tobytes()
    while len(blob) % 4:
        blob.append(0)
    offset = len(blob)
    blob.extend(data)
    doc["bufferViews"].append({"buffer": 0, "byteOffset": offset, "byteLength": len(data), "target": 34963})
    doc["accessors"].append({"bufferView": len(doc["bufferViews"]) - 1, "componentType": 5125,
                             "count": int(idx.size), "type": "SCALAR"})
    return len(doc["accessors"]) - 1


def occluder_names(scene, s, top=3):
    count = {}
    for (piece, _), c in s.occluders.items():
        name = scene.nodes[scene.pieces[piece].node].name
        count[name] = count.get(name, 0) + c
    region = max(getattr(s, "region", 1), 1)
    return ", ".join(f"{n} {c / region:.3f}" for n, c in sorted(count.items(), key=lambda kv: -kv[1])[:top])


def run(pack, vehicle, fix):
    scene = Scene(pack, vehicle)
    rows = []
    changed = False
    i = 0
    while i < len(cams := cameras(scene)):
        cam = cams[i]
        i += 1
        if fix:
            status, delta, chosen, before, after = ladder(scene, cam)
            if np.linalg.norm(delta) > 0 or chosen:
                apply(scene, cam, delta, chosen)
                changed = True
        else:
            before = after = evaluate(scene, cam)
            status, delta, chosen = ("ok" if before.passed(cam.role) else "FAIL"), np.zeros(3), set()
        rows.append((vehicle, cam.part, cam.kind, cam.role, status, before, after, delta, chosen,
                     occluder_names(scene, before)))
        if fix and (np.linalg.norm(delta) > 0 or chosen):
            scene = reload(scene)
    if changed:
        glb.write(scene.glb_file, scene.doc, scene.blob)
        with open(scene.def_file, "w") as f:
            f.write(json.dumps(scene.defn, indent=2) + "\n")
    return rows


def reload(scene):
    """Same staged files, edits so far kept in memory: re-derive pieces / nodes from the edited doc."""
    fresh = Scene.__new__(Scene)
    fresh.pack, fresh.packs, fresh.vehicle = scene.pack, scene.packs, scene.vehicle
    fresh.def_file, fresh.defn, fresh.glb_file = scene.def_file, scene.defn, scene.glb_file
    fresh.doc, fresh.blob = scene.doc, scene.blob
    fresh.nodes, fresh.pieces = [], []
    fresh.parts = {p["id"]: dict(p) for p in fresh.defn.get("parts", [])}
    mounts = {}
    for p in fresh.defn.get("parts", []):
        for m in p.get("mounts", []):
            mounts.setdefault(m["node"], []).append((p["id"], m["model"]))
        hp = p.get("hardpoint")
        if hp and hp.get("fitted"):
            kit = json.load(open(def_path(hp["fitted"], "hardware", fresh.packs)))
            fresh.parts[p["id"]].update(kit.get("station", {}))
            mounts.setdefault(hp["node"], []).append((p["id"], kit["model"]))
    fresh.load(fresh.doc, fresh.blob, -1, True, mounts)
    fresh.byname = {}
    for i, n in enumerate(fresh.nodes):
        fresh.byname.setdefault(n.name, i)
    return fresh


def fmt(s, role):
    return f"centre {s.centre} frac {s.frac:.3f}" if role == "sight" else f"frac {s.frac:.3f}"


def lines(rows):
    for (vid, part, kind, role, status, before, after, delta, chosen, occ) in rows:
        move = f" move {np.round(delta, 2).tolist()}" if np.linalg.norm(delta) > 0 else ""
        hide = f" hide {len(chosen)}" if chosen else ""
        yield (f"{vid} {part} {kind} {role} {status} {fmt(before, role)} -> {fmt(after, role)} @ {after.pose}"
               f"{move}{hide} | {occ}")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    fix = "--fix" in sys.argv
    pack, vehicles = args[0], args[1:]
    for v in vehicles:
        for line in lines(run(pack, v, fix)):
            print(line, flush=True)


if __name__ == "__main__":
    main()
