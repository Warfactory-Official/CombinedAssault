"""Author a combinedassault glTF vehicle in Blender: frames, colliders, hit modules, sockets, armour, hinges, part
clips, export. Every role is written into the node's glTF `extras` (docs/vehicle-pack-format.md §6).

Frame: 1 unit = 1 block, world origin = vehicle origin, nose toward -Y, port +X, up +Z. The glTF exporter's
Y-up conversion `(x, y, z) -> (x, z, -y)` then gives the vehicle frame (+Z forward, +X port, +Y up).

Install: Edit > Preferences > Add-ons > Install from disk. Panels: Object, Material and Scene properties.
"""

bl_info = {
    "name": "combinedassault tools",
    "author": "combinedassault",
    "version": (3, 0, 0),
    "blender": (4, 2, 0),
    "location": "Properties > Object / Material / Scene > combinedassault",
    "description": "Frames, colliders, hit modules, sockets, armour, part clips and pack export for combinedassault glTF vehicles",
    "category": "Import-Export",
}

import json
import os
import re

import bmesh
import bpy
from bpy.props import (BoolProperty, EnumProperty, FloatProperty, IntProperty, PointerProperty,
                       StringProperty)
from bpy.types import Operator, Panel, PropertyGroup
from mathutils import Matrix, Vector

# Must equal the game's vocabularies; test_combinedassault_tools.py parses the Java sources and compares.
# ComponentKind names but crew, generic.
SYSTEMS = ["hull", "engine", "track", "wheel", "turret", "barrel", "fuel", "ammo", "optics", "rotor",
           "tail_rotor", "wing"]
# NodeRole SOCKET kind.
SOCKET_FIELDS = ["seat", "dismount", "view", "sight", "bolt", "era", "slat", "mount_point", "hitch", "towable",
                 "axle", "exhaust", "vortex", "smoke", "afterburner", "camera", "camera_zoomed", "light", "cargo_bay",
                 "center_of_mass", "pivot", "track", "propeller"]
# Socket fields whose marker is a quad mesh (addon panel extent) rather than an empty.
PANEL_FIELDS = {"era", "slat"}
# Socket fields read from their mesh: panels + track (outline = path).
MESH_FIELDS = PANEL_FIELDS | {"track"}
# Socket fields with no interface (MountDefaults `none`).
UNTARGETED = {"towable", "axle", "camera", "camera_zoomed", "exhaust", "vortex", "smoke", "afterburner", "light",
              "cargo_bay", "center_of_mass", "pivot", "propeller"}
ROLE = "combinedassaultRole"
INTERFACE = "combinedassaultInterface"
META = "combinedassault"
HIDDEN = "gemrenderHidden"
ARMOR = re.compile(r"(?i)armou?r[_ ]?(\d+(?:\.\d+)?)")
DECOR = re.compile(r"(?i)(?:^|[_ .])decor(?:$|[_ .])")

PLANAR_EPS = 1.0e-4
CONVEX_EPS = 1.0e-4
DEGENERATE_EPS = 1.0e-6
IDENTITY_EPS = 1.0e-5
# Clip name == part id, or <part id>.<anything>: the game scrubs it by that part's progress.
CLIP_NAME = re.compile(r"^[a-z0-9_]+(\.[A-Za-z0-9_.]+)?$")
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
    gaps = open_edges(faces)
    if gaps:
        problems.append("is not closed: %d open edge(s), first %d-%d" % (len(gaps), gaps[0][0], gaps[0][1]))
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


def check_object(obj, depsgraph=None):
    """(errors, warnings) for one object as the export would write it."""
    errors, warnings = [], []
    settings = obj.combinedassault
    role = role_of(obj)
    action = obj.animation_data.action if obj.animation_data else None
    if role != "NONE" and role != "BODY" and not under_body(obj):
        errors.append("is a %s but not under the body frame" % role.lower())
    if role == "BODY" and not obj.matrix_world.is_identity:
        errors.append("body frame is not at the origin, unrotated, unscaled")
    if role in ("PART", "PITCH") and not settings.target:
        errors.append("%s frame names no part (interface)" % role.lower())
    if role == "COLLIDER":
        errors += check_collider(obj, depsgraph)
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
    if role == "SOCKET":
        if settings.field in UNTARGETED and settings.target:
            errors.append("socket %s takes no interface" % settings.field)
        if settings.field not in UNTARGETED and not settings.target:
            errors.append("socket %s names no %s (interface)" % (settings.field, "id" if settings.field in (
                "era", "slat", "mount_point", "hitch") else "part"))
        if obj.type == "MESH" and settings.field not in MESH_FIELDS:
            warnings.append("socket is a mesh; only era/slat/track use the geometry")
        if obj.type != "MESH" and settings.field in MESH_FIELDS:
            errors.append("%s socket needs a mesh (%s)" % (settings.field, "outline = path" if settings.field == "track"
                                                            else "quad"))
    if obj.type == "MESH" and is_mirrored(obj):
        errors.append("is mirrored: exports inside out. Apply scale, rebuild with a rotation")
    if obj.type == "MESH" and len(obj.data.uv_layers) > 1:
        warnings.append("has %d UV maps; GemRender draws TEXCOORD_0 only" % len(obj.data.uv_layers))
    if settings.posed and not local_is_identity(obj):
        errors.append("is posed by a script but its local transform is not identity: a pose replaces it. "
                      "Use Make hinge")
    return errors, warnings


