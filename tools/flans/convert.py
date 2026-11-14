#!/usr/bin/env python3
"""Flan's Mod 1.12.2 vehicle -> CA optional pack entry (glb + def + lang + recipe + icon).

    python3 tools/flans/convert.py <FlansMod checkout> <dump dir> <packs root> <Flan pack dir name> <ShortName ...|all>

Per-vehicle real-world data + overrides: tools/flans/vehicles.json, keyed by Flan ShortName.
Then colliders (tools/gen_hulls.py, CoACD venv; vehicles.json `hulls` = extra args), the slot icon (tools/model_icon.py),
seat fit (seatfit.py) and the camera fix ladder (tools/sightline.py --fix).
"""
import fcntl
import json
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import flan  # noqa: E402
import seatfit  # noqa: E402
import sightline  # noqa: E402
from mqo import GltfWriter  # noqa: E402

EYE_ABOVE_SEAT = 0.75
HEAD_OUT = 0.35
COM_BELOW_HUB = 0.15
STATIC_SAG = 0.10
ZETA = 0.3
# TODO: per-vehicle main guns land in guns.json `main:<shortname>`; until then barrel => this.
PLACEHOLDER_GUN = "combinedassault:cannon_105mm"
TEMPLATES = {"tracked_vehicle": "t90", "wheeled_vehicle": "humvee", "generic": "flak36"}
BUILTIN = Path(__file__).resolve().parents[2] / "src/main/resources/combinedassault_packs/combinedassault"
OPTIONAL = Path(__file__).resolve().parents[2] / "optional_packs"
WHEEL_GROUPS = {
    "wheel_fl": "leftFrontWheelModel", "wheel_fr": "rightFrontWheelModel",
    "wheel_rl": "leftBackWheelModel", "wheel_rr": "rightBackWheelModel",
    "wheel_f": "frontWheelModel", "wheel_r": "backWheelModel",
}
TRACK_MODULE = 0.15
MGS = {"combinedassault:gun_7.62mm", "combinedassault:gun_12.7mm", "combinedassault:ma_pkm", "combinedassault:kord",
       "flans_ww2:mg34", "flans_ww2:m1919a4", "flans_ww2:browning_303", "flans_ww2:bren", "flans_ww2:dp28",
       "flans_ww2:vickers_303", "flans_ww2:vickers_k", "flans_ww2:type97_mg", "flans_ww2:mg17", "flans_ww2:lmg08_15"}
KIT_MODEL = {"light": "combinedassault:models/weapon/pkm/pkm.glb", "heavy": "combinedassault:models/weapon/m2/m2.glb"}


def _loop(idle, run=None, track=False, rng=96, run_rng=160, idle_vol=((0.05, 0), (0.3, 1), (0.6, 1), (0.75, 0)),
          idle_pitch=((0.3, 0.85), (0.6, 1))):
    loops = [{"sound": {"event": idle, "range": rng}, "input": "rpm", "volume": [list(v) for v in idle_vol],
              "pitch": [list(v) for v in idle_pitch]}]
    if run:
        loops.append({"sound": {"event": run, "range": run_rng}, "input": "rpm", "volume": [[0.6, 0], [0.7, 1]],
                      "pitch": [[0.6, 0.8], [1, 1.1]]})
    if track:
        loops.append({"sound": {"event": "combinedassault:track_run", "range": rng}, "input": "speed",
                      "volume": [[0.01, 0], [0.1, 0.6], [0.6, 1]], "pitch": [[0, 0.8], [1, 1.1]]})
    return loops


CA = "combinedassault:"
# built-in blocks: t90 / m1a2 (tanks), m113 (light tracked), humvee (car), m983 (truck); motorcycle events for bikes
ENGINES = {
    "tank": ({"start": {"event": CA + "ztz99a_engine_start", "range": 144}, "loops": [
        {"sound": {"event": CA + "ztz99a_engine_idle", "range": 144}, "input": "rpm",
         "volume": [[0.05, 0], [0.3, 1], [0.6, 1], [0.75, 0]], "pitch": [[0.3, 0.85], [0.6, 1]]},
        {"sound": {"event": CA + "ztz99a_engine_run", "range": 240}, "input": "rpm", "volume": [[0.6, 0], [0.7, 1]],
         "pitch": [[0.6, 0.8], [1, 1.1]]}, *_loop("x", track=True, rng=144)[1:]]}, 0.85),
    "tank_turbine": ({"start": {"event": CA + "m1a2_engine_start", "range": 144}, "loops": [
        {"sound": {"event": CA + "m1a2_turbine_idle", "range": 144}, "input": "rpm",
         "volume": [[0.05, 0], [0.6, 0.7], [1, 0.8]], "pitch": [[0.3, 0.5], [1.2, 2]]},
        {"sound": {"event": CA + "m1a2_turbine_run", "range": 240}, "input": "rpm",
         "volume": [[0.3, 0], [0.6, 0.35], [1, 1]], "pitch": [[0.4, 0.5], [1.6, 2]]},
        *_loop("x", track=True, rng=144)[1:]]}, 0.85),
    "light_tracked": ({"start": {"event": CA + "lav150_engine_start", "range": 96},
                       "loops": _loop(CA + "truck_engine_idle", track=True)}, 0.8),
    "truck": ({"start": {"event": CA + "lav150_engine_start", "range": 96},
               "loops": _loop(CA + "truck_engine_idle", CA + "truck_engine_run")}, 0.6),
    "car": ({"start": {"event": CA + "lav150_engine_start", "range": 96}, "loops": [
        {"sound": {"event": CA + "humvee_engine_idle", "range": 96}, "input": "rpm",
         "volume": [[0.05, 0.0], [0.1, 0.318], [0.15, 0.45], [0.25, 0.45], [0.3, 0.318], [0.35, 0.0]],
         "pitch": [[0.103, 0.5], [0.412, 2.0]]},
        {"sound": {"event": CA + "humvee_engine_mid", "range": 128}, "input": "rpm",
         "volume": [[0.25, 0.0], [0.3, 0.53], [0.35, 0.75], [0.55, 0.75], [0.615, 0.53], [0.68, 0.0]],
         "pitch": [[0.206, 0.5], [0.824, 2.0]]},
        {"sound": {"event": CA + "humvee_engine_high", "range": 160}, "input": "rpm",
         "volume": [[0.55, 0.0], [0.615, 0.707], [0.68, 1.0]], "pitch": [[0.382, 0.5], [1.529, 2.0]]}]}, 0.4),
    "bike": ({"start": {"event": CA + "motorcycle_engine_start", "range": 96},
              "loops": _loop(CA + "motorcycle_engine_idle", CA + "motorcycle_engine_run")}, 0.0),
}
BODY_GROUPS = ("bodyModel", "bodyDoorCloseModel", "trailerModel", "steeringWheelModel", "drillHeadModel")


def quat_y(deg):
    h = np.radians(deg) / 2
    return [0.0, float(np.sin(h)), 0.0, float(np.cos(h))]


def hidden(w, name, parent, role, iface=None, translation=(0, 0, 0), extra=None, scale=None):
    i = w.node(name, translation, parent=parent)
    n = w.doc["nodes"][i]
    n["extras"] = {"combinedassaultRole": role, "gemrenderHidden": True}
    if iface:
        n["extras"]["combinedassaultInterface"] = iface
    if extra:
        n["extras"]["combinedassault"] = extra
    if scale is not None:
        n["scale"] = [float(s) for s in scale]
    return i


def frame(w, name, parent, role, iface, translation, extra=None):
    i = w.node(name, translation, parent=parent)
    n = w.doc["nodes"][i]
    n["extras"] = {"combinedassaultRole": role}
    if iface:
        n["extras"]["combinedassaultInterface"] = iface
    if extra:
        n["extras"]["combinedassault"] = extra
    return i


def drawn(w, name, boxes, parent, origin, material="paint", at=(0, 0, 0)):
    pos, nrm, uv = flan.triangles(boxes, origin)
    if not len(pos):
        return None
    return w.node(name, [float(x) for x in at], mesh=w.mesh(name, pos, nrm, uv, material), parent=parent)


def prism(center_local, radius, half_width, sides=12):
    """Wheel probe prism, axis X, in frame-local coordinates."""
    pts = []
    for sx in (-half_width, half_width):
        for k in range(sides):
            a = 2 * np.pi * k / sides
            pts.append([center_local[0] + sx, center_local[1] + radius * np.cos(a), center_local[2] + radius * np.sin(a)])
    faces = [list(range(sides))[::-1], list(range(sides, 2 * sides))]
    for k in range(sides):
        k2 = (k + 1) % sides
        faces.append([k, k2, sides + k2, sides + k])
    return np.array(pts), faces


def cluster_by_pivot(boxes, tol=0.02):
    """Wheel sub-meshes: boxes sharing a rotation point (hub) within tol blocks."""
    groups = []
    for b in boxes:
        for g in groups:
            if np.linalg.norm(g[0]["pivot"] - b["pivot"]) < tol:
                g.append(b)
                break
        else:
            groups.append([b])
    return groups


def box_center(b):
    lo, hi = flan.bounds([b])
    return (lo + hi) / 2, hi - lo


def mirror_box(b):
    m = np.array([-1.0, 1.0, 1.0])
    return {"pivot": b["pivot"] * m, "hidden": b["hidden"], "spin": b.get("spin", False),
            "polys": [((p * m)[::-1], t[::-1]) for p, t in b["polys"]]}


OUTBOARD = True


