#!/usr/bin/env python3
"""Colliders from the vehicle glTF: CoACD convex decomposition per drawn node -> role `collider` nodes in that glTF.
`--hit-cylinder` instead: role `hit_surface` prism per named node (wheels), replacing that node's drawn subtree as hit
surface; axis = thinnest local extent.

    python3 -m venv ~/.cache/combinedassault-hulls && ~/.cache/combinedassault-hulls/bin/pip install coacd numpy     (once)
    ~/.cache/combinedassault-hulls/bin/python tools/gen_hulls.py <vehicle> [options]

    --nodes a,b        only these nodes (default: every drawn untagged or frame node but running gear (suspension / track part
                       frames), rotors (rotor-kind system), and *gear* under a landing_gear part: its colliders are the legs)
    --include a,b      add nodes the default leaves out
    --concavity 0.05   CoACD threshold: lower = tighter fit, more pieces
    --budget 12        pieces across the selected nodes, shared by bounding volume (each node >= 1)
    --min-volume 0.01  drop pieces smaller than this (blocks^3)
    --max-vertices 24  per piece (CoACD decimation): SAT cost + no sliver faces
    --fresh            also drop hand-made colliders (locked ones still kept)
    --merge NAME       --nodes decomposed as one mesh (hull split into drawn sections); genHulls node = NAME
    --main             largest piece made => `main` (physics hull; ships: probe beam/length = its OBB)
    --list             print nodes -> frames and exit
    --hit-cylinder a,b hit surfaces only (no CoACD); regenerating replaces the named nodes' generated ones
    --sides 12         prism sides
    --material rubber  hit_surface material name (token: armor_<mm>, rubber, glass, soft; pack doc §6.6)

Primitives with a `decor` material token are left out. Generated colliders carry extras.genHulls {node}; regenerating
replaces those of the selected nodes; hand-made colliders (no genHulls) or genHulls.locked are kept. Each collider sits
under its node's frame (nearest body / part / pitch above it), one primitive per face.
"""
import argparse
import json
import os
import re
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import convert_pack  # noqa: E402
import mqo  # noqa: E402

PACK = "src/main/resources/combinedassault_packs/combinedassault"
FRAMES = ("body", "part", "pitch")
RUNNING_GEAR = ("wheel", "track")
ROTOR = ("rotor", "blade")
# ModelDataManager.DECOR: drawn only, neither hit nor collided
DECOR = re.compile(r"(?i)(?:^|[_ .])decor(?:$|[_ .])")
# ConvexHull.EPS: Newell normal length (2 x area) below => load failure
FACE_EPS = 1.0e-4


def read_json(path):
    with open(path) as f:
        return json.load(f)


def asset(ns_path, kind="assets"):
    ns, path = ns_path.split(":", 1)
    return os.path.join(PACK, kind, ns, path)


