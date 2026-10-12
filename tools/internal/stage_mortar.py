#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage the M252A1 mortar glTF into default_vehicle and derive its structure model.

The export is a flat list of 172 loose parts pointing down -Z, which is three things short of a
vehicle: it faces backwards, nothing in it is grouped, and the tube is modelled at its rest lean
rather than at the zero the part system poses from. So this does four things, in this order:

1. **Turn it to face +Z**, the vehicle frame's forward. A 180 degree yaw, applied to the vertex data
   and to every node's own transform together, so the model is natively in the vehicle frame and
   nothing downstream has to know it was ever turned. A root rotation would not do: the two posed
   bones sit under the root, so a yaw there would put their rotation axes in a turned frame and the
   part bindings would drive them the wrong way round.
2. **Group it into the three pieces that move differently.** The baseplate is dug in and stays put,
   the bipod and the tube swing together in azimuth, and the tube alone elevates. Both moving groups
   pivot on the socket ball, which is where a real one pivots.
3. **Take the rest lean out of the tube.** A pose replaces a bone's rotation rather than adding to
   it, so a tube modelled at 62 degrees and then posed to 62 would end up at 124. The lean is
   therefore rotated out of the tube group's children, leaving the tube lying along +Z at a bone
   rotation of zero, which is the angle the structure model and the pack's x_rot are measured from.
4. **Give the finish a texture to live in.** The export paints itself with nine baseColorFactors and no
   image at all, and GemRender only considers a material for its sheet when the material carries a
   texture or PBR maps, so every one of them fell through to untextured and the whole mortar drew
   white. The colours are therefore baked into a real sheet here, one cell per material, with each
   primitive's texture coordinates pinned to the middle of its own cell. That converts them out of
   linear on the way, which they need anyway: glTF states baseColorFactor in linear light and a
   Minecraft texture is read as though it were sRGB.

