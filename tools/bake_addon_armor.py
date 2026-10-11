#!/usr/bin/env python3
"""Bake addon_armor[].surface from each vehicle's glTF (server traces none of it).

    tools/bake_addon_armor.py [vehicle ...] [--pack src/main/resources/combinedassault_packs/combinedassault] [--dry]

Authored frame: the entry's own center/normal/up/width/height, else its `era` / `slat` socket quad (as MountDefaults).
Per mount: rays along -normal on a 7x4 grid over the panel, against drawn triangles at rest (role nodes, hidden and
`decor` skipped). Outermost skin only (hits within SKIN_BAND of the shallowest). ERA: least-squares plane ->
center/normal/up. Slat: authored orientation, center = skin + standoff.
"""
import argparse
import json
import math
import pathlib
import re
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import convert_pack  # noqa: E402

COLUMNS, ROWS, SPAN = 7, 4, 0.9
OUT, IN = 0.8, 1.2
SKIN_BAND = 0.12
MIN_HITS = 4


DECOR = re.compile(r"(?i)(?:^|[_ .])decor(?:$|[_ .])")
FLAT = 0.2


class Triangles:
    """Drawn triangles at rest pose, vehicle frame: untagged and frame nodes, not hidden, not `decor` materials."""

    def __init__(self, gltf):
        tris = []
        for i, node in enumerate(gltf.nodes):
            extras = node.get("extras", {})
            if "mesh" not in node or extras.get(convert_pack.HIDDEN) \
                    or extras.get(convert_pack.ROLE, "body") not in ("body", "part", "pitch"):
                continue
            world = gltf.world(i)
            for prim in gltf.doc["meshes"][node["mesh"]]["primitives"]:
                if "material" in prim and DECOR.search(gltf.doc["materials"][prim["material"]].get("name", "")):
                    continue
                pos = accessor(gltf, prim["attributes"]["POSITION"])
                pos = (world @ np.c_[pos, np.ones(len(pos))].T).T[:, :3]
                tris.append(pos[accessor(gltf, prim["indices"]).astype(int).reshape(-1, 3)])
        self.tris = np.vstack(tris)

    def __len__(self):
        return len(self.tris)

    def trace(self, start, end):
        o, d = np.array(start, float), np.array(end, float) - np.array(start, float)
        a, b, c = self.tris[:, 0], self.tris[:, 1], self.tris[:, 2]
        e1, e2 = b - a, c - a
        p = np.cross(d, e2)
        det = (e1 * p).sum(1)
        ok = np.abs(det) > 1e-12
        inv = np.where(ok, 1 / np.where(ok, det, 1), 0)
        s = o - a
        u = (s * p).sum(1) * inv
        q = np.cross(s, e1)
        v = (d * q).sum(1) * inv
        t = (e2 * q).sum(1) * inv
        hit = ok & (u >= 0) & (v >= 0) & (u + v <= 1) & (t >= 0) & (t <= 1)
        return float(t[hit].min()) if hit.any() else None


def accessor(gltf, i):
    acc = gltf.doc["accessors"][i]
    view = gltf.doc["bufferViews"][acc["bufferView"]]
    dtype = {5126: np.float32, 5125: np.uint32, 5123: np.uint16, 5121: np.uint8}[acc["componentType"]]
    width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3}[acc["type"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = view.get("byteStride", 0)
    blob = bytes(gltf.buffers[view["buffer"]])
    if stride and stride != width * np.dtype(dtype).itemsize:
        return np.array([np.frombuffer(blob, dtype, width, start + k * stride) for k in range(acc["count"])])
    return np.frombuffer(blob, dtype, acc["count"] * width, start).reshape(acc["count"], width)


def socket_frame(gltf, target):
    """MountDefaults.addon: era / slat quad socket -> kind, center, normal (winding), up (edge nearest +Y, flat: +Z),
    width, height."""
    for i, node in enumerate(gltf.nodes):
        extras = node.get("extras", {})
        if extras.get(convert_pack.ROLE) not in ("era", "slat") or extras.get(convert_pack.IFACE) != target:
            continue
        world = gltf.world(i)
        t = []
        for prim in gltf.doc["meshes"][node["mesh"]]["primitives"]:
            pos = accessor(gltf, prim["attributes"]["POSITION"])
            pos = (world @ np.c_[pos, np.ones(len(pos))].T).T[:, :3]
            t += list(pos[accessor(gltf, prim["indices"]).astype(int).reshape(-1, 3)])
        n = sum(np.cross(b - a, c - a) for a, b, c in t)
        n = n / np.linalg.norm(n)
        up = edge_nearest(t, n, np.array([0, 1.0, 0]))
        if up is None:
            up = edge_nearest(t, n, np.array([0, 0, 1.0]))
        u = np.cross(up, n)
        pts = np.vstack(t)
        uu, vv, depth = pts @ u, pts @ up, (pts @ n).mean()
        centre = u * (uu.min() + uu.max()) / 2 + up * (vv.min() + vv.max()) / 2 + n * depth
        return {"kind": extras[convert_pack.ROLE], "center": centre.tolist(), "normal": n.tolist(), "up": up.tolist(),
                "width": float(uu.max() - uu.min()), "height": float(vv.max() - vv.min())}
    raise SystemExit(f"no era / slat socket for {target}")


def edge_nearest(tris, n, axis):
    best, best_dot = None, FLAT
    for tri in tris:
        for k in range(3):
            e = tri[(k + 1) % 3] - tri[k]
            e = e - n * e.dot(n)
            length = np.linalg.norm(e)
            if length < 1e-6:
                continue
            e = e / length
            if e.dot(axis) < 0:
                e = -e
            if e.dot(axis) > best_dot:
                best, best_dot = e, e.dot(axis)
    return best


def norm(v):
    n = math.sqrt(sum(c * c for c in v))
    return [c / n for c in v]


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def solve3(m, v):
    det = (m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
           + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]))
    if abs(det) < 1e-9:
        return None
    out = []
    for c in range(3):
        mc = [row[:] for row in m]
        for r in range(3):
            mc[r][c] = v[r]
        out.append((mc[0][0] * (mc[1][1] * mc[2][2] - mc[1][2] * mc[2][1])
                    - mc[0][1] * (mc[1][0] * mc[2][2] - mc[1][2] * mc[2][0])
                    + mc[0][2] * (mc[1][0] * mc[2][1] - mc[1][1] * mc[2][0])) / det)
    return out


