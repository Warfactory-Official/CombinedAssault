#!/usr/bin/env python3
"""Jet `stores` mesh -> one node per store station (`store_<part>_<i>`, origin = store centre, +Z nose), listed as the
weapon's `round_bones` in the vehicle def (pack doc §4.5). Components left unclaimed stay in `stores`.

    python3 tools/split_stores.py [jet ...]      (from combinedassault/; in place, run once per staged model)

Station = approximate store axis (x, y, z) from the stores components; a component joins the nearest station within
CAPTURE (xy) and REACH (z), lateral first. Pods, wingtip rails and bays with no baked store: no station.
"""
import json
import os
import re
import struct
import sys

import numpy as np

PACK = "src/main/resources/combinedassault_packs/combinedassault"
MODELS = os.path.join(PACK, "assets/combinedassault/models/aircraft")
DEFS = os.path.join(PACK, "combinedassault/combinedassault/vehicles")
CAPTURE = 0.4
REACH = 2.0


def mirror(x, y, z):
    return [(x, y, z), (-x, y, z)]


# part_unit_id -> station axes; list order = fired first.
JETS = {
    "a10": {
        "ir_missile": [(-5.67, 1.90, -4.68), (-6.20, 1.90, -4.68)],
        "ag_missile": mirror(3.86, 1.61, -4.65),
        "bomb": mirror(1.83, 1.48, -4.82) + mirror(0.56, 1.46, -4.86),
    },
    "av8b": {
        "ir_missile": mirror(4.20, 1.28, -4.77),
        "ag_missile": mirror(3.41, 1.31, -4.46),
        "bomb": mirror(2.23, 1.25, -3.66) + mirror(1.84, 1.25, -3.66),
    },
    "su33": {
        "as_missile": mirror(1.08, 0.70, 1.16),
        "ar_missile": mirror(2.77, 2.10, 1.09),
    },
    "tornado": {
        "bomb": [p for z in (-2.33, -4.94) for p in mirror(0.34, 0.83, z) + mirror(0.50, 1.09, z) + mirror(0.17, 1.09, z)],
    },
    "f22a": {
        "ar_missile": mirror(0.74, 1.36, -4.78),
        "guided_bomb": [p for z in (-3.91, -5.67) for p in mirror(0.26, 1.32, z) + mirror(0.49, 1.32, z)],
    },
    "f35a": {
        "guided_bomb": [p for z in (-3.40, -5.15) for p in mirror(0.94, 1.81, z) + mirror(1.16, 1.81, z)],
    },
    "su47": {
        "ir_missile": mirror(0.19, 2.03, -1.95),
    },
}

FORMATS = {5126: ("f", 4), 5125: ("I", 4), 5123: ("H", 2), 5121: ("B", 1)}
WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def read(gltf, binary, index):
    a = gltf["accessors"][index]
    view = gltf["bufferViews"][a["bufferView"]]
    fmt, size = FORMATS[a["componentType"]]
    n = WIDTH[a["type"]]
    stride = view.get("byteStride", size * n)
    start = view.get("byteOffset", 0) + a.get("byteOffset", 0)
    rows = [struct.unpack_from("<" + fmt * n, binary, start + k * stride) for k in range(a["count"])]
    return np.array(rows, dtype=np.float64 if fmt == "f" else np.int64)