Run from the combinedassault directory:  python3 tools/internal/stage_mortar.py
"""
import json
import math
import os
import struct

import numpy as np
import mqo
import strip_cubes  # noqa: E402

HOME = os.path.expanduser("~")
SRC = f"{HOME}/models/warty/m252a1.zip"
MEMBER = "M252A1_semilow.glb"
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"
ID = "m252a1"

# The material sheet: a grid of flat cells, one per material, sampled dead centre. Large cells rather
# than a pixel each because a texel on a cell boundary is a texel that can be filtered across.
SHEET_CELLS = 4
SHEET_CELL = 16

# The tube pivots on this ball, and so does the bipod that carries it. Taken from the tube node's own
# translation rather than measured off the seat's box, because that is the point the part was built about.
PIVOT_FROM = "Tube | twelve sided silhouette"

# The two ends of the elevating screw, which is the one part of the mortar that changes length. Both
# are taken from the node that sits on the joint rather than measured off a box: the bipod's gear case
# carries the screw's lower pin, and the clevis head is its upper one.
SCREW_FOOT = "Bipod central gear case"
SCREW_HEAD = "Elevation pivot head"

# The elevation the tube is rotated back to horizontal from, and the resting x_rot the pack declares.
# The export's own lean is printed at staging time; the two agree to a thirtieth of a degree.
REST_ELEVATION = 62.0

# The band the tube is laid within. The floor is not the 45 degrees an M252 is rated to: the bipod's
# elevating screw runs out of travel first, because a real crew shifts the bipod for a gross change of
# elevation and the screw only trims it. Below about 53 degrees the two pins are closer together than
# the screw housing is long, which no amount of animation can draw.
MIN_ELEVATION = 55.0
MAX_ELEVATION = 85.0

COMPONENT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}

# The plate that is dug into the ground: it neither traverses nor elevates.
BASEPLATE = ("Baseplate ", "Cast baseplate", "Cast rim land", "Central socket casting",
             "Handle pivot hex head", "Inner structural hub", "Outer perimeter rolled edge",
             "Raised web reinforcement", "Socket retaining bolt", "Underside ground cleat")

# The tube and everything the tube carries: the barrel clamp that grips it, the cradle and bridge the
# clamp is bolted to, the sight on the clamp's dovetail, and the traversing gear built into the bridge.
TUBE = ("Tube | ", "Cooling jacket", "Lower tube collar", "Upper transition band",
        "Visible exterior spherical seat", "Muzzle", "Split barrel clamp", "Clamp ",
        "Clevis locking pin", "Sight | ", "Open cradle", "Traverse bridge casting",
        "Bridge top fastener", "Traverse drive drum", "Traverse drum shoulder",
        "Traversing handwheel", "Traversing slide rail", "Slide end ferrule", "Slide spring cover",
        "Cradle clevis pivot", "Elevation pivot head", "Elevation pivot end hex head")

# The elevating screw: a housing pinned to the bipod, carrying the crank that turns it, and a spindle
# that slides out of the housing far enough to reach wherever the tube has put the bridge.
SCREW_HOUSING = ("Elevation column outer housing", "Elevation housing upper gland",
                 "Elevation adjustment wheel", "Elevating crank", "Elevating folding handle",
                 "Elevating handle end")
SCREW_SPINDLE = ("Elevation exposed spindle",)

# How long the spindle is staged, in blocks. The screw's two pins move apart as the tube elevates and a
# rod cannot lengthen, because a posed bone's translation and scale are not honoured by the renderer and
# only its rotation is. So the screw is two rods instead: the housing stands on the lower pin and the spindle
# hangs from the upper one, and the two overlap by however much slack is left. This is the length that
# keeps them overlapping at the top of the elevation band while the spindle's own foot stays buried in
# the housing at the bottom of it; stage_mortar prints both margins.
SPINDLE_LENGTH = 0.32


def read_glb(path, member):
    import zipfile
    with zipfile.ZipFile(path) as zf:
        blob = zf.read(member)
    magic, version, length = struct.unpack("<III", blob[:12])
    if magic != 0x46546C67 or version != 2:
        raise SystemExit("not a glTF 2.0 binary")
    doc = None
    body = None
    off = 12
    while off < length:
        size, kind = struct.unpack("<II", blob[off:off + 8])
        chunk = blob[off + 8:off + 8 + size]
        if kind == 0x4E4F534A:
            doc = json.loads(chunk)
        elif kind == 0x004E4942:
            body = bytearray(chunk)
        off += 8 + size
    if doc is None or body is None:
        raise SystemExit("glb is missing a chunk")
    return doc, body


def accessor_view(doc, blob, index):
    acc = doc["accessors"][index]
    bv = doc["bufferViews"][acc["bufferView"]]
    if bv.get("byteStride"):
        raise SystemExit("strided buffer views are not handled")
    dtype = COMPONENT[acc["componentType"]]
    width = WIDTH[acc["type"]]
    start = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
    count = acc["count"] * width
    view = np.frombuffer(blob, dtype=dtype, count=count, offset=start)
    return acc, view.reshape(acc["count"], width)


def yaw_half_turn(doc, blob):
    """Turn the whole model through 180 degrees about Y: geometry, then every node's own transform.

    A half turn about Y negates X and Z and leaves handedness alone, so unlike a mirror there is no
    winding to flip. Positions and normals both get it; so do node translations, while node rotations
    are conjugated by the same turn, which for this one comes out as negating the quaternion's x and z.
    """
    done = set()
    for mesh in doc["meshes"]:
        for prim in mesh["primitives"]:
            for name, index in prim["attributes"].items():
                if name not in ("POSITION", "NORMAL") or index in done:
                    continue
                done.add(index)
                acc, view = accessor_view(doc, blob, index)
                view[:, 0] *= -1
                view[:, 2] *= -1
                if "min" in acc:
                    lo, hi = acc["min"], acc["max"]
                    lo[0], hi[0] = -hi[0], -lo[0]
                    lo[2], hi[2] = -hi[2], -lo[2]
    for node in doc["nodes"]:
        if "matrix" in node:
            raise SystemExit("matrix nodes cannot be posed, and cannot be turned here either")
        if "translation" in node:
            node["translation"][0] = -node["translation"][0]
            node["translation"][2] = -node["translation"][2]
        if "rotation" in node:
            q = node["rotation"]
            q[0], q[2] = -q[0], -q[2]


def quat_matrix(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def quat_multiply(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return [aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz]


def pitch_quaternion(degrees):
    half = math.radians(degrees) / 2
    return [math.sin(half), 0.0, 0.0, math.cos(half)]


def local_matrix(node):
    m = np.eye(4)
    r = quat_matrix(node.get("rotation", [0, 0, 0, 1]))
    m[:3, :3] = r @ np.diag(node.get("scale", [1, 1, 1]))
    m[:3, 3] = node.get("translation", [0, 0, 0])
    return m


def classify(name):
    if name.startswith(BASEPLATE):
        return "baseplate"
    if name.startswith(TUBE):
        return "tube"
    if name.startswith(SCREW_HOUSING):
        return "screw_housing"
    if name.startswith(SCREW_SPINDLE):
        return "screw_spindle"
    return "traverse"


def tube_elevation(doc, node):
    """The elevation the export models the tube at, read off the tube's own axis.

    The tube is a cylinder whose local -Y runs out of the muzzle, so the rotation on its node is the
    whole of its lean and there is no need to measure between two boxes to find it.
    """
    axis = quat_matrix(node["rotation"]) @ np.array([0.0, -1.0, 0.0])
    return math.degrees(math.atan2(axis[1], math.hypot(axis[0], axis[2])))


def srgb(linear):
    """glTF states baseColorFactor in linear light; a Minecraft texture is read as though it were sRGB."""
    if linear <= 0.0031308:
        return 12.92 * linear
    return 1.055 * (linear ** (1 / 2.4)) - 0.055


def bake_material_sheet(doc, blob, out):
    """Paint every material's colour into one sheet and pin each primitive's texture coordinates to it.

    The export carries nine flat colours and no image. GemRender only considers a material for its
    stitched sheet when that material has a texture or PBR maps of its own, so a colour-only material
    falls through to untextured and draws white; and a colour written into a texture also needs taking
    out of linear, which is where the glTF states it and not where a Minecraft texture is read.

    One image with one cell per material, rather than a tile each: nine identical single-colour
    materials would stitch to nine sprites for no gain, and one texture is one bind.
    """
    from PIL import Image

    materials = doc.get("materials", [])
    if len(materials) > SHEET_CELLS * SHEET_CELLS:
        raise SystemExit(f"{len(materials)} materials will not fit a {SHEET_CELLS}x{SHEET_CELLS} sheet")
    side = SHEET_CELLS * SHEET_CELL
    image = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    centres = []
    for index, material in enumerate(materials):
        pbr = material.setdefault("pbrMetallicRoughness", {})
        factor = pbr.get("baseColorFactor", [1.0, 1.0, 1.0, 1.0])
        colour = tuple(round(srgb(v) * 255) for v in factor[:3]) + (round(factor[3] * 255),)
        column, row = index % SHEET_CELLS, index // SHEET_CELLS
        image.paste(colour, (column * SHEET_CELL, row * SHEET_CELL,
                             (column + 1) * SHEET_CELL, (row + 1) * SHEET_CELL))
        centres.append(((column + 0.5) / SHEET_CELLS, (row + 0.5) / SHEET_CELLS))
        pbr["baseColorFactor"] = [1.0, 1.0, 1.0, factor[3]]
        pbr["baseColorTexture"] = {"index": 0, "texCoord": 0}
    image.save(out)

    doc["samplers"] = [{}]
    doc["images"] = [{"extras": {"resourceLocation": f"{NS}:textures/emplacement/{ID}.png"}}]
    doc["textures"] = [{"sampler": 0, "source": 0}]
    pin_texcoords(doc, blob, centres)


def pin_texcoords(doc, blob, centres):
    """Point every primitive at the middle of its own cell, whatever it was unwrapped for before.

    A quarter of this export's primitives carry no texture coordinates at all and the rest carry an
    unwrap for a texture that was never shipped, so neither set is worth keeping. Each material's run
    of vertices is written once at the length of its longest primitive and the shorter ones read a
    prefix of it, because every value in it is the same.
    """
    longest = {}
    for mesh in doc["meshes"]:
        for prim in mesh["primitives"]:
            material = prim.get("material", 0)
            count = doc["accessors"][prim["attributes"]["POSITION"]]["count"]
            longest[material] = max(longest.get(material, 0), count)

    views = {}
    for material, count in longest.items():
        while len(blob) % 4:
            blob.append(0)
        offset = len(blob)
        u, v = centres[material]
        blob.extend(struct.pack(f"<{2 * count}f", *([u, v] * count)))
        doc["bufferViews"].append({"buffer": 0, "byteOffset": offset, "byteLength": len(blob) - offset})
        views[material] = len(doc["bufferViews"]) - 1

    accessors = {}
    for mesh in doc["meshes"]:
        for prim in mesh["primitives"]:
            material = prim.get("material", 0)
            count = doc["accessors"][prim["attributes"]["POSITION"]]["count"]
            key = (material, count)
            if key not in accessors:
                u, v = centres[material]
                doc["accessors"].append({"bufferView": views[material], "componentType": 5126,
                                         "count": count, "type": "VEC2",
                                         "min": [u, v], "max": [u, v]})
                accessors[key] = len(doc["accessors"]) - 1
            prim["attributes"]["TEXCOORD_0"] = accessors[key]


def regroup(doc):
    r"""Rebuild the flat node list as the five pieces that move differently.

    Three of them are posed and two are only there to put a pivot where a posed bone needs one: a pose
    replaces a bone's translation as well as its rotation, so a posed bone has to sit at its own origin
    and be carried to where it belongs by an unposed parent.

        m252a1
        |- baseplate                       dug in, never moves
        \- mount            at the socket
           \- traverse      POSED yaw     the bipod, the screw and the tube swing together
              |- screw      at the gear case pin
              |  \- screw_housing  POSED pitch    aims at wherever the bridge has got to
              |     \- screw_spindle POSED slide  reaches the rest of the way
              \- tube       POSED pitch  the tube and everything clamped to it
    """
    nodes = doc["nodes"]
    by_name = {node.get("name", f"node{i}"): i for i, node in enumerate(nodes)}
    tube_node = nodes[by_name[PIVOT_FROM]]
    pivot = [float(v) for v in tube_node["translation"]]
    modelled = tube_elevation(doc, tube_node)
    foot = [float(v) for v in nodes[by_name[SCREW_FOOT]]["translation"]]
    head = [float(v) for v in nodes[by_name[SCREW_HEAD]]["translation"]]

    groups = {name: [] for name in
              ("baseplate", "traverse", "tube", "screw_housing", "screw_spindle")}
    for index, node in enumerate(nodes):
        groups[classify(node.get("name", f"node{index}"))].append(index)

    def restate(indices, origin, rotation=None):
        """Put a group's children in the frame of the bone that turns them."""
        for index in indices:
            node = nodes[index]
            translation = np.array(node.get("translation", [0.0, 0.0, 0.0])) - np.array(origin)
            if rotation is not None:
                translation = quat_matrix(rotation) @ translation
                node["rotation"] = quat_multiply(rotation, node.get("rotation", [0, 0, 0, 1]))
            node["translation"] = [float(v) for v in translation]

    # The screw is stated from its lower pin and stood upright, so the script that aims it only has to
    # write the angle it should be at rather than an angle relative to whatever the export happened to
    # model. Its rest tilt comes out of the two pins, which is the same pair the script solves between.
    rest = np.array(head) - np.array(foot)
    tilt = math.degrees(math.atan2(rest[2], rest[1]))
    rest_length = float(np.linalg.norm(rest))
    restate(groups["screw_housing"], foot, pitch_quaternion(-tilt))
    # The spindle hangs from the upper pin, which the tube carries, so it is stated from there and
    # stood upright the same way the housing is. Stretched to reach down into the housing at any lay.
    restate(groups["screw_spindle"], head, pitch_quaternion(-tilt))
    restate(groups["traverse"], pivot)
    restate(groups["tube"], pivot, pitch_quaternion(REST_ELEVATION))

    def add(name, children, translation=None):
        node = {"name": name, "children": children}
        if translation is not None:
            node["translation"] = [float(v) for v in translation]
        nodes.append(node)
        return len(nodes) - 1

    housing = add("screw_housing", groups["screw_housing"])
    screw_pivot = add("screw", [housing], translation=np.array(foot) - np.array(pivot))
    # The spindle's own rest length is stretched by a node of its own, because the pose that turns the
    # bone above it replaces that bone's scale and would undo it.
    own = spindle_length(doc, nodes, groups["screw_spindle"])
    stretch = add("screw_spindle_stretch", groups["screw_spindle"])
    nodes[stretch]["scale"] = [1.0, SPINDLE_LENGTH / own, 1.0]
    lean = quat_matrix(pitch_quaternion(REST_ELEVATION))
    spindle = add("screw_spindle", [stretch],
                  translation=lean @ (np.array(head) - np.array(pivot)))
    tube = add("tube", groups["tube"] + [spindle])
    traverse = add("traverse", groups["traverse"] + [screw_pivot, tube])
    mount = add("mount", [traverse], translation=pivot)
    baseplate = add("baseplate", groups["baseplate"])
    root = add(ID, [baseplate, mount])
    doc["scenes"] = [{"nodes": [root]}]
    doc["scene"] = 0
    named = {group: [nodes[i].get("name", f"node{i}") for i in members]
             for group, members in groups.items()}
    return named, pivot, modelled, foot, head, rest_length


