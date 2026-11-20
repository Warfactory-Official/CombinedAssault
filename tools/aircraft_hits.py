#!/usr/bin/env python3
"""Aircraft hit geometry: wing split out of a fused hull mesh, hit modules (engine / fuel / ...).

    python3 tools/aircraft_hits.py wings <vehicle> --cut X [--node body] [--cut-right X] [--name wing] [--no-cap] [--zrange ZLO ZHI] [--ymax Y]
    python3 tools/aircraft_hits.py hit <vehicle> NAME --system engine --box x0,y0,z0,x1,y1,z1 [--side left|right]
                                       [--unit U] [--health N] [--rha MM] [--parent NODE]
    python3 tools/aircraft_hits.py info <vehicle>

Model frame (parts at rest, world of the body node = identity); left = +x. Plates: `def_sockets.py plates`.
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import convert_pack  # noqa: E402
import def_sockets  # noqa: E402

ATTRS = {"POSITION": 3, "NORMAL": 3, "TEXCOORD_0": 2}
DTYPE = {5126: "<f4", 5125: "<u4", 5123: "<u2", 5121: "<u1"}
WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}


def load(vehicle):
    jsons = def_sockets.defs()
    vehicle = vehicle if ":" in vehicle else "combinedassault:" + vehicle
    if vehicle not in jsons:
        sys.exit("unknown vehicle %s" % vehicle)
    model = def_sockets.Model(json.load(open(jsons[vehicle]))["model"])
    return model.gltf, model


def save(model):
    model.gltf.save(model.path, lambda r, data: open(def_sockets.asset(r), "wb").write(data))


def body_node(g):
    for i, n in enumerate(g.nodes):
        if n.get("extras", {}).get(convert_pack.ROLE) == "body":
            return i
    sys.exit("no body node")


def read_primitive(g, prim):
    out = {}
    for name, width in ATTRS.items():
        if name in prim["attributes"]:
            out[name] = def_sockets.accessor_array(g, prim["attributes"][name])
    extra = set(prim["attributes"]) - set(ATTRS)
    if extra:
        sys.exit("unsupported primitive attributes %s" % sorted(extra))
    count = len(out["POSITION"])
    idx = def_sockets.primitive_indices(g, prim, count)
    return out, idx[: len(idx) // 3 * 3].reshape(-1, 3)


def clip_polygon(poly, axis_sign, c):
    """Sutherland-Hodgman against x*axis_sign >= c; poly = list of attribute dicts. Returns (clipped, cut points)."""
    out = []
    cut = []
    for k in range(len(poly)):
        a, b = poly[k], poly[(k + 1) % len(poly)]
        da = a["POSITION"][0] * axis_sign - c
        db = b["POSITION"][0] * axis_sign - c
        if da >= 0:
            out.append(a)
        if (da >= 0) != (db >= 0) and da != db:
            t = da / (da - db)
            v = {key: a[key] + (b[key] - a[key]) * t for key in a}
            v["POSITION"] = v["POSITION"].copy()
            v["POSITION"][0] = c * axis_sign
            out.append(v)
            cut.append(v)
    return out, cut


class Bucket:
    def __init__(self):
        self.verts = {name: [] for name in ATTRS}
        self.tris = []

    def add_poly(self, poly):
        if len(poly) < 3:
            return
        base = len(self.verts["POSITION"])
        for v in poly:
            for name in ATTRS:
                self.verts[name].append(v[name])
        for k in range(1, len(poly) - 1):
            self.tris.append((base, base + k, base + k + 1))


def vertex_rows(attrs, tri):
    return [{name: attrs[name][i].copy() for name in attrs} for i in tri]


def hull_loop(segments):
    """Convex hull (monotone chain over y, z) of the cut points."""
    pts = sorted({k for seg in segments for k in seg}, key=lambda k: (k[2], k[1]))

    def cross(o, a, b):
        return (a[2] - o[2]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[2] - o[2])

    lo, up = [], []
    for k in pts:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], k) <= 0:
            lo.pop()
        lo.append(k)
    for k in reversed(pts):
        while len(up) >= 2 and cross(up[-2], up[-1], k) <= 0:
            up.pop()
        up.append(k)
    return lo[:-1] + up[:-1]


def ear_clip(points):
    """Indices of triangles over a simple 2D loop (ccw or cw), None on failure."""
    n = len(points)
    if n < 3:
        return None
    area = sum(points[i][0] * points[(i + 1) % n][1] - points[(i + 1) % n][0] * points[i][1] for i in range(n))
    order = list(range(n)) if area > 0 else list(range(n))[::-1]
    out = []
    guard = 0
    while len(order) > 3 and guard < 10 * n:
        guard += 1
        for k in range(len(order)):
            i0, i1, i2 = order[k - 1], order[k], order[(k + 1) % len(order)]
            a, b, c = points[i0], points[i1], points[i2]
            if (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]) <= 1e-12:
                continue
            inside = False
            for j in order:
                if j in (i0, i1, i2):
                    continue
                p = points[j]
                d1 = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
                d2 = (c[0] - b[0]) * (p[1] - b[1]) - (c[1] - b[1]) * (p[0] - b[0])
                d3 = (a[0] - c[0]) * (p[1] - c[1]) - (a[1] - c[1]) * (p[0] - c[0])
                if d1 >= 0 and d2 >= 0 and d3 >= 0:
                    inside = True
                    break
            if not inside:
                out.append((i0, i1, i2))
                order.pop(k)
                break
        else:
            return None
    if len(order) == 3:
        out.append(tuple(order))
    return out


def loops(segments):
    """Chain segments (pair of keys) into closed loops of keys."""
    nxt = {}
    for a, b in segments:
        nxt.setdefault(a, []).append(b)
        nxt.setdefault(b, []).append(a)
    seen = set()
    result = []
    for start in nxt:
        if start in seen:
            continue
        loop = [start]
        seen.add(start)
        prev, cur = None, start
        while True:
            options = [v for v in nxt[cur] if v != prev and v not in seen]
            if not options:
                break
            prev, cur = cur, options[0]
            loop.append(cur)
            seen.add(cur)
        if len(loop) >= 3 and start in nxt[cur]:
            result.append(loop)
    return result


def make_accessor(g, array, componentType, kind, target):
    buf = g.buffers[0]
    while len(buf) % 4:
        buf.append(0)
    offset = len(buf)
    data = np.asarray(array, dtype=DTYPE[componentType]).tobytes()
    buf += data
    views = g.doc.setdefault("bufferViews", [])
    view = {"buffer": 0, "byteOffset": offset, "byteLength": len(data)}
    if target is not None:
        view["target"] = target
    views.append(view)
    acc = {"bufferView": len(views) - 1, "componentType": componentType, "count": len(array), "type": kind}
    if kind == "VEC3" and componentType == 5126 and len(array):
        acc["min"] = [float(v) for v in np.min(array, axis=0)]
        acc["max"] = [float(v) for v in np.max(array, axis=0)]
    g.doc.setdefault("accessors", []).append(acc)
    return len(g.doc["accessors"]) - 1


def make_accessor_scalar(g, values):
    """Float SCALAR accessor with min / max (animation input)."""
    index = make_accessor(g, np.array(values, dtype=np.float32), 5126, "SCALAR", None)
    g.doc["accessors"][index]["min"] = [float(min(values))]
    g.doc["accessors"][index]["max"] = [float(max(values))]
    return index


def emit(g, name, prims):
    """prims: list of (Bucket, material). Returns mesh index (None when empty)."""
    out = []
    for bucket, material in prims:
        if not bucket.tris:
            continue
        keys = [k for k in ATTRS if bucket.verts[k]]
        rows = np.concatenate([np.array(bucket.verts[k], dtype=np.float32).reshape(len(bucket.verts[k]), -1) for k in keys],
                              axis=1)
        unique, inverse = np.unique(rows, axis=0, return_inverse=True)
        inverse = inverse.reshape(-1)
        attrs, at = {}, 0
        for key in keys:
            width = ATTRS[key]
            attrs[key] = make_accessor(g, unique[:, at:at + width], 5126, "VEC%d" % width, 34962)
            at += width
        tris = inverse[np.array(bucket.tris, dtype=np.int64).reshape(-1)].astype(np.uint32)
        prim = {"attributes": attrs, "indices": make_accessor(g, tris, 5125, "SCALAR", 34963), "mode": 4}
        if material is not None:
            prim["material"] = material
        out.append(prim)
    if not out:
        return None
    g.doc.setdefault("meshes", []).append({"name": name, "primitives": out})
    return len(g.doc["meshes"]) - 1


def cmd_wings(args):
    g, model = load(args.vehicle)
    hits = [i for i, n in enumerate(g.nodes) if n.get("name") == args.node and "mesh" in n]
    if len(hits) != 1:
        sys.exit("node %s: %d mesh nodes" % (args.node, len(hits)))
    node = hits[0]
    if sum(1 for n in g.nodes if n.get("mesh") == g.nodes[node]["mesh"]) > 1:
        sys.exit("node %s shares its mesh" % args.node)
    names = {n.get("name") for n in g.nodes}
    for side in ("left", "right"):
        if "%s_%s" % (args.name, side) in names:
            sys.exit("%s_%s exists" % (args.name, side))
    world = g.world(node)
    if not np.allclose(world[:3, :3], np.eye(3), atol=1e-6):
        sys.exit("node %s has a rotated / scaled world frame; split needs identity" % args.node)
    shift = world[0, 3]
    cut_l = args.cut
    cut_r = args.cut_right if args.cut_right is not None else args.cut
    mesh = g.nodes[node]["mesh"]
    prims = g.doc["meshes"][mesh]["primitives"]
    center, left, right = [], [], []
    stats = {"tris": 0, "cut": 0}
    caps = {"left": [], "right": []}
    for prim in prims:
        if prim.get("mode", 4) != 4:
            sys.exit("non-triangle primitive")
        attrs, tris = read_primitive(g, prim)
        attrs["POSITION"] = attrs["POSITION"].copy()
        attrs["POSITION"][:, 0] += shift
        c, l, r = Bucket(), Bucket(), Bucket()
        for tri in tris:
            stats["tris"] += 1
            poly = vertex_rows(attrs, tri)
            xs = [v["POSITION"][0] for v in poly]
            zc = sum(v["POSITION"][2] for v in poly) / 3
            ymax = max(v["POSITION"][1] for v in poly)
            if (args.zrange and not args.zrange[0] <= zc <= args.zrange[1]) or (args.ymax is not None and ymax > args.ymax):
                c.add_poly(poly)
                continue
            # +x side beyond cut_l = left wing; -x side beyond cut_r = right wing.
            if min(xs) >= cut_l:
                l.add_poly(poly)
                continue
            if max(xs) <= -cut_r:
                r.add_poly(poly)
                continue
            if max(xs) <= cut_l and min(xs) >= -cut_r:
                c.add_poly(poly)
                continue
            stats["cut"] += 1
            rest = poly
            part_l, cut1 = clip_polygon(rest, 1, cut_l)
            rest, _ = clip_polygon(rest, -1, -cut_l)
            part_r, cut2 = clip_polygon(rest, -1, cut_r)
            rest, _ = clip_polygon(rest, 1, -cut_r)
            l.add_poly(part_l)
            r.add_poly(part_r)
            c.add_poly(rest)
            caps["left"].append((cut1, prim.get("material")))
            caps["right"].append((cut2, prim.get("material")))
        for v in (c, l, r):
            for key in ("POSITION",):
                if v.verts[key]:
                    arr = np.array(v.verts[key])
                    arr[:, 0] -= shift
                    v.verts[key] = list(arr)
        center.append((c, prim.get("material")))
        left.append((l, prim.get("material")))
        right.append((r, prim.get("material")))
    cap_tris = 0
    if not args.no_cap:
        for side, plane, outward_body, outward_wing in (("left", cut_l, 1, -1), ("right", -cut_r, -1, 1)):
            segments = []
            uv_of = {}
            material = None
            for cut, mat in caps[side]:
                cut = [v for v in cut if v is not None]
                if len(cut) == 2:
                    ka = tuple(np.round(cut[0]["POSITION"], 4))
                    kb = tuple(np.round(cut[1]["POSITION"], 4))
                    if ka != kb:
                        segments.append((ka, kb))
                        uv_of[ka] = cut[0].get("TEXCOORD_0", np.zeros(2))
                        uv_of[kb] = cut[1].get("TEXCOORD_0", np.zeros(2))
                        material = mat if material is None else material
            found = loops(segments)
            if not found and segments:
                found = [hull_loop(segments)]
                print("  %s cap: convex hull of %d points (cut loop did not close)" % (side, len(found[0])))
            for loop in found:
                pts2 = [(k[1], k[2]) for k in loop]
                tris = ear_clip(pts2)
                if tris is None:
                    print("  warning: %s cap loop of %d points could not be triangulated" % (side, len(loop)))
                    continue
                for dest, sign, buckets in ((center, outward_body, center), (left if side == "left" else right,
                                                                              outward_wing,
                                                                              left if side == "left" else right)):
                    bucket = dest[0][0]
                    base = len(bucket.verts["POSITION"])
                    for k in loop:
                        p = np.array(k, dtype=np.float32)
                        p[0] -= shift
                        bucket.verts["POSITION"].append(p)
                        bucket.verts["NORMAL"].append(np.array([sign, 0, 0], dtype=np.float32))
                        bucket.verts["TEXCOORD_0"].append(np.asarray(uv_of[k], dtype=np.float32))
                    for i0, i1, i2 in tris:
                        a, b, c2 = loop[i0], loop[i1], loop[i2]
                        n = np.cross(np.array(b) - np.array(a), np.array(c2) - np.array(a))
                        order = (i0, i1, i2) if n[0] * sign > 0 else (i0, i2, i1)
                        bucket.tris.append(tuple(base + o for o in order))
                        cap_tris += 1
    # Cap lives in the first primitive's bucket; its material is the first primitive's, uv from the nearest cut vertex.
    parent = g.parent.get(node)
    mesh_c = emit(g, args.node, center)
    mesh_l = emit(g, "%s_left" % args.name, left)
    mesh_r = emit(g, "%s_right" % args.name, right)
    if mesh_l is None or mesh_r is None:
        sys.exit("cut %.2f leaves a wing empty" % args.cut)
    g.nodes[node]["mesh"] = mesh_c
    for side, mesh_i in (("left", mesh_l), ("right", mesh_r)):
        new = {"name": "%s_%s" % (args.name, side), "mesh": mesh_i,
               "extras": {convert_pack.DATA: {"system": "wing", "side": side}}}
        for key in ("matrix", "translation", "rotation", "scale"):
            if key in g.nodes[node]:
                new[key] = g.nodes[node][key]
        g.add_node(new, parent)
    save(model)
    print("%s: %d tris, %d cut, %d cap tris; %s_left / %s_right added" % (args.vehicle, stats["tris"], stats["cut"], cap_tris,
                                                                           args.name, args.name))


def box_mesh(lo, hi):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    p = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    f = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (1, 2, 6, 5), (3, 0, 4, 7)]
    idx = []
    for a, b, c, d in f:
        idx += [a, b, c, a, c, d]
    return [v for q in p for v in q], idx


def cmd_hit(args):
    g, model = load(args.vehicle)
    if g.by_name(args.name) is not None:
        sys.exit("node %s exists" % args.name)
    vals = [float(v) for v in args.box.split(",")]
    if len(vals) != 6:
        sys.exit("--box x0,y0,z0,x1,y1,z1")
    lo, hi = np.minimum(vals[:3], vals[3:]), np.maximum(vals[:3], vals[3:])
    body = body_node(g)
    parent = body
    if args.parent:
        parent = g.by_name(args.parent)
        if parent is None:
            sys.exit("no node %s" % args.parent)
    local = np.linalg.inv(g.world(parent))
    corners = [np.array([x, y, z, 1.0]) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
    pts = np.array([(local @ c)[:3] for c in corners])
    positions, indices = box_mesh(pts.min(0), pts.max(0))
    mesh = g.add_mesh(args.name, positions, indices)
    data = {"system": args.system}
    if args.side:
        data["side"] = args.side
    if args.unit:
        data["unit"] = args.unit
    if args.health:
        data["health"] = args.health
    if args.rha is not None:
        data["rha"] = args.rha
    node = {"name": args.name, "mesh": mesh, "extras": {convert_pack.HIDDEN: True}}
    convert_pack.set_role(node, "hit", **data)
    g.add_node(node, parent)
    save(model)
    print("%s: %s %s" % (args.vehicle, args.name, data))


def cmd_info(args):
    g, model = load(args.vehicle)
    for i, n in enumerate(g.nodes):
        extras = n.get("extras", {})
        data = extras.get(convert_pack.DATA, {})
        role = extras.get(convert_pack.ROLE)
        if "mesh" not in n or role == "collider":
            continue
        P = def_sockets.positions(g, n["mesh"])
        W = g.world(i)
        P = (W[:3, :3] @ P.T).T + W[:3, 3]
        print("%-28s %-8s v=%-5d x[%.2f %.2f] y[%.2f %.2f] z[%.2f %.2f] %s" % (
            n.get("name"), role or "", len(P), P[:, 0].min(), P[:, 0].max(), P[:, 1].min(), P[:, 1].max(),
            P[:, 2].min(), P[:, 2].max(), json.dumps(data) if data else ""))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    w = sub.add_parser("wings")
    w.add_argument("vehicle")
    w.add_argument("--node", default="body")
    w.add_argument("--cut", type=float, required=True)
    w.add_argument("--cut-right", type=float)
    w.add_argument("--name", default="wing")
    w.add_argument("--no-cap", action="store_true")
    w.add_argument("--ymax", type=float, help="triangles reaching above this y stay on the body (tail fins)")
    w.add_argument("--zrange", type=float, nargs=2, metavar=("ZLO", "ZHI"),
                   help="triangles whose centroid z is outside stay on the body (tail surfaces)")
    w.set_defaults(fn=cmd_wings)
    h = sub.add_parser("hit")
    h.add_argument("vehicle")
    h.add_argument("name")
    h.add_argument("--system", required=True)
    h.add_argument("--box", required=True)
    h.add_argument("--side", choices=["left", "right"])
    h.add_argument("--unit")
    h.add_argument("--health", type=float)
    h.add_argument("--rha", type=float)
    h.add_argument("--parent", help="node the module hangs under (default body); under a wing node => dies with that wing")
    h.set_defaults(fn=cmd_hit)
    i = sub.add_parser("info")
    i.add_argument("vehicle")
    i.set_defaults(fn=cmd_info)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