def gltf_nodes(model_rl):
    path = asset(model_rl)
    doc = read_json(path)
    buffers = []
    for b in doc["buffers"]:
        if "uri" in b:
            buffers.append(open(os.path.join(os.path.dirname(path), b["uri"]), "rb").read())
        else:
            buffers.append(open(asset(b["extras"]["resourceLocation"]), "rb").read())

    def accessor(i):
        a = doc["accessors"][i]
        v = doc["bufferViews"][a["bufferView"]]
        n = {"SCALAR": 1, "VEC3": 3}[a["type"]]
        fmt = {5126: "f", 5125: "I", 5123: "H", 5121: "B"}[a["componentType"]]
        size = struct.calcsize(fmt)
        stride = v.get("byteStride", n * size)
        base = v.get("byteOffset", 0) + a.get("byteOffset", 0)
        data = buffers[v["buffer"]]
        return np.array([struct.unpack_from("<%d%s" % (n, fmt), data, base + k * stride) for k in range(a["count"])])

    def local(node):
        if "matrix" in node:
            return np.array(node["matrix"]).reshape(4, 4).T
        m = np.eye(4)
        x, y, z, w = node.get("rotation", [0, 0, 0, 1])
        r = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                      [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                      [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
        m[:3, :3] = r * np.array(node.get("scale", [1, 1, 1]))
        m[:3, 3] = node.get("translation", [0, 0, 0])
        return m

    out = []

    def visit(i, parent, world):
        node = doc["nodes"][i]
        w = world @ local(node)
        verts, faces = [], []
        if "mesh" in node:
            for p in doc["meshes"][node["mesh"]]["primitives"]:
                if p.get("mode", 4) != 4 or "material" in p and DECOR.search(doc["materials"][p["material"]].get("name", "")):
                    continue
                pos = accessor(p["attributes"]["POSITION"])
                idx = accessor(p["indices"]).reshape(-1) if "indices" in p else np.arange(len(pos))
                pos = (w @ np.c_[pos, np.ones(len(pos))].T).T[:, :3]
                faces.append(idx.reshape(-1, 3) + sum(len(v) for v in verts))
                verts.append(pos)
        out.append({"name": node.get("name", "node%d" % i), "index": i, "parent": parent,
                    "role": node.get("extras", {}).get(convert_pack.ROLE),
                    "verts": np.vstack(verts) if verts else np.zeros((0, 3)),
                    "faces": np.vstack(faces).astype(int) if faces else np.zeros((0, 3), int)})
        me = len(out) - 1
        for c in node.get("children", []):
            visit(c, me, w)

    scene = doc["scenes"][doc.get("scene", 0)]
    for root in scene["nodes"]:
        visit(root, -1, np.eye(4))
    return out


def polygons(verts, tris):
    """CoACD piece -> exact polytope of its facet planes (+ bounding box), sliver planes dropped (mqo.plane_hull)."""
    normals = []
    centre = verts.mean(axis=0)
    for t in tris:
        a, b, c = verts[t]
        n = np.cross(b - a, c - a)
        length = np.linalg.norm(n)
        if length < 1e-12:
            continue
        n /= length
        normals.append(n if np.dot(n, a - centre) >= 0 else -n)
    dirs = np.unique(np.round(np.vstack(normals + [np.eye(3), -np.eye(3)]), 6), axis=0)
    dirs /= np.linalg.norm(dirs, axis=1)[:, None]
    # Box planes pushed, never dropped: a dropped sliver plane left the piece unbounded (mk15: z to 48).
    box = [i for i, d in enumerate(dirs) if np.isclose(np.abs(d).max(), 1.0)]
    points, faces = mqo.plane_hull(dirs, (verts @ dirs.T).max(axis=0), range(len(dirs)), fixed=box)
    points = np.asarray(points)
    if len(points) and ((points < verts.min(axis=0) - 0.101).any() or (points > verts.max(axis=0) + 0.101).any()):
        return np.zeros((0, 3)), []  # box plane pushed to its limit and dropped
    return points, faces


def frame_of(gltf, i):
    while convert_pack.ROLE not in gltf.nodes[i].get("extras", {}) or \
            gltf.nodes[i]["extras"][convert_pack.ROLE] not in FRAMES:
        i = gltf.parent[i]
    return i


def spins(gltf, i):
    while True:
        system = gltf.nodes[i].get("extras", {}).get(convert_pack.DATA, {}).get("system")
        if system is not None:
            return system in ("rotor", "tail_rotor")
        if i not in gltf.parent:
            return False
        i = gltf.parent[i]


def default_node(gltf, node, part_types):
    """Drawn, untagged or a frame, not running gear / rotor / landing-gear leg."""
    extras = gltf.nodes[node["index"]].get("extras", {})
    if extras.get(convert_pack.ROLE, "body") not in FRAMES or extras.get(convert_pack.HIDDEN):
        return False
    frame = gltf.nodes[frame_of(gltf, node["index"])].get("extras", {})
    kind = part_types.get(frame.get(convert_pack.IFACE, ""), "")
    return not (kind in ("suspension", "track") or spins(gltf, node["index"])
                or kind == "landing_gear" and "gear" in node["name"].lower())


def cylinder(gltf, nodes, name, sides, material=None):
    """Prism round the drawn subtree of `name`, node-local; child of that node."""
    index = gltf.by_name(name)
    under = {next(k for k, n in enumerate(nodes) if n["name"] == name)}
    for k, n in enumerate(nodes):
        if n["parent"] in under and n["role"] is None:
            under.add(k)
    verts = np.vstack([nodes[k]["verts"] for k in under])
    if not len(verts):
        sys.exit(f"{name}: nothing drawn")
    local = (np.linalg.inv(gltf.world(index)) @ np.c_[verts, np.ones(len(verts))].T).T[:, :3]
    lo, hi = local.min(axis=0), local.max(axis=0)
    axis = int(np.argmin(hi - lo))
    u, w = (axis + 1) % 3, (axis + 2) % 3
    mid = (lo + hi) / 2
    # Circumscribed: flats at the furthest vertex, never inside the tyre.
    radius = np.hypot(local[:, u] - mid[u], local[:, w] - mid[w]).max() / np.cos(np.pi / sides)
    points = []
    for end in (lo[axis], hi[axis]):
        for k in range(sides):
            p = [0.0, 0.0, 0.0]
            p[axis] = float(end)
            p[u] = float(mid[u] + radius * np.cos(2 * np.pi * k / sides))
            p[w] = float(mid[w] + radius * np.sin(2 * np.pi * k / sides))
            points.append(p)
    ring = list(range(sides))
    faces = [ring[::-1], [sides + k for k in ring]] + [[k, (k + 1) % sides, sides + (k + 1) % sides, sides + k]
                                                       for k in ring]
    positions, indices = convert_pack.triangulate(points, faces)
    mesh = gltf.add_mesh(f"hit_{name}", positions, indices)
    if material:
        materials = gltf.doc.setdefault("materials", [])
        slot = next((i for i, m in enumerate(materials) if m.get("name") == material), None)
        if slot is None:
            materials.append({"name": material})
            slot = len(materials) - 1
        for primitive in gltf.doc["meshes"][mesh]["primitives"]:
            primitive["material"] = slot
    node = {"name": f"hit_{name}", "mesh": mesh,
            "extras": {convert_pack.HIDDEN: True, "genHulls": {"node": name, "hit": True}}}
    convert_pack.set_role(node, "hit_surface")
    gltf.add_node(node, index)
    return 2 * (sides - 2) + 2 * sides


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    ap = argparse.ArgumentParser()
    ap.add_argument("vehicle")
    ap.add_argument("--nodes")
    ap.add_argument("--include", default="")
    ap.add_argument("--concavity", type=float, default=0.05)
    ap.add_argument("--budget", type=int, default=12)
    ap.add_argument("--min-volume", type=float, default=0.01)
    ap.add_argument("--max-vertices", type=int, default=24)
    ap.add_argument("--fresh", action="store_true")
    ap.add_argument("--merge")
    ap.add_argument("--main", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--hit-cylinder")
    ap.add_argument("--sides", type=int, default=12)
    ap.add_argument("--material")
    args = ap.parse_args()

    ns, vid = args.vehicle.split(":", 1) if ":" in args.vehicle else ("combinedassault", args.vehicle)
    vehicle = read_json(os.path.join(PACK, "combinedassault", ns, "vehicles", vid + ".json"))
    path = asset(vehicle["model"])
    gltf = convert_pack.Gltf(path, lambda rl: open(asset(rl), "rb").read())
    part_types = {p["id"]: p["type"].split(":")[-1] for p in vehicle.get("parts", [])}
    nodes = gltf_nodes(vehicle["model"])
    if args.list:
        for node in nodes:
            frame = gltf.nodes[frame_of(gltf, node["index"])].get("name")
            print(f"{node['name']:32} -> {frame:28} {len(node['faces'])} tris"
                  f"{'' if default_node(gltf, node, part_types) else '  (skipped by default)'}")
        return
    if args.hit_cylinder:
        names = args.hit_cylinder.split(",")
        missing = [n for n in names if gltf.by_name(n) is None]
        if missing:
            sys.exit(f"no such node(s): {', '.join(missing)}")
        drop = [i for i, n in enumerate(gltf.nodes) if n.get("extras", {}).get("genHulls", {}).get("hit")
                and n["extras"]["genHulls"]["node"] in names]
        gltf.remove_nodes(drop)
        for name in names:
            print(f"{name}: {cylinder(gltf, nodes, name, args.sides, args.material)} tris")
        gltf.save(path, lambda rl, data: open(asset(rl), "wb").write(data))
        print(f"{path}: {len(names)} hit surfaces, {len(drop)} dropped")
        return
    wanted = set(args.nodes.split(",")) if args.nodes else {n["name"] for n in nodes if default_node(gltf, n, part_types)}
    wanted |= {n for n in args.include.split(",") if n}
    missing = wanted - {n["name"] for n in nodes}
    if missing:
        sys.exit(f"no such node(s): {', '.join(sorted(missing))}")
    if args.merge:
        sources = [n for n in nodes if n["name"] in wanted and len(n["faces"])]
        frames = {frame_of(gltf, n["index"]) for n in sources}
        if len(frames) != 1:
            sys.exit(f"--merge: nodes span {len(frames)} frames")
        offsets = np.cumsum([0] + [len(n["verts"]) for n in sources])
        nodes = [{"name": args.merge, "index": sources[0]["index"], "parent": -1, "role": None,
                  "verts": np.vstack([n["verts"] for n in sources]),
                  "faces": np.vstack([n["faces"] + o for n, o in zip(sources, offsets)])}]
        wanted = {args.merge}

    import coacd  # noqa: E402  (venv only)
    coacd.set_log_level("error")
    drop = []
    for i, n in enumerate(gltf.nodes):
        extras = n.get("extras", {})
        if extras.get(convert_pack.ROLE) != "collider":
            continue
        gen = extras.get("genHulls")
        if gen is None and args.fresh or gen is not None and not gen.get("locked") and gen.get("node") in wanted:
            drop.append(i)
    gltf.remove_nodes(drop)
    by_name = {n.get("name"): i for i, n in enumerate(gltf.nodes)}
    if args.merge:
        by_name[args.merge] = nodes[0]["index"]
    chosen = [n for n in nodes if n["name"] in wanted and len(n["faces"])]
    volume = [float(np.prod(np.maximum(n["verts"].max(axis=0) - n["verts"].min(axis=0), 1e-3))) for n in chosen]
    share = [max(1, round(args.budget * v / sum(volume))) for v in volume]
    made = 0
    largest = None
    for node, cap in zip(chosen, share):
        frame = frame_of(gltf, by_name[node["name"]])
        inverse = convert_pack.matrix_trs(np.linalg.inv(gltf.world(frame)))
        parts = coacd.run_coacd(coacd.Mesh(node["verts"], node["faces"]), threshold=args.concavity,
                                max_convex_hull=cap, decimate=True, max_ch_vertex=args.max_vertices)
        count = 0
        for k, (verts, tris) in enumerate(parts):
            verts = np.asarray(verts)
            points, faces = polygons(verts, np.asarray(tris))
            if len(faces) < 4 or np.prod(points.max(axis=0) - points.min(axis=0)) < args.min_volume:
                continue
            loops = [points[face] for face in faces]
            if min(np.linalg.norm(np.cross(l, np.roll(l, -1, axis=0)).sum(axis=0)) for l in loops) < FACE_EPS:
                print(f"{node['name']} {k}: sliver after rounding (game refuses it); dropped")
                continue
            name = f"collider_{node['name']}_{k}"
            positions, indices = convert_pack.triangulate(points.tolist(), faces)
            mesh = gltf.add_mesh(name, positions, indices, [len(f) - 2 for f in faces])
            t, q, sc = inverse
            entry = {"name": name, "mesh": mesh, "translation": t, "rotation": q, "scale": sc,
                     "extras": {convert_pack.HIDDEN: True, "genHulls": {"node": node["name"]}}}
            convert_pack.set_role(entry, "collider", None, name=f"{node['name']} {k}")
            gltf.add_node(entry, frame)
            size = float(np.prod(points.max(axis=0) - points.min(axis=0)))
            if largest is None or size > largest[0]:
                largest = (size, entry)
            count += 1
        made += count
        print(f"{node['name']} -> {gltf.nodes[frame].get('name')}: {count} piece(s)")
    if args.main and largest is not None:
        if any(n.get("extras", {}).get(convert_pack.DATA, {}).get("main") for n in gltf.nodes):
            sys.exit("--main: model keeps another `main` collider (--fresh, or remove it)")
        largest[1]["extras"][convert_pack.DATA]["main"] = True
        print(f"main: {largest[1]['name']}")
    gltf.save(path, lambda rl, data: open(asset(rl), "wb").write(data))
    print(path)
    print(f"{made} generated, {len(drop)} dropped")


if __name__ == "__main__":
    main()