def node_bounds(doc):
    """World box of every mesh node, walking the scene the way GltfBounds does."""
    out = {}

    def walk(index, parent):
        node = doc["nodes"][index]
        here = parent @ local_matrix(node)
        if "mesh" in node:
            lo = np.full(3, np.inf)
            hi = np.full(3, -np.inf)
            for prim in doc["meshes"][node["mesh"]]["primitives"]:
                acc = doc["accessors"][prim["attributes"]["POSITION"]]
                for cx in (acc["min"][0], acc["max"][0]):
                    for cy in (acc["min"][1], acc["max"][1]):
                        for cz in (acc["min"][2], acc["max"][2]):
                            w = here @ np.array([cx, cy, cz, 1.0])
                            lo = np.minimum(lo, w[:3])
                            hi = np.maximum(hi, w[:3])
            out[node.get("name", f"node{index}")] = (lo.tolist(), hi.tolist())
        for child in node.get("children", []):
            walk(child, here)

    for root in doc["scenes"][doc.get("scene", 0)]["nodes"]:
        walk(root, np.eye(4))
    return out


# ---------------------------------------------------------------- structure model

def union(bounds, names):
    lo = np.full(3, np.inf)
    hi = np.full(3, -np.inf)
    for name in names:
        box = bounds[name]
        lo = np.minimum(lo, box[0])
        hi = np.maximum(hi, box[1])
    return lo, hi