def outboard(boxes):
    """Flan wheel shapes are often extruded inboard of their rotation point (jeep front, kubel all): per wheel (shared
    pivot), reflect all its boxes about the pivot when the wheel sits entirely on the centreline side of it (pivot at the outer edge)."""
    out = []
    for cluster in cluster_by_pivot(boxes):
        lo, hi = flan.bounds(cluster)
        c = (lo[0] + hi[0]) / 2
        px = float(np.median([b["pivot"][0] for b in cluster]))
        outer = max(abs(lo[0]), abs(hi[0]))
        if not OUTBOARD or abs(px) < 0.05 or c * px <= 0 or abs(c) >= abs(px) - 0.04 or abs(px) < outer - 0.03:
            out += cluster
            continue
        m = np.array([-1.0, 1.0, 1.0])
        o = np.array([2 * px, 0.0, 0.0])
        out += [{"pivot": b["pivot"], "hidden": b["hidden"], "spin": b.get("spin", False),
                 "polys": [((p * m + o)[::-1], t[::-1]) for p, t in b["polys"]]} for b in cluster]
    return out


def belt_outline(clusters, floor=None, gap=0.04):
    """Belt loop (z, y), CCW: convex hull of the wheel circles grown by gap; floor => bottom run lowered to it."""
    pts = []
    for hub, _ in clusters:
        lo, hi = flan.bounds(hub)
        cz, cy = (lo[2] + hi[2]) / 2, (lo[1] + hi[1]) / 2
        r = max(hi[1] - lo[1], hi[2] - lo[2]) / 2 + gap
        pts += [(cz + r * np.cos(a), cy + r * np.sin(a)) for a in np.linspace(0, 2 * np.pi, 24, endpoint=False)]
    if floor is not None:
        zs = [p[0] for p in pts]
        pts += [(min(zs), floor), (max(zs), floor)]
    return hull2d(pts)


BELT_RES = 0.01


def belt_trace(boxes, floor=None):
    """Belt loop (z, y), CCW, along the middle of the Flan belt mesh's side silhouette: hole filled, eroded by half the
    median segment thickness. None: silhouette encloses no hole (open belt mesh)."""
    pos, _, _ = flan.triangles(boxes)
    if not len(pos):
        return None
    tri = pos.reshape(-1, 3, 3)[:, :, [2, 1]]
    lo = tri.reshape(-1, 2).min(0) - 0.1
    hi = tri.reshape(-1, 2).max(0) + 0.1
    n = np.ceil((hi - lo) / BELT_RES).astype(int) + 1
    grid = np.zeros((n[1], n[0]), bool)
    for t in (tri - lo) / BELT_RES:
        a, b, c = t
        area = (b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1])
        x0, y0 = np.floor(t.min(0)).astype(int)
        x1, y1 = np.ceil(t.max(0)).astype(int)
        ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1]
        if abs(area) < 1e-9:
            grid[ys.ravel()[:1], xs.ravel()[:1]] = True
            continue
        px, py = xs + 0.5, ys + 0.5
        w0 = ((b[0] - px) * (c[1] - py) - (c[0] - px) * (b[1] - py)) / area
        w1 = ((c[0] - px) * (a[1] - py) - (a[0] - px) * (c[1] - py)) / area
        inside = (w0 >= -0.05) & (w1 >= -0.05) & (1 - w0 - w1 >= -0.05)
        grid[ys[inside], xs[inside]] = True
    grid = _erode(_dilate(grid, 2), 2)
    filled = ~_flood(~grid)
    hole = filled & ~grid
    if hole.sum() < 0.5 * grid.sum():
        return None
    thick = float(np.median([belt_thickness(b) for b in boxes])) / BELT_RES
    mid = _erode(filled, max(1, int(round(thick / 2))))
    pts = _contour(mid) * BELT_RES + lo
    pts = _simplify(pts, 0.01)
    if floor is not None and pts[:, 1].min() > floor:
        low = pts[:, 1] < pts[:, 1].min() + 0.03
        pts[low, 1] = floor
    if _area(pts) < 0:
        pts = pts[::-1]
    return [tuple(p) for p in pts]


def belt_thickness(box):
    """Side-view thickness of one belt segment: silhouette area / its longest span."""
    pts = flan.triangles([box])[0][:, [2, 1]]
    if len(pts) < 3:
        return 0.0
    hull = np.array(hull2d(pts))
    span = max(np.linalg.norm(a - b) for a in hull for b in hull)
    return abs(_area(hull)) / span if span > 1e-6 else 0.0


def _morph(g, k, op, edge):
    out = g.copy()
    for _ in range(k):
        p = np.pad(out, 1, constant_values=edge)
        for m in (p[1:-1, :-2], p[1:-1, 2:], p[:-2, 1:-1], p[2:, 1:-1]):
            out = op(out, m)
    return out


def _dilate(g, k):
    return _morph(g, k, np.logical_or, False)


def _erode(g, k):
    return _morph(g, k, np.logical_and, False)


def _flood(free):
    """Free cells reachable from the border."""
    seen = np.zeros_like(free)
    h, w = free.shape
    stack = [(y, x) for y in range(h) for x in (0, w - 1) if free[y, x]] + \
            [(y, x) for x in range(w) for y in (0, h - 1) if free[y, x]]
    for y, x in stack:
        seen[y, x] = True
    while stack:
        y, x = stack.pop()
        for yy, xx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
            if 0 <= yy < h and 0 <= xx < w and free[yy, xx] and not seen[yy, xx]:
                seen[yy, xx] = True
                stack.append((yy, xx))
    return seen


def _contour(g):
    """Outer boundary of the largest blob, Moore tracing; (x, y) cell centres."""
    ys, xs = np.nonzero(g)
    start = min(zip(ys, xs), key=lambda p: (p[0], p[1]))
    dirs = [(-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1)]
    h, w = g.shape
    path, cur, back = [start], start, 6
    for _ in range(4 * g.size):
        for i in range(8):
            d = (back + 1 + i) % 8
            y, x = cur[0] + dirs[d][0], cur[1] + dirs[d][1]
            if 0 <= y < h and 0 <= x < w and g[y, x]:
                back = (d + 4) % 8
                cur = (y, x)
                break
        if cur == start:
            break
        path.append(cur)
    return np.array([(x + 0.5, y + 0.5) for y, x in path], float)


def _simplify(pts, tol):
    """Douglas-Peucker on a closed loop split at its far point."""
    far = int(np.argmax(np.linalg.norm(pts - pts[0], axis=1)))

    def dp(seg):
        if len(seg) < 3:
            return seg
        a, b = seg[0], seg[-1]
        ab = b - a
        n = np.linalg.norm(ab)
        r = seg - a
        d = np.abs(ab[0] * r[:, 1] - ab[1] * r[:, 0]) / n if n > 1e-9 else np.linalg.norm(r, axis=1)
        i = int(np.argmax(d))
        if d[i] <= tol:
            return np.array([a, b])
        return np.vstack([dp(seg[:i + 1])[:-1], dp(seg[i:])])
    one = dp(np.vstack([pts[:far + 1]]))
    two = dp(np.vstack([pts[far:], pts[:1]]))
    return np.vstack([one[:-1], two[:-1]])


def _area(pts):
    x, y = pts[:, 0], pts[:, 1]
    return 0.5 * float(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1)))


def hull2d(pts):
    """Andrew monotone chain, CCW."""
    pts = sorted(set((round(float(a), 5), round(float(b), 5)) for a, b in pts))

    def half(seq):
        out = []
        for p in seq:
            while len(out) >= 2 and (out[-1][0] - out[-2][0]) * (p[1] - out[-2][1]) - (out[-1][1] - out[-2][1]) * (p[0] - out[-2][0]) <= 0:
                out.pop()
            out.append(p)
        return out[:-1]
    return half(pts) + half(pts[::-1])


def split_sides(boxes, limit):
    """Geometric sides (CA +X = left): Flan left/right group names are not reliable. Boxes beyond limit dropped."""
    left, right, mid = [], [], []
    for b in boxes:
        c = box_center(b)[0][0]
        if abs(c) > limit:
            continue
        (mid if abs(c) < 0.05 else left if c > 0 else right).append(b)
    return left, right, mid


def complete_sides(left, right):
    """Mirror boxes whose counterpart (same y/z) is missing on the other side."""
    cl = [box_center(b)[0] for b in left]
    cr = [box_center(b)[0] for b in right]

    def missing(c, others):
        return not any(abs(c[1] - o[1]) < 0.08 and abs(c[2] - o[2]) < 0.08 for o in others)
    add_r = [mirror_box(b) for b, c in zip(left, cl) if missing(c, cr)]
    add_l = [mirror_box(b) for b, c in zip(right, cr) if missing(c, cl)]
    left += add_l
    right += add_r


def cluster_z(boxes, gap=0.03):
    """Wheels of one group side by side along Z (motorcycle: both wheels in one group)."""
    spans = sorted(((flan.bounds([b])[0][2], flan.bounds([b])[1][2], b) for b in boxes), key=lambda t: t[0])
    groups, end = [], None
    for lo, hi, b in spans:
        if groups and lo <= end + gap:
            groups[-1].append(b)
            end = max(end, hi)
        else:
            groups.append([b])
            end = hi
    return groups