def check_scene(scene, depsgraph=None):
    errors, warnings = [], []
    if abs(scene.unit_settings.scale_length - 1.0) > 1e-6:
        warnings.append("scene unit scale is %.3f; the exporters ignore it (1 unit = 1 block)"
                        % scene.unit_settings.scale_length)
    mains = [obj for obj in scene.objects if role_of(obj) == "COLLIDER" and obj.combinedassault.main]
    if len(mains) > 1:
        errors.append("more than one collider is Main: %s" % ", ".join(o.name for o in mains))
    bodies = [obj for obj in scene.objects if role_of(obj) == "BODY"]
    if len(bodies) != 1:
        errors.append("needs exactly one body frame, has %d" % len(bodies))
    seen = {}
    for obj in scene.objects:
        if role_of(obj) in ("PART", "PITCH"):
            key = (role_of(obj), obj.combinedassault.target)
            if key in seen:
                errors.append("%s %s on %s and %s" % (key[0].lower(), key[1], seen[key], obj.name))
            seen[key] = obj.name
    for obj in scene.objects:
        e, w = check_object(obj, depsgraph)
        errors += ["%s %s" % (obj.name, m) for m in e]
        warnings += ["%s %s" % (obj.name, m) for m in w]
    for material in used_materials(scene):
        if material.use_nodes:
            for node in material.node_tree.nodes:
                if node.type == "TEX_IMAGE" and node.image is not None:
                    ext = os.path.splitext(node.image.filepath or node.image.name)[1].lower()
                    if node.image.file_format != "PNG" and ext != ".png":
                        warnings.append("material %s image %s is %s; GemRender atlases PNG"
                                        % (material.name, node.image.name, node.image.file_format))
    return errors, warnings


def used_materials(scene):
    seen = []
    for obj in scene.objects:
        if obj.type == "MESH" and role_of(obj) not in ("COLLIDER", "HIT", "SOCKET"):
            for slot in obj.material_slots:
                if slot.material is not None and slot.material not in seen:
                    seen.append(slot.material)
    return seen


# ---------------------------------------------------------------- colliders


def style_collider(obj):
    obj.display_type = "WIRE"
    obj.show_in_front = True
    solid = obj.combinedassault.solid
    obj.color = (1.0, 0.85, 0.2, 1.0) if obj.combinedassault.main else (0.2, 1.0, 0.2, 1.0 if solid else 0.45)


# ---------------------------------------------------------------- glTF post-process


def pack_paths(scene):
    """(gltf path, texture dir, namespace, assets/<ns> dir)."""
    settings = scene.combinedassault_export
    root = bpy.path.abspath(settings.pack_root)
    ns = settings.namespace
    model = settings.model_path.strip("/")
    gltf = os.path.join(root, "assets", ns, "models", model + ".gltf")
    textures = os.path.join(root, "assets", ns, "textures", os.path.dirname(model))
    return gltf, textures, ns, os.path.join(root, "assets", ns)


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
    if role in ("COLLIDER", "HIT", "HIT_SURFACE", "SOCKET"):
        extras[HIDDEN] = True
    if role == "COLLIDER":
        if settings.main:
            meta["main"] = True
        if settings.deck:
            meta["deck"] = True
        if not settings.solid:
            meta["solid"] = False
        if settings.label:
            meta["name"] = settings.label
        if settings.locked:
            extras["genHulls"] = {"locked": True}
        if settings.hit_surface:
            meta["hit"] = True
    elif role == "SOCKET":
        if settings.index >= 0:
            meta["index"] = settings.index
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
        if settings.meta_health > 0:
            meta["health"] = round(settings.meta_health, 3)
        if role in ("PART", "PITCH") and settings.limit_pitch:
            meta["x_rot_min"] = round(settings.x_rot_min, PLACES)
            meta["x_rot_max"] = round(settings.x_rot_max, PLACES)
        if role == "PART" and settings.limit_yaw:
            meta["y_rot_min"] = round(settings.y_rot_min, PLACES)
            meta["y_rot_max"] = round(settings.y_rot_max, PLACES)
    if meta:
        extras[META] = meta
    return extras


