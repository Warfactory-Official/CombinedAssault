"""Metasequoia (.mqo) reader matching MCHeli's W_MetasequoiaObject, plus a glTF node-tree writer.

MCHeli divergences from Metasequoia: `mirror` ignored; quad (a b c d) -> triangles (c b a), (d c a);
normals smoothed per triangle within `facet`. Part = `$name` object + following non-`$` objects.
"""
import json
import math
import re

import numpy as np

_OBJECT = re.compile(r'^Object "([^"]*)" \{(.*?)^\}', re.S | re.M)
_VERTS = re.compile(r'vertex \d+ \{(.*?)\}', re.S)
_FACES = re.compile(r'face \d+ \{(.*?)\n\t\}', re.S)
_FACE = re.compile(r'(\d) V\(([^)]*)\)(?: M\((\d+)\))?(?: UV\(([^)]*)\))?')


class Obj:
    def __init__(self, name, verts, tris, facet):
        self.name, self.verts, self.tris, self.facet = name, verts, tris, facet


def read(path, encoding="cp932"):
    text = open(path, encoding=encoding, errors="replace").read()
    objects = []
    for name, body in _OBJECT.findall(text):
        facet = re.search(r'facet ([\d.]+)', body)
        vm = _VERTS.search(body)
        verts = np.array([[float(x) for x in line.split()] for line in (vm.group(1) if vm else "").split("\n")
                          if line.strip()], dtype=float).reshape(-1, 3) / 100.0
        tris = []
        fm = _FACES.search(body)
        for n, vs, _, uv in (_FACE.findall(fm.group(1)) if fm else []):
            idx = [int(v) for v in vs.split()]
            uvs = [float(u) for u in uv.split()] if uv else [0.0] * (2 * len(idx))
            uvs = [(uvs[2 * i], uvs[2 * i + 1]) for i in range(len(idx))]
            if n == "3":
                tris.append(([idx[2], idx[1], idx[0]], [uvs[2], uvs[1], uvs[0]]))
            elif n == "4":
                tris.append(([idx[2], idx[1], idx[0]], [uvs[2], uvs[1], uvs[0]]))
                tris.append(([idx[3], idx[2], idx[0]], [uvs[3], uvs[2], uvs[0]]))
        objects.append(Obj(name, verts, tris, math.cos(math.radians(float(facet.group(1)) if facet else 45.0))))
    return objects


def parts(objects):
    """{`$name`: [objects]} in MCHeli renderPart order; non-`$` objects before the first `$` are dropped."""
    out, current = {}, None
    for o in objects:
        if o.name.startswith("$"):
            current = out.setdefault(o.name, [])
        if current is not None:
            current.append(o)
    return out


