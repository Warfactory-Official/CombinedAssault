"""Author a combinedassault glTF vehicle in Blender: import a pack model, frames, colliders, hit modules, hit cylinders,
sockets, lights, screens, armour / hit effect tags, hinges, part clips, texture set-up, player reference, check, export. Every role is written into the node's
glTF `extras` (docs/vehicle-pack-format.md §6); extras the panels do not show survive import -> export.

Frame: 1 unit = 1 block, world origin = vehicle origin, nose toward -Y, port +X, up +Z. The glTF exporter's
Y-up conversion `(x, y, z) -> (x, z, -y)` then gives the vehicle frame (+Z forward, +X port, +Y up).

Install: `python3 tools/blender/build_addon.py` -> build/combinedassault_tools.zip (extension); Edit > Preferences >
Get Extensions > Install from Disk. Panels: Object, Material and Scene properties; File > Import > combinedassault pack
model.
"""

import json
import math
import os
import re
import struct

import bmesh
import bpy
from bpy.props import (BoolProperty, EnumProperty, FloatProperty, FloatVectorProperty, IntProperty, PointerProperty,
                       StringProperty)
from bpy.types import Operator, Panel, PropertyGroup
from bpy_extras.io_utils import ImportHelper
from mathutils import Matrix, Vector

try:
    from . import ca_reference, ca_validate
except ImportError:  # loaded as a plain module (tests, sys.path = tools/blender)
    import ca_reference
    import ca_validate

SYSTEMS = ca_validate.SYSTEMS
SOCKET_FIELDS = ca_validate.SOCKET_FIELDS
# Socket fields whose marker is a quad mesh (addon panel extent) rather than an empty.
PANEL_FIELDS = {"era", "slat"}
# Socket fields read from their mesh: panels + track (outline = path).
MESH_FIELDS = PANEL_FIELDS | {"track", "sight_lens"}
UNTARGETED = ca_validate.UNTARGETED
ROLE = ca_validate.ROLE
INTERFACE = ca_validate.INTERFACE
META = ca_validate.META
HIDDEN = ca_validate.HIDDEN
RESOURCE = ca_validate.RESOURCE
# combinedassault meta keys the panels own; any other key rides through from import (raw_extras).
OWNED_META = ca_validate.COLLIDER_KEYS | ca_validate.HIT_KEYS | ca_validate.FRAME_KEYS | ca_validate.LIGHT_KEYS
ARMOR = re.compile(r"(?i)armou?r[_ ]?(\d+(?:\.\d+)?)")
DECOR = re.compile(r"(?i)(?:^|[_ .])decor(?:$|[_ .])")
SURFACE_ITEMS = [("STEEL", "Steel", "Sparks, holes"), ("RUBBER", "Rubber", "Black bits, dust, no hole"),
                 ("GLASS", "Glass", "Shards, glint, no hole"), ("SOFT", "Soft", "Dust, fibres, no hole (fabric)")]
SURFACE = {key: re.compile(r"(?i)(?:^|[_ .])(?:%s)(?:$|[_ .])" % words) for key, words in ca_validate.SURFACES.items()}
# Socket fields whose node scale is a value: (axis label, meaning). glTF Z scale = Blender Y scale.
SCALE_FIELDS = {"bolt": ("Y", "barrel_length"), "eject": ("Y", "throw speed b/s"), "station": ("Y", "round length"),
                "propeller": ("mean", "radius"), "cargo_bay": ("all", "full size")}
# Mount model state nodes (by name), drawn while the carrying part's current weapon...
STATE_NODES = [(re.compile(r"^ammo_can$"), "has rounds or is reloading"),
               (re.compile(r"^round@(\d+)$"), "has more than {0} rounds"),
               (re.compile(r"^barrel@(\w+)$"), "carries barrel variant {0}, not being changed")]

PLANAR_EPS = 1.0e-4
CONVEX_EPS = 1.0e-4
DEGENERATE_EPS = 1.0e-6
IDENTITY_EPS = 1.0e-5
CLIP_NAME = ca_validate.CLIP_NAME
PLACES = 5

ROLE_ITEMS = [
    ("NONE", "Model", "Drawn geometry, hit as the vehicle's surface"),
    ("BODY", "Body", "The hull frame: exactly one, at the origin, unrotated, unscaled; every role node under it"),
    ("PART", "Part frame", "The part (interface) turns / moves this subtree: turret, wheel, door"),
    ("PITCH", "Pitch frame", "Weapon elevation of the part (interface), under its part frame"),
    ("COLLIDER", "Collider", "Convex collision piece, in its frame; hidden"),
    ("HIT", "Hit module", "Internal system volume: not drawn, reached by shots and blasts"),
    ("HIT_SURFACE", "Hit surface", "Replaces its parent's drawn subtree as hit surface (simple tyre); hidden"),
    ("SOCKET", "Socket", "Mount marker: seat, view, bolt, ERA panel, exhaust..."),
]
FRAMES = {"BODY", "PART", "PITCH"}


# ---------------------------------------------------------------- frame


def is_mirrored(obj):
    """Negative determinant: exports inside out (winding and normals flipped)."""
    return obj.matrix_world.to_3x3().determinant() < 0


def role_of(obj):
    return obj.combinedassault.role if hasattr(obj, "combinedassault") else "NONE"


def frame_of(obj):
    """Nearest frame object at or above obj; None if none."""
    while obj is not None and role_of(obj) not in FRAMES:
        obj = obj.parent
    return obj


def under_body(obj):
    while obj is not None:
        if role_of(obj) == "BODY":
            return True
        obj = obj.parent
    return False


# ---------------------------------------------------------------- geometry


def faces_of(obj, depsgraph=None):
    evaluated = obj.evaluated_get(depsgraph) if depsgraph else obj
    mesh = evaluated.to_mesh()
    try:
        matrix = evaluated.matrix_world
        return [matrix @ v.co for v in mesh.vertices], [list(p.vertices) for p in mesh.polygons]
    finally:
        evaluated.to_mesh_clear()


def plane_of(points, face):
    """Newell normal and offset; None when degenerate."""
    normal = Vector((0.0, 0.0, 0.0))
    for i in range(len(face)):
        a = points[face[i]]
        b = points[face[(i + 1) % len(face)]]
        normal.x += (a.y - b.y) * (a.z + b.z)
        normal.y += (a.z - b.z) * (a.x + b.x)
        normal.z += (a.x - b.x) * (a.y + b.y)
    if normal.length < DEGENERATE_EPS:
        return None
    normal.normalize()
    return normal, normal.dot(points[face[0]])


def open_edges(faces):
    edges = {}
    for face in faces:
        for i in range(len(face)):
            a, b = face[i], face[(i + 1) % len(face)]
            key = (min(a, b), max(a, b))
            edges[key] = edges.get(key, 0) + 1
    return [key for key, count in edges.items() if count != 2]


def coplanar_neighbours(points, faces):
    """Edge-sharing face pair on one plane, else None. Game keeps authored faces => 2-triangle box bottom = 3-corner
    ground manifold; LOCUST cabinet spun over on landing."""
    owner = {}
    for index, face in enumerate(faces):
        for i in range(len(face)):
            a, b = face[i], face[(i + 1) % len(face)]
            owner.setdefault((min(a, b), max(a, b)), []).append(index)
    for pair in owner.values():
        if len(pair) != 2:
            continue
        p, q = plane_of(points, faces[pair[0]]), plane_of(points, faces[pair[1]])
        if p and q and p[0].dot(q[0]) > 1 - 1.0e-6 and abs(p[1] - q[1]) < PLANAR_EPS:
            return pair
    return None


def check_collider(obj, depsgraph=None):
    """Reasons the game's ConvexHull would refuse this piece."""
    problems = []
    if obj.type != "MESH":
        return ["is not a mesh"]
    if is_mirrored(obj):
        problems.append("is mirrored: apply scale, rebuild with a rotation")
    points, faces = faces_of(obj, depsgraph)
    if len(points) < 4 or len(faces) < 4:
        problems.append("needs at least 4 vertices and 4 faces (%d, %d)" % (len(points), len(faces)))
        return problems
    interior = sum(points, Vector()) / len(points)
    for index, face in enumerate(faces):
        plane = plane_of(points, face)
        if plane is None:
            problems.append("face %d is degenerate" % index)
            continue
        normal, offset = plane
        if normal.dot(interior) > offset:
            normal, offset = -normal, -offset
        worst = max(abs(normal.dot(points[v]) - offset) for v in face)
        if worst > PLANAR_EPS:
            problems.append("face %d is not flat (%.5f off its plane)" % (index, worst))
        outside = max(normal.dot(p) - offset for p in points)
        if outside > CONVEX_EPS:
            problems.append("is not convex (%.5f outside face %d): split it or use Make convex hull"
                            % (outside, index))
            break
    return problems


def local_is_identity(obj):
    return all(abs(obj.matrix_basis[r][c] - (1.0 if r == c else 0.0)) < IDENTITY_EPS
               for r in range(4) for c in range(4))


def check_object(obj, depsgraph=None, part_model=False):
    """(errors, warnings) for one object as the export would write it. part_model: scene has no body (part mounts)."""
    errors, warnings = [], []
    settings = obj.combinedassault
    role = role_of(obj)
    action = obj.animation_data.action if obj.animation_data else None
    if part_model and role in ("PART", "PITCH"):
        errors.append("is a %s frame in a part model (no body): frames live on the carrier" % role.lower())
    elif not part_model and role != "NONE" and role != "BODY" and not under_body(obj):
        errors.append("is a %s but not under the body frame" % role.lower())
    if role == "BODY" and not obj.matrix_world.is_identity:
        errors.append("body frame is not at the origin, unrotated, unscaled")
    if role in ("PART", "PITCH") and not settings.target:
        errors.append("%s frame names no part (interface)" % role.lower())
    if role == "COLLIDER":
        errors += check_collider(obj, depsgraph)
        if obj.type == "MESH":
            gaps = open_edges(faces_of(obj, depsgraph)[1])
            if gaps:
                # The game builds the hull from the faces alone: a T-junction is fine, a missing face is a hole.
                warnings.append("is not closed: %d open edge(s), first %d-%d; fine for a vertex on another face's "
                                "edge, a missing face leaves a hole" % (len(gaps), gaps[0][0], gaps[0][1]))
            points, faces = faces_of(obj, depsgraph)
            flat = coplanar_neighbours(points, faces)
            if flat:
                warnings.append("faces %d and %d share a plane: the game keeps each, a contact clips to one; "
                                "Make convex hull merges them" % tuple(flat))
        if settings.main and role_of(frame_of(obj)) != "BODY":
            errors.append("main collider rides a part frame")
        if action is not None:
            warnings.append("collider is animated; colliders are rest pose (moving collision: linked hinge part)")
        return errors, warnings
    if action is not None and not CLIP_NAME.match(action.name):
        warnings.append("clip '%s' is not <part id>[.<suffix>]: the game never plays it" % action.name)
    if action is not None and settings.posed:
        warnings.append("posed by a script and keyed in clip '%s': the clip wins on its channels" % action.name)
    if role == "HIT_SURFACE":
        if obj.type != "MESH":
            errors.append("hit surface is not a mesh")
        if obj.parent is None or role_of(obj.parent) == "BODY":
            warnings.append("hit surface replaces its parent's whole subtree; under the body that is the vehicle")
    if role == "HIT":
        if obj.type != "MESH":
            errors.append("hit module is not a mesh")
        else:
            _, faces = faces_of(obj, depsgraph)
            if open_edges(faces):
                warnings.append("hit module is open: a shot can enter without crossing it")
    if role == "SOCKET" and settings.field == "light":
        errors += ca_validate.light_problems(light_meta(settings))
    if role in ("NONE", "BODY", "PART", "PITCH", "HIT", "HIT_SURFACE") and settings.meta_unit:
        system, spin = inherited(obj, "system"), inherited(obj, "spin")
        if system in ("rotor", "tail_rotor") and not spin:
            warnings.append("rotor unit %s has no spin (cw / ccw) on it or above" % settings.meta_unit)
    if role == "SOCKET":
        if settings.field in UNTARGETED and settings.target:
            errors.append("socket %s takes no interface" % settings.field)
        if settings.field not in UNTARGETED and not settings.target and not part_model:
            errors.append("socket %s names no %s (interface)" % (settings.field, "id" if settings.field in (
                "era", "slat", "mount_point", "hitch") else "part"))
        if obj.type == "MESH" and settings.field not in MESH_FIELDS:
            warnings.append("socket is a mesh; only era/slat/track/sight_lens use the geometry")
        if obj.type != "MESH" and settings.field in MESH_FIELDS:
            errors.append("%s socket needs a mesh (%s)" % (settings.field, {"track": "outline = path", "sight_lens": "lens mesh"}.get(settings.field, "quad")))
    if obj.type == "MESH" and is_mirrored(obj):
        errors.append("is mirrored: exports inside out. Apply scale, rebuild with a rotation")
    if obj.type == "MESH" and role == "NONE" and obj.material_slots and not obj.data.uv_layers:
        warnings.append("has no UV map: its texture cannot be placed")
    if obj.type == "MESH" and len(obj.data.uv_layers) > 1:
        warnings.append("has %d UV maps; GemRender draws TEXCOORD_0 only" % len(obj.data.uv_layers))
    if settings.posed and not local_is_identity(obj):
        errors.append("is posed by a script but its local transform is not identity: a pose replaces it. "
                      "Use Make hinge")
    return errors, warnings