def components(positions, indices):
    parent = list(range(len(positions)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    seen = {}
    for i, p in enumerate(positions):
        key = tuple(np.round(p, 4))
        if key in seen:
            parent[find(i)] = find(seen[key])
        else:
            seen[key] = i
    for t in range(0, len(indices), 3):
        a, b, c = indices[t:t + 3]
        parent[find(b)] = find(a)
        parent[find(c)] = find(a)
    return np.array([find(i) for i in range(len(positions))])


class Writer:
    """Appends accessors; `compact` then drops every buffer view nothing references."""

    def __init__(self, gltf, binary):
        self.gltf = gltf
        self.binary = bytearray(binary)

    def add(self, array, component, kind, target):
        fmt, size = FORMATS[component]
        while len(self.binary) % 4:
            self.binary.append(0)
        data = struct.pack("<" + fmt * array.size, *array.flatten().tolist())
        self.gltf["bufferViews"].append({"buffer": 0, "byteOffset": len(self.binary), "byteLength": len(data),
                                         "target": target})
        self.binary += data
        accessor = {"bufferView": len(self.gltf["bufferViews"]) - 1, "componentType": component,
                    "count": len(array), "type": kind}
        if kind == "VEC3" and component == 5126:
            accessor["min"] = array.min(0).tolist()
            accessor["max"] = array.max(0).tolist()
        self.gltf["accessors"].append(accessor)
        return len(self.gltf["accessors"]) - 1

    def compact(self):
        g = self.gltf
        used = set()
        for mesh in g["meshes"]:
            for p in mesh["primitives"]:
                used.update(p["attributes"].values())
                if "indices" in p:
                    used.add(p["indices"])
        for anim in g.get("animations", []):
            for s in anim["samplers"]:
                used.update((s["input"], s["output"]))
        for skin in g.get("skins", []):
            if "inverseBindMatrices" in skin:
                used.add(skin["inverseBindMatrices"])
        amap = {old: new for new, old in enumerate(sorted(used))}
        g["accessors"] = [g["accessors"][old] for old in sorted(used)]
        for mesh in g["meshes"]:
            for p in mesh["primitives"]:
                p["attributes"] = {k: amap[v] for k, v in p["attributes"].items()}
                if "indices" in p:
                    p["indices"] = amap[p["indices"]]
        for anim in g.get("animations", []):
            for s in anim["samplers"]:
                s["input"], s["output"] = amap[s["input"]], amap[s["output"]]
        for skin in g.get("skins", []):
            if "inverseBindMatrices" in skin:
                skin["inverseBindMatrices"] = amap[skin["inverseBindMatrices"]]
        views = sorted({a["bufferView"] for a in g["accessors"]} | {i["bufferView"] for i in g.get("images", [])
                                                                     if "bufferView" in i})
        out = bytearray()
        vmap = {}
        kept = []
        for old in views:
            v = dict(g["bufferViews"][old])
            while len(out) % 4:
                out.append(0)
            start = v.get("byteOffset", 0)
            chunk = self.binary[start:start + v["byteLength"]]
            v["byteOffset"] = len(out)
            out += chunk
            vmap[old] = len(kept)
            kept.append(v)
        g["bufferViews"] = kept
        for a in g["accessors"]:
            a["bufferView"] = vmap[a["bufferView"]]
        for i in g.get("images", []):
            if "bufferView" in i:
                i["bufferView"] = vmap[i["bufferView"]]
        g["buffers"][0]["byteLength"] = len(out)
        return bytes(out)


def split(jet, stations):
    path = os.path.join(MODELS, jet, jet + ".gltf")
    gltf = json.load(open(path))
    binary = open(os.path.join(MODELS, jet, jet + ".bin"), "rb").read()
    nodes = gltf["nodes"]
    if any(n["name"].startswith("store_") for n in nodes):
        sys.exit(f"{jet}: already split")
    store = next(i for i, n in enumerate(nodes) if n["name"] == "stores")
    parent = next(i for i, n in enumerate(nodes) if store in n.get("children", []))
    (prim,) = gltf["meshes"][nodes[store]["mesh"]]["primitives"]
    attrs = {k: read(gltf, binary, v) for k, v in prim["attributes"].items()}
    kinds = {k: (gltf["accessors"][v]["componentType"], gltf["accessors"][v]["type"]) for k, v in
             prim["attributes"].items()}
    indices = read(gltf, binary, prim["indices"]).flatten()
    positions = attrs["POSITION"]
    roots = components(positions, indices)

    axes = [(part, i, np.array(p)) for part, ps in stations.items() for i, p in enumerate(ps)]
    owner = {}
    for root in np.unique(roots):
        verts = positions[roots == root]
        centre = (verts.min(0) + verts.max(0)) / 2
        best = None
        for k, (_, _, axis) in enumerate(axes):
            lateral = round(float(np.hypot(*(centre[:2] - axis[:2]))), 2)
            along = abs(centre[2] - axis[2])
            if lateral <= CAPTURE and along <= REACH and (best is None or (lateral, along) < best[0]):
                best = ((lateral, along), k)
        if best is not None:
            owner[root] = best[1]
    triangle_owner = np.array([owner.get(roots[indices[t]], -1) for t in range(0, len(indices), 3)])

    writer = Writer(gltf, binary)

    def primitive(triangles, offset):
        tri = indices.reshape(-1, 3)[triangles].flatten()
        verts, remap = np.unique(tri, return_inverse=True)
        out = {}
        for k, values in attrs.items():
            data = values[verts] - (offset if k == "POSITION" else 0)
            component, kind = kinds[k]
            out[k] = writer.add(data.astype(np.float32), component, kind, 34962)
        index_type = 5123 if len(verts) < 65536 else 5125
        p = {"attributes": out, "indices": writer.add(remap.astype(np.uint32), index_type, "SCALAR", 34963)}
        if "material" in prim:
            p["material"] = prim["material"]
        return p

    bones = {part: [] for part in stations}
    for k, (part, i, _) in enumerate(axes):
        mask = triangle_owner == k
        if not mask.any():
            sys.exit(f"{jet}: station {part} {i} claims nothing")
        verts = positions[np.unique(indices.reshape(-1, 3)[mask])]
        centre = (verts.min(0) + verts.max(0)) / 2
        name = f"store_{part}_{i}"
        gltf["meshes"].append({"name": name, "primitives": [primitive(mask, centre)]})
        nodes.append({"name": name, "translation": [round(float(c), 6) for c in centre],
                      "mesh": len(gltf["meshes"]) - 1})
        nodes[parent]["children"].append(len(nodes) - 1)
        bones[part].append(name)
        print(f"{jet} {name} centre {np.round(centre, 3).tolist()} triangles {int(mask.sum())}")
    rest = triangle_owner < 0
    if rest.any():
        gltf["meshes"][nodes[store]["mesh"]]["primitives"] = [primitive(rest, np.zeros(3))]
        print(f"{jet} stores keeps {int(rest.sum())} triangles")
    else:
        del nodes[store]["mesh"]
    binary = writer.compact()
    with open(path, "w") as f:
        json.dump(gltf, f, indent=1)
    with open(os.path.join(MODELS, jet, jet + ".bin"), "wb") as f:
        f.write(binary)
    return bones


def write_bones(jet, bones):
    path = os.path.join(DEFS, jet + ".json")
    text = open(path).read()
    for part, names in bones.items():
        pattern = re.compile(r'\n(\s*)"part_unit_id": "' + re.escape(part) + '"')
        if len(pattern.findall(text)) != 1:
            sys.exit(f"{jet}: weapon entry {part} not unique")
        listed = ", ".join(f'"{n}"' for n in names)
        text = pattern.sub(lambda m: f'{m.group(0)},\n{m.group(1)}"round_bones": [{listed}]', text)
    open(path, "w").write(text)


def main():
    for jet in sys.argv[1:] or JETS:
        write_bones(jet, split(jet, JETS[jet]))


if __name__ == "__main__":
    main()
