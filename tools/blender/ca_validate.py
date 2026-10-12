#!/usr/bin/env python3
"""Check a pack glTF the way the game loads it (ModelRig, ConvexHull, GemRender materials). No Blender needed.

    python3 tools/blender/ca_validate.py <model.gltf> [...]      exit 1 on any error

Pack root = the folder above assets/<ns>/models/. Also imported by the Blender add-on (vocabularies, post-export check).
"""
import base64
import json
import math
import os
import re
import struct
import sys

# Must equal the game's vocabularies; test_combinedassault_tools.py parses the Java sources and compares.
# ComponentKind names but crew, generic.
SYSTEMS = ["hull", "engine", "track", "wheel", "turret", "barrel", "fuel", "ammo", "optics", "rotor",
           "tail_rotor", "wing"]
# NodeRole SOCKET kind.
SOCKET_FIELDS = ["seat", "dismount", "view", "sight", "bolt", "era", "slat", "mount_point", "hitch", "towable",
                 "axle", "exhaust", "vortex", "smoke", "afterburner", "camera", "camera_zoomed", "light", "cargo_bay",
                 "center_of_mass", "pivot", "track", "propeller"]
# Socket fields with no interface (MountDefaults `none`).
UNTARGETED = {"towable", "axle", "camera", "camera_zoomed", "exhaust", "vortex", "smoke", "afterburner", "light",
              "cargo_bay", "center_of_mass", "pivot", "propeller"}
FRAME_ROLES = {"body", "part", "pitch"}
SHAPE_ROLES = {"collider", "hit", "hit_surface"}
ROLES = FRAME_ROLES | SHAPE_ROLES | set(SOCKET_FIELDS)
# ModelRig.COLLIDER_KEYS.
COLLIDER_KEYS = {"main", "deck", "solid", "world", "name", "hit"}
ROLE = "combinedassaultRole"
INTERFACE = "combinedassaultInterface"
META = "combinedassault"
HIDDEN = "gemrenderHidden"
RESOURCE = "resourceLocation"
# GltfMaterial.CUTOUTS: other cutoffs snap to the nearest.
CUTOUTS = (0.0, 0.1, 0.5)
# Clip name == part id, or <part id>.<anything>: the game scrubs it by that part's progress.
CLIP_NAME = re.compile(r"^[a-z0-9_]+(\.[A-Za-z0-9_.]+)?$")
# ConvexHull.EPS / WELD, blocks.
EPS = 1.0e-4
MAPS = ("normalTexture", "occlusionTexture", "emissiveTexture")
VARIANTS = ("_damaged", "_wrecked", "_paint")
PNG = b"\x89PNG\r\n\x1a\n"
COMPONENTS = {5120: "b", 5121: "B", 5122: "h", 5123: "H", 5125: "I", 5126: "f"}
WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def pack_of(gltf_path):
    """(pack root, namespace) from .../assets/<ns>/models/...; (None, None) outside a pack."""
    parts = os.path.abspath(gltf_path).split(os.sep)
    for i in range(len(parts) - 3, 0, -1):
        if parts[i] == "assets" and parts[i + 2] == "models":
            return os.sep.join(parts[:i]) or os.sep, parts[i + 1]
    return None, None


def resolve(pack_root, location):
    """`ns:path` -> file under <pack>/assets/ns/; None when unparseable or no pack."""
    if pack_root is None or ":" not in location:
        return None
    ns, rel = location.split(":", 1)
    return os.path.join(pack_root, "assets", ns, *rel.split("/"))


def quat_matrix(q):
    x, y, z, w = q
    return [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]


def local_matrix(node):
    if "matrix" in node:
        m = node["matrix"]
        return [[m[c * 4 + r] for c in range(4)] for r in range(4)]
    r = quat_matrix(node.get("rotation", [0, 0, 0, 1]))
    s = node.get("scale", [1, 1, 1])
    t = node.get("translation", [0, 0, 0])
    return [[r[i][0] * s[0], r[i][1] * s[1], r[i][2] * s[2], t[i]] for i in range(3)] + [[0, 0, 0, 1]]


def mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def apply(m, p):
    return tuple(m[i][0] * p[0] + m[i][1] * p[1] + m[i][2] * p[2] + m[i][3] for i in range(3))


def is_identity(m):
    return all(abs(m[i][j] - (1.0 if i == j else 0.0)) < 1.0e-5 for i in range(4) for j in range(4))


class Model:
    """Parsed glTF with buffers loaded; problems while loading go to errors."""

    def __init__(self, path):
        self.path = path
        self.root, self.ns = pack_of(path)
        with open(path, encoding="utf-8") as source:
            self.gltf = json.load(source)
        self.errors, self.warnings, self.notes = [], [], []
        self.buffers = [self.load_buffer(i, b) for i, b in enumerate(self.gltf.get("buffers", []))]
        nodes = self.gltf.get("nodes", [])
        self.parent = {c: i for i, n in enumerate(nodes) for c in n.get("children", [])}
        self.world = [None] * len(nodes)

    def load_buffer(self, index, buffer):
        uri = buffer.get("uri")
        if uri is not None and uri.startswith("data:"):
            return base64.b64decode(uri.split(",", 1)[1])
        if uri is not None:
            self.errors.append("buffer %d has uri '%s': the game reads extras.%s only" % (index, uri, RESOURCE))
            path = os.path.join(os.path.dirname(self.path), uri)
        else:
            location = buffer.get("extras", {}).get(RESOURCE)
            if location is None:
                self.errors.append("buffer %d has neither uri nor extras.%s" % (index, RESOURCE))
                return None
            path = resolve(self.root, location)
            if path is None:
                self.warnings.append("buffer %d: not inside assets/<ns>/models/, %s unchecked" % (index, location))
                return None
        if not os.path.isfile(path):
            self.errors.append("buffer %d: file missing %s" % (index, path))
            return None
        with open(path, "rb") as source:
            data = source.read()
        if len(data) < buffer.get("byteLength", 0):
            self.errors.append("buffer %d: %s is %d bytes, byteLength %d"
                               % (index, os.path.basename(path), len(data), buffer["byteLength"]))
        return data

    def node_world(self, i):
        if self.world[i] is None:
            local = local_matrix(self.gltf["nodes"][i])
            self.world[i] = local if i not in self.parent else mul(self.node_world(self.parent[i]), local)
        return self.world[i]

    def accessor(self, index):
        """List of tuples, or None when the data is unavailable."""
        acc = self.gltf["accessors"][index]
        if "bufferView" not in acc or "sparse" in acc:
            return None
        view = self.gltf["bufferViews"][acc["bufferView"]]
        data = self.buffers[view["buffer"]]
        if data is None:
            return None
        fmt = COMPONENTS[acc["componentType"]]
        width = WIDTH[acc["type"]]
        size = struct.calcsize("<" + fmt)
        stride = view.get("byteStride") or size * width
        base = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
        return [struct.unpack_from("<%d%s" % (width, fmt), data, base + i * stride) for i in range(acc["count"])]

    def triangles(self, mesh_index):
        """World-free triangles (vertex tuples) per primitive."""
        out = []
        for primitive in self.gltf["meshes"][mesh_index]["primitives"]:
            if primitive.get("mode", 4) != 4:
                continue
            points = self.accessor(primitive["attributes"]["POSITION"])
            if points is None:
                return None
            order = [i[0] for i in self.accessor(primitive["indices"])] if "indices" in primitive \
                else list(range(len(points)))
            out.append([points[order[k]] for k in range(len(order) - len(order) % 3)])
        return out