def inherited(obj, key):
    """Effective `system` / `spin` hit override: nearest set value at or above obj; "" when none."""
    while obj is not None:
        settings = obj.combinedassault
        if key == "system":
            value = settings.system if role_of(obj) == "HIT" else "" if settings.meta_system == "AUTO" \
                else settings.meta_system
        else:
            value = "" if settings.meta_spin == "NONE" else settings.meta_spin.lower()
        if value:
            return value
        obj = obj.parent
    return ""


def check_scene(scene, depsgraph=None):
    errors, warnings = [], []
    if abs(scene.unit_settings.scale_length - 1.0) > 1e-6:
        warnings.append("scene unit scale is %.3f; the exporters ignore it (1 unit = 1 block)"
                        % scene.unit_settings.scale_length)
    objects = [obj for obj in scene.objects if not obj.combinedassault.reference]
    mains = [obj for obj in objects if role_of(obj) == "COLLIDER" and obj.combinedassault.main]
    if len(mains) > 1:
        errors.append("more than one collider is Main: %s" % ", ".join(o.name for o in mains))
    bodies = [obj for obj in objects if role_of(obj) == "BODY"]
    # No body: part model (vehicle `mounts`), like ca_validate.
    if len(bodies) > 1:
        errors.append("needs at most one body frame, has %d" % len(bodies))
    seen = {}
    for obj in objects:
        if role_of(obj) in ("PART", "PITCH"):
            key = (role_of(obj), obj.combinedassault.target)
            if key in seen:
                errors.append("%s %s on %s and %s" % (key[0].lower(), key[1], seen[key], obj.name))
            seen[key] = obj.name
    wheels = [obj.name for obj in objects if role_of(obj) == "NONE" and obj.combinedassault.meta_road_wheel]
    if wheels and not any(role_of(o) == "SOCKET" and o.combinedassault.field == "track" for o in objects):
        errors.append("road wheels %s but no track socket" % ", ".join(wheels))
    for obj in objects:
        e, w = check_object(obj, depsgraph, not bodies)
        errors += ["%s %s" % (obj.name, m) for m in e]
        warnings += ["%s %s" % (obj.name, m) for m in w]
    for material in used_materials(scene):
        e, w = material_problems(material)
        errors += ["material %s %s" % (material.name, m) for m in e]
        warnings += ["material %s %s" % (material.name, m) for m in w]
    return errors, warnings


def used_materials(scene):
    seen = []
    for obj in scene.objects:
        # Hidden shapes: material = name tokens only, never drawn.
        if obj.type == "MESH" and role_of(obj) not in ("COLLIDER", "HIT", "HIT_SURFACE", "SOCKET") \
                and not obj.combinedassault.reference:
            for slot in obj.material_slots:
                if slot.material is not None and slot.material not in seen:
                    seen.append(slot.material)
    return seen


# ---------------------------------------------------------------- colliders


def hull_into(data, points):
    """data = convex hull of points, coplanar triangles merged back into flat faces (every face a separating axis)."""
    mesh = bmesh.new()
    try:
        for point in points:
            mesh.verts.new(point)
        bmesh.ops.remove_doubles(mesh, verts=mesh.verts, dist=ca_validate.EPS)
        result = bmesh.ops.convex_hull(mesh, input=mesh.verts, use_existing_faces=False)
        bmesh.ops.delete(mesh, geom=list({v for v in result["geom_interior"] + result["geom_unused"]
                                          if isinstance(v, bmesh.types.BMVert)}), context="VERTS")
        bmesh.ops.dissolve_limit(mesh, angle_limit=0.0017, verts=mesh.verts, edges=mesh.edges)
        mesh.to_mesh(data)
    finally:
        mesh.free()


def style_collider(obj):
    obj.display_type = "WIRE"
    obj.show_in_front = True
    solid = obj.combinedassault.solid
    obj.color = (1.0, 0.85, 0.2, 1.0) if obj.combinedassault.main else (0.2, 1.0, 0.2, 1.0 if solid else 0.45)


# ---------------------------------------------------------------- glTF post-process


def pack_paths(scene):
    """(glb path, texture dir, namespace, assets/<ns> dir)."""
    settings = scene.combinedassault_export
    root = bpy.path.abspath(settings.pack_root)
    ns = settings.namespace
    model = settings.model_path.strip("/")
    glb = os.path.join(root, "assets", ns, "models", model + ".glb")
    textures = os.path.join(root, "assets", ns, "textures", os.path.dirname(model))
    return glb, textures, ns, os.path.join(root, "assets", ns)


def resource_location(ns, root_dir, path):
    """`ns:<path under assets/ns/>` for a file inside the pack."""
    rel = os.path.relpath(path, root_dir).replace(os.sep, "/")
    return "%s:%s" % (ns, rel)


def node_extras(obj):
    """glTF extras for one object: role, interface, parameters, hidden; empty = untagged, no overrides."""
    settings = obj.combinedassault
    role = settings.role
    extras, meta = {}, {}
    if role != "NONE":
        extras[ROLE] = settings.field if role == "SOCKET" else role.lower()
    if role in ("PART", "PITCH") or role == "SOCKET" and settings.target:
        extras[INTERFACE] = settings.target
    if role in ("COLLIDER", "HIT", "HIT_SURFACE", "SOCKET") or settings.hidden:
        extras[HIDDEN] = True
    if role == "COLLIDER":
        if settings.main:
            meta["main"] = True
        if settings.deck:
            meta["deck"] = True
        if not settings.solid:
            meta["solid"] = False
        if not settings.world:
            meta["world"] = False
        if settings.label:
            meta["name"] = settings.label
        if settings.locked:
            extras["genHulls"] = {"locked": True}
        if settings.hit_surface:
            meta["hit"] = True
    elif role == "SOCKET":
        if settings.index >= 0:
            meta["index"] = settings.index
        if settings.field == "light":
            meta.update(light_meta(settings))
    else:
        if role == "HIT":
            meta["system"] = settings.system
        elif settings.meta_system != "AUTO":
            meta["system"] = settings.meta_system
        if settings.side != "NONE":
            meta["side"] = settings.side.lower()
        if settings.meta_skip:
            meta["skip"] = True
        if settings.meta_swept != "AUTO":
            meta["swept"] = settings.meta_swept == "ON"
        if settings.meta_unit:
            meta["unit"] = settings.meta_unit
        if settings.meta_spin != "NONE":
            meta["spin"] = settings.meta_spin.lower()
        if settings.meta_health > 0:
            meta["health"] = round(settings.meta_health, 3)
        if role == "NONE" and settings.meta_road_wheel:
            meta["road_wheel"] = True
        if role in ("PART", "PITCH") and settings.limit_pitch:
            meta["x_rot_min"] = round(settings.x_rot_min, PLACES)
            meta["x_rot_max"] = round(settings.x_rot_max, PLACES)
        if role == "PART" and settings.limit_yaw:
            meta["y_rot_min"] = round(settings.y_rot_min, PLACES)
            meta["y_rot_max"] = round(settings.y_rot_max, PLACES)
        if role in FRAMES and settings.follows_clips:
            meta["follows_clips"] = True
    if meta:
        extras[META] = meta
    return extras


def light_meta(settings):
    """Light socket keys; kind always written, others only when set (0 / off = the kind's default)."""
    meta = {"kind": settings.light_kind.lower()}
    if settings.light_use_color:
        meta["color"] = "#%02x%02x%02x" % tuple(round(max(0.0, min(1.0, c)) * 255) for c in settings.light_color)
    for key in ("range", "intensity", "inner", "outer"):
        value = getattr(settings, "light_" + key)
        if value > 0:
            meta[key] = round(value, PLACES)
    if not settings.light_shadow:
        meta["shadow"] = False
    if settings.light_cookie:
        meta["cookie"] = settings.light_cookie
    return meta


def merged_extras(obj):
    """node_extras over the imported extras: panel-owned keys from the panels, every other key kept."""
    raw = json.loads(obj.combinedassault.raw_extras or "{}")
    for key in (ROLE, INTERFACE, HIDDEN):
        raw.pop(key, None)
    # `index` outside sockets: no panel, kept as imported.
    owned = OWNED_META if obj.combinedassault.role == "SOCKET" else OWNED_META - {"index"}
    meta = {k: v for k, v in raw.pop(META, {}).items() if k not in owned}
    hulls = {k: v for k, v in raw.pop("genHulls", {}).items() if k != "locked"}
    out = node_extras(obj)
    if meta:
        out[META] = {**meta, **out.get(META, {})}
    if hulls or "genHulls" in out:
        out["genHulls"] = {**hulls, **out.get("genHulls", {})}
    return {**raw, **out}


def pack_location(path):
    """`ns:<path>` when path lies under some <pack>/assets/<ns>/; None otherwise."""
    parts = os.path.realpath(path).split(os.sep)
    for i in range(len(parts) - 2, 0, -1):
        if parts[i] == "assets":
            return "%s:%s" % (parts[i + 1], "/".join(parts[i + 2:]))
    return None


def image_file(image):
    """The image's own file on disk, or None (generated, packed, unsaved paint)."""
    if image is None or image.packed_file is not None or image.is_dirty or not image.filepath:
        return None
    path = bpy.path.abspath(image.filepath, library=image.library)
    return path if os.path.isfile(path) else None


