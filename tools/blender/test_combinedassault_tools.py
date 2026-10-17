"""Headless check of combinedassault_tools.py against real Blender (pip `bpy`, Python 3.13).

  uv venv --python 3.13 bpyenv && uv pip install --python bpyenv/bin/python bpy
  bpyenv/bin/python tools/blender/test_combinedassault_tools.py
  or: blender -b --factory-startup --python "$PWD/tools/blender/test_combinedassault_tools.py"   (Flatpak: absolute path)

Builds a small vehicle, checks it, exports it into a temp pack and reads the files back. Also compares the
add-on's vocabularies with the Java sources they must match.
"""
import json
import math
import os
import re
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import bmesh  # noqa: E402
import bpy  # noqa: E402
from mathutils import Vector  # noqa: E402

import combinedassault_tools as T  # noqa: E402

FAILURES = []


def check(what, ok, detail=""):
    print("%s %s %s" % ("PASS" if ok else "FAIL", what, detail))
    if not ok:
        FAILURES.append(what)


def java(path):
    with open(os.path.join(REPO, "src/main/java/com/wf/combinedassault", path), encoding="utf-8") as source:
        return source.read()


def vocabulary():
    kinds = re.findall(r'^\s*([A-Z_]+)\("([a-z_]+)"', java("ballistics/component/ComponentKind.java"), re.M)
    systems = {name.lower() for name, code in kinds if name not in ("CREW", "GENERIC")}
    check("systems == ComponentKind names", systems == set(T.SYSTEMS), str(systems ^ set(T.SYSTEMS)))
    roles = re.findall(r'^\s*([A-Z_]+)\(Kind\.SOCKET\)', java("vehicle/hit/NodeRole.java"), re.M)
    fields = {r.lower() for r in roles}
    check("socket fields == NodeRole sockets", fields == set(T.SOCKET_FIELDS), str(fields ^ set(T.SOCKET_FIELDS)))
    armor = re.search(r'ARMOR = Pattern.compile\("(.*?)"\)', java("vehicle/hit/ModelDataManager.java")).group(1)
    check("armour pattern == ModelDataManager.ARMOR", armor.replace("\\\\", "\\") == T.ARMOR.pattern, armor)


def box(name, location, size):
    bpy.ops.mesh.primitive_cube_add(location=location)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = Vector(size) / 2
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    return obj


def select(*objs):
    for obj in bpy.context.scene.objects:
        obj.select_set(obj in objs)
    bpy.context.view_layer.objects.active = objs[0]


def build(textures):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    T.register()
    scene = bpy.context.scene

    root = bpy.data.objects.new("tank", None)
    scene.collection.objects.link(root)
    root.combinedassault.role = "BODY"
    body = box("body", (0, -1, 1), (4, 6, 2))
    body.parent = root
    image = bpy.data.images.new("tank_skin", 8, 8)
    image.filepath_raw = os.path.join(textures, "tank_skin.png")
    image.file_format = "PNG"
    image.save()
    paint = bpy.data.materials.new("paint")
    paint.use_nodes = True
    texture = paint.node_tree.nodes.new("ShaderNodeTexImage")
    texture.image = image
    paint.node_tree.links.new(texture.outputs["Color"], paint.node_tree.nodes["Principled BSDF"].inputs["Base Color"])
    body.data.materials.append(paint)
    front = T.armor_material(paint, 80)
    body.data.polygons[0].material_index = T.slot_for(body, front)
    body.combinedassault.meta_system = "none"

    collider = box("hull_body", (0, -1, 1), (4, 6, 2))
    collider.parent = root
    collider.combinedassault.role = "COLLIDER"
    collider.combinedassault.main = True

    engine = box("engine_block", (0, 1.5, 1), (2, 1.5, 1))
    engine.parent = root
    engine.combinedassault.role = "HIT"
    engine.combinedassault.system = "engine"
    engine.combinedassault.side = "LEFT"
    engine.combinedassault.meta_health = 50

    scene.cursor.location = (0.5, -0.5, 2)
    select(body)
    bpy.ops.combinedassault.add_socket(field="seat", target="driver", index=-1)

    flap = box("flap_mesh", (2.5, 2, 1), (1, 0.6, 0.1))
    flap.parent = root
    scene.cursor.location = (2.5, 1.7, 1)
    select(flap)
    bpy.ops.combinedassault.make_hinge(name="left_flap", part="flaps_l")
    right = box("right_flap_mesh", (-2.5, 2, 1), (1, 0.6, 0.1))
    right.parent = root
    scene.cursor.location = (-2.5, 1.7, 1)
    select(right)
    bpy.ops.combinedassault.make_hinge(name="right_flap", part="flaps_r")

    # One clip over both pivots: start at rest, end 30 deg trailing edge down, 1 s.
    pivots = [scene.objects["left_flap"], scene.objects["right_flap"]]
    select(*pivots)
    bpy.ops.combinedassault.key_clip(clip="flaps", end="START")
    for pivot in pivots:
        pivot.rotation_euler = (math.radians(30), 0, 0)
    bpy.ops.combinedassault.key_clip(clip="flaps", end="END", seconds=1.0)
    scene.frame_set(scene.frame_start)
    return scene, body, collider, engine, flap


