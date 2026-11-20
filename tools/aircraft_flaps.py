#!/usr/bin/env python3
"""Flap surface carved out of a wing node, hinged on its own frame, with the `flaps` part clip.

    python3 tools/aircraft_flaps.py flap <vehicle> --wing wing_left --hinge x0,y0,z0,x1,y1,z1 [--name left_flap]
                                       [--deflection 30] [--aft -z] [--chord 3.0] [--ymin Y] [--ymax Y]

Model frame (parts at rest, wing node world = identity), left = +x. Hinge A -> B inboard -> outboard; flap = triangles
aft of the hinge line (<= --chord behind it), between A and B along the hinge, clipped on the planes. Creates
`<wing>/<name>_mount` (hinge frame) > `<name>` (animated) > `<name>_mesh`; appends rotation channels to the glTF
animation `flaps` (end key = --deflection, trailing edge DOWN; deepest notch). Vehicle json part: `combinedassault:flaps`
(see vehicles/mq9.json). Flap carries the wing's system => sheds with the wing. No caps (hinge gap hidden by overlap).
"""
import argparse
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aircraft_hits as ah  # noqa: E402
import convert_pack  # noqa: E402


def clip_plane(poly, n, d):
    """(inside n.p >= d, outside) polygons, attributes interpolated."""
    inside, outside = [], []
    for k in range(len(poly)):
        a, b = poly[k], poly[(k + 1) % len(poly)]
        da = float(np.dot(n, a["POSITION"])) - d
        db = float(np.dot(n, b["POSITION"])) - d
        (inside if da >= 0 else outside).append(a)
        if (da >= 0) != (db >= 0) and da != db:
            t = da / (da - db)
            v = {key: a[key] + (b[key] - a[key]) * t for key in a}
            inside.append(v)
            outside.append({key: val.copy() for key, val in v.items()})
    return inside, outside


def quat_axis_x(angle):
    return [math.sin(angle / 2), 0.0, 0.0, math.cos(angle / 2)]


def quat_y(angle):
    return [0.0, math.sin(angle / 2), 0.0, math.cos(angle / 2)]


def add_clip(g, node_index, end_quat):
    doc = g.doc
    anims = doc.setdefault("animations", [])
    clip = next((a for a in anims if a.get("name") == "flaps"), None)
    if clip is None:
        times = ah.make_accessor_scalar(g, [0.0, 1.0])
        clip = {"name": "flaps", "samplers": [], "channels": [], "_times": times}
        anims.append(clip)
    times = clip.pop("_times", None)
    if times is None:
        times = clip["samplers"][0]["input"]
    out = ah.make_accessor(g, np.array([[0, 0, 0, 1], end_quat], dtype=np.float32), 5126, "VEC4", None)
    clip["samplers"].append({"input": times, "output": out, "interpolation": "LINEAR"})
    clip["channels"].append({"sampler": len(clip["samplers"]) - 1, "target": {"node": node_index, "path": "rotation"}})


