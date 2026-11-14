#!/usr/bin/env python3
"""Collider hulls vs wflib ConvexHull.of (float32, Newell face normals, outward by vertex mean, EPS 1e-4).

    python3 tools/bvp/hullfix.py check <pack dir>          every collider of every glb; exit 1 on any failure
    python3 tools/bvp/hullfix.py fix <glb ...>             generated colliders rebuilt as the exact hull of their vertices

gen_hulls faces (mqo.plane_hull, merged planes, points rounded) can leave a vertex up to ~5e-4 outside a face plane:
the game refuses the model and the gametest JVM hangs (mi24_hind_a 2026-11-11). Rebuild: facets of the vertex set,
coplanar within MERGE, loops ordered round the facet centre; one primitive per face.
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import convert_pack  # noqa: E402
import glb  # noqa: E402

EPS = np.float32(1.0e-4)
MERGE = 1.0e-6
DEDUPE = 1.0e-5
MIN_AREA = 2.0e-4


def java_check(points, faces):
    """None if ConvexHull.of accepts (points float32 [n, 3], faces index loops), else its message."""
    p = np.asarray(points, dtype=np.float32)
    if len(p) < 4 or len(faces) < 4:
        return f"too small: {len(p)} points, {len(faces)} faces"
    interior = p.mean(axis=0, dtype=np.float32)
    planes = []
    for f, face in enumerate(faces):
        a, b = p[face], p[np.roll(face, -1)]
        n = np.array([np.sum((a[:, 1] - b[:, 1]) * (a[:, 2] + b[:, 2]), dtype=np.float32),
                      np.sum((a[:, 2] - b[:, 2]) * (a[:, 0] + b[:, 0]), dtype=np.float32),
                      np.sum((a[:, 0] - b[:, 0]) * (a[:, 1] + b[:, 1]), dtype=np.float32)], dtype=np.float32)
        length = np.float32(np.sqrt(np.sum(n * n)))
        if length < EPS:
            return f"face {f} is degenerate"
        n = n / length
        d = np.float32(np.dot(n, p[face[0]]))
        if np.dot(n, interior) > d:
            n, d = -n, -d
        planes.append((n, d))
    for f, (n, d) in enumerate(planes):
        off = (p[faces[f]] @ n - d)
        if np.abs(off).max() > EPS:
            return f"face {f} is not flat: {float(np.abs(off).max()):.3g}"
    for f, (n, d) in enumerate(planes):
        out = p @ n - d
        if out.max() > EPS:
            return f"vertex {int(out.argmax())} sits {float(out.max()):.4g} outside face {f}"
    return None


def exact_hull(points):
    """(points, faces) of the convex hull of `points`: brute-force facet planes over vertex triples (pieces <= ~40)."""
    pts = []
    for q in np.asarray(points, dtype=np.float64):
        if not any(np.linalg.norm(q - r) < DEDUPE for r in pts):
            pts.append(q)
    pts = np.array(pts)
    n = len(pts)
    centre = pts.mean(axis=0)
    span = float(np.ptp(pts, axis=0).max())
    planes = []
    for i in range(n):
        for j in range(i + 1, n):
            for k in range(j + 1, n):
                nrm = np.cross(pts[j] - pts[i], pts[k] - pts[i])
                length = np.linalg.norm(nrm)
                if length < 1e-9 * span * span:
                    continue
                nrm /= length
                d = nrm @ pts[i]
                if nrm @ centre > d:
                    nrm, d = -nrm, -d
                if (pts @ nrm - d).max() > MERGE:
                    continue
                if any(np.abs(nrm - m).max() < 1e-7 and abs(d - e) < MERGE for m, e in planes):
                    continue
                planes.append((nrm, d))
    faces = []
    for nrm, d in planes:
        on = np.where(np.abs(pts @ nrm - d) <= MERGE)[0]
        c = pts[on].mean(axis=0)
        u = pts[on[0]] - c
        u /= np.linalg.norm(u)
        v = np.cross(nrm, u)
        loop = list(on[np.argsort(np.arctan2((pts[on] - c) @ v, (pts[on] - c) @ u))])
        area = 0.5 * np.linalg.norm(sum(np.cross(pts[loop[t]], pts[loop[(t + 1) % len(loop)]]) for t in range(len(loop))))
        if len(loop) >= 3 and area >= MIN_AREA:
            faces.append(loop)
    return pts, faces


COMPONENT = {5121: np.uint8, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}


def accessor(gltf, index):
    a = gltf.doc["accessors"][index]
    view = gltf.doc["bufferViews"][a["bufferView"]]
    start = view.get("byteOffset", 0) + a.get("byteOffset", 0)
    width = WIDTH[a["type"]]
    return np.frombuffer(bytes(gltf.buffers[view.get("buffer", 0)]), dtype=COMPONENT[a["componentType"]],
                         count=a["count"] * width, offset=start).reshape(-1, width)


def colliders(gltf):
    for i, node in enumerate(gltf.nodes):
        if node.get("extras", {}).get(convert_pack.ROLE) == "collider" and "mesh" in node:
            yield i, node


def mesh_faces(gltf, node):
    """Collider mesh -> (points, face loops): one primitive per face, else one face per triangle."""
    mesh = gltf.doc["meshes"][node["mesh"]]
    pos = accessor(gltf, mesh["primitives"][0]["attributes"]["POSITION"]).astype(np.float32)
    faces = []
    for prim in mesh["primitives"]:
        idx = accessor(gltf, prim["indices"]).astype(np.int64).reshape(-1)
        if len(mesh["primitives"]) > 1:
            loop = []
            for k in idx:
                if k not in loop:
                    loop.append(int(k))
            faces.append(loop)
        else:
            faces += [list(t) for t in idx.reshape(-1, 3)]
    return pos, faces


def check_model(path):
    gltf = convert_pack.Gltf(str(path), lambda rl: b"")
    bad = []
    for i, node in colliders(gltf):
        pos, faces = mesh_faces(gltf, node)
        world = (np.c_[pos, np.ones(len(pos))] @ convert_pack.trs(node).T)[:, :3].astype(np.float32)
        for label, pts in (("mesh", pos), ("posed", world)):
            err = java_check(pts, faces)
            if err:
                bad.append(f"{node['name']} ({label}): {err}")
                break
    return bad


def fix_model(path):
    """Generated colliders whose faces the game would refuse -> exact hull, one primitive per face."""
    gltf = convert_pack.Gltf(str(path), lambda rl: b"")
    fixed = 0
    for i, node in colliders(gltf):
        if "genHulls" not in node.get("extras", {}):
            continue
        pos, faces = mesh_faces(gltf, node)
        world = (np.c_[pos, np.ones(len(pos))] @ convert_pack.trs(node).T)[:, :3].astype(np.float32)
        if java_check(pos, faces) is None and java_check(world, faces) is None:
            continue
        pts, loops = exact_hull(pos)
        pts = pts.astype(np.float32)
        err = java_check(pts, loops)
        assert err is None, f"{path}: {node['name']}: rebuilt hull still refused: {err}"
        positions, indices = convert_pack.triangulate(pts.tolist(), loops)
        node["mesh"] = gltf.add_mesh(node["name"], positions, indices, [len(f) - 2 for f in loops])
        fixed += 1
    if fixed:
        gltf.save(str(path), lambda rl, data: None)
    return fixed


def main():
    mode, args = sys.argv[1], sys.argv[2:]
    if mode == "fix":
        for p in args:
            print(f"{p}: {fix_model(p)} collider(s) rebuilt")
        return
    failures = 0
    for p in sorted(Path(args[0]).glob("assets/*/models/**/*.glb")):
        bad = check_model(p)
        failures += len(bad)
        for line in bad:
            print(f"{p.relative_to(args[0])}: {line}")
    print(f"{failures} refused collider(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
