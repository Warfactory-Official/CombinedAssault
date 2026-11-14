#!/usr/bin/env python3
"""Seated-player fit of a staged vehicle: arm / head clearance per seat against the drawn mesh.

  python3 tools/flans/seatfit.py <pack dir> <ns:id> ...   (table; s = uniform scale that makes every seat fit)

Seat socket = eye (worldSeatPosition = socket - eye height). Vanilla player x 0.9375: feet = eye - 1.62, shoulder top
eye - 0.22, hands eye - 0.92, head top eye + 0.26, arm outer edge 0.5 off the centreline.
Available = farthest skin crossing within OPEN (outer wall; consoles / seats / guns nearer are inside), median over
the spread rays. Side enclosed iff its shoulder height crosses skin within OPEN: a rim below the shoulders is open.
"""
import glob
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import flan  # noqa: E402
import glb  # noqa: E402
import model_icon as mi  # noqa: E402

ARM = 0.5
HEAD = 0.26
MARGIN = 1 / 16
OPEN = 2.0
HEIGHTS = (-0.22, -0.57, -0.92)
SPREAD = (-25.0, 0.0, 25.0)


def scene(path):
    """(triangles (n, 3, 3) of drawn meshes, {seat id: (eye, yaw deg)}), model frame at rest."""
    doc, blob = glb.read(path)
    tris, seats = [], {}

    def visit(i, parent):
        n = doc["nodes"][i]
        name = n.get("name", "")
        here = parent @ mi.local(n)
        ex = n.get("extras") or {}
        if name.startswith("socket_seat_"):
            fwd = here[:3, :3] @ np.array([0.0, 0.0, 1.0])
            seats[name[len("socket_seat_"):]] = (here[:3, 3].copy(), float(np.degrees(np.arctan2(fwd[0], fwd[2]))))
        if name.startswith(("hit_", "socket_")) or ex.get("gemrenderHidden") or ex.get("combinedassaultRole") == "collider":
            return
        if "mesh" in n:
            for prim in doc["meshes"][n["mesh"]]["primitives"]:
                pos = mi.accessor(doc, blob, prim["attributes"]["POSITION"]).astype(float) @ here[:3, :3].T + here[:3, 3]
                idx = mi.accessor(doc, blob, prim["indices"]).astype(np.int64).reshape(-1, 3)
                tris.append(pos[idx])
        for c in n.get("children", []):
            visit(c, here)

    for r in doc["scenes"][doc.get("scene", 0)]["nodes"]:
        visit(r, np.eye(4))
    return (np.concatenate(tris) if tris else np.zeros((0, 3, 3))), seats


def far_hits(tri, origin, dirs):
    """Per ray: farthest triangle crossing within OPEN, inf when none."""
    e1, e2 = tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]
    out = np.full(len(dirs), np.inf)
    for k, d in enumerate(dirs):
        p = np.cross(d, e2)
        det = np.einsum("ij,ij->i", e1, p)
        ok = np.abs(det) > 1e-12
        inv = np.where(ok, 1 / np.where(ok, det, 1), 0)
        sv = origin - tri[:, 0]
        u = np.einsum("ij,ij->i", sv, p) * inv
        q = np.cross(sv, e1)
        v = (q @ d) * inv
        t = np.einsum("ij,ij->i", e2, q) * inv
        hit = ok & (u >= 0) & (v >= 0) & (u + v <= 1) & (t > 1e-4) & (t < OPEN)
        if hit.any():
            out[k] = t[hit].max()
    return out


def seat_fit(tri, eye, yaw):
    """(required / available) worst over the enclosed sides and the roof; 0 when open. + which."""
    worst, why = 0.0, "open"
    for side in (1, -1):
        hits = []
        for h in HEIGHTS:
            dirs = []
            for d in SPREAD:
                a = np.radians(yaw + 90 * side + d)
                dirs.append([np.sin(a), 0.0, np.cos(a)])
            dirs = np.array(dirs)
            # perpendicular clearance: hit distance x cos(spread)
            dist = far_hits(tri, eye + [0, h, 0], dirs) * np.cos(np.radians(SPREAD))
            hits.append(float(np.median(dist)))
        if hits[0] >= OPEN:
            continue
        avail = min(hits)
        ratio = (ARM + MARGIN) / max(avail, 1e-3)
        if ratio > worst:
            worst, why = ratio, f"{'left' if side > 0 else 'right'} {avail:.2f}"
    up = float(np.median(far_hits(tri, eye, np.array([[0.0, 1.0, 0.0], [0.15, 0.99, 0.0], [-0.15, 0.99, 0.0]]))))
    if up < OPEN:
        ratio = (HEAD + MARGIN) / max(up, 1e-3)
        if ratio > worst:
            worst, why = ratio, f"roof {up:.2f}"
    return worst, why


def fit(path):
    """(s >= 1, limiting seat, its clause) for a staged glb."""
    tri, seats = scene(path)
    best = (1.0, None, "")
    for sid, (eye, yaw) in sorted(seats.items()):
        r, why = seat_fit(tri, eye, yaw)
        if r > best[0]:
            best = (r, sid, why)
    return best


def main():
    pack = os.path.abspath(sys.argv[1])
    for rid in sys.argv[2:]:
        ns, vid = rid.split(":")
        (path,) = glob.glob(os.path.join(pack, "assets", ns, "models", "*", vid, f"{vid}.glb"))
        s, seat, why = fit(path)
        print(f"{rid}\t{s:.3f}\t{seat or '-'}\t{why}")


if __name__ == "__main__":
    main()