def relocate(gltf_path, ns, assets, scene=None, written=()):
    """URIs -> extras.resourceLocation, the form the mod's loaders read (GemRender needs it for images);
    per-node role, interface, parameters -> extras (nodes are named after their objects); material transparency and
    extras. An image already in a pack keeps its file (shared textures, _damaged/_paint siblings): the copy the
    exporter just wrote (`written`) is removed."""
    with open(gltf_path, encoding="utf-8") as source:
        gltf = json.load(source)
    if scene is not None:
        for node in gltf.get("nodes", []):
            obj = scene.objects.get(node.get("name", ""))
            extras = merged_extras(obj) if obj is not None else {}
            if extras:
                node.setdefault("extras", {}).update(extras)
        for material in gltf.get("materials", []):
            source_material = bpy.data.materials.get(material.get("name", ""))
            if source_material is not None:
                material_out(source_material, material)
    here = os.path.dirname(gltf_path)
    if scene is not None and len(gltf.get("buffers", [])) == 1 and "uri" in gltf["buffers"][0]:
        bin_path = os.path.normpath(os.path.join(here, bpy.path.native_pathsep(gltf["buffers"][0]["uri"])))
        with open(bin_path, "rb") as source:
            data = rewrite_colliders(gltf, source.read(), scene)
        with open(bin_path, "wb") as out:
            out.write(data)
        gltf["buffers"][0]["byteLength"] = len(data)
    images = {}
    for image in bpy.data.images:
        images.setdefault(image.name, image)
        images.setdefault(os.path.splitext(image.name)[0], image)
    # The exporter may name an image after its file (imported `Mavic3Pro_basecolor_2k` -> mavic3pro_basecolor_512).
    for image in bpy.data.images:
        if image_file(image):
            images.setdefault(os.path.splitext(os.path.basename(image_file(image)))[0], image)
    for entry in gltf.get("images", []):
        uri = entry.get("uri")
        if uri is None or uri.startswith("data:"):
            continue
        copy = os.path.normpath(os.path.join(here, bpy.path.native_pathsep(uri)))
        source = image_file(images.get(entry.get("name", "")))
        location = pack_location(source) if source else None
        if location is not None and os.path.realpath(source) != os.path.realpath(copy):
            if os.path.realpath(copy) in written:
                os.remove(copy)
        else:
            location = resource_location(ns, assets, copy)
        del entry["uri"]
        entry.setdefault("extras", {})[RESOURCE] = location
    for entry in gltf.get("buffers", []):
        uri = entry.get("uri")
        if uri is None or uri.startswith("data:"):
            continue
        del entry["uri"]
        entry.setdefault("extras", {})[RESOURCE] = resource_location(
            ns, assets, os.path.normpath(os.path.join(here, bpy.path.native_pathsep(uri))))
    gltf["asset"].setdefault("extras", {})["staged_by"] = "tools/blender/combinedassault_tools.py"
    with open(gltf_path, "w", encoding="utf-8") as out:
        json.dump(gltf, out, separators=(",", ":"), ensure_ascii=False)
    return gltf


def rewrite_colliders(gltf, data, scene):
    """Collider meshes -> one primitive per Blender face (shared POSITION): the game checks each authored face whole
    (ModelRig.hull); one triangulated primitive is regrouped by plane and slivers fail. Returns the repacked buffer."""
    blob = bytearray(data)

    def view(payload, target):
        while len(blob) % 4:
            blob.append(0)
        gltf["bufferViews"].append({"buffer": 0, "byteOffset": len(blob), "byteLength": len(payload),
                                    "target": target})
        blob.extend(payload)
        return len(gltf["bufferViews"]) - 1

    for node in gltf.get("nodes", []):
        obj = scene.objects.get(node.get("name", ""))
        if obj is None or obj.type != "MESH" or role_of(obj) != "COLLIDER" or "mesh" not in node:
            continue
        points = [(v.co.x, v.co.z, -v.co.y) for v in obj.data.vertices]
        positions = view(b"".join(struct.pack("<3f", *p) for p in points), 34962)
        gltf["accessors"].append({"bufferView": positions, "componentType": 5126, "count": len(points), "type": "VEC3",
                                  "min": [min(p[a] for p in points) for a in range(3)],
                                  "max": [max(p[a] for p in points) for a in range(3)]})
        position = len(gltf["accessors"]) - 1
        wide = len(points) > 65535
        primitives = []
        for polygon in obj.data.polygons:
            loop = list(polygon.vertices)
            fan = [i for k in range(1, len(loop) - 1) for i in (loop[0], loop[k], loop[k + 1])]
            indices = view(struct.pack("<%d%s" % (len(fan), "I" if wide else "H"), *fan), 34963)
            gltf["accessors"].append({"bufferView": indices, "componentType": 5125 if wide else 5123,
                                      "count": len(fan), "type": "SCALAR"})
            primitives.append({"attributes": {"POSITION": position}, "indices": len(gltf["accessors"]) - 1,
                               "mode": 4})
        gltf["meshes"].append({"name": obj.data.name, "primitives": primitives})
        node["mesh"] = len(gltf["meshes"]) - 1
    return repack(gltf, bytes(blob))


def repack(gltf, data):
    """Drop meshes no node uses, then accessors and buffer views nothing uses; one buffer. Returns its bytes."""
    meshes = sorted({n["mesh"] for n in gltf.get("nodes", []) if "mesh" in n})
    mesh_map = {old: new for new, old in enumerate(meshes)}
    gltf["meshes"] = [gltf["meshes"][i] for i in meshes]
    for node in gltf.get("nodes", []):
        if "mesh" in node:
            node["mesh"] = mesh_map[node["mesh"]]
    used = set()
    for mesh in gltf["meshes"]:
        for primitive in mesh["primitives"]:
            used.update(primitive["attributes"].values())
            used.update(v for target in primitive.get("targets", []) for v in target.values())
            if "indices" in primitive:
                used.add(primitive["indices"])
    for animation in gltf.get("animations", []):
        for sampler in animation.get("samplers", []):
            used.update((sampler["input"], sampler["output"]))
    for skin in gltf.get("skins", []):
        if "inverseBindMatrices" in skin:
            used.add(skin["inverseBindMatrices"])
    accessors = sorted(used)
    acc_map = {old: new for new, old in enumerate(accessors)}
    gltf["accessors"] = [gltf["accessors"][i] for i in accessors]
    views = sorted({a["bufferView"] for a in gltf["accessors"] if "bufferView" in a}
                   | {i["bufferView"] for i in gltf.get("images", []) if "bufferView" in i})
    view_map, out = {}, bytearray()
    for old in views:
        entry = gltf["bufferViews"][old]
        while len(out) % 4:
            out.append(0)
        start = entry.get("byteOffset", 0)
        payload = data[start:start + entry["byteLength"]]
        entry["byteOffset"] = len(out)
        out.extend(payload)
        view_map[old] = len(view_map)
    gltf["bufferViews"] = [gltf["bufferViews"][i] for i in views]
    for accessor in gltf["accessors"]:
        if "bufferView" in accessor:
            accessor["bufferView"] = view_map[accessor["bufferView"]]
    for image in gltf.get("images", []):
        if "bufferView" in image:
            image["bufferView"] = view_map[image["bufferView"]]
    for mesh in gltf["meshes"]:
        for primitive in mesh["primitives"]:
            primitive["attributes"] = {k: acc_map[v] for k, v in primitive["attributes"].items()}
            if "targets" in primitive:
                primitive["targets"] = [{k: acc_map[v] for k, v in t.items()} for t in primitive["targets"]]
            if "indices" in primitive:
                primitive["indices"] = acc_map[primitive["indices"]]
    for animation in gltf.get("animations", []):
        for sampler in animation.get("samplers", []):
            sampler["input"], sampler["output"] = acc_map[sampler["input"]], acc_map[sampler["output"]]
    for skin in gltf.get("skins", []):
        if "inverseBindMatrices" in skin:
            skin["inverseBindMatrices"] = acc_map[skin["inverseBindMatrices"]]
    return bytes(out)


def material_out(source, material):
    """Panel transparency (AUTO = what the exporter derived) and imported extras onto one exported material."""
    settings = source.combinedassault
    if settings.alpha != "AUTO":
        material["alphaMode"] = settings.alpha
        material.pop("alphaCutoff", None)
        if settings.alpha == "MASK":
            material["alphaCutoff"] = float(settings.cutoff)
    elif material.get("alphaMode") == "MASK":
        cutoff = material.get("alphaCutoff", 0.5)
        material["alphaCutoff"] = min(ca_validate.CUTOUTS, key=lambda c: abs(c - cutoff))
    raw = json.loads(settings.raw_extras or "{}")
    raw.pop(ca_validate.TEXTURE_SLOT, None)
    if settings.texture_slot:
        raw[ca_validate.TEXTURE_SLOT] = settings.texture_slot
    if raw:
        material["extras"] = {**raw, **material.get("extras", {})}


# ---------------------------------------------------------------- import


def plain(value):
    """Blender ID property -> JSON value."""
    if hasattr(value, "to_dict"):
        return value.to_dict()
    if hasattr(value, "to_list"):
        return value.to_list()
    return value


def take_extras(idblock):
    """The glTF importer's custom properties (= extras), removed from the block."""
    extras = {key: plain(idblock[key]) for key in list(idblock.keys()) if not key.startswith("_")}
    for key in extras:
        del idblock[key]
    return extras


def apply_extras(obj, extras):
    """Panel settings from a node's glTF extras; the whole dict kept for export (merged_extras)."""
    settings = obj.combinedassault
    settings.raw_extras = json.dumps(extras) if extras else ""
    role = extras.get(ROLE, "")
    meta = extras.get(META, {})
    settings.target = extras.get(INTERFACE, "")
    if role in SOCKET_FIELDS:
        settings.role = "SOCKET"
        settings.field = role
        settings.index = meta.get("index", -1)
        if role == "light":
            settings.light_kind = str(meta.get("kind", "head")).upper()
            settings.light_use_color = "color" in meta
            if "color" in meta:
                hex_ = meta["color"].lstrip("#")
                settings.light_color = tuple(int(hex_[i:i + 2], 16) / 255 for i in (0, 2, 4))
            for key in ("range", "intensity", "inner", "outer"):
                setattr(settings, "light_" + key, meta.get(key, 0.0))
            settings.light_shadow = meta.get("shadow", True)
            settings.light_cookie = meta.get("cookie", "")
        return
    if role.upper() not in {key for key, _, _ in ROLE_ITEMS}:
        settings.hidden = bool(extras.get(HIDDEN))
        role = ""
    settings.role = role.upper() or "NONE"
    if role == "collider":
        settings.main = bool(meta.get("main"))
        settings.deck = bool(meta.get("deck"))
        settings.solid = meta.get("solid", True)
        settings.world = meta.get("world", True)
        settings.label = meta.get("name", "")
        settings.hit_surface = bool(meta.get("hit"))
        settings.locked = bool(extras.get("genHulls", {}).get("locked"))
        style_collider(obj)
        return
    if role == "hit":
        settings.system = meta.get("system", "engine")
    elif meta.get("system") in SYSTEMS or meta.get("system") == "none":
        settings.meta_system = meta["system"]
    if role in ("hit", "hit_surface"):
        obj.display_type = "WIRE"
    settings.side = meta.get("side", "none").upper()
    settings.meta_skip = bool(meta.get("skip"))
    if "swept" in meta:
        settings.meta_swept = "ON" if meta["swept"] else "OFF"
    settings.meta_health = meta.get("health", 0.0)
    settings.meta_unit = meta.get("unit", "")
    settings.meta_spin = str(meta.get("spin", "none")).upper()
    settings.follows_clips = bool(meta.get("follows_clips"))
    settings.meta_road_wheel = bool(meta.get("road_wheel"))
    if "x_rot_min" in meta or "x_rot_max" in meta:
        settings.limit_pitch = True
        settings.x_rot_min = meta.get("x_rot_min", -90.0)
        settings.x_rot_max = meta.get("x_rot_max", 90.0)
    if "y_rot_min" in meta or "y_rot_max" in meta:
        settings.limit_yaw = True
        settings.y_rot_min = meta.get("y_rot_min", -180.0)
        settings.y_rot_max = meta.get("y_rot_max", 180.0)