def bone(name, cubes, parent=None, pivot=(0, 0, 0)):
    out = {"name": name, "pivot": [round(float(v) * 16, 3) for v in (-pivot[0], pivot[1], pivot[2])],
           "cubes": cubes}
    if parent:
        out["parent"] = parent
    return out


def structure(bones):
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16},
        "bones": bones}]}


def mortar_structure(bounds, pivot):
    """Pivots only; the tube bone rests horizontal (pack x_rot leans it), as the display model was staged."""
    muzzle = bounds["Muzzle lip"]
    reach = math.dist(pivot, [(muzzle[0][i] + muzzle[1][i]) / 2 for i in range(3)])
    bones = [bone("vehicle_body", []),
             bone("mortar", [], pivot=pivot),
             bone("mortar_barrel", [], parent="mortar", pivot=pivot)]
    return structure(bones), reach


def screw_constants(doc, pivot, foot, head, rest_length):
    """The numbers the screw script is written around, and the margins the two rods leave each other.

    Everything is stated from the socket, because that is the one point both pins are measured against:
    the lower one sits still in the bipod's frame and the upper one is carried round by the tube, so how
    far apart they are is a function of the elevation and of nothing else. Neither rod can change
    length, so what has to hold at every lay is that they still overlap at the top of the band and that
    the spindle's own foot is still buried at the bottom of it.
    """
    names = {node.get("name"): node for node in doc["nodes"]}
    housing = subtree_extent(doc, names["screw_housing"])
    j = np.array(foot) - np.array(pivot)
    lean = quat_matrix(pitch_quaternion(REST_ELEVATION))
    p0 = lean @ (np.array(head) - np.array(pivot))
    print(f"   screw: foot {np.round(j, 5)} head-at-zero {np.round(p0, 5)} rest {rest_length:.5f} "
          f"housing {housing:.5f} spindle staged to {SPINDLE_LENGTH:.3f}")
    for elevation in (MIN_ELEVATION, REST_ELEVATION, MAX_ELEVATION):
        angle = math.radians(-elevation)
        p = np.array([0.0,
                      p0[1] * math.cos(angle) - p0[2] * math.sin(angle),
                      p0[1] * math.sin(angle) + p0[2] * math.cos(angle)])
        v = p - j
        length = float(np.linalg.norm(v))
        # Where the spindle's foot sits along the screw, measured up from the lower pin.
        seat = length - SPINDLE_LENGTH
        print(f"      at {elevation:5.1f} deg: pins {length:.4f} apart, tilt "
              f"{math.degrees(math.atan2(v[2], v[1])):+7.3f} deg, spindle foot at {seat:+.4f} "
              f"({'overlaps housing by %.4f' % (housing - seat) if seat <= housing else 'GAP %.4f' % (seat - housing)})")