def accessor(gltf, pack, index):
    """Float accessor -> list of tuples (or floats for SCALAR)."""
    import struct
    acc = gltf["accessors"][index]
    view = gltf["bufferViews"][acc["bufferView"]]
    ns, path = gltf["buffers"][view["buffer"]]["extras"]["resourceLocation"].split(":", 1)
    with open(os.path.join(pack, "assets", ns, path), "rb") as source:
        blob = source.read()
    width = {"SCALAR": 1, "VEC3": 3, "VEC4": 4}[acc["type"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = view.get("byteStride", 4 * width)
    rows = [struct.unpack_from("<%df" % width, blob, start + i * stride) for i in range(acc["count"])]
    return [r[0] for r in rows] if width == 1 else rows


def run():
    vocabulary()
    pack = tempfile.mkdtemp(prefix="combinedassault_pack_")
    work = tempfile.mkdtemp(prefix="combinedassault_src_")
    scene, body, collider, engine, flap = build(work)

    check("socket named from its settings", "socket_seat_driver" in scene.objects)
    check("socket parented to the active object's frame", scene.objects["socket_seat_driver"].parent == scene.objects["tank"])
    pivot = scene.objects["left_flap"]
    check("hinge pivot is identity at the clip start, not script-posed",
          T.local_is_identity(pivot) and not pivot.combinedassault.posed)
    check("hinged mesh keeps its world pose",
          (flap.matrix_world.translation - Vector((2.5, 2, 1))).length < 1e-6)

    errors, warnings = T.check_scene(scene)
    check("clean scene has no errors", not errors, str(errors))

    dent = box("dent", (6, 0, 0), (1, 1, 1))
    dent.parent = scene.objects["tank"]
    dent.combinedassault.role = "COLLIDER"
    dent.data.vertices[0].co = Vector((0, 0, 0))
    flap.combinedassault.posed = True
    errors, _ = T.check_scene(scene)
    check("non-convex collider refused", any("dent" in e and "convex" in e for e in errors), str(errors))
    check("posed non-identity refused", any("flap_mesh" in e and "identity" in e for e in errors), str(errors))
    bpy.data.objects.remove(dent, do_unlink=True)
    flap.combinedassault.posed = False

    flat = box("flat", (6, 0, 0), (1, 1, 1))
    flat.parent = scene.objects["tank"]
    flat.combinedassault.role = "COLLIDER"
    _, warnings = T.check_scene(scene)
    check("quad collider: no shared-plane warning", not any("flat" in w and "plane" in w for w in warnings), str(warnings))
    bm = bmesh.new()
    bm.from_mesh(flat.data)
    bmesh.ops.triangulate(bm, faces=bm.faces)
    bm.to_mesh(flat.data)
    bm.free()
    _, warnings = T.check_scene(scene)
    check("triangulated collider warned", any("flat" in w and "share a plane" in w for w in warnings), str(warnings))
    bpy.data.objects.remove(flat, do_unlink=True)

    wheel = box("road_wheel_0", (2, 0, 0.5), (0.3, 1, 1))
    wheel.parent = body
    wheel.combinedassault.meta_road_wheel = True
    errors, _ = T.check_scene(scene)
    check("road wheel without a track refused", any("no track socket" in e for e in errors), str(errors))
    bpy.data.objects.remove(wheel, do_unlink=True)

    orphan = box("orphan", (10, 0, 0), (1, 1, 1))
    orphan.combinedassault.role = "HIT"
    errors, _ = T.check_scene(scene)
    check("role node outside the body refused", any("orphan" in e and "body" in e for e in errors), str(errors))
    bpy.data.objects.remove(orphan, do_unlink=True)

    stray = box("stray", (8, 0, 0), (1, 1, 1))
    stray.animation_data_create()
    stray.animation_data.action = bpy.data.actions.new("Door Open")
    stray.keyframe_insert("location", frame=1)
    _, warnings = T.check_scene(scene)
    check("unplayable clip name warned", any("Door Open" in w for w in warnings), str(warnings))
    bpy.data.objects.remove(stray, do_unlink=True)

    settings = scene.combinedassault_export
    tyre = box("tyre_hit", (0, -1, 3), (1, 1, 1))
    tyre.parent = body
    tyre.combinedassault.role = "HIT_SURFACE"
    limits = bpy.data.objects["left_flap"].combinedassault
    limits.limit_pitch = True
    limits.x_rot_min = -10
    limits.x_rot_max = 30

    settings.pack_root = pack
    settings.model_path = "test/tank/tank"
    result = bpy.ops.combinedassault.export()
    check("export finished", result == {"FINISHED"}, str(result))

    gltf_path = os.path.join(pack, "assets/combinedassault/models/test/tank/tank.gltf")
    with open(gltf_path, encoding="utf-8") as source:
        gltf = json.load(source)
    nodes = {node["name"]: node for node in gltf["nodes"]}

    def resolves(rl):
        ns, path = rl.split(":", 1)
        return os.path.isfile(os.path.join(pack, "assets", ns, path))

    images = gltf.get("images", [])
    check("images carry resourceLocation, no uri",
          images and all("uri" not in i and resolves(i["extras"]["resourceLocation"]) for i in images), str(images))
    check("texture lands under textures/<model dir>",
          any(i["extras"]["resourceLocation"].startswith("combinedassault:textures/test/tank/") for i in images))
    buffers = gltf["buffers"]
    check("buffers carry resourceLocation, no uri",
          all("uri" not in b and resolves(b["extras"]["resourceLocation"]) for b in buffers), str(buffers))
    check("frames, collider, hit module, socket, hinge exported",
          all(n in nodes for n in ("tank", "hull_body", "engine_block", "socket_seat_driver", "left_flap_mount",
                                   "left_flap")), str(sorted(nodes)))
    pivot_node = nodes["left_flap"]
    check("hinge pivot exported at identity", not any(k in pivot_node for k in ("translation", "rotation", "scale")),
          str(pivot_node))
    mount = nodes["left_flap_mount"].get("translation", [0, 0, 0])
    check("hinge frame in vehicle frame (x, z, -y)",
          max(abs(a - b) for a, b in zip(mount, (2.5, 1, -1.7))) < 1e-5, str(mount))
    seat = nodes["socket_seat_driver"].get("translation", [0, 0, 0])
    check("socket in vehicle frame", max(abs(a - b) for a, b in zip(seat, (0.5, 2, 0.5))) < 1e-5, str(seat))
    check("body frame role", nodes["tank"].get("extras") == {T.ROLE: "body"}, str(nodes["tank"].get("extras")))
    check("part frame role + interface", nodes["left_flap"].get("extras", {}).get(T.INTERFACE) == "flaps_l"
          and nodes["left_flap"]["extras"].get(T.ROLE) == "part", str(nodes["left_flap"].get("extras")))
    check("drawn node override", nodes["body"].get("extras", {}).get(T.META) == {"system": "none"},
          str(nodes["body"].get("extras")))
    check("hit module extras", nodes["engine_block"].get("extras") == {
        T.ROLE: "hit", T.HIDDEN: True, T.META: {"system": "engine", "side": "left", "health": 50.0}},
          str(nodes["engine_block"].get("extras")))
    check("collider extras", nodes["hull_body"].get("extras") == {T.ROLE: "collider", T.HIDDEN: True,
                                                                   T.META: {"main": True}},
          str(nodes["hull_body"].get("extras")))
    check("socket extras", nodes["socket_seat_driver"].get("extras") == {T.ROLE: "seat", T.INTERFACE: "driver",
                                                                          T.HIDDEN: True},
          str(nodes["socket_seat_driver"].get("extras")))
    check("hit surface extras", nodes["tyre_hit"].get("extras") == {T.ROLE: "hit_surface", T.HIDDEN: True},
          str(nodes["tyre_hit"].get("extras")))
    check("frame limits", nodes["left_flap"].get("extras", {}).get(T.META) == {"x_rot_min": -10.0, "x_rot_max": 30.0},
          str(nodes["left_flap"].get("extras")))
    clips = {a["name"]: a for a in gltf.get("animations", [])}
    check("one clip per action name", sorted(clips) == ["flaps"], str(sorted(clips)))
    flaps = clips.get("flaps", {"channels": [], "samplers": []})
    rotations = {gltf["nodes"][c["target"]["node"]]["name"]: flaps["samplers"][c["sampler"]]
                 for c in flaps["channels"] if c["target"]["path"] == "rotation"}
    check("clip turns both pivots", sorted(rotations) == ["left_flap", "right_flap"], str(sorted(rotations)))
    if "left_flap" in rotations:
        sampler = rotations["left_flap"]
        times = accessor(gltf, pack, sampler["input"])
        values = accessor(gltf, pack, sampler["output"])
        end = values[-1]
        want = (math.sin(math.radians(15)), 0, 0, math.cos(math.radians(15)))
        check("clip starts at 0, ends at its length", abs(times[0]) < 1e-6 and abs(times[-1] - 1.0) < 1e-3,
              str((times[0], times[-1])))
        check("end key = +30 deg about hinge X", max(abs(a - b) for a, b in zip(end, want)) < 1e-4, str(end))
    materials = [m["name"] for m in gltf["materials"]]
    check("armour material exported", "paint_armor_80" in materials, str(materials))

    roundtrip("ground/humvee/humvee", ["humvee"])
    roundtrip("aircraft/c5/c5", ["c5"])
    roundtrip("ground/t90/t90", ["t90"])
    validator_refuses()
    texture_setup(work)
    player_reference()
    part_model()

    print("SUMMARY %d failure(s)" % len(FAILURES))
    return 1 if FAILURES else 0


PACK = os.path.join(REPO, "src/main/resources/combinedassault_packs/combinedassault")


def fresh_scene():
    """Empty file; registered classes survive the reset."""
    bpy.ops.wm.read_factory_settings(use_empty=True)
    if not hasattr(bpy.types.Object, "combinedassault"):
        T.register()


def roundtrip(model, textures):
    """Pack model copied to a temp pack -> import -> export in place: same roles, extras, materials, textures."""
    fresh_scene()
    pack = tempfile.mkdtemp(prefix="combinedassault_rt_")
    base = os.path.join(PACK, "assets/combinedassault")
    for rel in [os.path.join("models", model + ".gltf"), os.path.join("models", model + ".bin")] + [
            os.path.join("textures", os.path.dirname(model), t + suffix + ".png") for t in textures
            for suffix in ("", "_damaged", "_wrecked", "_paint")]:
        if os.path.isfile(os.path.join(base, rel)):
            os.makedirs(os.path.dirname(os.path.join(pack, "assets/combinedassault", rel)), exist_ok=True)
            shutil.copy(os.path.join(base, rel), os.path.join(pack, "assets/combinedassault", rel))
    path = os.path.join(pack, "assets/combinedassault/models", model + ".gltf")
    with open(path, encoding="utf-8") as source:
        original = json.load(source)
    texture_dir = os.path.join(pack, "assets/combinedassault/textures", os.path.dirname(model))
    before = sorted(os.listdir(texture_dir))
    result = bpy.ops.combinedassault.import_model(filepath=path)
    check("%s imported" % model, result == {"FINISHED"}, str(result))
    scene = bpy.context.scene
    settings = scene.combinedassault_export
    check("%s export target = the imported file" % model, T.pack_paths(scene)[0] == path, T.pack_paths(scene)[0])
    bodies = [o.name for o in scene.objects if o.combinedassault.role == "BODY"]
    check("%s body role restored" % model, len(bodies) == 1, str(bodies))
    errors, warnings = T.check_scene(scene, bpy.context.evaluated_depsgraph_get())
    check("%s imported scene passes the check" % model, not errors, str(errors[:5]))
    result = bpy.ops.combinedassault.export()
    check("%s re-exported" % model, result == {"FINISHED"}, str(result))
    with open(path, encoding="utf-8") as source:
        out = json.load(source)
    want = {n["name"]: n.get("extras", {}) for n in original["nodes"]}
    got = {n["name"]: n.get("extras", {}) for n in out["nodes"]}
    lost = [k for k in want if want[k] != got.get(k)]
    check("%s every node's extras survive" % model, not lost,
          "; ".join("%s: %s -> %s" % (k, want[k], got.get(k)) for k in lost[:4]))
    images = lambda g: sorted(i.get("extras", {}).get(T.RESOURCE) for i in g.get("images", []))
    check("%s images keep their pack files" % model, images(out) == images(original),
          "%s -> %s" % (images(original), images(out)))
    check("%s imported images reference their pack files" % model,
          all(T.image_file(i) is not None for i in bpy.data.images if i.users), str([i.name for i in bpy.data.images]))
    check("%s no texture copies left" % model, sorted(os.listdir(texture_dir)) == before,
          str(sorted(set(os.listdir(texture_dir)) ^ set(before))))
    mat = lambda g: {m["name"]: (m.get("alphaMode", "OPAQUE"), m.get("alphaCutoff", 0.5) if m.get("alphaMode") == "MASK"
                                 else None, m.get("extras")) for m in g.get("materials", [])}
    check("%s material transparency + extras survive" % model, mat(out) == mat(original),
          "%s -> %s" % (mat(original), mat(out)))
    check("%s clips survive" % model, sorted(a["name"] for a in out.get("animations", []))
          == sorted(a["name"] for a in original.get("animations", [])))
    errors, _, _ = T.ca_validate.validate(path)
    check("%s validator passes the export" % model, not errors, str(errors[:5]))
    # Saved as a new model elsewhere: textures still referenced in place, no copies, no empty folder.
    settings.model_path = os.path.dirname(model) + "_copy/" + os.path.basename(model)
    settings.namespace = "copyns"
    elsewhere = os.path.join(pack, "assets/copyns/textures")
    bpy.ops.combinedassault.export()
    with open(T.pack_paths(scene)[0], encoding="utf-8") as source:
        copy = json.load(source)
    check("%s saved elsewhere keeps the pack textures" % model, images(copy) == images(original),
          "%s -> %s" % (images(original), images(copy)))
    check("%s saved elsewhere makes no texture folder" % model, not os.path.exists(elsewhere), elsewhere)
    shutil.rmtree(pack)


def validator_refuses():
    """Hand-broken copies of a good model: each caught."""
    good = os.path.join(PACK, "assets/combinedassault/models/ground/humvee/humvee.gltf")
    with open(good, encoding="utf-8") as source:
        base = json.load(source)
    errors, _, _ = T.ca_validate.validate(good)
    check("validator passes the shipped humvee", not errors, str(errors))
    folder = os.path.dirname(good)

    def broken(what, edit, expect):
        gltf = json.loads(json.dumps(base))
        edit(gltf)
        path = os.path.join(folder, "_validator_case.gltf")
        try:
            with open(path, "w", encoding="utf-8") as out:
                json.dump(gltf, out)
            errors, _, _ = T.ca_validate.validate(path)
        finally:
            os.remove(path)
        check("validator refuses " + what, any(expect in e for e in errors), str(errors[:3]))

    def uri(g):
        g["images"][0]["uri"] = "humvee.png"
        del g["images"][0]["extras"]
    broken("an image with a uri", uri, "untextured")
    broken("a second UV map", lambda g: g["materials"][0]["pbrMetallicRoughness"]["baseColorTexture"].update(texCoord=1),
           "first UV map")
    broken("a missing texture file", lambda g: g["images"][0]["extras"].update(
        resourceLocation="combinedassault:textures/ground/humvee/nope.png"), "file missing")
    body = next(i for i, n in enumerate(base["nodes"]) if n.get("extras", {}).get(T.ROLE) == "body")
    collider = next(i for i, n in enumerate(base["nodes"]) if n.get("extras", {}).get(T.ROLE) == "collider")
    broken("two bodies", lambda g: g["nodes"][collider]["extras"].update({T.ROLE: "body"}), "exactly one")
    broken("an unknown collider key", lambda g: g["nodes"][collider]["extras"].setdefault(T.META, {}).update(
        bouncy=True), "unknown key")
    broken("a moved body", lambda g: g["nodes"][body].update(translation=[0, 1, 0]), "not at the model origin")
    broken("an unknown role", lambda g: g["nodes"][collider]["extras"].update({T.ROLE: "colider"}), "unknown role")


def texture_setup(work):
    """Unconnected texture => error; Set up texture => clean, pixel-sharp, cut-out written."""
    fresh_scene()
    scene = bpy.context.scene
    body = bpy.data.objects.new("tank", None)
    scene.collection.objects.link(body)
    body.combinedassault.role = "BODY"
    hull = box("hull", (0, 0, 1), (2, 4, 1))
    hull.parent = body
    material = bpy.data.materials.new("skin")
    material.use_nodes = True
    hull.data.materials.append(material)
    path = os.path.join(work, "skin.png")
    image = bpy.data.images.new("skin", 4, 4, alpha=True)
    image.filepath_raw = path
    image.file_format = "PNG"
    image.save()
    loose = material.node_tree.nodes.new("ShaderNodeTexImage")
    loose.image = image
    errors, _ = T.check_scene(scene)
    check("unconnected texture refused", any("not connected to Base Color" in e for e in errors), str(errors))
    T.setup_texture(material, image, "MASK")
    errors, warnings = T.material_problems(material)
    check("Set up texture leaves no material problem", not errors and not warnings, str(errors + warnings))
    check("texture is pixel-sharp", T.base_image_node(material).interpolation == "Closest")
    pack = tempfile.mkdtemp(prefix="combinedassault_tex_")
    settings = scene.combinedassault_export
    settings.pack_root = pack
    settings.model_path = "test/skin/skin"
    result = bpy.ops.combinedassault.export()
    check("textured export finished", result == {"FINISHED"}, str(result))
    gltf_path = os.path.join(pack, "assets/combinedassault/models/test/skin/skin.gltf")
    with open(gltf_path, encoding="utf-8") as source:
        gltf = json.load(source)
    written = gltf["materials"][0]
    check("cut-out written as MASK 0.5", written.get("alphaMode") == "MASK" and written.get("alphaCutoff", 0.5) == 0.5,
          str(written))
    check("outside-pack texture copied into the pack", gltf["images"][0]["extras"][T.RESOURCE]
          == "combinedassault:textures/test/skin/skin.png", str(gltf["images"]))
    errors, _, _ = T.ca_validate.validate(gltf_path)
    check("validator passes the textured export", not errors, str(errors))
    shutil.rmtree(pack)


def player_reference():
    fresh_scene()
    scene = bpy.context.scene
    body = bpy.data.objects.new("tank", None)
    scene.collection.objects.link(body)
    body.combinedassault.role = "BODY"
    hull = box("hull", (0, 0, 1), (2, 4, 1))
    hull.parent = body
    bpy.ops.combinedassault.player_reference(seated=False)
    bpy.ops.combinedassault.player_reference(seated=True)
    refs = [o for o in scene.objects if o.combinedassault.reference]
    top = max(v.co.z for o in refs if o.type == "MESH" and "standing" in o.name and "hitbox" not in o.name
              for v in o.data.vertices)
    check("standing player 1.875 tall", abs(top - 1.875) < 1e-4, str(top))
    seated_feet = min(v.co.y for o in refs if o.type == "MESH" and "seated_leg" in o.name.replace("player_", "")
                      for v in o.data.vertices)
    check("seated legs reach forward (-Y = nose)", seated_feet < -0.6, str(seated_feet))
    errors, _ = T.check_scene(scene)
    check("reference never checked", not errors, str(errors))
    pack = tempfile.mkdtemp(prefix="combinedassault_ref_")
    settings = scene.combinedassault_export
    settings.pack_root = pack
    settings.model_path = "test/ref/ref"
    bpy.ops.combinedassault.export()
    with open(os.path.join(pack, "assets/combinedassault/models/test/ref/ref.gltf"), encoding="utf-8") as source:
        names = [n["name"] for n in json.load(source)["nodes"]]
    check("reference never exported", not any("player" in n for n in names), str(names))
    shutil.rmtree(pack)



def part_model():
    fresh_scene()
    scene = bpy.context.scene
    root = bpy.data.objects.new("gun", None)
    scene.collection.objects.link(root)
    box("gun_mesh", (0, 0, 0), (1, 1, 1)).parent = root
    bolt = bpy.data.objects.new("socket_bolt_0", None)
    scene.collection.objects.link(bolt)
    bolt.parent = root
    bolt.combinedassault.role = "SOCKET"
    bolt.combinedassault.field = "bolt"
    errors, _ = T.check_scene(scene)
    check("part model (no body), untargeted socket accepted", not errors, str(errors))
    root.combinedassault.role = "PITCH"
    root.combinedassault.target = "gun"
    errors, _ = T.check_scene(scene)
    check("frame in a part model refused", any("gun" in e and "part model" in e for e in errors), str(errors))


if __name__ == "__main__":
    sys.exit(run())