def cmd_flap(args):
    g, model = ah.load(args.vehicle)
    wing = g.by_name(args.wing)
    if wing is None or "mesh" not in g.nodes[wing]:
        sys.exit("no drawn wing node %s" % args.wing)
    names = {n.get("name") for n in g.nodes}
    for suffix in ("", "_mount", "_mesh"):
        if args.name + suffix in names:
            sys.exit("%s exists" % (args.name + suffix))
    world = g.world(wing)
    if not np.allclose(world[:3, :3], np.eye(3), atol=1e-6):
        sys.exit("wing node world frame is rotated")
    shift = world[:3, 3]
    vals = [float(v) for v in args.hinge.split(",")]
    A, B = np.array(vals[:3]), np.array(vals[3:])
    h = B - A
    h[1] = 0.0
    length = float(np.linalg.norm(h))
    h /= length
    aft_sign = -1.0 if args.aft == "-z" else 1.0
    aft = np.array([0.0, 0.0, aft_sign])
    n_aft = np.cross(np.array([0.0, 1.0, 0.0]), h)
    if np.dot(n_aft, aft) < 0:
        n_aft = -n_aft
    d_aft = float(np.dot(n_aft, A))
    d_chord = float(np.dot(n_aft, A)) + args.chord
    d0 = float(np.dot(h, A))
    d1 = float(np.dot(h, B))
    mesh = g.nodes[wing]["mesh"]
    flap_prims, wing_prims = [], []
    counts = {"flap": 0, "cut": 0}
    for prim in g.doc["meshes"][mesh]["primitives"]:
        attrs, tris = ah.read_primitive(g, prim)
        attrs["POSITION"] = attrs["POSITION"].copy() + shift
        fb, wb = ah.Bucket(), ah.Bucket()
        for tri in tris:
            poly = ah.vertex_rows(attrs, tri)
            ys = [v["POSITION"][1] for v in poly]
            if (args.ymin is not None and max(ys) < args.ymin) or (args.ymax is not None and min(ys) > args.ymax):
                wb.add_poly(poly)
                continue
            pieces_out = []
            inside = poly
            for n, d in ((n_aft, d_aft), (-n_aft, -d_chord), (h, d0), (-h, -d1)):
                inside, outside = clip_plane(inside, n, d)
                if len(outside) >= 3:
                    pieces_out.append(outside)
                if len(inside) < 3:
                    inside = []
                    break
            if inside:
                fb.add_poly(inside)
                counts["flap"] += 1
            for p in pieces_out:
                wb.add_poly(p)
            if inside and pieces_out:
                counts["cut"] += 1
        for b in (fb, wb):
            if b.verts["POSITION"]:
                arr = np.array(b.verts["POSITION"])
                arr -= shift
                b.verts["POSITION"] = list(arr)
        flap_prims.append((fb, prim.get("material")))
        wing_prims.append((wb, prim.get("material")))
    if counts["flap"] == 0:
        sys.exit("no triangles inside the flap region")
    flap_mesh = ah.emit(g, args.name, flap_prims)
    wing_mesh = ah.emit(g, g.nodes[wing].get("name"), wing_prims)
    g.nodes[wing]["mesh"] = wing_mesh
    theta = math.atan2(-h[2], h[0])
    mount_world = np.eye(4)
    mount_world[:3, :3] = np.array([[math.cos(theta), 0, math.sin(theta)], [0, 1, 0], [-math.sin(theta), 0, math.cos(theta)]])
    mount_world[:3, 3] = A - shift
    q_mount = quat_y(theta)
    local_aft = mount_world[:3, :3].T @ aft
    deflect = math.radians(args.deflection)
    # Rotation about local +x by phi moves (y, z) -> (y cos - z sin, y sin + z cos): down needs y' < 0 for the aft vector.
    sign = 1.0 if -local_aft[2] * math.sin(deflect) < 0 else -1.0
    mount = {"name": args.name + "_mount", "translation": [float(v) for v in mount_world[:3, 3]], "rotation": q_mount}
    mount_i = g.add_node(mount, wing)
    anim_i = g.add_node({"name": args.name}, mount_i)
    inverse = np.linalg.inv(mount_world)
    mesh_node = {"name": args.name + "_mesh", "mesh": flap_mesh,
                 "matrix": [float(round(v, 7)) for v in inverse.T.reshape(-1)]}
    g.add_node(mesh_node, anim_i)
    add_clip(g, anim_i, quat_axis_x(sign * deflect))
    ah.save(model)
    print("%s: %s %d tris carved (%d cut), hinge %.2f m, theta %.1f deg, deflection %+.0f deg about local x" % (
        args.vehicle, args.name, counts["flap"], counts["cut"], length, math.degrees(theta), sign * args.deflection))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("flap")
    f.add_argument("vehicle")
    f.add_argument("--wing", required=True)
    f.add_argument("--hinge", required=True)
    f.add_argument("--name", required=True)
    f.add_argument("--deflection", type=float, default=30.0)
    f.add_argument("--aft", choices=["-z", "+z"], default="-z")
    f.add_argument("--chord", type=float, default=3.0)
    f.add_argument("--ymin", type=float)
    f.add_argument("--ymax", type=float)
    f.set_defaults(fn=cmd_flap)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