def baked_wheels(model, method):
    """Road wheels baked into bodyModel (Flan drew them as stacked slabs): list of box lists, one per wheel.
    keys: slices sharing (x, y, height, width) and contiguous in Z, ~square (Sherman / Fury).
    components: x-banded overlap components, 0.7..2.4 aspect (boxy wheels: Cromwell, Churchill, Crusader, ChiNu, Tiger rows)."""
    info = [(b, *flan.bounds([b])) for b in model.boxes("bodyModel")]
    hw = max(max(abs(i[1][0]), abs(i[2][0])) for i in info)
    floor = min(i[1][1] for i in info)
    cand = []
    for b, lo, hi in info:
        c, d = (lo + hi) / 2, hi - lo
        if abs(c[0]) > 0.5 * hw and c[1] < floor + 0.9 and d[1] < 1.6 and d[2] < 1.6 and d[0] < (0.8 if method == "keys" else 1.0):
            cand.append((b, lo, hi))
    out = []
    if method == "keys":
        groups = {}
        for b, lo, hi in cand:
            c, d = (lo + hi) / 2, hi - lo
            groups.setdefault((round(c[0] / 0.03), round(c[1] / 0.03), round(d[1] / 0.03), round(d[0] / 0.03)), []).append((b, lo, hi))
        for g in groups.values():
            g.sort(key=lambda t: t[1][2] + t[2][2])
            runs, end = [], None
            for t in g:
                if runs and t[1][2] <= end + 0.04:
                    runs[-1].append(t)
                    end = max(end, t[2][2])
                else:
                    runs.append([t])
                    end = t[2][2]
            for run in runs:
                lo = np.min([t[1] for t in run], axis=0)
                hi = np.max([t[2] for t in run], axis=0)
                d = hi - lo
                if len(run) >= 2 and 0.75 <= d[2] / d[1] <= 1.35 and 0.25 <= d[1] <= 1.4:
                    out.append([t[0] for t in run])
        return out
    parent = list(range(len(cand)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    for i in range(len(cand)):
        for j in range(i + 1, len(cand)):
            a, b = cand[i], cand[j]
            ax, bx = (a[1][0] + a[2][0]) / 2, (b[1][0] + b[2][0]) / 2
            if ax * bx < 0 or abs(ax - bx) > 0.04:
                continue
            if a[1][1] <= b[2][1] + 0.02 and b[1][1] <= a[2][1] + 0.02 and a[1][2] <= b[2][2] + 0.02 and b[1][2] <= a[2][2] + 0.02:
                parent[find(i)] = find(j)
    comps = {}
    for i in range(len(cand)):
        comps.setdefault(find(i), []).append(cand[i])
    for comp in comps.values():
        lo = np.min([t[1] for t in comp], axis=0)
        hi = np.max([t[2] for t in comp], axis=0)
        d = hi - lo
        if len(comp) >= 2 and 0.25 <= d[1] <= 1.4 and 0.25 <= d[2] <= 2.2 and 0.7 <= d[2] / d[1] <= 2.4:
            out.append([t[0] for t in comp])
    return out


SIDES = {"l": "left", "r": "right", "m": "middle"}


def weapon_name(weapon_id):
    """Pack weapons are authored in optional_packs, not converter output."""
    ns, wid = weapon_id.split(":")
    root = BUILTIN if ns == "combinedassault" else OPTIONAL / ns
    return json.loads((root / "combinedassault" / ns / "weapons" / f"{wid}.json").read_text())["name"]


def part_label(pid, weapon):
    """Display name for a converter part id (wheel_rl_1, suspension_left, landing_gear_r, rocket_l, pilot ...)."""
    words = pid.split("_")
    index = f" {int(words.pop()) + 1}" if words[-1].isdigit() else ""
    side = SIDES.get(words[-1]) if len(words) > 1 and words[0] != "wheel" else None
    if side:
        words.pop()
    if weapon:
        base = weapon_name(weapon)
    elif words[0] == "wheel":
        pos = words[1]
        base = " ".join(filter(None, [{"f": "front", "r": "rear"}[pos[0]], SIDES.get(pos[1:]), "wheel"]))
    elif words[0] == "suspension":
        base = f"{words[1]} track"
    else:
        base = " ".join(words)
    label = (f"{side} " if side else "") + base + index if not weapon else base + (f" ({side})" if side else "") + index
    return label[0].upper() + label[1:]


class Vehicle:
    def __init__(self, flans, dump, pack_dir, cfg_path, info):
        self.cfg = flan.Config(cfg_path)
        self.info = info
        self.pack_dir = pack_dir
        self.flans = flans
        self.short = self.cfg.str("ShortName")
        self.vid = info.get("id", flan.slug(self.short))
        self.ns = flan.FLAN_PACKS[pack_dir.name]
        model_scale = self.cfg.float("ModelScale", 1.0)
        cls, _ = flan.model_class(self.cfg.str("Model"))
        raw = flan.Model(dump / f"{cls}.json", model_scale)
        lo, hi = flan.bounds(raw.boxes(*BODY_GROUPS, "leftTrackModel", "rightTrackModel",
                                       *WHEEL_GROUPS.values()))
        self.k = (info["length"] / (hi[2] - lo[2]) if "length" in info else 1.0 / 1.5) * info.get("fit_scale", 1.0)
        self.model = flan.Model(dump / f"{cls}.json", model_scale * self.k)
        # Flan scales only the drawing; config points authored against the unscaled model => same scale here.
        self.cfg_k = self.k * model_scale
        self.kind = info.get("type") or ("tracked_vehicle" if self.cfg.bool("Tank") else "wheeled_vehicle")
        blo, bhi = flan.bounds(self.model.boxes(*BODY_GROUPS))
        self.half_width = max(abs(blo[0]), bhi[0])
        tb = self.model.boxes("leftTrackModel", "rightTrackModel")
        self.side_limit = max(self.half_width, flan.bounds(tb)[1][0], -flan.bounds(tb)[0][0]) + 0.35

    def pos(self, key):
        v = self.cfg.vec(key)
        return None if v is None else v * self.cfg_k

    def build(self, out_root):
        ns, vid = self.ns, self.vid
        w = GltfWriter(None, f"{ns}:textures/vehicle/{vid}/{vid}.png", "CombinedAssault tools/flans")
        del w.doc["buffers"][0]["extras"]
        w.doc["materials"][0]["doubleSided"] = True
        root = w.node(vid)
        w.doc["nodes"][root]["extras"] = {"combinedassaultRole": "body"}
        m = self.model
        self.parts, self.weapons_main = [], []
        self.spin, self.track_boxes, self.kits = [], [], {}
        turret_origin = self.pos("TurretOrigin")
        lo, hi = flan.bounds(m.boxes(*BODY_GROUPS))
        static_gear = m.boxes("leftTrackModel", "rightTrackModel", "leftTrackWheelModels", "rightTrackWheelModels",
                              *WHEEL_GROUPS.values()) if self.kind in ("generic", "ship") else []
        self.baked = []
        if self.kind == "tracked_vehicle" and self.info.get("bake_wheels") and not m.boxes("leftTrackWheelModels", "rightTrackWheelModels"):
            self.baked = baked_wheels(m, self.info["bake_wheels"])
        baked_ids = {id(b) for wheel in self.baked for b in wheel}
        drawn(w, "body", [b for b in m.boxes(*BODY_GROUPS) if id(b) not in baked_ids] + static_gear, root, (0, 0, 0))
        turret = None
        if m.has("turretModel") or m.has("barrelModel"):
            tlo, _ = flan.bounds(m.boxes("turretModel") or m.boxes("barrelModel"))
            # Flan yaws about TurretOrigin, default (0, 0, 0); height irrelevant to yaw => turret floor.
            turret_origin = np.array([0.0, tlo[1], 0.0]) if turret_origin is None else turret_origin
            turret = frame(w, "turret", root, "part", "turret", turret_origin, {"system": "turret"})
            drawn(w, "turret_mesh", m.boxes("turretModel") + m.boxes("ammoModel"), turret, turret_origin)
            # Flan pitches about TurretOrigin too (often ground level); breech at barrel mid-height instead.
            blo, bhi = flan.bounds(m.boxes("barrelModel"))
            trunnion = np.array([turret_origin[0], (blo[1] + bhi[1]) / 2, blo[2]]) if m.has("barrelModel") else turret_origin
            pitch = frame(w, "cannon", turret, "pitch", "turret", trunnion - turret_origin, self.pitch_limits())
            drawn(w, "barrel", m.boxes("barrelModel"), pitch, trunnion)
            muzzles = [p for p in (v * self.cfg_k for v in map(self.vec_of, self.cfg.all("BarrelPosition"))) if np.linalg.norm(p) > 1e-6]
            if not muzzles:
                muzzles = [np.array([trunnion[0], trunnion[1], bhi[2]])] if m.has("barrelModel") else []
            for i, mz in enumerate(muzzles):
                d = mz - trunnion
                hidden(w, f"socket_bolt_turret_{i}", pitch, "bolt", "turret", (d[0], d[1], 0), {"index": i},
                       scale=(1, 1, max(d[2], 0.1)))
            hidden(w, "socket_sight_turret", pitch, "sight", "turret", self.sight_point(trunnion) - trunnion)
        self.turret_origin = turret_origin
        self.root, self.turret = root, turret
        if self.kind == "tracked_vehicle":
            self.tracks(w, root)
            self.tracked_com(lo, hi)
        elif self.kind == "wheeled_vehicle":
            self.wheels(w, root)
            if self.info.get("halftrack"):
                self.tracks(w, root)
                self.halftrack_support()
        self.seats(w, root, turret)
        hidden(w, "socket_camera", root, "camera", translation=(0, hi[1] + 1.0, 0))
        # COM over the support polygon: drill head / sidecar skew the body bounds
        cx, cz = self.support if getattr(self, "support", None) else (0.0, (lo[2] + hi[2]) / 2)
        cy = getattr(self, "com_y", lo[1] + 0.4 * (hi[1] - lo[1]))
        self.com_point = (cx, cy, cz)
        hidden(w, "socket_center_of_mass", root, "center_of_mass", translation=(cx, cy, cz))
        model_rel = f"models/vehicle/{vid}/{vid}.glb"
        out = out_root / "assets" / ns / model_rel
        out.parent.mkdir(parents=True, exist_ok=True)
        w.write(str(out), None)
        self.copy_textures(out_root)
        self.write_controller(out_root)
        self.write_track_link(out_root)
        self.write_kits(out_root)
        self.write_def(out_root, f"{ns}:{model_rel}")

    def write_controller(self, out_root):
        """wheel / road-wheel bones spin with travel; steering on front tyres (shipped humvee / t90 controllers)."""
        self.controller = None
        if not self.spin:
            return
        bindings = []
        groups = {}
        for entry in self.spin:
            groups.setdefault((entry[2], entry[1]), []).append(entry[0])
        for (source, radius), bones in sorted(groups.items()):
            bindings.append({"bones": bones, "source": source, "axis": "x", "param": radius})
        steer = [e[0] for e in self.spin if len(e) > 3 and e[3]]
        if steer:
            bindings.append({"bones": steer, "source": "steering_angle", "axis": "y", "multiplier": 1.0, "min": -30.0,
                             "max": 30.0})
        name = f"{self.vid}_controller"
        doc = {"name": name, "parameters": {}, "graph": {"type": "merge", "inputs": [
            {"type": "bone_binding", "special_bindings": bindings}]}}
        path = out_root / "assets" / self.ns / "animation_controllers" / f"{name}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(doc, indent=2) + "\n")
        self.controller = f"{self.ns}:{name}"

    def write_track_link(self, out_root):
        """One link = flat plate (X width, Z length) skinned with the Flan track texels; CA lays it along the track socket loop."""
        self.track = None
        if not self.track_boxes:
            return
        width = float(max(np.median([box_center(b)[1][0] for b in self.track_boxes]), 0.2))
        best, area = None, -1.0
        for b in self.track_boxes:
            for _, t in b["polys"]:
                a = float(np.ptp(t[:, 0]) * np.ptp(t[:, 1]))
                if a > area:
                    best, area = t, a
        u0, v0 = best.min(axis=0)
        u1, v1 = best.max(axis=0)
        hw, ht, hl = width / 2, 0.04, TRACK_MODULE / 2
        c = [(-hw, -ht, -hl), (hw, -ht, -hl), (hw, ht, -hl), (-hw, ht, -hl),
             (-hw, -ht, hl), (hw, -ht, hl), (hw, ht, hl), (-hw, ht, hl)]
        faces = [((0, 3, 2, 1), (0, 0, -1)), ((4, 5, 6, 7), (0, 0, 1)), ((0, 4, 7, 3), (-1, 0, 0)),
                 ((1, 2, 6, 5), (1, 0, 0)), ((3, 7, 6, 2), (0, 1, 0)), ((0, 1, 5, 4), (0, -1, 0))]
        quad_uv = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
        pos, nrm, uv = [], [], []
        for idx, n in faces:
            for i in (0, 1, 2, 0, 2, 3):
                pos.append(c[idx[i]])
                nrm.append(n)
                uv.append(quad_uv[i])
        w = GltfWriter(None, f"{self.ns}:textures/vehicle/{self.vid}/{self.vid}.png", "CombinedAssault tools/flans")
        del w.doc["buffers"][0]["extras"]
        w.doc["materials"][0]["doubleSided"] = True
        w.node("link", mesh=w.mesh("link", np.array(pos), np.array(nrm), np.array(uv), "paint"))
        rel = f"models/effect/{self.vid}_track_link.glb"
        out = out_root / "assets" / self.ns / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        w.write(str(out), None)
        self.track = {"model": f"{self.ns}:{rel}", "module_length": TRACK_MODULE}

    @staticmethod
    def vec_of(args):
        return flan.ca([float(x) for x in args[:3]]) / 16.0

    def pitch_limits(self):
        s = flan.seats(self.cfg)
        if not s:
            return None
        return self.x_rot(s[0].pitch)

    @staticmethod
    def x_rot(pitch):
        """Flan seat pitch: + = up. CA x_rot: - = up."""
        lo, hi = pitch
        return {"x_rot_min": -min(hi, 89.0), "x_rot_max": -max(lo, -89.0)}

    def tracks(self, w, root):
        m = self.model
        tl, tr, _ = split_sides(m.boxes("leftTrackModel", "rightTrackModel")
                                + m.boxes("leftAnimTrackModel", "rightAnimTrackModel", sub=0), self.side_limit)
        rear = ("leftBackWheelModel", "rightBackWheelModel") if self.info.get("halftrack") else ()
        wl, wr, _ = split_sides(m.boxes("leftTrackWheelModels", "rightTrackWheelModels", *rear), self.side_limit)
        complete_sides(tl, tr)
        complete_sides(wl, wr)
        if self.info.get("halftrack") and wl and wr:
            # Flan sides are unequal (M3: left wheels x 0.77, right -0.51); the lever arm of the track pair is the stance
            cl, cr = [float(np.mean([box_center(b)[0][0] for b in bs])) for bs in (wl, wr)]
            if abs(abs(cl) - abs(cr)) > 0.04:
                if abs(cl) > abs(cr):
                    tr, wr = [mirror_box(b) for b in tl], [mirror_box(b) for b in wl]
                else:
                    tl, wl = [mirror_box(b) for b in tr], [mirror_box(b) for b in wr]
        if not (tl or tr or wl or wr or self.baked):
            # KV1: the Flan model has no track / wheel geometry at all; belts from the hull footprint
            blo, bhi = flan.bounds(m.boxes(*BODY_GROUPS))
            for side, sign in (("left", 1), ("right", -1)):
                x = sign * (max(abs(blo[0]), abs(bhi[0])) - 0.35)
                pts = np.array([[x + dx, y, z] for dx in (-0.25, 0.25) for y in (blo[1] - 0.05, blo[1] + 0.9)
                                for z in (blo[2] + 0.2, bhi[2] - 0.2)])
                box = {"pivot": np.zeros(3), "hidden": False, "polys": [(pts, np.zeros((len(pts), 2)))]}
                if side == "left":
                    tl = [box]
                else:
                    tr = [box]
        mass = self.info.get("mass", 10000)
        self.rear_centres = []
        baked_ids = {id(b) for wheel in self.baked for b in wheel}
        body_boxes = [b for b in m.boxes(*BODY_GROUPS) if id(b) not in baked_ids]
        if tl + tr:
            lo, hi = flan.bounds(tl + tr)
            self.support = (0.0, float((lo[2] + hi[2]) / 2))
        for side, boxes, wheels in (("left", tl, wl), ("right", tr, wr)):
            if not boxes and not wheels and not self.baked:
                continue
            lo, hi = flan.bounds(boxes or wheels or sum(self.baked, []))
            part = frame(w, f"suspension_{side}", root, "part", f"suspension_{side}", (0, 0, 0),
                         {"system": "track", "side": side})
            # spin / support at the wheel's centre: Flan pivots sit off it (Kettenkrad left 0.38 vs right 0.50)
            clusters = [(cl, np.mean(flan.bounds(cl), axis=0)) for cl in cluster_by_pivot(wheels)]
            for wheel in self.baked:
                wlo, whi = flan.bounds(wheel)
                if (wlo[0] + whi[0] > 0) == (side == "left"):
                    clusters.append((wheel, (wlo + whi) / 2))
            road = self.road_wheels(clusters) if self.info.get("halftrack") else set()
            for k, (hub, c) in enumerate(clusters):
                hlo, hhi = flan.bounds(hub)
                r = round(float(max(hhi[1] - hlo[1], hhi[2] - hlo[2]) / 2), 2)
                node = drawn(w, f"wheel_{side}_{k}", hub, part, c, at=c)
                if k in road and node is not None:
                    w.doc["nodes"][node]["extras"] = {"combinedassault": {"road_wheel": True}}
                self.spin.append((f"wheel_{side}_{k}", r, f"{side}_wheel_rotation"))
            if side == "left" or not self.track_boxes:
                self.track_boxes = boxes or wheels or sum(self.baked, [])
            if clusters:
                wlo, whi = flan.bounds([b for cl, _ in clusters for b in cl])
                # loop wraps the wheels: bottom run at wheel bottoms, top run just over them (belt boxes include fenders / skirts)
                lo = np.array([lo[0], wlo[1] - 0.04, min(lo[2], wlo[2])])
                hi = np.array([hi[0], whi[1] + 0.04, max(hi[2], whi[2])])
            ref = boxes or wheels or sum(self.baked, [])
            # belt lateral position: median over boxes (Fury: 2 inboard drive boxes widened the bounds to 1.15)
            x = float(np.median([box_center(b)[0][0] for b in ref]))
            if road:
                x = float(np.mean([clusters[k][1][0] for k in road]))
            belt_w = float(np.median([box_center(b)[1][0] for b in ref]))
            # body geometry hanging below the wheels in this side's lateral band (UC2Pdr axle stubs, 0.1 under the belt)
            below = [flan.bounds([b])[0][1] for b in body_boxes
                     if abs(box_center(b)[0][0] - x) < belt_w / 2 + 0.4 and lo[2] <= box_center(b)[0][2] <= hi[2]
                     and lo[1] - 0.2 < flan.bounds([b])[0][1] < lo[1]]
            if below:
                lo = np.array([lo[0], min(below) - 0.02, lo[2]])
            self.contact_y = min(getattr(self, "contact_y", float(lo[1])), float(lo[1]))
            traced = belt_trace(boxes, float(lo[1]) if below else None) if boxes else None
            if traced:
                ring = [[x, y, z] for z, y in traced]
            elif clusters:
                ring = [[x, y, z] for z, y in belt_outline(clusters, float(lo[1]) if below else None)]
            else:
                r = (hi[1] - lo[1]) / 2
                ring = []
                for k in range(16):
                    a = 2 * np.pi * k / 16
                    ring.append([x, (lo[1] + hi[1]) / 2 + r * np.sin(a),
                                 (lo[2] + r if np.cos(a) < 0 else hi[2] - r) + r * np.cos(a)])
            sock = hidden(w, f"socket_track_suspension_{side}_0", part, "track", f"suspension_{side}", extra={"index": 0})
            self.append_mesh(w, sock, np.array(ring), [list(range(len(ring)))])
            allb = (boxes or []) + wheels + sum(self.baked, [])
            alo, ahi = flan.bounds(allb)
            self.rear_centres.append((alo + ahi) / 2)
            if self.info.get("halftrack"):
                # per-unit load as a wheel (tank-convention 20 x mass springs failed the washboard)
                k, c = self.spring(len(self.front_centres) + 2)
                self.parts.append({"id": f"suspension_{side}", "name": f"suspension_{side}",
                                   "type": "combinedassault:track", "is_seat": False, "max_health": 120,
                                   "detachable": False, "spring_stiffness": k, "damping": c,
                                   "rest_length": 0.25, "max_compression": 0.25, "max_extension": 0.2, "max_droop": 0.12,
                                   "track_width": float(max(belt_w, 0.2))})
                continue
            # stiffness ~ 20 x mass, damping ~ 1.9 x mass (shipped t90 / m113)
            self.parts.append({"id": f"suspension_{side}", "name": f"suspension_{side}", "type": "combinedassault:track",
                               "is_seat": False, "spring_stiffness": round(self.info.get("spring", 20 * mass)),
                               "damping": round(self.info.get("damping", 1.9 * mass)), "rest_length": 0.4,
                               "max_compression": 0.4, "max_extension": 0.1, "max_droop": 0.12,
                               "track_width": float(max(belt_w, 0.2))})

    @staticmethod
    def road_wheels(clusters):
        """Half-track belt wheels that carry the load: bottom row, one per axle z, outermost x (inner tyres share the z)."""
        bounds = [flan.bounds(hub) for hub, _ in clusters]
        floor = min(b[0][1] for b in bounds)
        low = [k for k, b in enumerate(bounds) if b[0][1] < floor + 0.08]
        out = set()
        for k in low:
            peers = [j for j in low if abs(clusters[j][1][2] - clusters[k][1][2]) < 0.06]
            if k == max(peers, key=lambda j: abs(clusters[j][1][0])):
                out.add(k)
        return out

    def halftrack_support(self):
        """COM over the 4 contact units (2 front tyres, 2 rear belts)."""
        cs = np.array(self.front_centres + self.rear_centres)
        self.support = (float(cs[:, 0].mean()), float(cs[:, 2].mean()))
        self.com_y = float(cs[:, 1].mean()) - COM_BELOW_HUB

    def fuel(self):
        """Litres = engine mass units (1 L = 1 kg): vehicles.json `fuel`, else the engine_class default."""
        if "fuel" in self.info:
            return self.info["fuel"]
        return json.loads((HERE / "vehicles.json").read_text())["_fuel_default"].get(self.info.get("engine_class"), 600)

    def tracked_com(self, lo, hi):
        """COM 0.4 x hull height above the track contact, never above the volume centroid (the engine's default COM put
        stug / panzeriil / type4horo / tunnelbore 1.2-1.8 above the contact)."""
        top = float(hi[1])
        contact = getattr(self, "contact_y", float(lo[1]))
        vols, ys = [], []
        for b in self.model.boxes(*BODY_GROUPS, "turretModel", "barrelModel"):
            c, size = box_center(b)
            vols.append(max(float(np.prod(size)), 1e-6))
            ys.append(float(c[1]))
        centroid = float(np.average(ys, weights=vols))
        self.com_y = min(contact + 0.4 * (top - contact), centroid)

    def spring(self, units):
        """k from the per-unit load for STATIC_SAG, c for ZETA (c = 2 zeta sqrt(k m_unit); engine mass = pack mass + fuel)."""
        loaded = self.info.get("mass", 2000) + self.fuel()
        k = loaded * 9.8 / units / STATIC_SAG
        return round(k), round(2 * ZETA * (k * loaded / units) ** 0.5)

    def wheel_sets(self):
        """[(part id, boxes, material)]. Halftrack / Kettenkrad (`tracks_as_wheels`): each rear track unit = one sprung wheel."""
        m = self.model
        lim = self.side_limit
        sets = []
        used_back = bool(self.info.get("halftrack"))
        if self.info.get("tracks_as_wheels"):
            tl, tr, _ = split_sides(m.boxes("leftTrackModel", "rightTrackModel"), lim)
            wl, wr, _ = split_sides(m.boxes("leftTrackWheelModels", "rightTrackWheelModels"), lim)
            bl, br, _ = split_sides(m.boxes("leftBackWheelModel", "rightBackWheelModel"), lim)
            complete_sides(tl, tr)
            for b in wl + wr + bl + br:
                b["spin"] = True
            for pid, boxes in (("wheel_rl", tl + wl + bl), ("wheel_rr", tr + wr + br)):
                if boxes:
                    sets.append((pid, boxes, "paint"))
            used_back = True
        for tag, groups in (("f", ("leftFrontWheelModel", "rightFrontWheelModel")),
                            ("r", ("leftBackWheelModel", "rightBackWheelModel"))):
            if tag == "r" and used_back:
                continue
            left, right, mid = split_sides(outboard(m.boxes(*groups)), lim)
            for suffix, boxes in (("l", left), ("r", right), ("m", mid)):
                for k, c in enumerate(cluster_z(boxes)):
                    sets.append((f"wheel_{tag}{suffix}" + (f"_{k}" if k else ""), c, "paint_rubber"))
        for pid, group in (("wheel_f", "frontWheelModel"), ("wheel_r", "backWheelModel")):
            for k, c in enumerate(cluster_z(m.boxes(group))):
                sets.append((pid + (f"_{k}" if k else ""), c, "paint_rubber"))
        return self.symmetric_axles(sets)

    @staticmethod
    def symmetric_axles(sets):
        """Both tyres of an axle at the outer one's |x| (authoring left the inner pivots / extrusions uneven)."""
        by = {pid: (boxes, material) for pid, boxes, material in sets}
        for pid in list(by):
            other = pid.replace("_fl", "_fr", 1).replace("_rl", "_rr", 1) if pid.startswith(("wheel_fl", "wheel_rl")) else None
            if not other or other not in by:
                continue
            (lb, mat), (rb, _) = by[pid], by[other]
            cl, cr = [float(sum(box_center(b)[0][0] for b in bs) / len(bs)) for bs in (lb, rb)]
            if abs(abs(cl) - abs(cr)) < 0.04:
                continue
            if abs(cl) > abs(cr):
                by[other] = ([mirror_box(b) for b in lb], mat)
            else:
                by[pid] = ([mirror_box(b) for b in rb], mat)
        return [(pid, by[pid][0], by[pid][1]) for pid, _, _ in sets]

    def wheels(self, w, root):
        mass = self.info.get("mass", 2000)
        sets = self.wheel_sets()
        centres = np.array([(flan.bounds(b)[0] + flan.bounds(b)[1]) / 2 for _, b, _ in sets])
        self.front_centres = list(centres)
        if len(centres):
            self.support = (float(centres[:, 0].mean()), float(centres[:, 2].mean()))
            # shipped humvee: COM 0.08 under hub height; body-bounds COM (0.5 above the hubs) rolled every wheeled conversion; 0.05 still flipped kubel on SLALOM
            self.com_y = float(centres[:, 1].mean()) - COM_BELOW_HUB
        for pid, boxes, material in sets:
            lo, hi = flan.bounds(boxes)
            c = (lo + hi) / 2
            part = frame(w, pid, root, "part", pid, c, {"system": "wheel"})
            spinning = [b for b in boxes if b.get("spin")]
            drawn(w, f"{pid}_mesh", [b for b in boxes if not b.get("spin")], part, c, material)
            for k, hub in enumerate(cluster_by_pivot(spinning)):
                hlo, hhi = flan.bounds(hub)
                hc = (hlo + hhi) / 2
                drawn(w, f"{pid}_roll_{k}", hub, part, hc, material, at=hc - c)
                self.spin.append((f"{pid}_roll_{k}", round(float(max(hhi[1] - hlo[1], hhi[2] - hlo[2]) / 2), 2), "wheel_rotation"))
            r = (hi[1] - lo[1]) / 2 if material == "paint" else max(hi[1] - lo[1], hi[2] - lo[2]) / 2
            pts, faces = prism((0, 0, 0), r, max((hi[0] - lo[0]) / 2, 0.05))
            col = hidden(w, f"collider_{pid}", part, "collider", extra={"solid": False, "name": pid})
            self.append_mesh(w, col, pts, faces)
            if material == "paint_rubber":
                self.spin.append((pid, round(float(r), 3), "wheel_rotation", pid.startswith("wheel_f")))
            k, c = self.spring(len(sets) + (2 if self.info.get("halftrack") else 0))
            self.parts.append({"id": pid, "name": pid, "type": "combinedassault:suspension", "max_health": 40,
                               "is_seat": False, "spring_stiffness": k, "damping": c,
                               "rest_length": 0.25, "max_compression": 0.25, "max_extension": 0.2})

    @staticmethod
    def append_mesh(w, node, pts, faces):
        pos = np.ascontiguousarray(pts, dtype=np.float32)
        idx = np.ascontiguousarray([[f[0], f[k], f[k + 1]] for f in faces for k in range(1, len(f) - 1)],
                                   dtype=np.uint32).reshape(-1)
        ia = len(w.doc["accessors"])
        w.doc["accessors"].append({"bufferView": w._view(pos.tobytes(), 34962), "componentType": 5126,
                                   "count": len(pos), "type": "VEC3", "min": pos.min(0).tolist(),
                                   "max": pos.max(0).tolist()})
        w.doc["accessors"].append({"bufferView": w._view(idx.tobytes(), 34963), "componentType": 5125,
                                   "count": len(idx), "type": "SCALAR"})
        w.doc["meshes"].append({"name": w.doc["nodes"][node]["name"],
                                "primitives": [{"attributes": {"POSITION": ia}, "indices": ia + 1}]})
        w.doc["nodes"][node]["mesh"] = len(w.doc["meshes"]) - 1

    def gun_model(self, name, on_turret):
        """Drawn passenger gun: yaw-only boxes ride the part frame, pitch boxes the pitch frame. -> (yaw, pitch, pivot)."""
        key = f"gunModels:{name}"
        yaw_boxes = self.model.boxes(key, sub=0)
        pitch_boxes = self.model.boxes(key, sub=1) + self.model.boxes(key, sub=2)
        ref = pitch_boxes or yaw_boxes
        pivot = np.median(np.array([b["pivot"] for b in ref]), axis=0) if ref else None
        return yaw_boxes, pitch_boxes, pivot

    def roof(self, p):
        """Median top height of upward faces of hull + turret around (x, z); None when nothing is there."""
        if not hasattr(self, "_roof"):
            pos, nrm, _ = flan.triangles(self.model.boxes(*BODY_GROUPS, "turretModel"))
            tri = pos.reshape(-1, 3, 3)
            self._roof = tri[nrm.reshape(-1, 3, 3)[:, 0, 1] > 0.3]
        t = self._roof
        tops = []
        for dx, dz in ((0, 0), (0.2, 0), (-0.2, 0), (0, 0.2), (0, -0.2)):
            a, b, c = t[:, 0], t[:, 1], t[:, 2]
            x, z = p[0] + dx, p[2] + dz
            d = (b[:, 2] - c[:, 2]) * (a[:, 0] - c[:, 0]) + (c[:, 0] - b[:, 0]) * (a[:, 2] - c[:, 2])
            ok = np.abs(d) > 1e-12
            dd = np.where(ok, d, 1)
            l1 = ((b[:, 2] - c[:, 2]) * (x - c[:, 0]) + (c[:, 0] - b[:, 0]) * (z - c[:, 2])) / dd
            l2 = ((c[:, 2] - a[:, 2]) * (x - c[:, 0]) + (a[:, 0] - c[:, 0]) * (z - c[:, 2])) / dd
            hit = ok & (l1 >= 0) & (l2 >= 0) & (1 - l1 - l2 >= 0)
            if hit.any():
                tops.append((l1 * a[:, 1] + l2 * b[:, 1] + (1 - l1 - l2) * c[:, 1])[hit].max())
        return float(np.median(tops)) if tops else None

    def sight_point(self, trunnion):
        """Gunner sight: coaxial, left of the barrel (CA +X), on the mantlet front face, clear forward view."""
        barrel = self.model.boxes("barrelModel")
        lo, hi = flan.bounds(barrel) if barrel else (trunnion, trunnion)
        hull, _, _ = flan.triangles(self.model.boxes(*BODY_GROUPS, "turretModel"))
        tri = hull.reshape(-1, 3, 3)
        p = np.array([hi[0] + 0.12, trunnion[1], 50.0])
        hit = flan.ray_hits(tri, p, np.array([[0.0, 0.0, -1.0]]))[0]
        p[2] = p[2] - hit + 0.05 if np.isfinite(hit) else trunnion[2]
        return p

    @staticmethod
    def facing(s, pos):
        """Seat yaw-range middle; side seats (mid off 0/180) look out of their own side."""
        mid = (s.yaw[0] + s.yaw[1]) / 2 if s.yaw[1] - s.yaw[0] < 359 else 0.0
        side = abs(abs(mid) % 180)
        if 10 < side < 170:
            mid = abs(mid) * (1 if pos[0] >= 0 else -1)
        return mid

    def eye_for(self, pos, roofed):
        """Flan seat point rides the head-out position: eye = hatch rim + HEAD_OUT, else seat + EYE_ABOVE_SEAT."""
        top = self.roof(pos) if roofed else None
        return np.array([0, (top + HEAD_OUT - pos[1]) if top is not None else EYE_ABOVE_SEAT, 0])

    def seats(self, w, root, turret):
        guns = self.cfg.all("AddGun")
        table = json.loads((HERE / "guns.json").read_text())
        main = table.get(f"main:{self.short}") or table.get(f"main:{self.short.lower()}") or (PLACEHOLDER_GUN if self.model.has("barrelModel") else None)
        for s in flan.seats(self.cfg):
            pos = (s.pos + s.rotated_offset) * self.cfg_k
            if s.id == 0 and turret is not None:
                pid = "turret"
                eye = self.eye_for(pos, True)
                rel = pos + eye - self.turret_origin
                hidden(w, f"socket_seat_{pid}", turret, "seat", pid, rel)
                hidden(w, f"socket_view_{pid}", turret, "view", pid, rel)
                weapons = [main] if main else []
                for k, g in enumerate(guns):
                    sub = f"mg_{k}"
                    gpos = flan.ca([float(x) for x in g[:3]]) / 16 * self.cfg_k
                    parent = turret if g[3] == "turret" else root
                    off = gpos - (self.turret_origin if g[3] == "turret" else 0)
                    pf = frame(w, f"{sub}_pitch", parent, "pitch", sub, off)
                    hidden(w, f"socket_bolt_{sub}_0", pf, "bolt", sub,
                           (0, 0, 0), {"index": 0}, scale=(1, 1, 0.5))
                    weapons.append({"id": self.gun_weapon(g[4]), "part_unit_id": sub, "secondary": True})
                    self.parts.append({"id": sub, "name": sub, "type": "combinedassault:weapon", "is_seat": False,
                                       "rot_info": {"x_rot_speed": 3, "y_rot_speed": 3}})
                rot = self.cfg.get("DriverAimSpeed")
                yaw_limits = {"y_rot_min": s.yaw[0], "y_rot_max": s.yaw[1]} if s.yaw[1] - s.yaw[0] < 359 else {}
                self.parts.insert(0, {
                    "id": pid, "name": "part.combinedassault.turret", "type": "combinedassault:weapon",
                    "crew_roles": ["driver", "gunner"], "max_health": 600, "render_bone": "turret",
                    "rot_info": {"x_rot_speed": float(rot[1].rstrip("f")) * 2 if rot else 1.5,
                                 "y_rot_speed": float(rot[0].rstrip("f")) * 2 if rot else 2, **yaw_limits},
                    "optical_sight_type": "optical_scope", "zoom_max": 4, "weapons": weapons,
                    "protect_passenger": True})
                continue
            pid = "driver" if s.id == 0 else f"seat_{s.id}"
            on_turret = s.part == "turret" and turret is not None
            org = self.turret_origin if on_turret else 0
            eye = self.eye_for(pos, on_turret and not s.gun)
            yaw_boxes, pitch_boxes, pivot = self.gun_model(s.gun_name, on_turret) if s.gun else ([], [], None)
            rel = pos + eye - org
            parent = turret if on_turret else root
            part = {"id": pid, "name": f"part.{self.ns}.{pid}" if s.id else "part.combinedassault.driver",
                    "type": "combinedassault:generic", "protect_passenger": False}
            if s.gun:
                weapon = self.gun_weapon(s.gun)
                limits = {}
                if s.yaw[1] - s.yaw[0] < 359:
                    limits = {"y_rot_min": s.yaw[0], "y_rot_max": s.yaw[1]}
                kit = self.mg_kit(weapon)
                origin = pivot if pivot is not None else (s.gun_origin * self.cfg_k if s.gun_origin is not None else pos)
                g = frame(w, f"{pid}_gun", parent, "part", pid, origin - org)
                pf = frame(w, f"{pid}_gun_pitch", g, "pitch", pid, (0, 0, 0), self.x_rot(s.pitch))
                if kit:
                    # MG spot = pintle hardpoint; the kit model replaces the Flan gun mesh
                    part.update({"type": "combinedassault:weapon", "crosshair_style": "cross", "crew_roles": ["gunner"],
                                 "handling": "manual", "max_health": 120, "render_bone": f"{pid}_gun",
                                 "rot_info": {"x_rot_speed": 4, "y_rot_speed": 4, "need_power": False, **limits},
                                 "hardpoint": {"node": f"{pid}_gun_pitch", "accepts": ["pintle"], "fitted": kit},
                                 "operator_on_weapon_unit": False, "pose": "stand"})
                else:
                    part.update({"type": "combinedassault:weapon", "crosshair_style": "cross",
                                 "rot_info": {"x_rot_speed": 4, "y_rot_speed": 4, **limits},
                                 "weapons": [weapon], "operator_on_weapon_unit": True})
                    if yaw_boxes:
                        drawn(w, f"{pid}_gun_yaw_mesh", yaw_boxes, g, origin)
                    if pitch_boxes:
                        drawn(w, f"{pid}_gun_mesh", pitch_boxes, pf, origin)
                        blo, bhi = flan.bounds(pitch_boxes)
                        hidden(w, f"socket_bolt_{pid}_0", pf, "bolt", pid,
                               ((blo[0] + bhi[0]) / 2 - origin[0], (blo[1] + bhi[1]) / 2 - origin[1], blo[2] - origin[2]),
                               {"index": 0}, scale=(1, 1, max(bhi[2] - blo[2], 0.3)))
                hidden(w, f"socket_seat_{pid}", g, "seat", pid, pos + eye - origin)
                hidden(w, f"socket_view_{pid}", g, "view", pid, pos + eye - origin)
            else:
                hidden(w, f"socket_seat_{pid}", parent, "seat", pid, rel)
            if s.id == 0:
                part["crew_roles"] = ["driver"]
            self.parts.append(part)

    def mg_kit(self, weapon_id):
        """Hardware kit id for a machine gun weapon (None: cannon, stays fixed). 12.7 => shipped m2, rest => pack kit on the pkm model."""
        if weapon_id not in MGS:
            return None
        if weapon_id == CA + "gun_12.7mm":
            return CA + "m2"
        slug = flan.slug(weapon_id.split(":")[1])
        self.kits[slug] = weapon_id
        return f"{self.ns}:{slug}"

    def write_kits(self, out_root):
        for slug, weapon in self.kits.items():
            path = out_root / "combinedassault" / self.ns / "hardware" / f"{slug}.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"model": KIT_MODEL["light"], "weapons": [weapon], "fits": ["pintle"],
                                        "install_ticks": 80, "station": {"optical_sight_type": "operator", "zoom_max": 2}},
                                       indent=2) + "\n")

    def gun_weapon(self, flan_gun):
        table = json.loads((HERE / "guns.json").read_text())
        g = flan_gun.lower()
        return table.get(f"{self.short.lower()}:{g}") or table.get(g) or table["_default"]

    def copy_textures(self, out_root):
        skins = self.flans / "run/Flan" / self.pack_dir.name / "assets/flansmod/skins"
        tex = self.cfg.str("Texture")
        src = next((p for p in skins.glob("*.png") if p.stem.lower() == tex.lower()), None)
        assert src, f"{self.short}: no skin {tex}"
        dst = out_root / "assets" / self.ns / "textures/vehicle" / self.vid / f"{self.vid}.png"
        dst.parent.mkdir(parents=True, exist_ok=True)
        # Flan skins may be palette PNGs
        Image.open(src).convert("RGBA").save(dst)
        (out_root / "assets" / self.ns / "textures/slot").mkdir(parents=True, exist_ok=True)

    def write_def(self, out_root, model_rl):
        info, cfg = self.info, self.cfg
        speed = info.get("speed_kmh", 40) / 3.6 / 20
        mass = info.get("mass", 10000)
        d = {"type": f"combinedassault:{self.kind}"}
        if self.kind == "tracked_vehicle":
            d["attributes"] = {"brake_acceleration": 0.025, "forward_acceleration": 0.008, "backward_acceleration": 0.008,
                               "max_speed_forward": round(speed, 3), "max_speed_backward": round(speed * 0.4, 3),
                               "turn_acceleration": 1, "max_turn": 2}
            # rolling resistance 0.04 m g (Flan's 2 m N is a coefficient of 0.2); wheeled keep the engine default 0.015 m g
            d["physics_info"] = {"mass": mass, "friction": round(0.04 * (mass + self.fuel()) * 9.8),
                                 "suspension_response": 4, "can_destroy_block": mass > 15000,
                                 "center": [round(float(v), 3) for v in self.com_point]}
        elif self.kind == "wheeled_vehicle":
            # engine mass = pack mass + 1 kg per fuel unit (measured: m3 9000 -> 9600, Kettenkrad 1200 -> 1800); forces sized on
            # that, else the Kettenkrad (1200 kg) lost 30% of its acceleration vs the M3
            loaded = mass + self.fuel()
            d["attributes"] = {"brake_force": loaded * 5, "forward_force": loaded * 5.5, "backward_force": loaded * 2.8,
                               "max_speed_forward": round(speed, 3), "max_speed_backward": 0.2,
                               "steer_fade_speed": 0.6, "turn_step": 0.25, "max_turn": 2.5}
            d["physics_info"] = {"mass": mass}
        elif self.kind == "ship":
            lo, _ = flan.bounds(self.model.boxes(*BODY_GROUPS))
            d["attributes"] = {"waterline": 0.0, "draft": round(float(-lo[1]), 3), "max_speed_forward": round(speed, 3),
                               "max_speed_backward": round(speed * 0.3, 3), "acceleration_time": 8, "throttle_rate": 0.5,
                               "rudder_rate": 1.5, "turn_rate": 18, "turn_lag": 1.0, "low_speed_turn": 0.1, "drift": 0.3,
                               "heel": -0.5, "trim": 3.0, "heave_period": 3.0, "righting_rate": 10.0,
                               "min_keel_clearance": 0.5, "shallow_drag": 2.0}
            d["physics_info"] = {"mass": mass, "can_tumble": False}
        else:
            d["attributes"] = {}
            d["physics_info"] = {"mass": mass}
        if self.kind in ("tracked_vehicle", "wheeled_vehicle"):
            d["attributes"]["engine_power"] = self.engine_power()
        d["max_health"] = info.get("health", 400)
        if "armor" in info:
            d["armor"] = info["armor"]
        d["view_info"] = {"third_person_distance": cfg.float("CameraDistance", 7)}
        d["energy_info"] = {"energy_capacity": self.fuel(), "energy_consumption_per_tick": 0.004}
        slots = int(cfg.float("CargoSlots", 9))
        d["storage"] = {"slots": max(9, min(54, slots))}
        d["parts"] = self.parts
        d["model"] = model_rl
        d["display"] = {"tab_index": info.get("tab_index", 900)}
        d["display"]["icon"] = f"{self.ns}:textures/slot/{self.vid}.png"
        engine = self.engine_class()
        if engine:
            d["display"]["engine"], d["display"]["cabin_muffle"] = ENGINES[engine]
        if getattr(self, "controller", None):
            d["display"]["controller"] = self.controller
        if getattr(self, "track", None):
            d["display"]["track"] = self.track
        desc = cfg.str("Description")
        if desc:
            d["display"]["description"] = desc
        for k, v in info.get("def", {}).items():
            d[k] = v
        self.write_lang(out_root)
        path = out_root / "combinedassault" / self.ns / "vehicles" / f"{self.vid}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(d, indent=2) + "\n")
        self.write_recipe(out_root)

    def engine_power(self):
        """kW: real power x loaded mass / real mass (vehicles.json engine_kw + real_mass), else W/kg of its engine_class x loaded mass."""
        loaded = self.info.get("mass", 10000) + self.fuel()
        if "engine_kw" in self.info:
            return round(self.info["engine_kw"] * loaded / self.info["real_mass"], 1)
        wpkg = json.loads((HERE / "vehicles.json").read_text())["_engine_wpkg"][self.info["engine_class"]]
        return round(wpkg * loaded / 1000, 1)

    def engine_class(self):
        mass = self.info.get("mass", 10000)
        if self.kind == "tracked_vehicle":
            if self.info.get("engine"):
                return self.info["engine"]
            return "tank" if mass >= 28000 else "light_tracked"
        if self.kind == "wheeled_vehicle":
            if self.info.get("engine"):
                return self.info["engine"]
            return "bike" if mass < 600 or self.short in ("BMWR75", "SdkFz2") else "car" if mass < 3000 else "truck"
        return None

    def write_lang(self, out_root):
        path = out_root / "assets" / self.ns / "lang/en_us.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path.with_suffix(".lock"), "w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            lang = json.loads(path.read_text()) if path.exists() else {}
            lang[f"entity.{self.ns}.{self.vid}"] = self.cfg.str("Name")
            lang[f"{self.ns}.{self.vid}"] = self.cfg.str("Name")
            for slug, weapon in self.kits.items():
                lang[f"hardware.{self.ns}.{slug}"] = weapon_name(weapon)
            stations = {w["part_unit_id"]: w["id"] for p in self.parts for w in p.get("weapons", []) if "part_unit_id" in w}
            for p in self.parts:
                if p["name"].startswith(f"part.{self.ns}."):
                    lang[p["name"]] = "Seat " + p["id"].split("_")[-1]
                elif not p["name"].startswith("part."):
                    p["name"] = f"part.{self.ns}.{self.vid}.{p['id']}"
                    lang[p["name"]] = part_label(p["id"], stations.get(p["id"]))
            path.write_text(json.dumps(dict(sorted(lang.items())), indent=2, ensure_ascii=False) + "\n")
        path.with_suffix(".lock").unlink(missing_ok=True)

    def write_recipe(self, out_root):
        tpl = TEMPLATES.get(self.kind, "humvee")
        r = json.loads((BUILTIN / "data/combinedassault/recipe" / f"{tpl}.json").read_text())
        r["result"]["components"]["minecraft:custom_data"]["CombinedAssaultId"] = f"{self.ns}:{self.vid}"
        path = out_root / "data" / self.ns / "recipe" / f"{self.vid}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(r, indent=2) + "\n")