def import_pack_model(context, path):
    """Pack glTF -> scene: resourceLocations resolved, roles onto the panels, export target = the same file.
    Returns (objects, materials) imported."""
    root, ns = ca_validate.pack_of(path)
    if root is None:
        raise ValueError("not inside <pack>/assets/<namespace>/models/: " + path)
    gltf, binary = ca_validate.read_container(path)
    folder = bpy.app.tempdir or os.path.dirname(path)
    temp = os.path.join(folder, "combinedassault_import.gltf")
    temp_bin = os.path.join(folder, "combinedassault_import.bin")
    for entry in gltf.get("images", []) + gltf.get("buffers", []):
        location = entry.get("extras", {}).get(RESOURCE)
        if "uri" not in entry and location:
            entry["uri"] = ca_validate.resolve(root, location).replace(os.sep, "/")
    if binary is not None and gltf.get("buffers") and "uri" not in gltf["buffers"][0]:
        with open(temp_bin, "wb") as out:
            out.write(binary)
        gltf["buffers"][0]["uri"] = os.path.basename(temp_bin)
    with open(temp, "w", encoding="utf-8") as out:
        json.dump(gltf, out)
    objects, materials = set(bpy.data.objects), set(bpy.data.materials)
    try:
        # Unpacked: an image packed into the .blend has no pack file to reference in place on export.
        bpy.ops.import_scene.gltf(filepath=temp, import_pack_images=False)
        model = ca_validate.Model(temp)
    finally:
        os.remove(temp)
        if os.path.isfile(temp_bin):
            os.remove(temp_bin)
    new_objects = [obj for obj in bpy.data.objects if obj not in objects]
    new_materials = [m for m in bpy.data.materials if m not in materials]
    by_node = {re.sub(r"\.\d{3}$", "", obj.name): obj for obj in new_objects}
    for obj in new_objects:
        apply_extras(obj, take_extras(obj))
    for index, node in enumerate(gltf.get("nodes", [])):
        obj = by_node.get(node.get("name"))
        if obj is not None and obj.type == "MESH" and obj.combinedassault.role == "COLLIDER":
            # The importer splits vertices per face and triangulates; slivers then tilt past the game's 1e-4 checks.
            built = ca_validate.collider_faces(model, index)
            if built is not None:
                points, loops = built
                obj.data.clear_geometry()
                obj.data.from_pydata([(x, -z, y) for x, y, z in points], [], loops)
                obj.data.update()
    by_name = {m.get("name"): m for m in gltf.get("materials", [])}
    for material in new_materials:
        take_extras(material)
        source = by_name.get(material.name, {})
        settings = material.combinedassault
        settings.alpha = source.get("alphaMode", "OPAQUE")
        cutoff = source.get("alphaCutoff", 0.5)
        settings.cutoff = "%.1f" % min(ca_validate.CUTOUTS, key=lambda c: abs(c - cutoff))
        settings.raw_extras = json.dumps(source["extras"]) if source.get("extras") else ""
        settings.texture_slot = source.get("extras", {}).get(ca_validate.TEXTURE_SLOT, "")
    export = context.scene.combinedassault_export
    export.pack_root = root + os.sep
    export.namespace = ns
    model = os.path.relpath(path, os.path.join(root, "assets", ns, "models"))
    export.model_path = os.path.splitext(model)[0].replace(os.sep, "/")
    return new_objects, new_materials


# ---------------------------------------------------------------- materials