def pieces(o):
    """Connected pieces of an object, each an Obj over the same vertex array."""
    parent = list(range(len(o.verts)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for idx, _ in o.tris:
        for i in idx[1:]:
            parent[find(i)] = find(idx[0])
    groups = {}
    for tri in o.tris:
        groups.setdefault(find(tri[0][0]), []).append(tri)
    return [Obj(o.name, o.verts, tris, o.facet) for tris in groups.values()]


def axles(objs, transform, tol=0.05):
    """Tyre objects -> [(centre, radius, [Obj])], one per axle: connected pieces grouped by side and (y, z) centre."""
    pieces_ = []
    for o in objs:
        for piece in pieces(o):
            used = sorted({i for idx, _ in piece.tris for i in idx})
            pts = transform(o.verts[used])
            pieces_.append(((pts.min(axis=0) + pts.max(axis=0)) / 2, pts, piece))
    out = []
    for centre, pts, obj in pieces_:
        for axle in out:
            c = axle[0]
            if np.sign(c[0]) == np.sign(centre[0]) and abs(c[1] - centre[1]) < tol and abs(c[2] - centre[2]) < tol:
                axle[2].append(pts)
                axle[3].append(obj)
                break
        else:
            out.append([centre, None, [pts], [obj]])
    result = []
    for centre, _, pts, members in out:
        pts = np.vstack(pts)
        lo, hi = pts.min(axis=0), pts.max(axis=0)
        result.append(((lo + hi) / 2, float(hi[1] - lo[1]) / 2, members))
    return result


def triangles(obj, transform):
    """(positions, normals, uvs) per corner, MCHeli's smoothing; `transform` maps (N,3) MCHeli blocks."""
    v = transform(obj.verts)
    face_n = []
    for idx, _ in obj.tris:
        a, b, c = v[idx[0]], v[idx[1]], v[idx[2]]
        n = np.cross(b - a, c - a)
        ln = np.linalg.norm(n)
        face_n.append(n / ln if ln > 0 else np.array([0.0, 1.0, 0.0]))
    by_vert = {}
    for t, (idx, _) in enumerate(obj.tris):
        for i in idx:
            by_vert.setdefault(i, []).append(t)
    pos, nrm, tex = [], [], []
    for t, (idx, uvs) in enumerate(obj.tris):
        fn = face_n[t]
        for i, uv in zip(idx, uvs):
            s = sum((face_n[o] for o in by_vert[i] if face_n[o] @ fn >= obj.facet), np.zeros(3))
            ln = np.linalg.norm(s)
            vn = s / ln if ln > 0 else fn
            pos.append(v[i])
            nrm.append(vn if vn @ fn >= obj.facet else fn)
            tex.append(uv)
    return np.array(pos), np.array(nrm), np.array(tex)


class GltfWriter:
    """Nodes with one textured primitive each; buffer and image addressed by resource location."""

    def __init__(self, bin_location, texture_location, generator):
        self.blob = bytearray()
        self.doc = {
            "asset": {"version": "2.0", "generator": generator},
            "scene": 0, "scenes": [{"nodes": []}], "nodes": [], "meshes": [], "accessors": [], "bufferViews": [],
            # MCHeli alpha-tests; EMB4 paints transparent texels magenta.
            "materials": [{"name": "paint", "doubleSided": False, "alphaMode": "MASK", "alphaCutoff": 0.5,
                           "pbrMetallicRoughness": {"baseColorTexture": {"index": 0},
                                                    "metallicFactor": 0.0, "roughnessFactor": 1.0}}],
            "textures": [{"sampler": 0, "source": 0}],
            "samplers": [{"magFilter": 9728, "minFilter": 9984}],
            "images": [{"mimeType": "image/png", "extras": {"resourceLocation": texture_location}}],
            "buffers": [{"byteLength": 0, "extras": {"resourceLocation": bin_location}}],
        }

    def _view(self, data, target):
        while len(self.blob) % 4:
            self.blob.append(0)
        self.doc["bufferViews"].append({"buffer": 0, "byteOffset": len(self.blob), "byteLength": len(data),
                                        "target": target})
        self.blob += data
        return len(self.doc["bufferViews"]) - 1

    def _accessor(self, arr, kind, bounds=False):
        arr = np.ascontiguousarray(arr, dtype=np.float32)
        acc = {"bufferView": self._view(arr.tobytes(), 34962), "componentType": 5126, "count": len(arr), "type": kind}
        if bounds:
            acc["min"] = arr.min(axis=0).tolist()
            acc["max"] = arr.max(axis=0).tolist()
        self.doc["accessors"].append(acc)
        return len(self.doc["accessors"]) - 1

    def material(self, name):
        """Index of material `name`, cloned from paint on first use (e.g. `paint_decor`: drawn, never hit)."""
        names = [m["name"] for m in self.doc["materials"]]
        if name not in names:
            self.doc["materials"].append(dict(self.doc["materials"][0], name=name))
            names.append(name)
        return names.index(name)

    def mesh(self, name, pos, nrm, uv, material="paint"):
        """Unindexed-to-indexed by exact (pos, nrm, uv) key."""
        key = np.round(np.hstack([pos, nrm, uv]), 6)
        uniq, inverse = np.unique(key, axis=0, return_inverse=True)
        idx = np.ascontiguousarray(inverse.reshape(-1), dtype=np.uint32)
        self.doc["accessors"].append({"bufferView": self._view(idx.tobytes(), 34963), "componentType": 5125,
                                      "count": len(idx), "type": "SCALAR"})
        ia = len(self.doc["accessors"]) - 1
        prim = {"attributes": {"POSITION": self._accessor(uniq[:, 0:3], "VEC3", True),
                               "NORMAL": self._accessor(uniq[:, 3:6], "VEC3"),
                               "TEXCOORD_0": self._accessor(uniq[:, 6:8], "VEC2")},
                "indices": ia, "material": self.material(material)}
        self.doc["meshes"].append({"name": name, "primitives": [prim]})
        return len(self.doc["meshes"]) - 1

    def node(self, name, translation=(0, 0, 0), mesh=None, parent=None, rotation=None):
        n = {"name": name}
        if any(abs(t) > 1e-9 for t in translation):
            n["translation"] = [float(t) for t in translation]
        if rotation is not None:
            n["rotation"] = [float(q) for q in rotation]
        if mesh is not None:
            n["mesh"] = mesh
        self.doc["nodes"].append(n)
        i = len(self.doc["nodes"]) - 1
        if parent is None:
            self.doc["scenes"][0]["nodes"].append(i)
        else:
            self.doc["nodes"][parent].setdefault("children", []).append(i)
        return i

    def write(self, gltf_path, bin_path, extras=None):
        self.doc["buffers"][0]["byteLength"] = len(self.blob)
        if extras:
            self.doc["asset"]["extras"] = extras
        with open(bin_path, "wb") as f:
            f.write(self.blob)
        with open(gltf_path, "w") as f:
            json.dump(self.doc, f, separators=(",", ":"))


def append_hit_nodes(gltf_path, bin_path, pieces):
    """Hidden hit_* nodes (GemRender never draws them) under the scene root: [(name, points, faces)], model frame.
    Faces: convex index loops, fanned. POSITION + indices only."""
    for name, _, _ in pieces:
        assert name.startswith("hit_"), name
    with open(gltf_path) as f:
        doc = json.load(f)
    root = doc["nodes"][doc["scenes"][doc.get("scene", 0)]["nodes"][0]]
    assert not any(k in root for k in ("translation", "rotation", "scale", "matrix")), "root moved"
    append_nodes(gltf_path, bin_path, [{"name": name, "mesh": (points, faces)} for name, points, faces in pieces])


def append_nodes(gltf_path, bin_path, nodes):
    """Append nodes: dicts {name, parent: node name (absent = scene root), local translation, rotation (xyzw),
    scale, mesh: (points, faces) in node space}. Faces: convex index loops, fanned. POSITION + indices only.
    A node name already present => AssertionError."""
    with open(gltf_path) as f:
        doc = json.load(f)
    with open(bin_path, "rb") as f:
        blob = bytearray(f.read())
    root = doc["scenes"][doc.get("scene", 0)]["nodes"][0]
    names = [n.get("name") for n in doc["nodes"]]

    def view(data, target):
        while len(blob) % 4:
            blob.append(0)
        doc["bufferViews"].append({"buffer": 0, "byteOffset": len(blob), "byteLength": len(data), "target": target})
        blob.extend(data)
        return len(doc["bufferViews"]) - 1

    for spec in nodes:
        assert spec["name"] not in names, f"{gltf_path}: {spec['name']} exists"
        node = {"name": spec["name"]}
        for key in ("translation", "rotation", "scale"):
            if key in spec:
                node[key] = [float(v) for v in spec[key]]
        if "mesh" in spec:
            points, faces = spec["mesh"]
            pos = np.ascontiguousarray(points, dtype=np.float32)
            idx = np.ascontiguousarray([[f[0], f[k], f[k + 1]] for f in faces for k in range(1, len(f) - 1)],
                                       dtype=np.uint32).reshape(-1)
            doc["accessors"].append({"bufferView": view(pos.tobytes(), 34962), "componentType": 5126,
                                     "count": len(pos), "type": "VEC3",
                                     "min": pos.min(axis=0).tolist(), "max": pos.max(axis=0).tolist()})
            pa = len(doc["accessors"]) - 1
            doc["accessors"].append({"bufferView": view(idx.tobytes(), 34963), "componentType": 5125,
                                     "count": len(idx), "type": "SCALAR"})
            doc.setdefault("meshes", []).append({"name": spec["name"], "primitives": [
                {"attributes": {"POSITION": pa}, "indices": len(doc["accessors"]) - 1}]})
            node["mesh"] = len(doc["meshes"]) - 1
        parent = root if spec.get("parent") is None else names.index(spec["parent"])
        doc["nodes"].append(node)
        names.append(spec["name"])
        doc["nodes"][parent].setdefault("children", []).append(len(doc["nodes"]) - 1)
    doc["buffers"][0]["byteLength"] = len(blob)
    with open(bin_path, "wb") as f:
        f.write(blob)
    with open(gltf_path, "w") as f:
        json.dump(doc, f, separators=(",", ":"))


def hinge_keys(axis, degrees, seconds):
    """(times, xyzw quaternions): rotation about node-local `axis` ("x"|"y"|"z") 0 -> `degrees`; keys <= 90 deg apart
    so glTF slerp keeps the direction."""
    steps = max(1, int(np.ceil(abs(degrees) / 90.0)))
    times, values = [], []
    for k in range(steps + 1):
        half = np.radians(degrees * k / steps) / 2
        q = [0.0, 0.0, 0.0, float(np.cos(half))]
        q["xyz".index(axis)] = float(np.sin(half))
        times.append(seconds * k / steps)
        values.append(q)
    return times, values


def repack(doc, blob):
    """Drop accessors/bufferViews nothing references; rewrite the buffer densely. Returns the new blob."""
    used = set()
    for mesh in doc.get("meshes", []):
        for prim in mesh["primitives"]:
            used.update(prim["attributes"].values())
            if "indices" in prim:
                used.add(prim["indices"])
    for anim in doc.get("animations", []):
        for sampler in anim["samplers"]:
            used.update((sampler["input"], sampler["output"]))
    accessors = sorted(used)
    acc_map = {old: new for new, old in enumerate(accessors)}
    views = sorted({doc["accessors"][a]["bufferView"] for a in accessors}
                   | {i["bufferView"] for i in doc.get("images", []) if "bufferView" in i})
    view_map = {old: new for new, old in enumerate(views)}
    out = bytearray()
    new_views = []
    for old in views:
        v = doc["bufferViews"][old]
        while len(out) % 4:
            out.append(0)
        start = v.get("byteOffset", 0)
        new_views.append({**v, "byteOffset": len(out)})
        out.extend(blob[start:start + v["byteLength"]])
    doc["accessors"] = [{**doc["accessors"][a], "bufferView": view_map[doc["accessors"][a]["bufferView"]]}
                        for a in accessors]
    doc["bufferViews"] = new_views
    for image in doc.get("images", []):
        if "bufferView" in image:
            image["bufferView"] = view_map[image["bufferView"]]
    for mesh in doc.get("meshes", []):
        for prim in mesh["primitives"]:
            prim["attributes"] = {k: acc_map[v] for k, v in prim["attributes"].items()}
            if "indices" in prim:
                prim["indices"] = acc_map[prim["indices"]]
    for anim in doc.get("animations", []):
        for sampler in anim["samplers"]:
            sampler["input"], sampler["output"] = acc_map[sampler["input"]], acc_map[sampler["output"]]
    doc["buffers"][0]["byteLength"] = len(out)
    return out


def append_clip(gltf_path, bin_path, name, channels, absolute=False):
    """Append animation `name`: channels [(node name, path, times, values)], LINEAR. A rotation channel's node must
    rest at identity (the clip replaces it) unless `absolute` (values already include the rest). Existing clip `name`
    => AssertionError."""
    with open(gltf_path) as f:
        doc = json.load(f)
    with open(bin_path, "rb") as f:
        blob = bytearray(f.read())
    assert name not in [a.get("name") for a in doc.get("animations", [])], f"{gltf_path}: clip {name} exists"
    names = [n.get("name") for n in doc["nodes"]]

    def accessor(values, kind):
        arr = np.ascontiguousarray(values, dtype=np.float32)
        while len(blob) % 4:
            blob.append(0)
        doc["bufferViews"].append({"buffer": 0, "byteOffset": len(blob), "byteLength": arr.nbytes})
        blob.extend(arr.tobytes())
        entry = {"bufferView": len(doc["bufferViews"]) - 1, "componentType": 5126, "count": len(values), "type": kind}
        if kind == "SCALAR":
            entry.update(min=[float(arr.min())], max=[float(arr.max())])
        doc["accessors"].append(entry)
        return len(doc["accessors"]) - 1

    samplers, targets = [], []
    for node, path, times, values in channels:
        index = names.index(node)
        if path == "rotation" and not absolute:
            assert doc["nodes"][index].get("rotation", [0, 0, 0, 1]) == [0, 0, 0, 1], f"{node}: rest rotation"
        samplers.append({"input": accessor(times, "SCALAR"),
                         "output": accessor(values, {3: "VEC3", 4: "VEC4"}[len(values[0])]),
                         "interpolation": "LINEAR"})
        targets.append({"sampler": len(samplers) - 1, "target": {"node": index, "path": path}})
    doc.setdefault("animations", []).append({"name": name, "channels": targets, "samplers": samplers})
    doc["buffers"][0]["byteLength"] = len(blob)
    with open(bin_path, "wb") as f:
        f.write(blob)
    with open(gltf_path, "w") as f:
        json.dump(doc, f, separators=(",", ":"))


def read_markers(gltf_path, bin_path, prefix="socket_"):
    """`prefix` nodes of an existing glTF as append_nodes specs, so a restage can put them back; [] if none."""
    import os
    if not os.path.exists(gltf_path):
        return []
    with open(gltf_path) as f:
        doc = json.load(f)
    with open(bin_path, "rb") as f:
        blob = f.read()
    parent_of = {c: i for i, n in enumerate(doc["nodes"]) for c in n.get("children", [])}
    root = doc["scenes"][doc.get("scene", 0)]["nodes"][0]

    def accessor(index, dtype, width):
        a = doc["accessors"][index]
        v = doc["bufferViews"][a["bufferView"]]
        start = v.get("byteOffset", 0) + a.get("byteOffset", 0)
        count = a["count"] * {"SCALAR": 1, "VEC3": 3}[a["type"]]
        return np.frombuffer(blob, dtype=dtype, count=count, offset=start).reshape(-1, width)

    out = []
    for i, node in enumerate(doc["nodes"]):
        if not node.get("name", "").lower().startswith(prefix):
            continue
        assert not node.get("children"), f"{node['name']}: a marker carries no children"
        parent = parent_of[i]
        spec = {"name": node["name"], "parent": None if parent == root else doc["nodes"][parent]["name"]}
        for key in ("translation", "rotation", "scale"):
            if key in node:
                spec[key] = node[key]
        if "mesh" in node:
            primitive = doc["meshes"][node["mesh"]]["primitives"][0]
            points = accessor(primitive["attributes"]["POSITION"], np.float32, 3).tolist()
            tris = accessor(primitive["indices"], np.uint32 if doc["accessors"][primitive["indices"]]["componentType"]
                            == 5125 else np.uint16, 3).tolist()
            spec["mesh"] = (points, tris)
        out.append(spec)
    return out


def box(lo, hi):
    """Axis box as (points, faces) for append_hit_nodes."""
    (x0, y0, z0), (x1, y1, z1) = lo, hi
    points = [[x, y, z] for z in (z0, z1) for y in (y0, y1) for x in (x0, x1)]
    faces = [[0, 2, 3, 1], [4, 5, 7, 6], [0, 1, 5, 4], [2, 6, 7, 3], [0, 4, 6, 2], [1, 3, 7, 5]]
    return points, faces


def plane_hull(dirs, support, chosen, fixed=(), push=0.01, max_push=0.1):
    """Convex piece of half-spaces dirs[chosen].x <= support[chosen]; sliver faces (area or edge < 1e-3) lose their
    plane, a `fixed` one moves out by `push` (<= max_push, then dropped too) -> (points rounded 5, faces CCW from
    outside): flat and convex to ConvexHull's 1e-4."""
    chosen = list(chosen)
    support = np.array(support, dtype=float)
    pushed = np.zeros(len(support))
    verts = polytope(dirs[chosen], support[chosen])
    while True:
        faces, sliver = [], None
        for c in chosen:
            n, d = dirs[c], support[c]
            on = np.flatnonzero(np.abs(verts @ n - d) < 1e-6)
            if len(on) < 3:
                continue
            centre = verts[on].mean(axis=0)
            u = verts[on[0]] - centre
            u /= np.linalg.norm(u)
            rel = verts[on] - centre
            loop = on[np.argsort(np.arctan2(rel @ np.cross(n, u), rel @ u))]
            ring = verts[loop]
            area = np.linalg.norm(np.cross(ring - centre, np.roll(ring, -1, axis=0) - centre).sum(axis=0)) / 2
            edge = np.linalg.norm(ring - np.roll(ring, -1, axis=0), axis=1).min()
            if (area < 1e-3 or edge < 1e-3) and (sliver is None or area < sliver[1]):
                sliver = (c, area)
            faces.append([int(i) for i in loop])
        if sliver is None:
            break
        c = sliver[0]
        if c in fixed and pushed[c] < max_push:
            support[c] += push
            pushed[c] += push
        else:
            chosen.remove(c)  # plane dropped => piece grows, never cuts a point
        verts = polytope(dirs[chosen], support[chosen])
    used = sorted({i for f in faces for i in f})
    remap = {v: i for i, v in enumerate(used)}
    return [[round(float(x), 5) for x in verts[v]] for v in used], [[remap[v] for v in f] for f in faces]


def polytope(n, d):
    """Deduplicated vertices of the half-space intersection n.x <= d."""
    from itertools import combinations
    triples = np.array(list(combinations(range(len(n)), 3)))
    a = n[triples]
    ok = np.abs(np.linalg.det(a)) > 1e-9
    p = np.linalg.solve(a[ok], d[triples[ok]][..., None])[..., 0]
    p = p[np.all(p @ n.T <= d + 1e-7, axis=1)]
    return np.unique(np.round(p, 7), axis=0)