def roles(model):
    nodes = model.gltf.get("nodes", [])
    body = [i for i, n in enumerate(nodes) if n.get("extras", {}).get(ROLE) == "body"]
    # No body: a part model (gun, turret on a part frame) or a plain display; its sockets take the part's interface.
    rig = bool(body)
    if not rig:
        model.notes.append("no body node: part or display model (vehicle models need one)")
    if len(body) > 1:
        model.errors.append("needs exactly one node with role body, has %d%s"
                            % (len(body), " (%s)" % ", ".join(nodes[i].get("name", "?") for i in body) if body else ""))
    elif rig and not is_identity(model.node_world(body[0])):
        model.errors.append("body node %s is not at the model origin, unrotated, unscaled" % nodes[body[0]].get("name"))
    under = set()

    def mark(i):
        under.add(i)
        for c in nodes[i].get("children", []):
            mark(c)
    for b in body:
        mark(b)
    seen, mains = {}, []
    for i, node in enumerate(nodes):
        extras = node.get("extras", {})
        role = extras.get(ROLE, "")
        name = node.get("name", "node %d" % i)
        iface = extras.get(INTERFACE, "")
        meta = extras.get(META, {})
        if not role:
            continue
        if role not in ROLES:
            model.errors.append("%s: unknown role '%s'" % (name, role))
            continue
        if rig and role != "body" and i not in under:
            model.errors.append("%s (%s) is not under the body node" % (name, role))
        if role in ("part", "pitch"):
            if not iface:
                model.errors.append("%s: %s frame names no part (%s)" % (name, role, INTERFACE))
            elif (role, iface) in seen:
                model.errors.append("%s %s on %s and %s" % (role, iface, seen[(role, iface)], name))
            seen[(role, iface)] = name
        if rig and role in SOCKET_FIELDS:
            if role in UNTARGETED and iface:
                model.errors.append("%s: socket %s takes no interface" % (name, role))
            if role not in UNTARGETED and not iface:
                model.errors.append("%s: socket %s names no interface" % (name, role))
        if role in SHAPE_ROLES | set(SOCKET_FIELDS) and not extras.get(HIDDEN):
            model.warnings.append("%s (%s) is drawn: set %s" % (name, role, HIDDEN))
        if role == "collider":
            unknown = sorted(set(meta) - COLLIDER_KEYS)
            if unknown:
                model.errors.append("collider %s: unknown key(s) %s" % (name, ", ".join(unknown)))
            if meta.get("main"):
                mains.append(name)
                if not frame_is_body(model, i):
                    model.errors.append("main collider %s rides a part" % name)
            if "mesh" not in node:
                model.errors.append("collider %s has no mesh" % name)
            else:
                for problem in hull_problems(model, i):
                    model.errors.append("collider %s: %s" % (name, problem))
        if role == "hit" and meta.get("system") not in SYSTEMS:
            model.errors.append("hit module %s: system '%s' not one of %s" % (name, meta.get("system"), SYSTEMS))
    if len(mains) > 1:
        model.errors.append("more than one main collider: %s" % ", ".join(mains))


def frame_is_body(model, i):
    nodes = model.gltf["nodes"]
    while i in model.parent:
        i = model.parent[i]
        role = nodes[i].get("extras", {}).get(ROLE)
        if role in ("part", "pitch"):
            return False
        if role == "body":
            return True
    return True


def newell(points, loop):
    n = [0.0, 0.0, 0.0]
    for k in range(len(loop)):
        a, b = points[loop[k]], points[loop[(k + 1) % len(loop)]]
        n[0] += (a[1] - b[1]) * (a[2] + b[2])
        n[1] += (a[2] - b[2]) * (a[0] + b[0])
        n[2] += (a[0] - b[0]) * (a[1] + b[1])
    return n