class Sentry(Vehicle):
    """aaguns/*.txt: ModelAAGun = base + yawing seat + gun and barrels pitching about (barrelX, barrelY, barrelZ)."""

    def __init__(self, flans, dump, pack_dir, cfg_path, info):
        self.cfg = flan.Config(cfg_path)
        self.info, self.pack_dir, self.flans = info, pack_dir, flans
        self.short = self.cfg.str("ShortName")
        self.vid = info.get("id", flan.slug(self.short))
        self.ns = flan.FLAN_PACKS[pack_dir.name]
        cls, _ = flan.model_class(self.cfg.str("Model"))
        self.model = flan.Model(dump / f"{cls}.json", 1.0)
        self.k = self.cfg_k = 1.0
        self.kind = "generic"

    def build(self, out_root):
        ns, vid, m = self.ns, self.vid, self.model
        self.spin, self.track_boxes, self.kits = [], [], {}
        w = GltfWriter(None, f"{ns}:textures/vehicle/{vid}/{vid}.png", "CombinedAssault tools/flans")
        del w.doc["buffers"][0]["extras"]
        w.doc["materials"][0]["doubleSided"] = True
        root = w.node(vid)
        w.doc["nodes"][root]["extras"] = {"combinedassaultRole": "body"}
        drawn(w, "body", m.boxes("baseModel"), root, (0, 0, 0))
        sc = m.scalars
        pivot = flan.ca([sc["barrelX"], sc["barrelY"], sc["barrelZ"]]) / 16
        slo, shi = flan.bounds(m.boxes("seatModel"))
        eye = np.array([(slo[0] + shi[0]) / 2, slo[1] + 0.9, (slo[2] + shi[2]) / 2])
        gun = frame(w, "gun_yaw", root, "part", "gun", (0, 0, 0))
        drawn(w, "seat_mesh", m.boxes("seatModel"), gun, (0, 0, 0))
        pitch = frame(w, "gun_pitch", gun, "pitch", "gun", pivot, {"x_rot_min": -float(self.cfg.float("TopViewLimit", 75)),
                                                                   "x_rot_max": float(self.cfg.float("BottomViewLimit", 0))})
        drawn(w, "gun_mesh", m.boxes("gunModel"), pitch, (0, 0, 0))
        for i, barrel in enumerate(m.groups.get("barrelModel", [])):
            bx = [b for b in barrel if not b["hidden"]]
            if not bx:
                continue
            drawn(w, f"barrel_{i}", bx, pitch, (0, 0, 0))
            lo, hi = flan.bounds(bx)
            hidden(w, f"socket_bolt_gun_{i}", pitch, "bolt", "gun", ((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, lo[2]),
                   {"index": i}, scale=(1, 1, hi[2] - lo[2]))
        hidden(w, "socket_seat_gun", gun, "seat", "gun", eye)
        hidden(w, "socket_camera", root, "camera", translation=(0, 3.0, 0))
        hidden(w, "socket_center_of_mass", root, "center_of_mass", translation=(0, 0.5, 0))
        self.parts = [{"id": "gun", "name": "part.combinedassault.turret", "type": "combinedassault:weapon",
                       "max_health": 100, "component": "barrel", "detachable": False, "operator_on_weapon_unit": True,
                       "crosshair_style": "cross", "rot_info": {"x_rot_speed": 3, "y_rot_speed": 4, "need_power": False},
                       "weapons": [self.gun_weapon("minigun")], "pose": "sit", "protect_passenger": False}]
        out = out_root / "assets" / ns / f"models/vehicle/{vid}/{vid}.glb"
        out.parent.mkdir(parents=True, exist_ok=True)
        w.write(str(out), None)
        self.copy_textures(out_root)
        self.write_def(out_root, f"{ns}:models/vehicle/{vid}/{vid}.glb")


def pack_meta(flans, out_root, pack_name):
    out_root.mkdir(parents=True, exist_ok=True)
    meta = {"pack": {"pack_format": 34, "description": f"[CA] Flan's {pack_name}"},
            "combinedassault": {"id": out_root.name, "requires": ["combinedassault"], "license": "CC-BY-NC-SA-3.0",
                                "authors": ["Jamio Flan (Flan's Mod)"]}}
    (out_root / "pack.mcmeta").write_text(json.dumps(meta, indent=2) + "\n")
    shutil.copy(flans / "LICENSE.txt", out_root / "LICENSE.txt")
    (out_root / "CREDITS.md").write_text(
        f"# Flan's {pack_name} for CombinedAssault\n\n"
        f"Models, textures and vehicle data: Jamio Flan, Flan's Mod 1.12.2 `{pack_name}` "
        "(https://github.com/FlansMods/FlansMod), CC BY-NC-SA 3.0 (`LICENSE.txt`).\n\n"
        "Changes: models converted from Flan's TMT boxes to glTF and rescaled to real vehicle dimensions; "
        "configs rewritten as CombinedAssault definitions; colliders and sockets added. This pack is CC BY-NC-SA 3.0. "
        "Not endorsed by Jamio Flan.\n")


def main():
    flans, dump, packs, pack_name = (Path(a).resolve() if i < 3 else a for i, a in enumerate(sys.argv[1:5]))
    names = sys.argv[5:]
    table = json.loads((HERE / "vehicles.json").read_text())
    pack_src = flans / "run/Flan" / pack_name
    out_root = packs / flan.FLAN_PACKS[pack_name]
    pack_meta(flans, out_root, pack_name)
    cfgs = sorted(p for d in ("vehicles", "planes", "aaguns") for p in (pack_src / d).glob("*.txt"))
    for path in cfgs:
        short = flan.Config(path).str("ShortName")
        if names != ["all"] and short not in names:
            continue
        info = table.get(short, {})
        cls = Sentry if path.parent.name == "aaguns" else Vehicle
        if path.parent.name == "planes":
            import aircraft
            cls = aircraft.Aircraft
        v = cls(flans, dump, pack_src, path, info)
        v.build(out_root)
        finish(out_root, f"{v.ns}:{v.vid}", info.get("hulls", default_hulls(v)))
        model = out_root / "assets" / v.ns / f"models/vehicle/{v.vid}/{v.vid}.glb"
        s, seat, why = seatfit.fit(model)
        fit = f"fit {s:.3f} {seat or '-'} {why}"
        if 1.005 < s <= FIT_CAP:
            # eye / head-out offsets are player-sized, not scaled => clearance not exactly linear in s: re-measure
            total, after = 1.0, s
            while after > 1.005 and total * after <= FIT_CAP:
                total *= after
                v = cls(flans, dump, pack_src, path, dict(info, fit_scale=total))
                v.build(out_root)
                finish(out_root, f"{v.ns}:{v.vid}", info.get("hulls", default_hulls(v)))
                after, seat2, why2 = seatfit.fit(model)
            fit += f" -> x{total:.3f}, after {after:.3f} {seat2 or '-'} {why2}"
        elif s > FIT_CAP:
            fit += " REVIEW (not scaled)"
        print(f"{short} -> {v.ns}:{v.vid}  k={v.k:.3f}  {fit}")
        for line in sightline.lines(sightline.run(str(out_root), f"{v.ns}:{v.vid}", True)):
            print("  sightline " + line)


# Seated-player fit above this => seat likely misplaced, not a small hull: left unscaled for review.
FIT_CAP = 1.3


def default_hulls(v):
    """generic: base only (turret piece must not win `main`). Wheeled keeps the multi-piece default: ONE convex body piece
    (--budget 1) measured worse on the terrain gate (14 vs 6 flips over the 6 wheeled vehicles)."""
    return ["--nodes", "body"] if v.kind == "generic" else []


def fix_main(out_root, vid):
    """gen_hulls --main picks the largest piece anywhere; main must ride the body frame (turret pieces fail the load)."""
    import glb
    ns, rid = vid.split(":")
    path = out_root / "assets" / ns / f"models/vehicle/{rid}/{rid}.glb"
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
        size = np.array(acc["max"]) - np.array(acc["min"])
        cands.append((float(np.prod(size)), i))
    assert cands, f"{vid}: no solid body collider"
    for n in nodes:
        ex = n.get("extras", {})
        if ex.get("combinedassaultRole") == "collider" and ex.get("combinedassault", {}).get("main"):
            del ex["combinedassault"]["main"]
            if not ex["combinedassault"]:
                del ex["combinedassault"]
    best = max(cands)[1]
    nodes[best].setdefault("extras", {}).setdefault("combinedassault", {})["main"] = True
    glb.write(path, doc, blob)


def finish(out_root, vid, hull_args):
    """CoACD colliders (main hull) + rendered slot icon."""
    import subprocess
    root = Path(__file__).resolve().parents[2]
    subprocess.run([str(Path.home() / ".cache/combinedassault-hulls/bin/python"), str(root / "tools/gen_hulls.py"),
                    "--pack", str(out_root), vid, "--main", *hull_args], check=True, stdout=subprocess.DEVNULL)
    fix_main(out_root, vid)
    subprocess.run([sys.executable, str(root / "tools/model_icon.py"), "--pack", str(out_root), vid], check=True,
                   stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
