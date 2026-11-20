#!/usr/bin/env python3
"""Control surface (rudder / elevator / stabilator / aileron / elevon) carved out of a carrier node, hinged on its own frame.

    python3 tools/aircraft_surfaces.py add  <vehicle> <name> --kind K --carrier NODE --hinge x0,y0,z0,x1,y1,z1
                                            [--aft -z] [--chord 99] [--thick T] [--side left|right] [--pitch P] [--roll R] [--yaw Y]
    python3 tools/aircraft_surfaces.py auto <vehicle> <kind> [--carrier NODE] [--box a0,a1,y0,y1,z0,z1] [--tz .25] [--frac F]
                                            [--span lo,hi] [--dry-run] [--pitch/--roll/--yaw]
    python3 tools/aircraft_surfaces.py remove <vehicle> <name>

Model frame: left = +x, nose +z, up +y; hinge A (root) -> B (tip). Surface = triangles with (p-A).a >= 0 (a = aft direction
perpendicular to the hinge) <= --chord, between the planes through A and B perpendicular to the hinge, |(p-A).u| <= thick (fin .3, tail .5, wing .8);
clipped, attributes interpolated, no caps. Tree: `<carrier>/<name>_mount` (x = hinge, z = a) > `<name>` (animated) >
`<name>_mesh`; mesh node = hit surface (follows the clip); carrier system kept. Clip `<name>` (= part id): t 0 / .5 / 1 =
-D / 0 / +D about local x, D = |pitch|+|roll|+|yaw|; +D = TE down (horizontal) / TE toward model right (-x) (vertical).
Vehicle json part `combinedassault:control_surface` {pitch, roll, yaw} (deg of part xRot per full input) written in place.
Re-run replaces (surface merged back into the carrier first). Sign table: docs/asset-tools.md.
"""
import argparse
import json
import math
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aircraft_hits as ah  # noqa: E402
import aircraft_flaps as af  # noqa: E402
import convert_pack  # noqa: E402
import def_sockets  # noqa: E402

# kind -> (pitch, roll, yaw) magnitudes; hinge fraction from the leading edge; carrier family
KINDS = {
    "elevator": ((20, 0, 0), 0.60, "tail"),
    "stabilator": ((20, 10, 0), 0.35, "tail"),
    "rudder": ((0, 0, 25), 0.60, "fin"),
    "aileron": ((0, 15, 0), 0.72, "wing"),
    "elevon": ((20, 20, 0), 0.80, "wing"),
}
# roll sign by side: right roll (+1) = right TE up = negative on the right (+xRot = TE down), positive on the left
ROLL_SIGN = {"left": 1.0, "right": -1.0}


def gains(kind, side, args):
    p, r, y = (a if a is not None else d for a, d in zip((args.pitch, args.roll, args.yaw), KINDS[kind][0]))
    return p, ROLL_SIGN.get(side, 1.0) * r + 0.0, y