def collider_faces(model, node, matrix=None):
    """ModelRig.hull faces: welded points (matrix applied), one loop per primitive, else one per plane; loops
    ordered counter-clockwise seen from outside. (points, loops); None when the data is unavailable."""
    triangles = model.triangles(model.gltf["nodes"][node]["mesh"])
    if triangles is None:
        return None
    points, groups = [], []
    for primitive in triangles:
        welded = []
        for p in primitive:
            v = apply(matrix, p) if matrix else tuple(p)
            found = next((j for j, q in enumerate(points) if sum((a - b) ** 2 for a, b in zip(v, q)) < EPS * EPS),
                         None)
            if found is None:
                points.append(v)
                found = len(points) - 1
            welded.append(found)
        groups.append(welded)
    if len(groups) == 1:
        planes = []
        tris = groups[0]
        for k in range(0, len(tris), 3):
            n = cross(points, tris[k:k + 3])
            length = math.sqrt(sum(x * x for x in n))
            if length < 1.0e-6:
                continue
            n = [x / length for x in n]
            d = sum(n[j] * points[tris[k]][j] for j in range(3))
            same = next((q for q in planes if sum(q[0][j] * n[j] for j in range(3)) > 0.99985 and abs(q[1] - d) < EPS),
                        None)
            if same is None:
                planes.append((n, d, list(tris[k:k + 3])))
            else:
                same[2].extend(tris[k:k + 3])
        groups = [q[2] for q in planes]
    centre = [sum(p[j] for p in points) / max(1, len(points)) for j in range(3)]
    loops = []
    for group in groups:
        loop = list(dict.fromkeys(group))
        n = [0.0, 0.0, 0.0]
        for k in range(0, len(group) - 2, 3):
            n = [a + b for a, b in zip(n, cross(points, group[k:k + 3]))]
        mid = [sum(points[v][j] for v in loop) / len(loop) for j in range(3)]
        if sum(n[j] * (mid[j] - centre[j]) for j in range(3)) < 0:
            n = [-x for x in n]
        u = [points[loop[0]][j] - mid[j] for j in range(3)]
        w = [n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]]
        loop.sort(key=lambda v: math.atan2(sum((points[v][j] - mid[j]) * w[j] for j in range(3)),
                                           sum((points[v][j] - mid[j]) * u[j] for j in range(3))))
        loops.append(loop)
    return points, loops


def cross(points, tri):
    a, b, c = (points[v] for v in tri)
    u = [b[j] - a[j] for j in range(3)]
    w = [c[j] - a[j] for j in range(3)]
    return [u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0]]


def hull_problems(model, i):
    """ConvexHull.of on the collider's world faces."""
    built = collider_faces(model, i, model.node_world(i))
    if built is None:
        return []
    points, loops = built
    if len(points) < 4:
        return ["needs at least 4 vertices, has %d" % len(points)]
    if len(loops) < 4:
        return ["needs at least 4 faces, has %d" % len(loops)]
    for loop in loops:
        n = newell(points, loop)
        length = math.sqrt(sum(x * x for x in n))
        if length < EPS:
            return ["a face is degenerate (zero area)"]
        n = [x / length for x in n]
        d = sum(n[j] * points[loop[0]][j] for j in range(3))
        if max(abs(sum(n[j] * points[v][j] for j in range(3)) - d) for v in loop) > EPS:
            return ["a face is not flat"]
        outside = max(sum(n[j] * p[j] for j in range(3)) - d for p in points)
        if outside > EPS:
            return ["not convex (a vertex sits %.4f outside a face): split it or rebuild as a convex hull" % outside]
    return []