def principled(material):
    if not material.use_nodes:
        return None
    return next((n for n in material.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None)


def upstream(socket, depth=0):
    """Nodes feeding socket, nearest first."""
    if not socket.is_linked or depth > 6:
        return []
    node = socket.links[0].from_node
    found = [node]
    for entry in node.inputs:
        found += upstream(entry, depth + 1)
    return found


def base_image_node(material):
    bsdf = principled(material)
    if bsdf is None:
        return None
    return next((n for n in upstream(bsdf.inputs["Base Color"]) if n.type == "TEX_IMAGE"), None)


def material_problems(material):
    """(errors, warnings) for how the exporter will write this material."""
    errors, warnings = [], []
    if not material.use_nodes:
        return errors, ["has no node tree: exported flat white, untextured"]
    images = [n for n in material.node_tree.nodes if n.type == "TEX_IMAGE"]
    bsdf = principled(material)
    if bsdf is None:
        if images:
            errors.append("has no Principled BSDF: the texture is not exported (use Set up texture)")
        return errors, warnings
    node = base_image_node(material)
    if node is None:
        if images:
            errors.append("texture %s is not connected to Base Color: the game draws it untextured "
                          "(use Set up texture)" % (images[0].image.name if images[0].image else "(empty)"))
        return errors, warnings
    chain = [n for n in upstream(bsdf.inputs["Base Color"]) if n != node]
    between = [n for n in chain if n not in upstream(node.inputs["Vector"])]
    if between:
        warnings.append("Base Color goes through %s before the texture: the exporter keeps only the image"
                        % ", ".join(sorted({n.bl_label for n in between})))
    if node.image is None:
        errors.append("image texture node has no image")
        return errors, warnings
    image = node.image
    for feeder in upstream(node.inputs["Vector"]):
        if feeder.type == "UVMAP" and feeder.uv_map:
            warnings.append("texture reads UV map '%s'; GemRender reads each mesh's first UV map" % feeder.uv_map)
        if feeder.type == "MAPPING":
            warnings.append("texture goes through a Mapping node: the game ignores the offset/scale")
    if image.source == "GENERATED" or not image.filepath and image.packed_file is None:
        warnings.append("image %s was never saved to a file: exported as %s.png (rename it first)"
                        % (image.name, os.path.splitext(image.name)[0]))
    elif image.is_dirty:
        warnings.append("image %s has unsaved painting: exported from memory into the pack" % image.name)
    ext = os.path.splitext(image.filepath or image.name)[1].lower()
    if image.file_format != "PNG" and ext != ".png":
        warnings.append("image %s is %s; GemRender atlases PNG" % (image.name, image.file_format))
    if material.combinedassault.alpha == "AUTO" and bsdf.inputs["Alpha"].is_linked:
        warnings.append("Alpha is connected but Transparency is Auto: pick Cut-out (holes) or See-through (glass)")
    return errors, warnings


def setup_texture(material, image, alpha):
    """Principled BSDF <- Image Texture (Closest, pixel art) on Base Color; Alpha linked unless OPAQUE."""
    material.use_nodes = True
    tree = material.node_tree
    tree.nodes.clear()
    out = tree.nodes.new("ShaderNodeOutputMaterial")
    out.location = (300, 0)
    bsdf = tree.nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Metallic"].default_value = 0.0
    bsdf.inputs["Roughness"].default_value = 1.0
    texture = tree.nodes.new("ShaderNodeTexImage")
    texture.location = (-400, 0)
    texture.image = image
    texture.interpolation = "Closest"
    tree.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    tree.links.new(texture.outputs["Color"], bsdf.inputs["Base Color"])
    if alpha != "OPAQUE":
        tree.links.new(texture.outputs["Alpha"], bsdf.inputs["Alpha"])
    material.combinedassault.alpha = alpha


# ---------------------------------------------------------------- player reference


def reference_collection(scene):
    collection = bpy.data.collections.get("combinedassault reference")
    if collection is None:
        collection = bpy.data.collections.new("combinedassault reference")
        scene.collection.children.link(collection)
    return collection


def add_player_reference(context, seated):
    """Boxes at the 3D cursor (feet / seat point), facing the nose (-Y); never exported or checked."""
    name = "player_seated" if seated else "player_standing"
    collection = reference_collection(context.scene)
    root = bpy.data.objects.new(name, None)
    root.empty_display_type = "SINGLE_ARROW"
    root.empty_display_size = ca_reference.EYE
    root.combinedassault.reference = True
    collection.objects.link(root)
    for part, kind, points in ca_reference.solids(seated):
        mesh = bpy.data.meshes.new("%s_%s" % (name, part))
        # glTF frame (x, y up, z forward) -> Blender (x, -z, y).
        mesh.from_pydata([(x, -z, y) for x, y, z in points], [], ca_reference.FACES)
        mesh.update()
        obj = bpy.data.objects.new(mesh.name, mesh)
        obj.color = ca_reference.COLOURS[kind] + (1.0,)
        if kind == "hitbox":
            obj.display_type = "WIRE"
        obj.combinedassault.reference = True
        obj.hide_select = kind != "hitbox"
        obj.parent = root
        collection.objects.link(obj)
    root.matrix_world = Matrix.Translation(context.scene.cursor.location)
    return root


# ---------------------------------------------------------------- passenger cameras

VIEW_FIELDS = ("seat", "view", "sight", "camera", "camera_zoomed")
VIEW_FOV = 70.0
VIEW_PREFIX = "view_"


def view_cameras(scene):
    return sorted((obj for obj in scene.objects if obj.type == "CAMERA" and obj.name.startswith(VIEW_PREFIX)
                   and obj.combinedassault.reference), key=lambda obj: obj.name)


def add_view_cameras(context):
    """One reference camera per seat / view / sight / camera socket, parented to it: eye = origin, looking along the
    socket's -Y (nose at yaw 0), 70 deg vertical like the game. Rebuilt each call; never exported or checked."""
    scene = context.scene
    for old in view_cameras(scene):
        data = old.data
        bpy.data.objects.remove(old)
        if data.users == 0:
            bpy.data.cameras.remove(data)
    collection = reference_collection(scene)
    made = []
    for socket in sorted(scene.objects, key=lambda obj: obj.name):
        if role_of(socket) != "SOCKET" or socket.combinedassault.field not in VIEW_FIELDS or socket.combinedassault.reference:
            continue
        data = bpy.data.cameras.new(VIEW_PREFIX + socket.name)
        data.sensor_fit = "VERTICAL"
        data.angle = math.radians(VIEW_FOV)
        data.clip_start = 0.05
        data.clip_end = 512.0
        data.display_size = 0.4
        camera = bpy.data.objects.new(VIEW_PREFIX + socket.name, data)
        camera.combinedassault.reference = True
        camera.parent = socket
        camera.rotation_euler = (math.radians(90.0), 0.0, math.radians(180.0))
        camera.hide_select = True
        collection.objects.link(camera)
        made.append(camera)
    return made


def look_through(context, camera):
    context.scene.camera = camera
    for area in context.screen.areas:
        if area.type == "VIEW_3D":
            area.spaces.active.region_3d.view_perspective = "CAMERA"


# ---------------------------------------------------------------- properties


class CombinedAssaultObjectSettings(PropertyGroup):
    role: EnumProperty(name="Role", items=ROLE_ITEMS, default="NONE")
    target: StringProperty(name="Interface",
                           description="Part id (frames, part sockets) or entry id (era, slat, mount_point, hitch)",
                           default="")
    posed: BoolProperty(
        name="Posed by script",
        description="A controller script sets this node's rotation (turret, wheel). A pose replaces the local "
                    "transform, so it must be identity under a hinge frame. Doors, flaps: key a part clip instead",
        default=False)
    # Collider.
    label: StringProperty(name="Label", description="Name in the in-game readout", default="")
    main: BoolProperty(name="Main", description="The physics body. None marked => largest solid body piece",
                       default=False)
    deck: BoolProperty(name="Deck", description="Other vehicles may park on it", default=False)
    solid: BoolProperty(name="Solid",
                        description="Off: a marker (wheel probe, gear leg, pylon station), not a surface",
                        default=True)
    locked: BoolProperty(name="Locked", description="tools/gen_hulls.py keeps this piece when it regenerates",
                         default=False)
    hit_surface: BoolProperty(name="Hit surface",
                              description="Also replaces its parent's drawn subtree as hit surface", default=False)
    world: BoolProperty(name="World",
                        description="Off: vehicles only, blocks pass through (a ramp lip under the ground)",
                        default=True)
    hidden: BoolProperty(name="Hidden in game", description="Not drawn (gemrenderHidden); shapes and sockets always",
                         default=False)
    reference: BoolProperty(name="Reference", description="Size reference: never exported or checked",
                            default=False)
    raw_extras: StringProperty(name="Imported extras", description="glTF extras as imported (JSON)", default="",
                               options={"HIDDEN"})
    # Frame limits -> part rot_info (degrees; x negative = up).
    limit_pitch: BoolProperty(name="Limit pitch", description="x_rot_min/max; on the pitch frame, else the part frame",
                              default=False)
    x_rot_min: FloatProperty(name="x_rot_min", default=-90.0)
    x_rot_max: FloatProperty(name="x_rot_max", default=90.0)
    limit_yaw: BoolProperty(name="Limit yaw", description="y_rot_min/max; part frame", default=False)
    y_rot_min: FloatProperty(name="y_rot_min", default=-180.0)
    y_rot_max: FloatProperty(name="y_rot_max", default=180.0)
    follows_clips: BoolProperty(
        name="Colliders follow clips",
        description="Colliders and hit nodes under this frame move with the part clip each tick (part must not be "
                    "rotatable, e.g. generic); else colliders stay at rest pose",
        default=False)
    # Hit module.
    system: EnumProperty(name="System", items=[(s, s, "") for s in SYSTEMS], default="engine")
    side: EnumProperty(name="Side", items=[("NONE", "None", ""), ("LEFT", "Left", ""), ("RIGHT", "Right", "")],
                       default="NONE")
    # Socket.
    field: EnumProperty(name="Field", items=[(f, f, "") for f in SOCKET_FIELDS], default="seat")
    index: IntProperty(name="Index", description="-1 = none; n = the n-th of a list (bolts, exhausts)",
                       default=-1, min=-1)
    # Hit overrides on drawn nodes and frames (vehicle json hitbox wins).
    meta_system: EnumProperty(
        name="System", description="Which system a hit here damages; inherited by the subtree",
        items=[("AUTO", "Auto", "Inherited"), ("none", "None", "Surface only")]
        + [(s, s, "") for s in SYSTEMS], default="AUTO")
    meta_skip: BoolProperty(name="Not hit", description="Not part of the hit surface at all", default=False)
    meta_swept: EnumProperty(
        name="Swept", description="Hit as the disc it spins through (rotors)",
        items=[("AUTO", "Auto", "Rotor systems spin"), ("ON", "On", ""), ("OFF", "Off", "")],
        default="AUTO")
    meta_health: FloatProperty(name="Health", description="0 = system default", default=0.0, min=0.0)
    meta_unit: StringProperty(name="Unit", description="Pool split by name (FL, R1); inherited; HUD ROTR FL",
                              default="")
    meta_spin: EnumProperty(
        name="Spin", description="Rotor unit turn direction seen from above; inherited; required on rotor units",
        items=[("NONE", "Inherit", ""), ("CW", "Clockwise", ""), ("CCW", "Counter-clockwise", "")], default="NONE")
    # Light socket; 0 = the kind's default (VehicleLight.Kind).
    light_kind: EnumProperty(
        name="Kind", items=[("HEAD", "Headlight", "Driver toggles"), ("SEARCH", "Searchlight", "Other crew toggle")],
        default="HEAD")
    light_use_color: BoolProperty(name="Colour", description="Off: the kind's colour", default=False)
    light_color: FloatVectorProperty(name="Colour", subtype="COLOR_GAMMA", size=3, min=0.0, max=1.0,
                                     default=(1.0, 0.945, 0.847))
    light_range: FloatProperty(name="Range", description="Blocks; 0 = kind default", default=0.0, min=0.0)
    light_intensity: FloatProperty(name="Intensity",
                                   description="Irradiance ~1 at sqrt(intensity) blocks; 0 = kind default",
                                   default=0.0, min=0.0)
    light_inner: FloatProperty(name="Inner", description="Full-bright half angle, deg; 0 = kind default",
                               default=0.0, min=0.0, max=88.9)
    light_outer: FloatProperty(name="Outer", description="Cone half angle, deg (< 89); 0 = kind default",
                               default=0.0, min=0.0, max=88.9)
    light_shadow: BoolProperty(name="Shadow", default=True)
    light_cookie: StringProperty(name="Cookie", description="Beam pattern texture, ns:path; empty = none",
                                 default="")
    meta_road_wheel: BoolProperty(
        name="Road wheel", description="Rides the nearest track: own spring probe under it, lower run follows",
        default=False)


class CombinedAssaultMaterialSettings(PropertyGroup):
    # Views of the material name (the game reads its tokens); setting one renames the material.
    armor_mm: FloatProperty(name="Armour (mm RHA)", min=0.0, soft_max=1500.0,
                            description="Plate thickness on these faces; 0 = the vehicle's aspect value",
                            get=lambda self: material_tags(self.id_data.name)[0],
                            set=lambda self, value: retag(self, armor=value))
    decor: BoolProperty(name="Decor", description="Drawn only: never hit, never in generated colliders",
                        get=lambda self: material_tags(self.id_data.name)[2],
                        set=lambda self, value: retag(self, decor=value))
    surface: EnumProperty(name="Hit effect", description="What a strike on these faces looks like",
                          items=SURFACE_ITEMS,
                          get=lambda self: [k for k, _, _ in SURFACE_ITEMS].index(material_tags(self.id_data.name)[1]),
                          set=lambda self, value: retag(self, surface=SURFACE_ITEMS[value][0]))
    texture_slot: StringProperty(
        name="Screen slot", description="extras.textureSlot: screen_<id> drawn by the vehicle's `screens` binding "
                                        "(UVs 0..1 across the screen); empty = ordinary material",
        default="")
    alpha: EnumProperty(
        name="Transparency", description="glTF alphaMode written on export",
        items=[("AUTO", "Auto", "Whatever the exporter derives from the nodes"),
               ("OPAQUE", "Solid", "Texture alpha ignored"),
               ("MASK", "Cut-out", "Pixels below the cutoff are holes (grilles, decals); cheap"),
               ("BLEND", "See-through", "Partly transparent (glass); sorted, costlier")],
        default="AUTO")
    cutoff: EnumProperty(name="Cutoff", description="Alpha below this is a hole (the game has these three)",
                         items=[("0.5", "0.5", ""), ("0.1", "0.1", ""), ("0.0", "0.0", "")], default="0.5")
    raw_extras: StringProperty(name="Imported extras", default="", options={"HIDDEN"})


class CombinedAssaultExportSettings(PropertyGroup):
    pack_root: StringProperty(name="Pack root", subtype="DIR_PATH", default="//",
                              description="Folder holding pack.mcmeta and assets/")
    namespace: StringProperty(name="Namespace", default="combinedassault")
    model_path: StringProperty(name="Model path", default="aircraft/untitled/untitled",
                               description="Under assets/<ns>/models/, without .glb")
    note: StringProperty(name="Last check", default="", options={"SKIP_SAVE"})


# ---------------------------------------------------------------- operators


class COMBINEDASSAULT_OT_make_hull(Operator):
    """Convex hull of the selection, as a collider"""

    bl_idname = "combinedassault.make_hull"
    bl_label = "Make convex hull"
    bl_options = {"REGISTER", "UNDO"}

    keep_original: BoolProperty(name="Keep original", default=True)

    @classmethod
    def poll(cls, context):
        return any(obj.type == "MESH" for obj in context.selected_objects)

    def execute(self, context):
        sources = [obj for obj in context.selected_objects if obj.type == "MESH"]
        depsgraph = context.evaluated_depsgraph_get()
        data = bpy.data.meshes.new(sources[0].name + "_hull")
        hull_into(data, [p for source in sources for p in faces_of(source, depsgraph)[0]])
        hull = bpy.data.objects.new(sources[0].name + "_hull", data)
        context.collection.objects.link(hull)
        frame = frame_of(sources[0])
        if frame is not None:
            hull.parent = frame
            hull.matrix_parent_inverse = frame.matrix_world.inverted()
        hull.combinedassault.role = "COLLIDER"
        style_collider(hull)
        if not self.keep_original:
            for source in sources:
                bpy.data.objects.remove(source, do_unlink=True)
        for obj in context.selected_objects:
            obj.select_set(False)
        hull.select_set(True)
        context.view_layer.objects.active = hull
        self.report({"INFO"}, "hull: %d vertices, %d faces" % (len(data.vertices), len(data.polygons)))
        return {"FINISHED"}


class COMBINEDASSAULT_OT_add_socket(Operator):
    """Socket empty at the 3D cursor, oriented by it"""

    bl_idname = "combinedassault.add_socket"
    bl_label = "Add socket"
    bl_options = {"REGISTER", "UNDO"}

    field: EnumProperty(name="Field", items=[(f, f, "") for f in SOCKET_FIELDS], default="seat")
    target: StringProperty(name="Interface", default="")
    index: IntProperty(name="Index", default=-1, min=-1)

    def execute(self, context):
        name = "_".join(x for x in ("socket", self.field, self.target, str(self.index) if self.index >= 0 else "")
                        if x)
        if self.field in MESH_FIELDS:
            data = bpy.data.meshes.new(name)
            data.from_pydata(*SOCKET_MESHES[self.field])
            data.update()
            obj = bpy.data.objects.new(name, data)
            obj.display_type = "WIRE"
            obj.show_in_front = True
        else:
            obj = bpy.data.objects.new(name, None)
            obj.empty_display_type, obj.empty_display_size = SOCKET_EMPTIES.get(self.field, ("ARROWS", 0.5))
        context.collection.objects.link(obj)
        obj.matrix_world = context.scene.cursor.matrix
        settings = obj.combinedassault
        settings.role = "SOCKET"
        settings.field = self.field
        settings.target = self.target
        settings.index = self.index
        if context.active_object is not None:
            obj.parent = frame_of(context.active_object)
            obj.matrix_world = context.scene.cursor.matrix
        for other in context.selected_objects:
            other.select_set(False)
        obj.select_set(True)
        context.view_layer.objects.active = obj
        return {"FINISHED"}

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)


# Mesh socket starters, Blender frame (nose -Y). era / slat: 1 x 1 quad facing the nose (normal = winding); lens:
# 0.1 quad facing the eye (+Y); track: side-view loop (YZ plane), path = outline.
SOCKET_MESHES = {
    "era": ([(-0.5, 0, -0.5), (0.5, 0, -0.5), (0.5, 0, 0.5), (-0.5, 0, 0.5)], [], [(0, 1, 2, 3)]),
    "slat": ([(-0.5, 0, -0.5), (0.5, 0, -0.5), (0.5, 0, 0.5), (-0.5, 0, 0.5)], [], [(0, 1, 2, 3)]),
    "sight_lens": ([(0.05, 0, -0.05), (-0.05, 0, -0.05), (-0.05, 0, 0.05), (0.05, 0, 0.05)], [], [(0, 1, 2, 3)]),
    "track": ([(0, -2.0, 0.2), (0, -2.3, 0.6), (0, -2.0, 1.0), (0, 2.0, 1.0), (0, 2.3, 0.6), (0, 2.0, 0.2)], [],
              [(0, 1, 2, 3, 4, 5)]),
}
# Empty look where the node's scale is a value (SCALE_FIELDS): drawn size at scale 1 = that value.
SOCKET_EMPTIES = {"cargo_bay": ("CUBE", 0.5), "propeller": ("CIRCLE", 1.0), "light": ("CONE", 0.3),
                  "center_of_mass": ("SPHERE", 0.15)}


