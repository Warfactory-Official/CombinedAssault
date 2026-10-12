"""Blender side of tools/internal/stage_mq9.py: bake the vehicle frame, build the posable pivots, export glTF.

  blender -b MQ9_Reaper_tweaked.blend --python tools/blender/export_mq9.py -- <out.gltf>

Source frame (README): forward -X, left -Y, up +Z. Rz(+90) first => exported glTF is the vehicle frame
(+Z forward, +X port, +Y up) on every node, no root rotation. A pose replaces a posed node's rotation and
translation, so each moving part is `<name>_mount` (position, hinge frame) -> `<name>` (identity, posed).
"""
import math
import sys

import bpy
from mathutils import Matrix, Vector

OUT = sys.argv[sys.argv.index("--") + 1]

# Not gear, despite the collection: the EO ball and the intake ring.
EO_BALL = "Cylinder"
INTAKE_RING = "Torus.001"
# Tile centres on the 4x4 painted atlas (Blender UV, v up): dark navy, dark grey.
EO_BALL_UV = (0.375, 0.625)
INTAKE_RING_UV = (0.375, 0.375)

scene = bpy.context.scene
for obj in list(bpy.data.objects):
    if obj.type in {"CAMERA", "LIGHT"}:
        bpy.data.objects.remove(obj)

turn = Matrix.Rotation(math.radians(90.0), 4, "Z")
for obj in bpy.data.objects:
    if obj.parent is None:
        obj.matrix_world = turn @ obj.matrix_world
bpy.context.view_layer.update()

material = bpy.data.materials["MQ9_EMB4_inspired_painted"]
for name, uv in ((EO_BALL, EO_BALL_UV), (INTAKE_RING, INTAKE_RING_UV)):
    mesh = bpy.data.objects[name].data
    mesh.materials.clear()
    mesh.materials.append(material)
    for loop in mesh.uv_layers.active.data:
        loop.uv = uv
# One material, one image.
for obj in bpy.data.objects:
    if obj.type == "MESH":
        for slot in obj.material_slots:
            slot.material = material


def empty(name, world, parent=None):
    node = bpy.data.objects.new(name, None)
    scene.collection.objects.link(node)
    if parent is not None:
        node.parent = parent
        node.matrix_parent_inverse = Matrix.Identity(4)
    node.matrix_world = world
    return node


def adopt(parent, children):
    bpy.context.view_layer.update()
    for child in children:
        world = child.matrix_world.copy()
        child.parent = parent
        child.matrix_parent_inverse = Matrix.Identity(4)
        child.matrix_world = world


def pivot(name, location, basis, children, posed=(None,)):
    """`name`_mount at location with rotation basis; chain of identity posed nodes; children under the last."""
    mount = empty(name + "_mount", Matrix.Translation(location) @ basis.to_4x4())
    parent = mount
    for posed_name in posed:
        bpy.context.view_layer.update()
        parent = empty(posed_name or name, parent.matrix_world.copy(), parent)
    adopt(parent, children)
    return mount


def bounds(obj):
    corners = [obj.matrix_world @ Vector(c) for c in obj.bound_box]
    return (Vector([min(c[i] for c in corners) for i in range(3)]),
            Vector([max(c[i] for c in corners) for i in range(3)]))


def hinge_basis(axis):
    """Rotation whose local X is the hinge axis (glTF keeps X under its Y-up conversion)."""
    x = axis.normalized()
    up = Vector((0.0, 0.0, 1.0))
    y = (up - x * up.dot(x)).normalized()
    z = x.cross(y)
    return Matrix((x, y, z)).transposed()


objects = bpy.data.objects
identity = Matrix.Identity(3)

spinner = objects["Spinner"]
pivot("propeller", spinner.matrix_world.translation.copy(), identity, [spinner])

for side in ("LEFT", "RIGHT"):
    aileron = objects[f"{side} aileron"]
    lo, hi = bounds(aileron)
    # Forward is -Y after the turn: the hinge runs along the leading edge.
    pivot(f"{side.lower()}_aileron", Vector(((lo.x + hi.x) / 2, lo.y, (lo.z + hi.z) / 2)), identity, [aileron])
    hinge = objects[f"{side}_RUDDERVATOR_HINGE"]
    pivot(f"{side.lower()}_ruddervator", hinge.matrix_world.translation.copy(),
          hinge_basis(hinge.matrix_world.to_3x3() @ Vector((0.0, 1.0, 0.0))), [objects[f"{side} ruddervator"]])

# The ball's own centre: EO_SENSOR_PITCH sits 0.14 below it, and the ball would swing off its collar.
lo, hi = bounds(objects[EO_BALL])
pivot("eo", (lo + hi) / 2, identity, [objects[EO_BALL]], posed=("eo_yaw", "eo_pitch"))

gear = bpy.data.collections["GEAR"]
gear_node = empty("gear", Matrix.Identity(4))
adopt(gear_node, [o for o in gear.objects if o.name not in {EO_BALL, INTAKE_RING} and o.parent is None])

bpy.context.view_layer.update()
bpy.ops.export_scene.gltf(
    filepath=OUT,
    export_format="GLTF_SEPARATE",
    export_yup=True,
    export_apply=True,
    export_cameras=False,
    export_lights=False,
    export_extras=False,
    export_animations=False,
    export_image_format="AUTO",
)
print("EXPORTED", OUT)