def relocate(gltf_path, ns, assets, scene=None):
    """URIs -> extras.resourceLocation, the form the mod's loaders read (GemRender needs it for images);
    per-node role, interface, parameters -> extras (nodes are named after their objects)."""
    with open(gltf_path, encoding="utf-8") as source:
        gltf = json.load(source)
    if scene is not None:
        for node in gltf.get("nodes", []):
            obj = scene.objects.get(node.get("name", ""))
            extras = node_extras(obj) if obj is not None else {}
            if extras:
                node.setdefault("extras", {}).update(extras)
    here = os.path.dirname(gltf_path)
    for entry in gltf.get("images", []) + gltf.get("buffers", []):
        uri = entry.get("uri")
        if uri is None or uri.startswith("data:"):
            continue
        del entry["uri"]
        entry.setdefault("extras", {})["resourceLocation"] = resource_location(
            ns, assets, os.path.normpath(os.path.join(here, bpy.path.native_pathsep(uri))))
    gltf["asset"].setdefault("extras", {})["staged_by"] = "tools/blender/combinedassault_tools.py"
    with open(gltf_path, "w", encoding="utf-8") as out:
        json.dump(gltf, out, separators=(",", ":"), ensure_ascii=False)
    return gltf


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
    # Frame limits -> part rot_info (degrees; x negative = up).
    limit_pitch: BoolProperty(name="Limit pitch", description="x_rot_min/max; on the pitch frame, else the part frame",
                              default=False)
    x_rot_min: FloatProperty(name="x_rot_min", default=-90.0)
    x_rot_max: FloatProperty(name="x_rot_max", default=90.0)
    limit_yaw: BoolProperty(name="Limit yaw", description="y_rot_min/max; part frame", default=False)
    y_rot_min: FloatProperty(name="y_rot_min", default=-180.0)
    y_rot_max: FloatProperty(name="y_rot_max", default=180.0)
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


class CombinedAssaultMaterialSettings(PropertyGroup):
    armor_mm: FloatProperty(name="Armour (mm RHA)", default=0.0, min=0.0, soft_max=1500.0)


class CombinedAssaultExportSettings(PropertyGroup):
    pack_root: StringProperty(name="Pack root", subtype="DIR_PATH", default="//",
                              description="Folder holding pack.mcmeta and assets/")
    namespace: StringProperty(name="Namespace", default="combinedassault")
    model_path: StringProperty(name="Model path", default="aircraft/untitled/untitled",
                               description="Under assets/<ns>/models/, without .gltf")
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
        mesh = bmesh.new()
        try:
            for source in sources:
                for point in faces_of(source, depsgraph)[0]:
                    mesh.verts.new(point)
            result = bmesh.ops.convex_hull(mesh, input=mesh.verts, use_existing_faces=False)
            bmesh.ops.delete(mesh, geom=result["geom_interior"], context="VERTS")
            bmesh.ops.delete(mesh, geom=result["geom_unused"], context="VERTS")
            # Coplanar triangles back into flat faces: every face is a separating axis.
            bmesh.ops.dissolve_limit(mesh, angle_limit=0.0017, verts=mesh.verts, edges=mesh.edges)
            data = bpy.data.meshes.new(sources[0].name + "_hull")
            mesh.to_mesh(data)
        finally:
            mesh.free()
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
        obj = bpy.data.objects.new("_".join(x for x in ("socket", self.field, self.target,
                                                       str(self.index) if self.index >= 0 else "") if x), None)
        obj.empty_display_type = "ARROWS"
        obj.empty_display_size = 0.5
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
    name = DECOR.sub("_", name)
    return re.sub(r"_+", "_", name).strip("_ .") or "material"


def armor_material(base, mm):
    """`<base>_armor_<mm>` (a copy of base, so the look is unchanged); mm 0 = the untagged base."""
    stem = strip_tags(base.name) if base else "material"
    name = stem if mm <= 0 else "%s_armor_%s" % (stem, ("%g" % mm))
    existing = bpy.data.materials.get(name)
    if existing is not None:
        return existing
    material = base.copy() if base else bpy.data.materials.new(name)
    material.name = name
    material.combinedassault.armor_mm = mm
    return material


def slot_for(obj, material):
    for index, slot in enumerate(obj.material_slots):
        if slot.material == material:
            return index
    obj.data.materials.append(material)
    return len(obj.material_slots) - 1