def spindle_length(doc, nodes, members):
    """How far the spindle reaches below the pin it hangs from, before it is stretched."""
    low = 0.0
    for index in members:
        node = nodes[index]
        here = local_matrix(node)
        for prim in doc["meshes"][node["mesh"]]["primitives"]:
            acc = doc["accessors"][prim["attributes"]["POSITION"]]
            for cx in (acc["min"][0], acc["max"][0]):
                for cy in (acc["min"][1], acc["max"][1]):
                    for cz in (acc["min"][2], acc["max"][2]):
                        low = min(low, (here @ np.array([cx, cy, cz, 1.0]))[1])
    return -low


def subtree_extent(doc, node):
    """How far a group's geometry reaches below its own origin, along Y."""
    low = 0.0
    high = 0.0

    def walk(index, parent):
        nonlocal low, high
        child = doc["nodes"][index]
        here = parent @ local_matrix(child)
        if "mesh" in child:
            for prim in doc["meshes"][child["mesh"]]["primitives"]:
                acc = doc["accessors"][prim["attributes"]["POSITION"]]
                for cx in (acc["min"][0], acc["max"][0]):
                    for cy in (acc["min"][1], acc["max"][1]):
                        for cz in (acc["min"][2], acc["max"][2]):
                            y = (here @ np.array([cx, cy, cz, 1.0]))[1]
                            low = min(low, y)
                            high = max(high, y)
        for grandchild in child.get("children", []):
            walk(grandchild, here)

    for child in node.get("children", []):
        walk(child, np.eye(4))
    return high - low


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    doc, blob = read_glb(SRC, MEMBER)
    if len(doc["buffers"]) != 1:
        raise SystemExit("expected one buffer")
    if doc.get("images"):
        raise SystemExit("this export carries no images; one that does needs staging too")

    yaw_half_turn(doc, blob)
    groups, pivot, modelled, foot, head, rest_length = regroup(doc)
    print(f"grouped { {k: len(v) for k, v in groups.items()} }, pivot {np.round(pivot, 4)}, "
          f"modelled elevation {modelled:.3f} deg, taken out as {REST_ELEVATION:.1f}")
    screw_constants(doc, pivot, foot, head, rest_length)

    models = os.path.join(PACK, "assets", NS, "models", "emplacement", ID)
    textures = os.path.join(PACK, "assets", NS, "textures", "emplacement")
    os.makedirs(models, exist_ok=True)
    os.makedirs(textures, exist_ok=True)
    sheet = os.path.join(textures, f"{ID}.png")
    bake_material_sheet(doc, blob, sheet)
    print(f"   painted {len(doc['materials'])} materials into {sheet}")
    doc["buffers"][0] = {"byteLength": len(blob),
                         "extras": {"resourceLocation": f"{NS}:models/emplacement/{ID}/{ID}.bin"}}
    doc.setdefault("asset", {})["extras"] = {"staged_by": "tools/internal/stage_mortar.py",
                                             "yaw": 180, "rest_elevation": REST_ELEVATION}
    gltf, binary = os.path.join(models, f"{ID}.gltf"), os.path.join(models, f"{ID}.bin")
    kept = mqo.read_markers(gltf, binary)
    with open(binary, "wb") as f:
        f.write(blob)
    with open(gltf, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    mqo.append_nodes(gltf, binary, kept)

    bounds = node_bounds(doc)
    lo, hi = union(bounds, list(bounds))
    print(f"staged, whole model {np.round(lo, 3)} .. {np.round(hi, 3)}")
    model, reach = mortar_structure(bounds, pivot)
    path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", f"{ID}.structure.json")
    with open(path, "w") as f:
        json.dump(model, f, indent=1)
    strip_cubes.split(PACK, ID)
    print(f"   barrel reach {reach:.3f} blocks from the socket")
    for group in model["minecraft:geometry"][0]["bones"]:
        print(f"   {group['name']:16s} pivot {group['pivot']} cubes {group['cubes']}")


if __name__ == "__main__":
    main()