def materials(model):
    gltf = model.gltf
    images = gltf.get("images", [])
    used = set()
    for index, image in enumerate(images):
        name = image.get("name", "image %d" % index)
        if "uri" in image:
            model.errors.append("image %s has uri '%s': GemRender draws it untextured (needs extras.%s)"
                                % (name, image["uri"], RESOURCE))
            continue
        location = image.get("extras", {}).get(RESOURCE)
        if location is None:
            model.errors.append("image %s has no extras.%s: drawn untextured" % (name, RESOURCE))
            continue
        path = resolve(model.root, location)
        if path is None:
            continue
        if not location.split(":", 1)[1].startswith("textures/"):
            model.warnings.append("image %s: %s is outside textures/" % (name, location))
        if not os.path.isfile(path):
            model.errors.append("image %s: file missing %s" % (name, location))
            continue
        with open(path, "rb") as source:
            if source.read(8) != PNG:
                model.errors.append("image %s: %s is not a PNG" % (name, location))
                continue
        stem = path[:-4]
        found = [suffix for suffix in VARIANTS if os.path.isfile(stem + suffix + ".png")]
        model.notes.append("texture %s%s" % (location, " + " + ", ".join(found) if found else ""))
    textures = gltf.get("textures", [])
    for index, material in enumerate(gltf.get("materials", [])):
        name = material.get("name", "material %d" % index)
        pbr = material.get("pbrMetallicRoughness", {})
        refs = [("baseColorTexture", pbr.get("baseColorTexture")),
                ("metallicRoughnessTexture", pbr.get("metallicRoughnessTexture"))] + \
               [(key, material.get(key)) for key in MAPS]
        for key, ref in refs:
            if ref is None:
                continue
            if ref.get("texCoord", 0) != 0:
                model.errors.append("material %s: %s uses UV map %d; GemRender reads the first UV map only"
                                    % (name, key, ref["texCoord"]))
            if "KHR_texture_transform" in ref.get("extensions", {}):
                model.warnings.append("material %s: %s has a texture transform (Mapping node); the game ignores it"
                                      % (name, key))
            texture = textures[ref["index"]] if ref.get("index", -1) < len(textures) else None
            if texture is None or texture.get("source") is None:
                model.errors.append("material %s: %s points at no image" % (name, key))
            else:
                used.add(texture["source"])
        if pbr.get("baseColorTexture") is None:
            model.warnings.append("material %s has no base colour texture: drawn in its flat colour" % name)
        mode = material.get("alphaMode", "OPAQUE")
        cutoff = material.get("alphaCutoff", 0.5)
        if mode == "MASK" and min(abs(cutoff - c) for c in CUTOUTS) > 0.05:
            model.warnings.append("material %s: alpha cutoff %.2f snaps to %.1f in game"
                                  % (name, cutoff, min(CUTOUTS, key=lambda c: abs(cutoff - c))))
        if mode == "BLEND":
            model.notes.append("material %s is see-through (BLEND): sorted, costlier than cut-out (MASK)" % name)
    for index, image in enumerate(images):
        if index not in used:
            model.warnings.append("image %s is not used by any material" % image.get("name", index))
    for mesh in gltf.get("meshes", []):
        for primitive in mesh.get("primitives", []):
            material = primitive.get("material")
            textured = material is not None and \
                gltf["materials"][material].get("pbrMetallicRoughness", {}).get("baseColorTexture") is not None
            if textured and "TEXCOORD_0" not in primitive.get("attributes", {}):
                model.errors.append("mesh %s: textured but has no UV map" % mesh.get("name", "?"))


def animations(model):
    names = [n.get("name") for n in model.gltf.get("nodes", [])]
    for clip in model.gltf.get("animations", []):
        name = clip.get("name", "")
        if not CLIP_NAME.match(name):
            model.warnings.append("clip '%s' is not <part id>[.<suffix>]: the game never plays it" % name)
        for channel in clip.get("channels", []):
            target = channel.get("target", {}).get("node")
            if target is None or target >= len(names):
                model.errors.append("clip '%s' targets a missing node" % name)


def validate(path):
    """(errors, warnings, notes) for one glTF file."""
    try:
        model = Model(path)
    except (OSError, ValueError) as e:
        return ["cannot read: %s" % e], [], []
    if model.root is None:
        model.warnings.append("not inside <pack>/assets/<ns>/models/: file references unchecked")
    roles(model)
    materials(model)
    animations(model)
    return model.errors, model.warnings, model.notes


def main(argv):
    failed = 0
    for path in argv:
        errors, warnings, notes = validate(path)
        print("%s: %d error(s), %d warning(s)" % (path, len(errors), len(warnings)))
        for kind, messages in (("ERROR", errors), ("warning", warnings), ("note", notes)):
            for message in messages:
                print("  %s %s" % (kind, message))
        failed += bool(errors)
    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