class COMBINEDASSAULT_OT_make_hinge(Operator):
    """Parent the selection under <name>_mount (hinge frame at the 3D cursor, local X = hinge axis) -> <name>
    (identity; key a part clip on it, or pose it from a script)"""

    bl_idname = "combinedassault.make_hinge"
    bl_label = "Make hinge"
    bl_options = {"REGISTER", "UNDO"}

    name: StringProperty(name="Node name", description="The node a part clip or script turns", default="hinge")
    part: StringProperty(name="Part id", description="Non-empty: the pivot is that part's frame", default="")
    posed: BoolProperty(name="Posed by script", description="Off: a part clip moves it", default=False)

    @classmethod
    def poll(cls, context):
        return bool(context.selected_objects)

    def execute(self, context):
        movers = list(context.selected_objects)
        parent = movers[0].parent
        mount = bpy.data.objects.new(self.name + "_mount", None)
        pivot = bpy.data.objects.new(self.name, None)
        for node in (mount, pivot):
            node.empty_display_type = "PLAIN_AXES"
            node.empty_display_size = 0.3
            context.collection.objects.link(node)
        mount.parent = parent
        mount.matrix_world = context.scene.cursor.matrix
        pivot.parent = mount
        pivot.matrix_basis = Matrix.Identity(4)
        pivot.combinedassault.posed = self.posed
        if self.part:
            pivot.combinedassault.role = "PART"
            pivot.combinedassault.target = self.part
        context.view_layer.update()
        for obj in movers:
            world = obj.matrix_world.copy()
            obj.parent = pivot
            obj.matrix_parent_inverse = Matrix.Identity(4)
            obj.matrix_world = world
        self.report({"INFO"}, "%s_mount -> %s -> %d object(s); turn %s about its local X"
                    % (self.name, self.name, len(movers), self.name))
        return {"FINISHED"}

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)


class COMBINEDASSAULT_OT_merge_coplanar(Operator):
    """Merge edge-sharing faces on one plane in the selected colliders (the game keeps each authored face as a hull
    face; a triangulated box bottom is a 3-corner ground contact)"""

    bl_idname = "combinedassault.merge_coplanar"
    bl_label = "Merge coplanar faces"
    bl_options = {"REGISTER", "UNDO"}

    @classmethod
    def poll(cls, context):
        return any(role_of(obj) == "COLLIDER" and obj.type == "MESH" for obj in context.selected_objects)

    def execute(self, context):
        merged = 0
        for obj in context.selected_objects:
            if role_of(obj) != "COLLIDER" or obj.type != "MESH":
                continue
            before = len(obj.data.polygons)
            mesh = bmesh.new()
            mesh.from_mesh(obj.data)
            bmesh.ops.dissolve_limit(mesh, angle_limit=1.0e-3, use_dissolve_boundaries=False,
                                     verts=mesh.verts, edges=mesh.edges)
            mesh.to_mesh(obj.data)
            mesh.free()
            obj.data.update()
            merged += before - len(obj.data.polygons)
            points, faces = faces_of(obj)
            if coplanar_neighbours(points, faces):
                self.report({"WARNING"}, "%s: coplanar faces remain (offset beyond %g)" % (obj.name, PLANAR_EPS))
        self.report({"INFO"}, "%d face(s) merged" % merged)
        return {"FINISHED"}


class COMBINEDASSAULT_OT_key_clip(Operator):
    """Key the selection's current transform into part clip <clip>: Start = the part's default state (progress 0),
    End = its other state (progress 1; flaps: deepest notch). Switchables play Start -> End in the clip's length"""

    bl_idname = "combinedassault.key_clip"
    bl_label = "Key part clip"
    bl_options = {"REGISTER", "UNDO"}

    clip: StringProperty(name="Clip", default="door",
                         description="Part id, or <part id>.<suffix> for more clips on one part")
    end: EnumProperty(name="Key", items=[("START", "Start", "Progress 0"), ("END", "End", "Progress 1")],
                      default="START")
    seconds: FloatProperty(name="Length s", default=0.6, min=0.01,
                           description="End key time; a switchable's open/close time")

    @classmethod
    def poll(cls, context):
        return bool(context.selected_objects)

    def execute(self, context):
        if not CLIP_NAME.match(self.clip):
            self.report({"ERROR"}, "clip name: <part id>[.<suffix>], part id lowercase a-z 0-9 _")
            return {"CANCELLED"}
        scene = context.scene
        frame = scene.frame_start
        if self.end == "END":
            frame += round(self.seconds * scene.render.fps / scene.render.fps_base)
        action = bpy.data.actions.get(self.clip) or bpy.data.actions.new(self.clip)
        for obj in context.selected_objects:
            if obj.animation_data is None:
                obj.animation_data_create()
            if obj.animation_data.action is not action:
                obj.animation_data.action = action
            for path in ("location", rotation_path(obj), "scale"):
                obj.keyframe_insert(path, frame=frame)
        self.report({"INFO"}, "%s: %s key at frame %d on %d object(s)"
                    % (self.clip, self.end.lower(), frame, len(context.selected_objects)))
        return {"FINISHED"}

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)


def rotation_path(obj):
    return {"QUATERNION": "rotation_quaternion", "AXIS_ANGLE": "rotation_axis_angle"}.get(obj.rotation_mode,
                                                                                         "rotation_euler")


class COMBINEDASSAULT_OT_assign_armor(Operator):
    """Give the selected faces a copy of their material tagged armor_<mm> (edit mode)"""

    bl_idname = "combinedassault.assign_armor"
    bl_label = "Assign armour to faces"
    bl_options = {"REGISTER", "UNDO"}

    mm: FloatProperty(name="mm RHA", default=100.0, min=0.0)

    @classmethod
    def poll(cls, context):
        return context.object is not None and context.object.type == "MESH" and context.mode == "EDIT_MESH"

    def execute(self, context):
        obj = context.object
        mesh = bmesh.from_edit_mesh(obj.data)
        selected = [face for face in mesh.faces if face.select]
        if not selected:
            self.report({"ERROR"}, "select faces first")
            return {"CANCELLED"}
        slots = {}
        for face in selected:
            base = obj.material_slots[face.material_index].material if obj.material_slots else None
            key = base.name if base else ""
            if key not in slots:
                slots[key] = slot_for(obj, armor_material(base, self.mm))
            face.material_index = slots[key]
        bmesh.update_edit_mesh(obj.data)
        self.report({"INFO"}, "%d face(s) at %g mm" % (len(selected), self.mm))
        return {"FINISHED"}

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)


def strip_tags(name):
    name = ARMOR.sub("", name)
    for pattern in [DECOR] + list(SURFACE.values()):
        # Overlapping delimiters: `a_rubber_glass` needs a second pass per token.
        name = pattern.sub("_", pattern.sub("_", name))
    return re.sub(r"_+", "_", name).strip("_ .") or "material"


def surface_of(name):
    """SurfaceMaterial.of: first matching token, in declaration order; STEEL when none."""
    return next((key.upper() for key, pattern in SURFACE.items() if pattern.search(name)), "STEEL")


def tagged_name(name, armor, surface, decor):
    """`<stem>_decor` or `<stem>[_<surface>][_armor_<mm>]`: the tokens the game reads from a material name."""
    stem = strip_tags(name)
    if decor:
        return stem + "_decor"
    if surface != "STEEL":
        stem += "_" + surface.lower()
    return stem + ("_armor_%g" % armor if armor > 0 else "")


def material_tags(name):
    """(armor mm, surface, decor) as the game reads the name."""
    armor = ARMOR.search(name)
    return float(armor.group(1)) if armor else 0.0, surface_of(name), bool(DECOR.search(name))


def retag(settings, armor=None, surface=None, decor=None):
    """Rename the owning material; armour / surface set => decor off."""
    material = settings.id_data
    old_armor, old_surface, old_decor = material_tags(material.name)
    if decor is None:
        decor = old_decor and armor is None and surface is None
    material.name = tagged_name(material.name, old_armor if armor is None else armor,
                                old_surface if surface is None else surface, decor)


def armor_material(base, mm):
    """Copy of base (look unchanged) named with plate mm (0 = none), base's hit effect kept, decor dropped."""
    name = tagged_name(base.name if base else "material", mm, surface_of(base.name) if base else "STEEL", False)
    existing = bpy.data.materials.get(name)
    if existing is not None:
        return existing
    material = base.copy() if base else bpy.data.materials.new(name)
    material.name = name
    return material


def slot_for(obj, material):
    for index, slot in enumerate(obj.material_slots):
        if slot.material == material:
            return index
    obj.data.materials.append(material)
    return len(obj.material_slots) - 1


class COMBINEDASSAULT_OT_check(Operator):
    """Check the scene as the export would write it"""

    bl_idname = "combinedassault.check"
    bl_label = "Check vehicle"

    def execute(self, context):
        errors, warnings = check_scene(context.scene, context.evaluated_depsgraph_get())
        for message in errors:
            self.report({"ERROR"}, message)
        for message in warnings:
            self.report({"WARNING"}, message)
        context.scene.combinedassault_export.note = "%d error(s), %d warning(s)" % (len(errors), len(warnings))
        return {"CANCELLED"} if errors else {"FINISHED"}


class COMBINEDASSAULT_OT_export(Operator):
    """Check, then write glTF (+bin, textures) into the pack"""

    bl_idname = "combinedassault.export"
    bl_label = "Export vehicle"

    def execute(self, context):
        scene = context.scene
        settings = scene.combinedassault_export
        depsgraph = context.evaluated_depsgraph_get()
        errors, warnings = check_scene(scene, depsgraph)
        for message in warnings:
            self.report({"WARNING"}, message)
        if errors:
            for message in errors:
                self.report({"ERROR"}, message)
            self.report({"ERROR"}, "nothing written: %d error(s)" % len(errors))
            return {"CANCELLED"}
        gltf_path, texture_dir, ns, assets = pack_paths(scene)
        made = []  # folders this export creates for textures, innermost first
        folder = texture_dir
        while not os.path.isdir(folder):
            made.append(folder)
            folder = os.path.dirname(folder)
        staging = os.path.splitext(gltf_path)[0] + ".gltf"
        written = export_gltf(context, staging, texture_dir)
        relocate(staging, ns, assets, scene, written)
        pack_glb(staging, gltf_path)
        for folder in made:
            if os.listdir(folder):
                break
            os.rmdir(folder)
        errors, warnings, _ = ca_validate.validate(gltf_path)
        for message in warnings:
            self.report({"WARNING"}, "written file: " + message)
        for message in errors:
            self.report({"ERROR"}, "written file: " + message)
        settings.note = "wrote %s: %d error(s), %d warning(s)" % (os.path.basename(gltf_path), len(errors),
                                                                   len(warnings))
        self.report({"INFO"}, "wrote " + gltf_path)
        return {"FINISHED"}


def pack_glb(staging, glb_path):
    """Staged .gltf + .bin -> one .glb (images stay resourceLocation references); staging files removed."""
    document, _ = ca_validate.read_container(staging)
    bin_path = os.path.splitext(staging)[0] + ".bin"
    with open(bin_path, "rb") as source:
        ca_validate.write_glb(glb_path, document, source.read())
    os.remove(staging)
    os.remove(bin_path)


def export_gltf(context, gltf_path, texture_dir):
    """Every visible object but references; separate .gltf/.bin, textures into the pack's textures/ tree. Actions ->
    clips, sampled per frame (constraints, drivers baked); same-named actions on several objects -> one clip.
    Returns the texture files this export created (real paths)."""
    os.makedirs(os.path.dirname(gltf_path), exist_ok=True)
    os.makedirs(texture_dir, exist_ok=True)
    before = {os.path.realpath(os.path.join(texture_dir, f)) for f in os.listdir(texture_dir)}
    selection = list(context.selected_objects)
    active = context.view_layer.objects.active
    try:
        for obj in context.scene.objects:
            obj.select_set(obj.visible_get() and not obj.combinedassault.reference)
        bpy.ops.export_scene.gltf(
            filepath=gltf_path,
            export_format="GLTF_SEPARATE",
            export_texture_dir=os.path.relpath(texture_dir, os.path.dirname(gltf_path)),
            use_selection=True,
            export_yup=True,
            export_apply=True,
            export_extras=False,
            export_animations=True,
            export_animation_mode="ACTIONS",
            export_merge_animation="ACTION",
            export_force_sampling=True,
            export_anim_slide_to_zero=True,
        )
    finally:
        for obj in context.scene.objects:
            obj.select_set(obj in selection)
        context.view_layer.objects.active = active
    return {os.path.realpath(os.path.join(texture_dir, f)) for f in os.listdir(texture_dir)} - before