def quat_from_matrix(m):
    t = m[0, 0] + m[1, 1] + m[2, 2]
    if t > 0:
        s = math.sqrt(t + 1.0) * 2
        q = [(m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s, 0.25 * s]
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = math.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        q = [0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s, (m[2, 1] - m[1, 2]) / s]
    elif m[1, 1] > m[2, 2]:
        s = math.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        q = [(m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s, (m[0, 2] - m[2, 0]) / s]
    else:
        s = math.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        q = [(m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s, (m[1, 0] - m[0, 1]) / s]
    return [float(v) for v in q]


def poly_area(poly):
    p = [v["POSITION"] for v in poly]
    return sum(np.linalg.norm(np.cross(p[k] - p[0], p[k + 1] - p[0])) for k in range(1, len(p) - 1)) / 2


def bucket_area(b):
    pos = np.array(b.verts["POSITION"]) if b.verts["POSITION"] else np.zeros((0, 3))
    return sum(np.linalg.norm(np.cross(pos[j] - pos[i], pos[k] - pos[i])) / 2 for i, j, k in b.tris)


def strip(g, name):
    """Surface merged back into its carrier, nodes + clip removed. Returns carrier node name or None."""
    mesh_i, anim_i, mount_i = (g.by_name(name + s) for s in ("_mesh", "", "_mount"))
    if mount_i is None:
        return None
    carrier = g.parent.get(mount_i)
    if mesh_i is not None and carrier is not None and "mesh" in g.nodes[mesh_i]:
        prims = g.doc["meshes"][g.nodes[carrier]["mesh"]]["primitives"]
        prims.extend(g.doc["meshes"][g.nodes[mesh_i]["mesh"]]["primitives"])
    anims = g.doc.get("animations", [])
    doomed = {i for i in (mesh_i, anim_i, mount_i) if i is not None}
    for clip in list(anims):
        keep = [c for c in clip["channels"] if c["target"]["node"] not in doomed]
        if clip.get("name") == name or not keep:
            anims.remove(clip)
        elif len(keep) != len(clip["channels"]):
            used = sorted({c["sampler"] for c in keep})
            clip["samplers"] = [clip["samplers"][s] for s in used]
            for c in keep:
                c["sampler"] = used.index(c["sampler"])
            clip["channels"] = keep
    if not anims:
        g.doc.pop("animations", None)
    for suffix in ("_mesh", "", "_mount"):
        g.remove_nodes([g.by_name(name + suffix)])
    return carrier


def json_path(vehicle):
    return def_sockets.defs()[vehicle if ":" in vehicle else "combinedassault:" + vehicle]


def write_part(vehicle, name, p, r, y):
    path = json_path(vehicle)
    text = open(path).read()
    p, r, y = (int(v) if float(v) == int(v) else float(v) for v in (p, r, y))
    part = {"id": name, "name": "part.combinedassault.control_surface", "type": "combinedassault:control_surface",
            "is_seat": False, "pitch": p, "roll": r, "yaw": y, "rot_info": {"x_rot_speed": 1}}
    line = json.dumps(part)
    pat = re.compile(r'^([ \t]*)\{"id": "%s",[^\n]*control_surface[^\n]*\}(,?)$' % re.escape(name), re.M)
    if pat.search(text):
        text = pat.sub(lambda m: m.group(1) + line + m.group(2), text, count=1)
    else:
        marker = '"parts": ['
        at = text.index(marker) + len(marker)
        text = text[:at] + "\n" + " " * 8 + line + "," + text[at:]
    json.loads(text)
    open(path, "w").write(text)


def overlaps(g, lo, hi, roles):
    out = []
    for i, n in enumerate(g.nodes):
        if "mesh" not in n or n.get("extras", {}).get(convert_pack.ROLE) not in roles:
            continue
        P = def_sockets.positions(g, n["mesh"])
        W = g.world(i)
        P = P @ W[:3, :3].T + W[:3, 3]
        if (P.max(0) >= lo).all() and (P.min(0) <= hi).all():
            out.append("%s(%s)" % (n.get("name"), n["extras"][convert_pack.ROLE]))
    return out


def cmd_add(args):
    g, model = ah.load(args.vehicle)
    name, kind = args.name, args.kind
    prev = strip(g, name)
    carrier_name = args.carrier or prev
    carrier = g.by_name(carrier_name) if carrier_name else None
    if carrier is None or "mesh" not in g.nodes[carrier]:
        sys.exit("no drawn carrier node %s" % carrier_name)
    mesh_i = g.nodes[carrier]["mesh"]
    if sum(1 for n in g.nodes if n.get("mesh") == mesh_i) > 1:
        sys.exit("carrier %s shares its mesh" % carrier_name)
    W = g.world(carrier)
    R, t = W[:3, :3], W[:3, 3]
    if not np.allclose(R.T @ R, np.eye(3), atol=1e-5) or np.linalg.det(R) < 0:
        sys.exit("carrier world frame is scaled / mirrored")
    vals = [float(v) for v in args.hinge.split(",")]
    A, B = np.array(vals[:3]), np.array(vals[3:])
    h = (B - A) / np.linalg.norm(B - A)
    aft = np.array([0.0, 0.0, -1.0 if args.aft == "-z" else 1.0])
    a = aft - np.dot(aft, h) * h
    a /= np.linalg.norm(a)
    u = np.cross(a, h)
    side = args.side or ("left" if (A[0] + B[0]) > 0 else "right")
    thick = args.thick or {"fin": 0.3, "tail": 0.5, "wing": 0.8}[KINDS[kind][2]]
    d_a = float(np.dot(a, A))
    planes = [(a, d_a), (-a, -(d_a + args.chord)), (h, float(np.dot(h, A))), (-h, -float(np.dot(h, B))),
              (u, float(np.dot(u, A)) - thick), (-u, -float(np.dot(u, A)) - thick)]
    sb, cb = [], []
    area0 = area1 = 0.0
    n_surf = n_cut = 0
    lo, hi = np.full(3, np.inf), np.full(3, -np.inf)
    for prim in g.doc["meshes"][mesh_i]["primitives"]:
        attrs, tris = ah.read_primitive(g, prim)
        attrs["POSITION"] = attrs["POSITION"] @ R.T + t
        attrs["NORMAL"] = attrs["NORMAL"] @ R.T
        s, c = ah.Bucket(), ah.Bucket()
        for tri in tris:
            poly = ah.vertex_rows(attrs, tri)
            area0 += poly_area(poly)
            pieces, inside = [], poly
            for n, d in planes:
                inside, outside = af.clip_plane(inside, n, d)
                if len(outside) >= 3:
                    pieces.append(outside)
                if len(inside) < 3:
                    inside = []
                    break
            if inside:
                s.add_poly(inside)
                n_surf += 1
                n_cut += bool(pieces)
                for v in inside:
                    lo, hi = np.minimum(lo, v["POSITION"]), np.maximum(hi, v["POSITION"])
            for p in pieces:
                c.add_poly(p)
        for b in (s, c):
            if b.verts["POSITION"]:
                b.verts["POSITION"] = list((np.array(b.verts["POSITION"]) - t) @ R)
                b.verts["NORMAL"] = list(np.array(b.verts["NORMAL"]) @ R)
        area1 += bucket_area(s) + bucket_area(c)
        sb.append((s, prim.get("material")))
        cb.append((c, prim.get("material")))
    if n_surf == 0:
        sys.exit("no triangles inside the surface region")
    surf_mesh = ah.emit(g, name, sb)
    g.nodes[carrier]["mesh"] = ah.emit(g, g.nodes[carrier]["name"], cb)
    # +phi about local x moves the trailing edge (local +z) toward -u
    target = np.array([0.0, -1.0, 0.0]) if abs(h[1]) < 0.5 else np.array([-1.0, 0.0, 0.0])
    sign = 1.0 if np.dot(-u, target) > 0 else -1.0
    frame_w = np.column_stack([h, u, a])
    q_world_to_local = R.T @ frame_w
    mount = {"name": name + "_mount", "translation": [float(v) for v in R.T @ (A - t)],
             "rotation": quat_from_matrix(q_world_to_local)}
    mount_i = g.add_node(mount, carrier)
    anim_i = g.add_node({"name": name}, mount_i)
    mount_local = np.eye(4)
    mount_local[:3, :3] = q_world_to_local
    mount_local[:3, 3] = mount["translation"]
    inverse = np.linalg.inv(mount_local)
    g.add_node({"name": name + "_mesh", "mesh": surf_mesh,
                "matrix": [float(round(v, 7)) for v in inverse.T.reshape(-1)]}, anim_i)
    if args.system:
        data = {"system": args.system}
        if args.side:
            data["side"] = args.side
        g.nodes[mount_i].setdefault("extras", {}).setdefault(convert_pack.DATA, {}).update(data)
    p, r, y = gains(kind, side, args)
    full = math.radians(abs(p) + abs(r) + abs(y))
    anims = g.doc.setdefault("animations", [])
    times = ah.make_accessor_scalar(g, [0.0, 0.5, 1.0])
    quats = np.array([af.quat_axis_x(-sign * full), [0, 0, 0, 1], af.quat_axis_x(sign * full)], dtype=np.float32)
    out = ah.make_accessor(g, quats, 5126, "VEC4", None)
    anims.append({"name": name, "samplers": [{"input": times, "output": out, "interpolation": "LINEAR"}],
                  "channels": [{"sampler": 0, "target": {"node": anim_i, "path": "rotation"}}]})
    g.compact()
    ah.save(model)
    write_part(args.vehicle, name, p, r, y)
    print("%s: %s %s on %s: %d tris (%d cut), area %.4f -> %.4f, hinge %.2f m, +D=%.0f deg x%+d, gains pitch %g roll %g yaw %g" % (
        args.vehicle, kind, name, carrier_name, n_surf, n_cut, area0, area1, np.linalg.norm(B - A), math.degrees(full),
        int(sign), p, r, y))
    box_lo, box_hi = lo - 0.05, hi + 0.05
    hits = overlaps(g, box_lo, box_hi, ("hit", "collider"))
    if hits:
        print("  hit modules / colliders under the footprint: %s" % ", ".join(hits))
    for i, n in enumerate(g.nodes):
        if n.get("extras", {}).get(convert_pack.ROLE) == "hit_surface" and g.parent.get(i) == carrier:
            print("  carrier has hit_surface override %s: surface mesh is not hit geometry" % n.get("name"))


# ------------------------------------------------------------------ auto

def world_tris(g, node):
    W = g.world(node)
    T = def_sockets.triangles(g, g.nodes[node]["mesh"]).reshape(-1, 3, 3)
    return T @ W[:3, :3].T + W[:3, 3]


def slice_z(T, axis, centre, half):
    """(zmin, zmax, mean x, median y) of triangle edges crossing the plane coordinate[axis] = centre."""
    pts = []
    for tri in T:
        for k in range(3):
            p, q = tri[k], tri[(k + 1) % 3]
            if (p[axis] - centre) * (q[axis] - centre) < 0:
                pts.append(p + (q - p) * ((centre - p[axis]) / (q[axis] - p[axis])))
    if len(pts) < 2:
        return None
    S = np.array(pts)
    return S[:, 2].min(), S[:, 2].max(), S[:, 0].mean(), float(np.median(S[:, 1]))


def hinge_fin(T, frac):
    V = T.reshape(-1, 3)
    y0, y1 = V[:, 1].min(), V[:, 1].max()
    H = y1 - y0
    pts = []
    for yk in (y0 + 0.1 * H, y1 - 0.1 * H):
        z0, z1, x, _ = slice_z(T, 1, yk, 0)
        pts.append(np.array([x, yk, z1 - frac * (z1 - z0)]))
    d = (pts[1] - pts[0]) / (pts[1][1] - pts[0][1])
    return pts[0] + d * (y0 - 0.02 * H - pts[0][1]), pts[0] + d * (y1 + 0.03 * H - pts[0][1])


def hinge_flat(T, frac, x0, x1):
    W = x1 - x0
    pts = []
    for xk in (x0 + 0.1 * W, x1 - 0.1 * W):
        z0, z1, _, y = slice_z(T, 0, xk, 0)
        pts.append(np.array([xk, y, z1 - frac * (z1 - z0)]))
    d = (pts[1] - pts[0]) / (pts[1][0] - pts[0][0])
    return pts[0] + d * (x0 - 0.02 * W - pts[0][0]), pts[0] + d * (x1 + 0.03 * W - pts[0][0])


def propose(g, kind, args):
    """[(side, name, carrier, A, B, extra)] from the plan.py rules."""
    frac = args.frac if args.frac is not None else KINDS[kind][1]
    family = KINDS[kind][2]
    out = []
    if family == "wing":
        carriers = [n.get("name") for i, n in enumerate(g.nodes) if "mesh" in n and
                    n.get("extras", {}).get(convert_pack.DATA, {}).get("system") == "wing"]
        carriers = [args.carrier] if args.carrier else (carriers or ["body"])
    else:
        carriers = [args.carrier or "body"]
    zones = {}
    for cn in carriers:
        ci = g.by_name(cn)
        T = world_tris(g, ci)
        ctr = T.mean(1)
        nm = np.cross(T[:, 1] - T[:, 0], T[:, 2] - T[:, 0])
        nm /= np.linalg.norm(nm, axis=1)[:, None] + 1e-12
        ax = np.abs(ctr[:, 0])
        xr = None
        allv = world_tris(g, g.by_name("body")).reshape(-1, 3)
        z0, z1, hs = allv[:, 2].min(), allv[:, 2].max(), np.abs(allv[:, 0]).max()
        tail = ctr[:, 2] < z0 + args.tz * (z1 - z0)
        if args.box:
            a0, a1, y0, y1, zA, zB = args.box
            sel = (ax >= a0) & (ax <= a1) & (ctr[:, 1] >= y0) & (ctr[:, 1] <= y1) & (ctr[:, 2] >= zA) & (ctr[:, 2] <= zB)
            sel &= (np.abs(nm[:, 1]) < 0.7) if family == "fin" else (np.abs(nm[:, 1]) > 0.7)
        elif family == "fin":
            flat = tail & (np.abs(nm[:, 1]) > 0.7) & (ax > 0.7)
            ydeck = np.median(ctr[flat, 1]) if flat.any() else ctr[:, 1].min()
            sel = tail & (np.abs(nm[:, 1]) < 0.55) & (ctr[:, 1] > ydeck + 0.25) & (ax < hs * 0.6)
        elif family == "tail":
            sel = tail & (np.abs(nm[:, 1]) > 0.7) & (ax > 0.7)
        else:
            lo, hi = args.span or ((0.55, 0.95) if kind == "aileron" else (0.2, 0.95))
            floor = 0.0
            for j, n in enumerate(g.nodes):
                up = g.parent.get(j)
                while up is not None and g.nodes[up].get("name") != cn:
                    up = g.parent.get(up)
                if up is not None and "mesh" in n:
                    floor = max(floor, np.abs(world_tris(g, j)[:, :, 0]).max())
            hs = max(hs, np.abs(T[:, :, 0]).max())
            xr = (max(lo * hs, floor), hi * hs)
            sel = (np.abs(nm[:, 1]) > 0.7) & (ax > xr[0] * 0.5)
        if not sel.any():
            continue
        zones[cn] = (T[sel], np.median(ax[sel]), xr if family == "wing" else None)
    for cn, (S, med, xr) in zones.items():
        groups = {"": S} if (KINDS[kind][2] == "fin" and med < 0.4) else \
            {"left": S[S.mean(1)[:, 0] > 0], "right": S[S.mean(1)[:, 0] < 0]}
        for side, G in groups.items():
            if len(G) < 2:
                continue
            if family == "fin":
                A, B = hinge_fin(G, frac)
            else:
                xs = np.abs(G[:, :, 0])
                mirror = np.array([-1.0 if side == "right" else 1.0, 1.0, 1.0])
                A, B = hinge_flat(G * mirror, frac, *(xr or (xs.min(), xs.max())))
                A, B = A * mirror, B * mirror
            out.append((side or "centre", cn, A, B))
    return out


def cmd_auto(args):
    g, _ = ah.load(args.vehicle)
    found = propose(g, args.kind, args)
    if not found:
        sys.exit("nothing selected")
    cmds = []
    for side, cn, A, B in found:
        name = (side + "_" if side != "centre" else "") + args.kind
        if len(found) > 1 and sum(1 for f in found if f[0] == side) > 1:
            name += "_" + cn
        chord = 99.0 if args.kind in ("stabilator", "elevator") else (args.chord or 99.0)
        hinge = ",".join("%.3f" % v for v in list(A) + list(B))
        cmds.append((name, cn, hinge, chord, side))
        print("%s: carrier %s hinge %s chord %g" % (name, cn, hinge, chord))
    if args.dry_run:
        return
    for name, cn, hinge, chord, side in cmds:
        ns = argparse.Namespace(vehicle=args.vehicle, name=name, kind=args.kind, carrier=cn, hinge=hinge, aft=args.aft,
                                chord=chord, thick=args.thick, side=side if side != "centre" else None, pitch=args.pitch,
                                roll=args.roll, yaw=args.yaw, system=args.system)
        cmd_add(ns)


def cmd_remove(args):
    g, model = ah.load(args.vehicle)
    if strip(g, args.name) is None:
        sys.exit("no surface %s" % args.name)
    g.compact()
    ah.save(model)
    path = json_path(args.vehicle)
    text = open(path).read()
    text = re.sub(r'^[ \t]*\{"id": "%s",[^\n]*control_surface[^\n]*\},?\n' % re.escape(args.name), "", text, flags=re.M)
    json.loads(text)
    open(path, "w").write(text)


def common(p):
    p.add_argument("--aft", choices=["-z", "+z"], default="-z")
    p.add_argument("--thick", type=float, help="half thickness of the slab about the hinge plane (default: fin 0.3, tail 0.5, wing 0.8)")
    p.add_argument("--pitch", type=float)
    p.add_argument("--roll", type=float)
    p.add_argument("--yaw", type=float)
    p.add_argument("--system", help="system tag on the mount frame (e.g. wing for a body-carved surface); default: carrier's")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("add")
    a.add_argument("vehicle")
    a.add_argument("name")
    a.add_argument("--kind", choices=list(KINDS), required=True)
    a.add_argument("--carrier")
    a.add_argument("--hinge", required=True)
    a.add_argument("--chord", type=float, default=99.0)
    a.add_argument("--side", choices=["left", "right"], help="roll-sign side (default: sign of hinge x)")
    common(a)
    a.set_defaults(fn=cmd_add)
    u = sub.add_parser("auto")
    u.add_argument("vehicle")
    u.add_argument("kind", choices=list(KINDS))
    u.add_argument("--carrier")
    u.add_argument("--box", type=lambda s: [float(v) for v in s.split(",")], help="|x|0,|x|1,y0,y1,z0,z1 selection of triangle centroids")
    u.add_argument("--tz", type=float, default=0.25, help="tail zone fraction of body length")
    u.add_argument("--frac", type=float, help="hinge fraction of chord from the leading edge")
    u.add_argument("--span", type=lambda s: [float(v) for v in s.split(",")], help="wing kinds: |x| fractions of half span")
    u.add_argument("--chord", type=float)
    u.add_argument("--dry-run", action="store_true")
    common(u)
    u.set_defaults(fn=cmd_auto)
    r = sub.add_parser("remove")
    r.add_argument("vehicle")
    r.add_argument("name")
    r.set_defaults(fn=cmd_remove)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
