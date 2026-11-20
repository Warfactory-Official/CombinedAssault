#!/usr/bin/env python3
"""Clip + control_surface part for a control surface already modelled as `<name>_mount` > `<name>` > mesh (no carving).

    python3 tools/aircraft_surfaces_bind.py <vehicle> NAME:pitch,roll,yaw [NAME:...]

Clip `NAME` t 0 / .5 / 1 = -D / 0 / +D about the animated node's local x, D = |pitch|+|roll|+|yaw|; +D = TE down,
i.e. the trailing edge moves toward the downward-facing normal of the surface. Mount x = hinge; TE side = mesh
centroid offset perpendicular to the hinge; sign derived from the world frames, not from the node rotations.
Re-run replaces clip and part. Part gains: see aircraft_surfaces.py.
"""
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aircraft_hits as ah  # noqa: E402
import aircraft_flaps as af  # noqa: E402
import aircraft_surfaces as sf  # noqa: E402
import def_sockets  # noqa: E402


def te_down_sign(g, name):
    anim = g.by_name(name)
    mount = g.by_name(name + "_mount")
    mesh_nodes = [i for i, p in g.parent.items() if p == anim and "mesh" in g.nodes[i]]
    if mount is None or anim is None or not mesh_nodes:
        sys.exit("%s: need %s_mount > %s > mesh node" % (name, name, name))
    W = g.world(mount)
    h = W[:3, 0] / np.linalg.norm(W[:3, 0])
    origin = W[:3, 3]
    P = []
    for i in mesh_nodes:
        M = g.world(i)
        pos = def_sockets.positions(g, g.nodes[i]["mesh"])
        P.append(pos @ M[:3, :3].T + M[:3, 3])
    c = np.concatenate(P).mean(0) - origin
    r = c - np.dot(c, h) * h
    n = np.cross(h, np.array([0.0, 0.0, 1.0]))
    n /= np.linalg.norm(n)
    if n[1] > 0:
        n = -n
    return 1.0 if np.dot(np.cross(h, r), n) > 0 else -1.0


def bind(g, vehicle, name, p, r, y):
    anim = g.by_name(name)
    sign = te_down_sign(g, name)
    anims = g.doc.setdefault("animations", [])
    for clip in list(anims):
        if clip.get("name") == name:
            anims.remove(clip)
    full = math.radians(abs(p) + abs(r) + abs(y))
    times = ah.make_accessor_scalar(g, [0.0, 0.5, 1.0])
    quats = np.array([af.quat_axis_x(-sign * full), [0, 0, 0, 1], af.quat_axis_x(sign * full)], dtype=np.float32)
    out = ah.make_accessor(g, quats, 5126, "VEC4", None)
    anims.append({"name": name, "samplers": [{"input": times, "output": out, "interpolation": "LINEAR"}],
                  "channels": [{"sampler": 0, "target": {"node": anim, "path": "rotation"}}]})
    print("%s: +D=%.0f deg x%+d, gains pitch %g roll %g yaw %g" % (name, math.degrees(full), int(sign), p, r, y))


def main():
    vehicle, specs = sys.argv[1], sys.argv[2:]
    g, model = ah.load(vehicle)
    parsed = []
    for s in specs:
        name, gains = s.split(":")
        p, r, y = (float(v) for v in gains.split(","))
        bind(g, vehicle, name, p, r, y)
        parsed.append((name, p, r, y))
    g.compact()
    ah.save(model)
    for name, p, r, y in parsed:
        sf.write_part(vehicle, name, p, r, y)


if __name__ == "__main__":
    main()