class COMBINEDASSAULT_OT_import(Operator, ImportHelper):
    """Open a model from a pack (assets/<namespace>/models/...): textures found, roles shown on the panels, Export
    writes back to the same file"""

    bl_idname = "combinedassault.import_model"
    bl_label = "Import pack model"
    bl_options = {"REGISTER", "UNDO"}

    filename_ext = ".glb"
    filter_glob: StringProperty(default="*.glb;*.gltf", options={"HIDDEN"})

    def execute(self, context):
        try:
            objects, materials = import_pack_model(context, self.filepath)
        except ValueError as e:
            self.report({"ERROR"}, str(e))
            return {"CANCELLED"}
        errors, warnings, _ = ca_validate.validate(self.filepath)
        for message in errors:
            self.report({"WARNING"}, "file already had: " + message)
        self.report({"INFO"}, "imported %d objects, %d materials; Export writes back to %s"
                    % (len(objects), len(materials), context.scene.combinedassault_export.model_path))
        return {"FINISHED"}


class COMBINEDASSAULT_OT_setup_texture(Operator, ImportHelper):
    """Wire a PNG into the active material the way the game reads it: Image Texture -> Base Color (pixel-sharp)"""

    bl_idname = "combinedassault.setup_texture"
    bl_label = "Set up texture"
    bl_options = {"REGISTER", "UNDO"}

    filename_ext = ".png"
    filter_glob: StringProperty(default="*.png", options={"HIDDEN"})
    alpha: EnumProperty(name="Transparency", items=[("OPAQUE", "Solid", ""), ("MASK", "Cut-out", "Holes where alpha "
                                                     "is low"), ("BLEND", "See-through", "Glass")], default="OPAQUE")

    @classmethod
    def poll(cls, context):
        return context.material is not None

    def execute(self, context):
        image = bpy.data.images.load(self.filepath, check_existing=True)
        setup_texture(context.material, image, self.alpha)
        if pack_location(self.filepath) is None:
            self.report({"WARNING"}, "%s is outside a pack: Export copies it into the pack's textures folder"
                        % os.path.basename(self.filepath))
        return {"FINISHED"}


class COMBINEDASSAULT_OT_player_reference(Operator):
    """Minecraft player boxes at the 3D cursor (feet or seat point), facing the nose; never exported"""

    bl_idname = "combinedassault.player_reference"
    bl_label = "Add player reference"
    bl_options = {"REGISTER", "UNDO"}

    seated: BoolProperty(name="Seated", description="Riding pose; place the cursor on the seat socket",
                         default=False)

    def execute(self, context):
        add_player_reference(context, self.seated)
        return {"FINISHED"}


class COMBINEDASSAULT_OT_view_cameras(Operator):
    """A camera at every seat / view / sight / camera socket (eye = origin, FOV 70), to look through as each
    passenger; never exported"""

    bl_idname = "combinedassault.view_cameras"
    bl_label = "Create passenger cameras"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        made = add_view_cameras(context)
        if not made:
            self.report({"WARNING"}, "no seat / view / sight / camera sockets in the scene")
            return {"CANCELLED"}
        look_through(context, made[0])
        self.report({"INFO"}, "%d passenger camera(s)" % len(made))
        return {"FINISHED"}


class COMBINEDASSAULT_OT_look_through(Operator):
    """Look through a passenger camera (step: next / previous when no camera is named)"""

    bl_idname = "combinedassault.look_through"
    bl_label = "Look through"

    camera: StringProperty(name="Camera", default="")
    step: IntProperty(name="Step", default=0)

    def execute(self, context):
        cameras = view_cameras(context.scene)
        if not cameras:
            self.report({"WARNING"}, "no passenger cameras: Create passenger cameras first")
            return {"CANCELLED"}
        if self.camera:
            target = bpy.data.objects.get(self.camera)
        else:
            current = context.scene.camera
            index = cameras.index(current) if current in cameras else -self.step - 1 if self.step < 0 else -1
            target = cameras[(index + self.step) % len(cameras)]
        if target is None:
            return {"CANCELLED"}
        look_through(context, target)
        return {"FINISHED"}


def drawn_points(obj):
    """Vertices of obj and its untagged mesh descendants, in obj's local frame (gen_hulls.py `cylinder` input)."""
    depsgraph = bpy.context.evaluated_depsgraph_get()
    inverse = obj.matrix_world.inverted()
    points, stack = [], [obj]
    while stack:
        node = stack.pop()
        if node.type == "MESH":
            points += [inverse @ p for p in faces_of(node, depsgraph)[0]]
        stack += [c for c in node.children if role_of(c) == "NONE" and not c.combinedassault.reference]
    return points


def prism(points, sides):
    """(vertices, faces): circumscribed prism round points, axis = thinnest extent (gen_hulls.py `cylinder`)."""
    lo = [min(p[a] for p in points) for a in range(3)]
    hi = [max(p[a] for p in points) for a in range(3)]
    axis = min(range(3), key=lambda a: hi[a] - lo[a])
    u, w = (axis + 1) % 3, (axis + 2) % 3
    mid = [(lo[a] + hi[a]) / 2 for a in range(3)]
    radius = max(math.hypot(p[u] - mid[u], p[w] - mid[w]) for p in points) / math.cos(math.pi / sides)
    verts = []
    for end in (lo[axis], hi[axis]):
        for k in range(sides):
            v = [0.0, 0.0, 0.0]
            v[axis] = end
            v[u] = mid[u] + radius * math.cos(2 * math.pi * k / sides)
            v[w] = mid[w] + radius * math.sin(2 * math.pi * k / sides)
            verts.append(tuple(v))
    ring = list(range(sides))
    faces = [ring[::-1], [sides + k for k in ring]] + [[k, (k + 1) % sides, sides + (k + 1) % sides, sides + k]
                                                       for k in ring]
    return verts, faces


class COMBINEDASSAULT_OT_hit_cylinder(Operator):
    """Hit surface prism round each selected node's drawn subtree (tyres: tread detail is noise), child of it;
    regenerable by tools/gen_hulls.py --hit-cylinder"""

    bl_idname = "combinedassault.hit_cylinder"
    bl_label = "Make hit cylinder"
    bl_options = {"REGISTER", "UNDO"}

    sides: IntProperty(name="Sides", default=12, min=3, max=64)
    surface: EnumProperty(name="Material", items=[("rubber", "rubber", ""), ("glass", "glass", ""),
                                                  ("soft", "soft", ""), ("", "None (steel)", "")], default="rubber")

    @classmethod
    def poll(cls, context):
        return any(role_of(obj) in ("NONE", "PART", "PITCH") for obj in context.selected_objects)

    def execute(self, context):
        made = []
        for obj in [o for o in context.selected_objects if role_of(o) in ("NONE", "PART", "PITCH")]:
            points = drawn_points(obj)
            if len(points) < 3:
                self.report({"WARNING"}, "%s: nothing drawn" % obj.name)
                continue
            data = bpy.data.meshes.new("hit_" + obj.name)
            verts, faces = prism(points, self.sides)
            data.from_pydata(verts, [], faces)
            data.update()
            if self.surface:
                data.materials.append(bpy.data.materials.get(self.surface) or bpy.data.materials.new(self.surface))
            hit = bpy.data.objects.new("hit_" + obj.name, data)
            context.collection.objects.link(hit)
            hit.parent = obj
            hit.matrix_parent_inverse = Matrix.Identity(4)
            hit.matrix_basis = Matrix.Identity(4)
            hit.display_type = "WIRE"
            hit.combinedassault.role = "HIT_SURFACE"
            hit.combinedassault.raw_extras = json.dumps({"genHulls": {"node": obj.name, "hit": True}})
            made.append(hit)
        for obj in context.selected_objects:
            obj.select_set(False)
        for hit in made:
            hit.select_set(True)
        self.report({"INFO"}, "%d hit cylinder(s)" % len(made))
        return {"FINISHED"} if made else {"CANCELLED"}

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)


class COMBINEDASSAULT_OT_add_screen(Operator):
    """Screen quad at the 3D cursor facing the rider (+Y), UVs 0..1, material with texture slot screen_<id>; bind it
    in the vehicle json `screens`"""

    bl_idname = "combinedassault.add_screen"
    bl_label = "Add screen"
    bl_options = {"REGISTER", "UNDO"}

    screen: StringProperty(name="Screen id", default="dash", description="`screens` key; slot = screen_<id>")
    width: FloatProperty(name="Width", default=0.2, min=0.01)
    height: FloatProperty(name="Height", default=0.15, min=0.01)

    def execute(self, context):
        slot = "screen_" + self.screen
        w, h = self.width / 2, self.height / 2
        data = bpy.data.meshes.new(slot)
        # Seen from the rider (looking -Y): u 0 -> 1 left to right (+X -> -X), v 0 -> 1 bottom to top.
        data.from_pydata([(w, 0, -h), (-w, 0, -h), (-w, 0, h), (w, 0, h)], [], [(0, 1, 2, 3)])
        uv = data.uv_layers.new(name="UVMap")
        for loop, coord in zip(data.loops, ((0, 0), (1, 0), (1, 1), (0, 1))):
            uv.data[loop.index].uv = coord
        data.update()
        material = next((m for m in bpy.data.materials if m.combinedassault.texture_slot == slot), None)
        if material is None:
            material = bpy.data.materials.new(slot)
            material.combinedassault.texture_slot = slot
        data.materials.append(material)
        obj = bpy.data.objects.new(slot, data)
        context.collection.objects.link(obj)
        if context.active_object is not None:
            obj.parent = frame_of(context.active_object)
        obj.matrix_world = context.scene.cursor.matrix
        for other in context.selected_objects:
            other.select_set(False)
        obj.select_set(True)
        context.view_layer.objects.active = obj
        return {"FINISHED"}

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)


LIGHT_PREFIX = "lamp_"


def preview_lights(scene):
    """Rebuild one reference spot per light socket, parented to it: beam along the socket's -Y (glTF +Z), cone and
    colour as the game resolves them; never exported or checked."""
    for old in [o for o in scene.objects if o.type == "LIGHT" and o.name.startswith(LIGHT_PREFIX)
                and o.combinedassault.reference]:
        data = old.data
        bpy.data.objects.remove(old)
        if data.users == 0:
            bpy.data.lights.remove(data)
    collection = reference_collection(scene)
    made = []
    for socket in sorted(scene.objects, key=lambda o: o.name):
        settings = socket.combinedassault
        if role_of(socket) != "SOCKET" or settings.field != "light" or settings.reference:
            continue
        colour, reach, intensity, inner, outer = ca_validate.LIGHT_KINDS[settings.light_kind.lower()]
        outer = settings.light_outer or outer
        inner = settings.light_inner or min(inner, outer)
        data = bpy.data.lights.new(LIGHT_PREFIX + socket.name, "SPOT")
        data.color = settings.light_color if settings.light_use_color else \
            tuple(((colour >> s) & 255) / 255 for s in (16, 8, 0))
        data.energy = (settings.light_intensity or intensity) * 4 * math.pi
        data.spot_size = math.radians(2 * min(outer, 88.9))
        data.spot_blend = max(0.0, min(1.0, 1 - inner / outer))
        data.use_shadow = settings.light_shadow
        data.use_custom_distance = True
        data.cutoff_distance = settings.light_range or reach
        lamp = bpy.data.objects.new(LIGHT_PREFIX + socket.name, data)
        lamp.combinedassault.reference = True
        lamp.parent = socket
        lamp.rotation_euler = (math.radians(90.0), 0.0, math.radians(180.0))
        lamp.hide_select = True
        collection.objects.link(lamp)
        made.append(lamp)
    return made