def fit(boxes, mount):
    n0 = norm(mount["normal"])
    up = mount.get("up", [0, 1, 0])
    v0 = norm([up[i] - n0[i] * dot(up, n0) for i in range(3)])
    u0 = cross(v0, n0)
    c0 = mount["center"]
    w, h = mount.get("width", 1.0), mount.get("height", 0.5)
    slat = mount.get("kind", "era") == "slat"
    reach_in = IN + (mount.get("standoff", 0) if slat else 0)
    hits = []
    for a in range(COLUMNS):
        for b in range(ROWS):
            x = (a / (COLUMNS - 1) - 0.5) * SPAN * w
            y = (b / (ROWS - 1) - 0.5) * SPAN * h
            p = [c0[i] + u0[i] * x + v0[i] * y for i in range(3)]
            start = [p[i] + n0[i] * OUT for i in range(3)]
            end = [p[i] - n0[i] * reach_in for i in range(3)]
            t = boxes.trace(start, end)
            if t is not None:
                hits.append((x, y, OUT - t * (OUT + reach_in)))
    if not hits:
        return None, "no hits"
    outer = max(hh for _, _, hh in hits)
    hits = [hit for hit in hits if hit[2] >= outer - SKIN_BAND]
    if len(hits) < MIN_HITS:
        return None, f"{len(hits)} skin hits"
    if slat:
        skin = sum(hh for _, _, hh in hits) / len(hits)
        spread = max(abs(hh - skin) for _, _, hh in hits)
        centre = [c0[i] + n0[i] * (skin + mount.get("standoff", 0)) for i in range(3)]
        return surface(centre, n0, v0, spread), f"{len(hits)} hits, gap {skin + mount.get('standoff', 0):+.3f}"
    ata = [[0.0] * 3 for _ in range(3)]
    atb = [0.0] * 3
    for x, y, hh in hits:
        row = (x, y, 1.0)
        for i in range(3):
            atb[i] += row[i] * hh
            for j in range(3):
                ata[i][j] += row[i] * row[j]
    coef = solve3(ata, atb)
    if coef is None:
        return None, f"{len(hits)} skin hits, collinear"
    normal = norm([n0[i] - coef[0] * u0[i] - coef[1] * v0[i] for i in range(3)])
    residual = max(abs(hh - (coef[0] * x + coef[1] * y + coef[2])) for x, y, hh in hits) * dot(normal, n0)
    centre = [c0[i] + n0[i] * coef[2] for i in range(3)]
    up_fit = norm([v0[i] - normal[i] * dot(v0, normal) for i in range(3)])
    tilt = math.degrees(math.acos(min(1.0, dot(normal, n0))))
    return surface(centre, normal, up_fit, residual), f"{len(hits)} hits, tilt {tilt:.1f}, residual {residual:.3f}"


def surface(centre, normal, up, residual):
    r = lambda v: [round(c, 4) for c in v]
    return {"center": r(centre), "normal": r(normal), "up": r(up), "residual": round(residual, 4)}


def bake(pack, vehicle, dry):
    path = pack / "combinedassault" / "combinedassault" / "vehicles" / f"{vehicle}.json"
    data = json.loads(path.read_text())
    entries = data.get("addon_armor")
    if not entries:
        print(f"{vehicle}: no addon_armor")
        return
    ns, model = data["model"].split(":", 1)
    gltf = convert_pack.Gltf(str(pack / "assets" / ns / model),
                             lambda rl: (pack / "assets" / rl.split(":")[0] / rl.split(":", 1)[1]).read_bytes())
    tris = Triangles(gltf)
    print(f"{vehicle}: {len(tris)} triangles")
    for entry in entries:
        frame = {k: entry[k] for k in ("kind", "center", "normal", "up", "width", "height") if k in entry}
        if "center" not in frame:
            frame = {**socket_frame(gltf, entry["id"]), **frame}
        result, note = fit(tris, {**entry, **frame})
        print(f"  {entry['id']:<24} {note}")
        if result is None:
            entry.pop("surface", None)
        else:
            entry["surface"] = result
    if not dry:
        path.write_text(json.dumps(data, indent=4))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("vehicles", nargs="*", default=["m1a2"])
    parser.add_argument("--pack", default="src/main/resources/combinedassault_packs/combinedassault")
    parser.add_argument("--dry", action="store_true")
    args = parser.parse_args()
    for vehicle in args.vehicles:
        bake(pathlib.Path(args.pack), vehicle, args.dry)


if __name__ == "__main__":
    sys.exit(main())