class COMBINEDASSAULT_OT_tag_material(Operator):
    """Rename the active material to carry its armour or decor tag"""

    bl_idname = "combinedassault.tag_material"
    bl_label = "Apply tag to name"
    bl_options = {"REGISTER", "UNDO"}

    decor: BoolProperty(name="Decor", description="Drawn only: never hit, never collides", default=False)

    @classmethod
    def poll(cls, context):
        return context.material is not None

    def execute(self, context):
        material = context.material
        stem = strip_tags(material.name)
        if self.decor:
            material.name = stem + "_decor"
        elif material.combinedassault.armor_mm > 0:
            material.name = "%s_armor_%s" % (stem, "%g" % material.combinedassault.armor_mm)
        else:
            material.name = stem
        return {"FINISHED"}


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
        export_gltf(context, gltf_path, texture_dir)
        relocate(gltf_path, ns, assets, scene)
        self.report({"INFO"}, "wrote " + gltf_path)
        return {"FINISHED"}


def export_gltf(context, gltf_path, texture_dir):
    """Every visible object; separate .gltf/.bin, textures into the pack's textures/ tree. Actions -> clips,
    sampled per frame (constraints, drivers baked); same-named actions on several objects -> one clip."""
    os.makedirs(os.path.dirname(gltf_path), exist_ok=True)
    os.makedirs(texture_dir, exist_ok=True)
    selection = list(context.selected_objects)
    active = context.view_layer.objects.active
    try:
        for obj in context.scene.objects:
            obj.select_set(obj.visible_get())
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
        elif role == "COLLIDER":
            layout.prop(settings, "label")
            row = layout.row()
            for key in ("main", "solid", "deck", "locked", "hit_surface"):
                row.prop(settings, key)
        elif role == "HIT":
            layout.prop(settings, "system")
            layout.prop(settings, "side")
        elif role == "SOCKET":
            layout.prop(settings, "field")
            layout.prop(settings, "target")
            layout.prop(settings, "index")
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
        errors, warnings = check_object(obj, context.evaluated_depsgraph_get())
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
        armor = ARMOR.search(material.name)
        decor = DECOR.search(material.name)
        layout.label(text="game reads: %s" % ("decor (drawn only)" if decor
                                                 else "%s mm" % armor.group(1) if armor else "surface, no plate"))
        layout.prop(material.combinedassault, "armor_mm")
        row = layout.row()
        row.operator("combinedassault.tag_material", text="Tag armour").decor = False
        row.operator("combinedassault.tag_material", text="Tag decor").decor = True
        layout.operator("combinedassault.assign_armor", icon="MOD_SOLIDIFY")


class COMBINEDASSAULT_PT_scene(Panel):
    bl_label = "combinedassault export"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "scene"

    def draw(self, context):
        layout = self.layout
        settings = context.scene.combinedassault_export
        layout.label(text="1 unit = 1 block, nose -Y, port +X, up +Z")
        for key in ("pack_root", "namespace", "model_path"):
            layout.prop(settings, key)
        counts = {}
        for obj in context.scene.objects:
            counts[role_of(obj)] = counts.get(role_of(obj), 0) + 1
        layout.label(text="frames %d, colliders %d, hit modules %d, sockets %d"
                          % (sum(counts.get(r, 0) for r in FRAMES), counts.get("COLLIDER", 0), counts.get("HIT", 0),
                             counts.get("SOCKET", 0)))
        if settings.note:
            layout.label(text=settings.note)
        layout.operator("combinedassault.make_hull", icon="MESH_ICOSPHERE")
        row = layout.row()
        row.operator("combinedassault.add_socket", icon="EMPTY_ARROWS")
        row.operator("combinedassault.make_hinge", icon="CON_PIVOT")
        layout.operator("combinedassault.key_clip", icon="KEY_HLT")
        layout.operator("combinedassault.check", icon="CHECKMARK")
        layout.operator("combinedassault.export", icon="EXPORT")


CLASSES = (
    CombinedAssaultObjectSettings,
    CombinedAssaultMaterialSettings,
    CombinedAssaultExportSettings,
    COMBINEDASSAULT_OT_make_hull,
    COMBINEDASSAULT_OT_add_socket,
    COMBINEDASSAULT_OT_make_hinge,
    COMBINEDASSAULT_OT_key_clip,
    COMBINEDASSAULT_OT_assign_armor,
    COMBINEDASSAULT_OT_tag_material,
    COMBINEDASSAULT_OT_check,
    COMBINEDASSAULT_OT_export,
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


def unregister():
    del bpy.types.Scene.combinedassault_export
    del bpy.types.Material.combinedassault
    del bpy.types.Object.combinedassault
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)


if __name__ == "__main__":
    register()