class COMBINEDASSAULT_OT_preview_lights(Operator):
    """A Blender spot at every light socket (beam, cone, colour, range as the game reads them); never exported.
    Rebuilt per call"""

    bl_idname = "combinedassault.preview_lights"
    bl_label = "Preview lights"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        made = preview_lights(context.scene)
        if not made:
            self.report({"WARNING"}, "no light sockets in the scene")
            return {"CANCELLED"}
        self.report({"INFO"}, "%d light(s)" % len(made))
        return {"FINISHED"}


def menu_import(self, context):
    self.layout.operator(COMBINEDASSAULT_OT_import.bl_idname, text="combinedassault pack model (.glb / .gltf)")


# ---------------------------------------------------------------- panels


def wrap(text, width=44):
    lines, line = [], ""
    for word in text.split():
        if line and len(line) + 1 + len(word) > width:
            lines.append(line)
            line = word
        else:
            line = word if not line else line + " " + word
    return lines + ([line] if line else [])


class COMBINEDASSAULT_PT_object(Panel):
    bl_label = "combinedassault"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "object"

    @classmethod
    def poll(cls, context):
        return context.object is not None

    def draw(self, context):
        layout = self.layout
        obj = context.object
        settings = obj.combinedassault
        if settings.reference:
            layout.label(text="Size reference: not exported", icon="INFO")
            return
        layout.prop(settings, "role")
        role = settings.role
        if role in ("PART", "PITCH"):
            layout.prop(settings, "target")
            row = layout.row()
            row.prop(settings, "limit_pitch")
            if settings.limit_pitch:
                row.prop(settings, "x_rot_min")
                row.prop(settings, "x_rot_max")
            if role == "PART":
                row = layout.row()
                row.prop(settings, "limit_yaw")
                if settings.limit_yaw:
                    row.prop(settings, "y_rot_min")
                    row.prop(settings, "y_rot_max")
            layout.prop(settings, "follows_clips")
        elif role == "COLLIDER":
            layout.prop(settings, "label")
            row = layout.row()
            for key in ("main", "solid", "deck", "world"):
                row.prop(settings, key)
            row = layout.row()
            for key in ("locked", "hit_surface"):
                row.prop(settings, key)
        elif role == "HIT":
            layout.prop(settings, "system")
            layout.prop(settings, "side")
        elif role == "SOCKET":
            layout.prop(settings, "field")
            layout.prop(settings, "target")
            layout.prop(settings, "index")
            if settings.field in SCALE_FIELDS:
                axis, meaning = SCALE_FIELDS[settings.field]
                if axis == "Y":
                    layout.prop(obj, "scale", index=1, text=meaning + " (Y scale)")
                else:
                    layout.prop(obj, "scale", text=meaning + (" (mean scale)" if axis == "mean" else ""))
            if settings.field == "light":
                box = layout.box()
                box.label(text="Beam -Y (nose at yaw 0), cookie up +Z; 0 = kind default")
                box.prop(settings, "light_kind")
                row = box.row()
                row.prop(settings, "light_use_color")
                if settings.light_use_color:
                    row.prop(settings, "light_color", text="")
                row = box.row()
                row.prop(settings, "light_range")
                row.prop(settings, "light_intensity")
                row = box.row()
                row.prop(settings, "light_inner")
                row.prop(settings, "light_outer")
                box.prop(settings, "light_shadow")
                box.prop(settings, "light_cookie")
        if role in ("NONE", "BODY", "PART", "PITCH"):
            layout.prop(settings, "hidden")
        if role == "NONE" and obj.type == "MESH":
            layout.prop(settings, "meta_road_wheel")
        if role == "NONE":
            for pattern, meaning in STATE_NODES:
                match = pattern.match(obj.name)
                if match:
                    layout.label(text="mount state node: drawn while the weapon " + meaning.format(*match.groups()),
                                 icon="HIDE_OFF")
        if role != "COLLIDER":
            layout.prop(settings, "posed")
        if role in ("NONE", "BODY", "PART", "PITCH", "HIT", "HIT_SURFACE"):
            box = layout.box()
            box.label(text="Hit overrides (glTF node extras)")
            if role != "HIT":
                box.prop(settings, "meta_system")
                box.prop(settings, "side")
            row = box.row()
            row.prop(settings, "meta_skip")
            row.prop(settings, "meta_swept")
            box.prop(settings, "meta_health")
            row = box.row()
            row.prop(settings, "meta_unit")
            row.prop(settings, "meta_spin", text="")
        errors, warnings = check_object(obj, context.evaluated_depsgraph_get(),
                                        not any(role_of(o) == "BODY" for o in context.scene.objects))
        if errors or warnings:
            box = layout.box()
            for message, icon in [(m, "ERROR") for m in errors] + [(m, "INFO") for m in warnings]:
                for i, line in enumerate(wrap(message)):
                    box.label(text=line, icon=icon if i == 0 else "NONE")
        elif role != "NONE":
            layout.label(text="ok", icon="CHECKMARK")


class COMBINEDASSAULT_PT_material(Panel):
    bl_label = "combinedassault"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "material"

    @classmethod
    def poll(cls, context):
        return context.material is not None

    def draw(self, context):
        layout = self.layout
        material = context.material
        layout.operator("combinedassault.setup_texture", icon="TEXTURE")
        row = layout.row()
        row.prop(material.combinedassault, "alpha")
        if material.combinedassault.alpha == "MASK":
            row.prop(material.combinedassault, "cutoff")
        node = base_image_node(material)
        if node is not None and node.image is not None:
            source = image_file(node.image)
            layout.label(text="texture: " + (pack_location(source) or source if source else node.image.name + " (memory)"),
                         icon="IMAGE_DATA")
        errors, warnings = material_problems(material)
        if errors or warnings:
            box = layout.box()
            for message, icon in [(m, "ERROR") for m in errors] + [(m, "INFO") for m in warnings]:
                for i, line in enumerate(wrap(message)):
                    box.label(text=line, icon=icon if i == 0 else "NONE")
        if node is not None and node.image is not None and image_file(node.image):
            stem = os.path.splitext(image_file(node.image))[0]
            found = [v for v in ("_damaged", "_wrecked", "_paint", "_paint_solid") if os.path.isfile(stem + v + ".png")]
            layout.label(text="variants: " + (", ".join(found) if found else "none (tools/damage_textures.py, "
                                                                               "tools/paint_layers.py)"))
        layout.prop(material.combinedassault, "texture_slot")
        box = layout.box()
        box.label(text="Read from the name; editing renames")
        box.prop(material.combinedassault, "armor_mm")
        row = box.row()
        row.prop(material.combinedassault, "surface")
        row.prop(material.combinedassault, "decor")
        layout.operator("combinedassault.assign_armor", icon="MOD_SOLIDIFY")


class COMBINEDASSAULT_PT_scene(Panel):
    bl_label = "combinedassault export"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "scene"

    def draw(self, context):
        layout = self.layout
        settings = context.scene.combinedassault_export
        layout.operator("combinedassault.import_model", icon="IMPORT")
        layout.label(text="1 unit = 1 block, nose -Y, port +X, up +Z")
        for key in ("pack_root", "namespace", "model_path"):
            layout.prop(settings, key)
        counts = {}
        for obj in context.scene.objects:
            if obj.combinedassault.reference:
                continue
            counts[role_of(obj)] = counts.get(role_of(obj), 0) + 1
        layout.label(text="frames %d, colliders %d, hit modules %d, sockets %d"
                          % (sum(counts.get(r, 0) for r in FRAMES), counts.get("COLLIDER", 0), counts.get("HIT", 0),
                             counts.get("SOCKET", 0)))
        if settings.note:
            layout.label(text=settings.note)
        layout.operator("combinedassault.make_hull", icon="MESH_ICOSPHERE")
        layout.operator("combinedassault.merge_coplanar", icon="MOD_DECIM")
        layout.operator("combinedassault.hit_cylinder", icon="MESH_CYLINDER")
        row = layout.row()
        row.operator("combinedassault.add_socket", icon="EMPTY_ARROWS")
        row.operator("combinedassault.make_hinge", icon="CON_PIVOT")
        layout.operator("combinedassault.key_clip", icon="KEY_HLT")
        row = layout.row()
        row.operator("combinedassault.add_screen", icon="WINDOW")
        row.operator("combinedassault.preview_lights", icon="LIGHT_SPOT")
        row = layout.row()
        row.operator("combinedassault.player_reference", text="Player standing", icon="ARMATURE_DATA").seated = False
        row.operator("combinedassault.player_reference", text="Player seated", icon="ARMATURE_DATA").seated = True
        layout.operator("combinedassault.view_cameras", icon="CAMERA_DATA")
        cameras = view_cameras(context.scene)
        if cameras:
            row = layout.row(align=True)
            row.operator("combinedassault.look_through", text="Previous", icon="TRIA_LEFT").step = -1
            row.operator("combinedassault.look_through", text="Next", icon="TRIA_RIGHT").step = 1
            column = layout.column(align=True)
            for camera in cameras:
                button = column.operator("combinedassault.look_through", text=camera.parent.name if camera.parent else camera.name,
                                         icon="OUTLINER_OB_CAMERA" if camera == context.scene.camera else "CAMERA_DATA")
                button.camera = camera.name
        layout.operator("combinedassault.check", icon="CHECKMARK")
        layout.operator("combinedassault.export", icon="EXPORT")


CLASSES = (
    CombinedAssaultObjectSettings,
    CombinedAssaultMaterialSettings,
    CombinedAssaultExportSettings,
    COMBINEDASSAULT_OT_make_hull,
    COMBINEDASSAULT_OT_add_socket,
    COMBINEDASSAULT_OT_make_hinge,
    COMBINEDASSAULT_OT_merge_coplanar,
    COMBINEDASSAULT_OT_key_clip,
    COMBINEDASSAULT_OT_assign_armor,
    COMBINEDASSAULT_OT_check,
    COMBINEDASSAULT_OT_export,
    COMBINEDASSAULT_OT_import,
    COMBINEDASSAULT_OT_setup_texture,
    COMBINEDASSAULT_OT_player_reference,
    COMBINEDASSAULT_OT_view_cameras,
    COMBINEDASSAULT_OT_look_through,
    COMBINEDASSAULT_OT_hit_cylinder,
    COMBINEDASSAULT_OT_add_screen,
    COMBINEDASSAULT_OT_preview_lights,
    COMBINEDASSAULT_PT_object,
    COMBINEDASSAULT_PT_material,
    COMBINEDASSAULT_PT_scene,
)


def register():
    for cls in CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.Object.combinedassault = PointerProperty(type=CombinedAssaultObjectSettings)
    bpy.types.Material.combinedassault = PointerProperty(type=CombinedAssaultMaterialSettings)
    bpy.types.Scene.combinedassault_export = PointerProperty(type=CombinedAssaultExportSettings)
    bpy.types.TOPBAR_MT_file_import.append(menu_import)


def unregister():
    bpy.types.TOPBAR_MT_file_import.remove(menu_import)
    del bpy.types.Scene.combinedassault_export
    del bpy.types.Material.combinedassault
    del bpy.types.Object.combinedassault
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)


if __name__ == "__main__":
    register()
