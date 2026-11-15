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
    roles = re.findall(r'^\s*([A-Z_]+)\(Kind\.SOCKET\)', java("ballistics/hit/NodeRole.java"), re.M)
    fields = {r.lower() for r in roles}
    check("socket fields == NodeRole sockets", fields == set(T.SOCKET_FIELDS), str(fields ^ set(T.SOCKET_FIELDS)))
    plate = re.search(r'PLATE = Pattern.compile\("(.*?)"\)', java("ballistics/hit/ModelDataManager.java")).group(1)
    check("plate pattern == ModelDataManager.PLATE", plate.replace("\\\\", "\\") == T.PLATE.pattern, plate)
    kit_roles = re.search(r"KIT_ROLES = EnumSet\.of\((.*?)\);", java("ballistics/hit/ModelDataManager.java"), re.S).group(1)
    kit_roles = {r.lower() for r in re.findall(r"NodeRole\.([A-Z_]+)", kit_roles)}
    check("kit roles == ModelDataManager.KIT_ROLES", kit_roles == T.ca_validate.KIT_ROLES,
          str(kit_roles ^ T.ca_validate.KIT_ROLES))

    def strings(source, name):
        body = re.search(name + r"\s*=\s*Set\.of\((.*?)\);", source, re.S).group(1)
        return set(re.findall(r'"([a-z_]+)"', body))
    manager = java("ballistics/hit/ModelDataManager.java")
    V = T.ca_validate
    check("hit keys == ModelDataManager.HIT_KEYS", strings(manager, "HIT_KEYS") == V.HIT_KEYS)
    check("frame keys == ModelDataManager.FRAME_KEYS", strings(manager, "FRAME_KEYS") == V.FRAME_KEYS)
    check("collider keys == ModelRig.COLLIDER_KEYS",
          strings(java("ballistics/hit/ModelRig.java"), "COLLIDER_KEYS") == V.COLLIDER_KEYS)
    check("station keys == HardwareKits.STATION_KEYS",
          strings(java("weapon/kit/HardwareKits.java"), "STATION_KEYS") == V.STATION_KEYS)
    light = java("vehicle/light/VehicleLight.java")
    check("light keys == VehicleLight.KEYS", strings(light, "KEYS") == V.LIGHT_KEYS)
    kinds = {k.lower(): (int(c, 16), float(r), float(i), float(a.rstrip("F")), float(b))
             for k, c, r, i, a, b in re.findall(r"([A-Z]+)\(0x([0-9A-F]{6}), ([\d.]+), ([\d.]+), ([\d.F]+), ([\d.]+)\)", light)}
    check("light kinds == VehicleLight.Kind", kinds == V.LIGHT_KINDS, str(kinds))
    surfaces = {k.lower(): w for k, w in re.findall(r'([A-Z]+)\(token\("([a-z|]+)"\)\)',
                                                    java("ballistics/hit/SurfaceMaterial.java"))}
    check("surface tokens == SurfaceMaterial", surfaces == V.SURFACES, str(surfaces))


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
    front = T.armor_material(paint, "ufp")
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


def accessor(gltf, blob, index):
    """Float accessor -> list of tuples (or floats for SCALAR)."""
    import struct
    acc = gltf["accessors"][index]
    view = gltf["bufferViews"][acc["bufferView"]]
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

    gltf_path = os.path.join(pack, "assets/combinedassault/models/test/tank/tank.glb")
    gltf, binary = T.ca_validate.read_container(gltf_path)
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
    check("one embedded buffer, no uri, no extras",
          len(buffers) == 1 and "uri" not in buffers[0] and "extras" not in buffers[0] and binary is not None
          and buffers[0]["byteLength"] == len(binary), str(buffers))
    check("staging .gltf/.bin removed", not os.path.exists(gltf_path[:-4] + ".gltf")
          and not os.path.exists(gltf_path[:-4] + ".bin"))
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
        times = accessor(gltf, binary, sampler["input"])
        values = accessor(gltf, binary, sampler["output"])
        end = values[-1]
        want = (math.sin(math.radians(15)), 0, 0, math.cos(math.radians(15)))
        check("clip starts at 0, ends at its length", abs(times[0]) < 1e-6 and abs(times[-1] - 1.0) < 1e-3,
              str((times[0], times[-1])))
        check("end key = +30 deg about hinge X", max(abs(a - b) for a, b in zip(end, want)) < 1e-4, str(end))
    materials = [m["name"] for m in gltf["materials"]]
    check("plate material exported", "paint_armor:ufp" in materials, str(materials))

    roundtrip("ground/humvee/humvee")
    roundtrip("aircraft/c5/c5")
    roundtrip("ground/t90/t90")
    roundtrip("aircraft/mavic/mavic")
    roundtrip("ground/m777/m777")
    roundtrip("ground/m142/m142")
    authoring(work)
    material_tags()
    validator_refuses_new()
    validator_refuses()
    texture_setup(work)
    player_reference()
    passenger_cameras()
    merge_coplanar()
    make_hull()
    part_model()
    hardware_kit()
    validator_hand_and_kit()

    print("SUMMARY %d failure(s)" % len(FAILURES))
    return 1 if FAILURES else 0


PACK = os.path.join(REPO, "src/main/resources/combinedassault_packs/combinedassault")


def fresh_scene():
    """Empty file; registered classes survive the reset."""
    bpy.ops.wm.read_factory_settings(use_empty=True)
    if not hasattr(bpy.types.Object, "combinedassault"):
        T.register()


def roundtrip(model):
    """Pack model copied to a temp pack -> import -> export in place: same roles, extras, materials, textures."""
    fresh_scene()
    pack = tempfile.mkdtemp(prefix="combinedassault_rt_")
    base = os.path.join(PACK, "assets/combinedassault")
    images = [i["extras"][T.RESOURCE].split(":", 1)[1][:-4]
              for i in T.ca_validate.read_container(os.path.join(base, "models", model + ".glb"))[0].get("images", [])]
    os.makedirs(os.path.join(pack, "assets/combinedassault/textures", os.path.dirname(model)), exist_ok=True)
    for rel in [os.path.join("models", model + ".glb")] + [
            stem + suffix + ".png" for stem in images for suffix in ("", "_damaged", "_wrecked", "_paint")]:
        if os.path.isfile(os.path.join(base, rel)):
            os.makedirs(os.path.dirname(os.path.join(pack, "assets/combinedassault", rel)), exist_ok=True)
            shutil.copy(os.path.join(base, rel), os.path.join(pack, "assets/combinedassault", rel))
    path = os.path.join(pack, "assets/combinedassault/models", model + ".glb")
    original, _ = T.ca_validate.read_container(path)
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
    out, _ = T.ca_validate.read_container(path)
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
    copy, _ = T.ca_validate.read_container(T.pack_paths(scene)[0])
    check("%s saved elsewhere keeps the pack textures" % model, images(copy) == images(original),
          "%s -> %s" % (images(original), images(copy)))
    check("%s saved elsewhere makes no texture folder" % model, not os.path.exists(elsewhere), elsewhere)
    shutil.rmtree(pack)


def validator_refuses():
    """Hand-broken copies of a good model: each caught."""
    good = os.path.join(PACK, "assets/combinedassault/models/ground/humvee/humvee.glb")
    base, binary = T.ca_validate.read_container(good)
    errors, _, _ = T.ca_validate.validate(good)
    check("validator passes the shipped humvee", not errors, str(errors))
    folder = os.path.dirname(good)

    def broken(what, edit, expect):
        gltf = json.loads(json.dumps(base))
        edit(gltf)
        path = os.path.join(folder, "_validator_case.glb")
        try:
            T.ca_validate.write_glb(path, gltf, binary)
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
    gltf_path = os.path.join(pack, "assets/combinedassault/models/test/skin/skin.glb")
    gltf, _ = T.ca_validate.read_container(gltf_path)
    written = gltf["materials"][0]
    check("cut-out written as MASK 0.5", written.get("alphaMode") == "MASK" and written.get("alphaCutoff", 0.5) == 0.5,
          str(written))
    check("outside-pack texture copied into the pack", gltf["images"][0]["extras"][T.RESOURCE]
          == "combinedassault:textures/test/skin/skin.png", str(gltf["images"]))
    errors, _, _ = T.ca_validate.validate(gltf_path)
    check("validator passes the textured export", not errors, str(errors))

    mask = bpy.data.images.new("skin_paint", 4, 4, alpha=True)
    mask.pixels = [0.5, 0.25, 0.0, 1.0] * 8 + [0.0, 0.0, 0.0, 0.0] * 8
    mask.filepath_raw = os.path.join(work, "skin_paint.png")
    mask.file_format = "PNG"
    mask.save()
    mean = T.mask_mean(bpy.data.images.load(mask.filepath_raw, check_existing=False))
    check("mask mean is coverage weighted", all(abs(a - b) < 0.01 for a, b in zip(mean, (0.5, 0.25, 0.0))), str(mean))
    paints = os.path.join(pack, "assets/combinedassault/paints")
    os.makedirs(paints)
    with open(os.path.join(paints, "red.json"), "w", encoding="utf-8") as out:
        json.dump({"color": "#804000"}, out)
    check("paint specs listed", list(T.paint_specs(scene)) == ["red"], str(T.paint_specs(scene)))
    before = [n.name for n in material.node_tree.nodes]
    check("paint preview applied", T.set_paint(scene, "red") == 1)
    check("Base Color fed by the preview", T.principled(material).inputs["Base Color"].links[0].from_node.name
          .startswith(T.PAINT_PREFIX))
    check("base image still found through the preview", T.base_image_node(material).image.name == "skin")
    errors, warnings = T.material_problems(material)
    check("preview adds no material problem", not errors and not warnings, str(errors + warnings))
    settings.paint_preview = "NONE"
    check("preview removed", [n.name for n in material.node_tree.nodes] == before
          and T.base_image_node(material).outputs["Color"].links[0].to_node.type == "BSDF_PRINCIPLED")
    settings.paint_preview = "red"
    result = bpy.ops.combinedassault.export()
    check("export with preview on finishes, preview restored", result == {"FINISHED"} and any(
        n.name.startswith(T.PAINT_PREFIX) for n in material.node_tree.nodes))
    errors, _, _ = T.ca_validate.validate(gltf_path)
    check("validator passes export made with preview", not errors, str(errors))
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
    names = [n["name"] for n in T.ca_validate.read_container(os.path.join(pack, "assets/combinedassault/models/test/ref/ref.glb"))[0]["nodes"]]
    check("reference never exported", not any("player" in n for n in names), str(names))
    shutil.rmtree(pack)



def passenger_cameras():
    fresh_scene()
    scene = bpy.context.scene
    body = bpy.data.objects.new("tank", None)
    scene.collection.objects.link(body)
    body.combinedassault.role = "BODY"
    box("hull", (0, 0, 1), (2, 4, 1)).parent = body
    for name, field, target, location, yaw in (("socket_seat_driver", "seat", "driver", (0.5, -1, 1.6), 0.0),
                                               ("socket_seat_rear", "seat", "rear", (-0.5, 1, 1.6), math.pi),
                                               ("socket_camera", "camera", "", (0, 4, 3), 0.0),
                                               ("socket_hitch", "towable", "", (0, 0, 2), 0.0)):
        socket = bpy.data.objects.new(name, None)
        scene.collection.objects.link(socket)
        socket.parent = body
        socket.location = location
        socket.rotation_euler = (0, 0, yaw)
        socket.combinedassault.role = "SOCKET"
        socket.combinedassault.field = field
        socket.combinedassault.target = target
    bpy.ops.combinedassault.view_cameras()
    cameras = T.view_cameras(scene)
    check("one camera per seat / camera socket", len(cameras) == 3, str([c.name for c in cameras]))
    bpy.context.view_layer.update()
    by = {c.parent.name: c for c in cameras}
    for name, expected in (("socket_seat_driver", (0, -1, 0)), ("socket_seat_rear", (0, 1, 0))):
        camera = by[name]
        look = camera.matrix_world.to_3x3() @ Vector((0, 0, -1))
        up = camera.matrix_world.to_3x3() @ Vector((0, 1, 0))
        check("%s looks along nose at its yaw" % name, (look - Vector(expected)).length < 1e-5, str(look))
        check("%s is level" % name, abs(up.z - 1) < 1e-5, str(up))
        eye = camera.matrix_world.translation
        check("%s at the socket" % name, (eye - camera.parent.matrix_world.translation).length < 1e-6, str(eye))
    check("camera fov 70 vertical", abs(math.degrees(cameras[0].data.angle_y) - 70) < 1e-3, str(cameras[0].data.angle_y))
    bpy.ops.combinedassault.look_through(step=1)
    check("look through next sets scene camera", scene.camera in cameras, str(scene.camera))
    bpy.ops.combinedassault.view_cameras()
    check("rebuild keeps one camera per socket", len(T.view_cameras(scene)) == 3)
    errors, _ = T.check_scene(scene)
    check("cameras never checked", not errors, str(errors))
    pack = tempfile.mkdtemp(prefix="combinedassault_cam_")
    settings = scene.combinedassault_export
    settings.pack_root = pack
    settings.model_path = "test/cam/cam"
    bpy.ops.combinedassault.export()
    written, _ = T.ca_validate.read_container(os.path.join(pack, "assets/combinedassault/models/test/cam/cam.glb"))
    check("cameras never exported", "cameras" not in written and not any(n["name"].startswith("view_") for n in written["nodes"]))
    shutil.rmtree(pack)


def merge_coplanar():
    fresh_scene()
    scene = bpy.context.scene
    cube = box("slab", (0, 0, 0), (2, 2, 1))
    cube.combinedassault.role = "COLLIDER"
    mesh = bmesh.new()
    mesh.from_mesh(cube.data)
    bmesh.ops.triangulate(mesh, faces=mesh.faces)
    mesh.to_mesh(cube.data)
    mesh.free()
    points, faces = T.faces_of(cube)
    check("triangulated box has coplanar neighbours", T.coplanar_neighbours(points, faces) is not None)
    select(cube)
    bpy.ops.combinedassault.merge_coplanar()
    points, faces = T.faces_of(cube)
    check("merge restores 6 quads", len(faces) == 6 and T.coplanar_neighbours(points, faces) is None, str(len(faces)))
    check("merged collider still valid", not T.check_collider(cube), str(T.check_collider(cube)))


def make_hull():
    fresh_scene()
    small = box("bevelled", (0, 0, 0), (0.2, 0.2, 0.2))
    bpy.ops.object.modifier_add(type="BEVEL")
    small.modifiers[-1].width = 0.005
    small.modifiers[-1].segments = 3
    bpy.ops.mesh.primitive_grid_add(x_subdivisions=40, y_subdivisions=40, size=4, location=(5, 0, 0))
    curved = bpy.context.active_object
    for v in curved.data.vertices:
        v.co.z = 0.002 * math.cos(v.co.x) * math.cos(v.co.y)
    bpy.ops.object.modifier_add(type="SOLIDIFY")
    curved.modifiers[-1].thickness = 0.3
    for source in (small, curved):
        select(source)
        bpy.ops.combinedassault.make_hull()
        hull = bpy.context.active_object
        problems = T.check_collider(hull)
        check("hull of %s valid for ConvexHull.of" % source.name,
              hull.combinedassault.role == "COLLIDER" and not problems, str(problems[:2]))


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


def authoring(work):
    """Rotor unit / spin, follows_clips, light overrides, hit cylinder, screen, mesh sockets: exported keys,
    validator clean, light preview."""
    fresh_scene()
    scene = bpy.context.scene
    root = bpy.data.objects.new("heli", None)
    scene.collection.objects.link(root)
    root.combinedassault.role = "BODY"
    hull = box("hull", (0, 0, 1), (2, 4, 1))
    hull.parent = root
    collider = box("hull_box", (0, 0, 1), (2, 4, 1))
    collider.parent = root
    collider.combinedassault.role = "COLLIDER"
    rotor = box("rotor_fl", (1, -1, 2), (1.5, 1.5, 0.05))
    rotor.parent = root
    rotor.combinedassault.meta_system = "rotor"
    rotor.combinedassault.meta_unit = "FL"
    _, warnings = T.check_scene(scene)
    check("rotor unit without spin warned", any("rotor_fl" in w and "spin" in w for w in warnings), str(warnings))
    rotor.combinedassault.meta_spin = "CW"
    _, warnings = T.check_scene(scene)
    check("rotor unit with spin not warned", not any("spin" in w for w in warnings), str(warnings))
    trail = bpy.data.objects.new("trail", None)
    scene.collection.objects.link(trail)
    trail.parent = root
    trail.combinedassault.role = "PART"
    trail.combinedassault.target = "trail"
    trail.combinedassault.follows_clips = True
    wheel = box("wheel", (1, 1.5, 0.4), (0.3, 0.8, 0.8))
    wheel.parent = root
    select(wheel)
    result = bpy.ops.combinedassault.hit_cylinder(sides=8, surface="rubber")
    check("hit cylinder made", result == {"FINISHED"}, str(result))
    hit = scene.objects.get("hit_wheel")
    check("hit cylinder is a hit surface child of its node", hit is not None and hit.parent == wheel
          and hit.combinedassault.role == "HIT_SURFACE")
    points = [hit.matrix_world @ v.co for v in hit.data.vertices]
    radius = max(math.hypot(p.y - 1.5, p.z - 0.4) for p in points)
    check("hit cylinder round the thinnest axis (X), circumscribed",
          max(abs(p.x - 1) for p in points) < 0.15 + 1e-5 and len(hit.data.polygons) == 10
          and radius >= math.hypot(0.4, 0.4) - 1e-5, "r %.4f" % radius)
    scene.cursor.location = (0, -1.5, 1.6)
    select(hull)
    bpy.ops.combinedassault.add_socket(field="light", index=-1)
    lamp = scene.objects["socket_light"]
    lamp.combinedassault.light_kind = "SEARCH"
    lamp.combinedassault.light_use_color = True
    lamp.combinedassault.light_color = (1.0, 0.5, 0.0)
    lamp.combinedassault.light_outer = 8.0
    lamp.combinedassault.light_shadow = False
    lamp.combinedassault.light_inner = 9.0
    errors, _ = T.check_scene(scene)
    check("light inner > outer refused", any("socket_light" in e and "inner" in e for e in errors), str(errors))
    lamp.combinedassault.light_inner = 0.0
    scene.cursor.location = (0, 1.5, 1)
    bpy.ops.combinedassault.add_socket(field="era", target="front_era", index=-1)
    era = scene.objects["socket_era_front_era"]
    check("era socket is a quad mesh", era.type == "MESH" and len(era.data.polygons) == 1)
    scene.cursor.location = (0.3, 0.5, 1.5)
    select(hull)
    bpy.ops.combinedassault.add_screen(screen="dash", width=0.2, height=0.1)
    screen = scene.objects["screen_dash"]
    check("screen quad has UVs and a slot material", screen.data.uv_layers
          and screen.data.materials[0].combinedassault.texture_slot == "screen_dash")
    errors, warnings = T.check_scene(scene)
    check("authoring scene passes the check", not errors, str(errors))
    lights = T.preview_lights(scene)
    bpy.context.view_layer.update()
    check("one preview spot per light socket", len(lights) == 1 and lights[0].parent == lamp)
    beam = lights[0].matrix_world.to_3x3() @ Vector((0, 0, -1))
    check("preview beam along the socket's -Y (nose)", (beam - Vector((0, -1, 0))).length < 1e-5, str(beam))
    check("preview cone = 2 x outer", abs(math.degrees(lights[0].data.spot_size) - 16) < 1e-3)

    pack = os.path.join(work, "authoring_pack")
    os.makedirs(pack, exist_ok=True)
    settings = scene.combinedassault_export
    settings.pack_root = pack + os.sep
    settings.namespace = "test"
    settings.model_path = "aircraft/heli/heli"
    result = bpy.ops.combinedassault.export()
    check("authoring export finished", result == {"FINISHED"}, str(result))
    path = T.pack_paths(scene)[0]
    gltf, _ = T.ca_validate.read_container(path)
    nodes = {n["name"]: n for n in gltf["nodes"]}
    meta = lambda name: nodes[name].get("extras", {}).get(T.META)
    check("rotor unit + spin exported", meta("rotor_fl") == {"system": "rotor", "unit": "FL", "spin": "cw"},
          str(meta("rotor_fl")))
    check("follows_clips exported", meta("trail") == {"follows_clips": True}, str(meta("trail")))
    check("light overrides exported", meta("socket_light") == {"kind": "search", "color": "#ff8000", "outer": 8.0,
                                                               "shadow": False}, str(meta("socket_light")))
    check("hit cylinder exported with genHulls", nodes["hit_wheel"].get("extras") == {
        T.ROLE: "hit_surface", T.HIDDEN: True, "genHulls": {"node": "wheel", "hit": True}},
        str(nodes["hit_wheel"].get("extras")))
    materials = {m["name"]: m for m in gltf["materials"]}
    check("hit cylinder material exported", "rubber" in materials, str(list(materials)))
    check("screen slot exported", materials.get("screen_dash", {}).get("extras") == {"textureSlot": "screen_dash"},
          str(materials.get("screen_dash")))
    check("preview lights never exported", not any(n.startswith(T.LIGHT_PREFIX) for n in nodes), str(list(nodes)))
    errors, _, _ = T.ca_validate.validate(path)
    check("validator passes the authoring export", not errors, str(errors))


def material_tags():
    """Armour / hit effect / decor fields read and rename the material name."""
    fresh_scene()
    material = bpy.data.materials.new("tyre")
    settings = material.combinedassault
    settings.surface = "RUBBER"
    check("surface renames", material.name == "tyre_rubber", material.name)
    settings.armor_plate = "side_1"
    check("plate renames, keeps surface", material.name == "tyre_rubber_armor:side_1", material.name)
    check("fields read the name", (settings.armor_plate, settings.surface, settings.decor) == ("side_1", "RUBBER", False))
    material.name = "tyre_rubber_armor:side_1.001"
    check("Blender duplicate suffix ignored", settings.armor_plate == "side_1", settings.armor_plate)
    material.name = "tyre_armor_20"
    check("legacy armor_<mm> reads no plate", settings.armor_plate == "", settings.armor_plate)
    check("legacy armor_<mm> flagged", any("no longer read" in w for w in T.material_problems(material)[1]),
          str(T.material_problems(material)))
    settings.armor_plate = "rear"
    check("retag drops the legacy token", material.name == "tyre_armor:rear", material.name)
    settings.armor_plate = ""
    check("empty plate unbinds", material.name == "tyre", material.name)
    settings.surface = "RUBBER"
    settings.decor = True
    check("decor drops plate and effect", material.name == "tyre_decor", material.name)
    settings.surface = "GLASS"
    check("effect clears decor", material.name == "tyre_glass", material.name)
    material.name = "visor_fabric"
    check("name typed by hand is read", settings.surface == "SOFT", settings.surface)
    copy = T.armor_material(material, "visor")
    check("plate copy keeps the base's effect", copy.name == "visor_soft_armor:visor", copy.name)


def validator_refuses_new():
    good = os.path.join(PACK, "assets/combinedassault/models/ground/humvee/humvee.glb")
    base, binary = T.ca_validate.read_container(good)
    folder = os.path.dirname(good)

    def broken(what, edit, expect):
        gltf = json.loads(json.dumps(base))
        edit(gltf)
        path = os.path.join(folder, "_validator_case.glb")
        try:
            T.ca_validate.write_glb(path, gltf, binary)
            errors, _, _ = T.ca_validate.validate(path)
        finally:
            os.remove(path)
        check("validator refuses " + what, any(expect in e for e in errors), str(errors[:3]))

    light = next(i for i, n in enumerate(base["nodes"]) if n.get("extras", {}).get(T.ROLE) == "light")
    plain = next(i for i, n in enumerate(base["nodes"]) if "mesh" in n and not n.get("extras", {}).get(T.ROLE))
    pitch = next((i for i, n in enumerate(base["nodes"]) if n.get("extras", {}).get(T.ROLE) == "part"), None)
    broken("a light colour that is not #rrggbb", lambda g: g["nodes"][light]["extras"][T.META].update(color="red"),
           "#rrggbb")
    broken("a light inner past outer", lambda g: g["nodes"][light]["extras"][T.META].update(inner=40, outer=30),
           "inner <= outer")
    broken("an unknown light key", lambda g: g["nodes"][light]["extras"][T.META].update(beam=1), "unknown key")
    broken("an unknown node key", lambda g: g["nodes"][plain].setdefault("extras", {}).setdefault(T.META, {}).update(
        armour=5), "unknown key")
    broken("a bad spin", lambda g: g["nodes"][plain].setdefault("extras", {}).setdefault(T.META, {}).update(
        spin="left"), "spin")
    broken("a bad system", lambda g: g["nodes"][plain].setdefault("extras", {}).setdefault(T.META, {}).update(
        system="engines"), "system")

    def yaw_on_pitch(g):
        g["nodes"][pitch]["extras"].update({T.ROLE: "pitch"})
        g["nodes"][pitch]["extras"].setdefault(T.META, {}).update(y_rot_min=-10)
    broken("yaw limits on a pitch frame", yaw_on_pitch, "yaw limits")


SCRATCH = os.path.join(REPO, "build", "test_combinedassault_kit")


def copy_into(pack, model):
    """Pack model `ns:models/...` + its textures (and _damaged / _wrecked / _paint siblings) copied into `pack`."""
    source = T.ca_validate.resolve(PACK, model)
    files = [source] + [T.ca_validate.resolve(PACK, i["extras"][T.RESOURCE][:-4] + suffix + ".png")
                        for i in T.ca_validate.read_container(source)[0].get("images", [])
                        for suffix in ("", "_damaged", "_wrecked", "_paint")]
    for path in files:
        if os.path.isfile(path):
            target = os.path.join(pack, os.path.relpath(path, PACK))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copy(path, target)


def hardware_kit():
    """m2 kit copied under build/: open, hand contact, kit role refusal, humvee preview, edit, export, compare."""
    shutil.rmtree(SCRATCH, ignore_errors=True)
    pack = os.path.join(SCRATCH, "pack")
    defs = os.path.join(pack, "combinedassault/combinedassault")
    os.makedirs(os.path.join(defs, "hardware"))
    os.makedirs(os.path.join(defs, "vehicles"))
    kit_json = os.path.join(defs, "hardware/m2.json")
    shutil.copy(os.path.join(PACK, "combinedassault/combinedassault/hardware/m2.json"), kit_json)
    shutil.copy(os.path.join(PACK, "combinedassault/combinedassault/vehicles/humvee.json"), os.path.join(defs, "vehicles"))
    with open(kit_json, encoding="utf-8") as source:
        original_text = source.read()
    humvee = json.loads(open(os.path.join(defs, "vehicles/humvee.json"), encoding="utf-8").read())
    for model in [json.loads(original_text)["model"], humvee["model"]] + [
            m["model"] for p in humvee["parts"] for m in p.get("mounts", [])]:
        copy_into(pack, model)
    glb = T.ca_validate.resolve(pack, json.loads(original_text)["model"])
    original, _ = T.ca_validate.read_container(glb)

    fresh_scene()
    scene = bpy.context.scene
    settings = scene.combinedassault_export
    result = bpy.ops.combinedassault.open_kit(filepath=kit_json)
    check("kit opened", result == {"FINISHED"}, str(result))
    check("kit fields read", (settings.kit_weapons, settings.kit_fits, settings.kit_install_ticks, settings.kit_item)
          == ("combinedassault:gun_12.7mm", "pintle, tripod, door", 120, ""), str(
        (settings.kit_weapons, settings.kit_fits, settings.kit_install_ticks)))
    check("kit export target = its model", T.pack_paths(scene)[0] == glb, T.pack_paths(scene)[0])
    errors, _ = T.check_scene(scene)
    check("opened kit passes the check", not errors, str(errors))

    gun = scene.objects["m2"]
    collider = box("kit_collider", (0, 0, 0), (0.1, 0.1, 0.1))
    collider.parent = gun
    collider.combinedassault.role = "COLLIDER"
    errors, _ = T.check_scene(scene)
    check("collider refused in a kit", any("kit_collider" in e and "kit model" in e for e in errors), str(errors))
    bpy.data.objects.remove(collider, do_unlink=True)

    select(gun)
    scene.cursor.location = (0.1, -0.3, 0.05)
    result = bpy.ops.combinedassault.add_hand(hand="LEFT", action="fire")
    hand = bpy.context.active_object
    check("hand contact added under the active object", result == {"FINISHED"} and hand.parent == gun
          and (hand.matrix_world.translation - Vector((0.1, -0.3, 0.05))).length < 1e-6, hand.name)
    errors, _ = T.check_scene(scene)
    check("kit with hand passes the check", not errors, str(errors))
    select(gun)
    result = bpy.ops.combinedassault.add_foot(hand="LEFT", action="brake")
    foot = bpy.context.active_object
    check("foot contact added", result == {"FINISHED"} and foot.parent == gun and foot.name == "foot_left_brake"
          and foot.combinedassault.field == "foot" and foot.combinedassault.foot_side == "LEFT", foot.name)
    bpy.data.objects.remove(foot)

    _, points = T.kit_hardpoints(settings)
    index = next(i for i, p in enumerate(points) if p["vehicle"] == "combinedassault:humvee")
    settings.kit_hardpoint = str(index)
    result = bpy.ops.combinedassault.preview_kit(clear=False)
    check("vehicle preview built", result == {"FINISHED"} and T.KIT_PREVIEW in scene.objects, str(result))
    bpy.context.view_layer.update()
    node = next((o for o in scene.objects if o.combinedassault.reference and o.name.startswith("roof_mount_pitch")), None)
    check("hardpoint node at the kit root", node is not None and node.matrix_world.translation.length < 1e-4,
          str(node and node.matrix_world.translation))
    turret = next((o for o in scene.objects if o.combinedassault.reference and o.name.startswith("humvee_turret")), None)
    check("part mounts grafted into the preview", turret is not None and turret.parent is not None, str(turret))
    result = bpy.ops.combinedassault.arm_reach()
    reach = [o for o in scene.objects if o.name.startswith(T.REACH_PREFIX)]
    check("arm reach sphere per seat", result == {"FINISHED"} and reach
          and all(abs(o.empty_display_size - T.ARM_REACH) < 1e-6 for o in reach), str([o.name for o in reach]))
    errors, _ = T.check_scene(scene)
    check("preview never checked", not errors, str(errors[:3]))

    settings.kit_install_ticks = 90
    result = bpy.ops.combinedassault.export()
    check("kit exported", result == {"FINISHED"}, str(result))
    with open(kit_json, encoding="utf-8") as source:
        written = source.read()
    check("kit json edited in place", written == original_text.replace('"install_ticks": 120', '"install_ticks": 90'),
          written)
    out, _ = T.ca_validate.read_container(glb)
    names = {n["name"]: n for n in out["nodes"]}
    check("kit nodes kept", {n["name"] for n in original["nodes"]} <= set(names),
          str({n["name"] for n in original["nodes"]} - set(names)))
    check("preview never exported", not any(n.startswith(("roof_mount", "humvee", T.REACH_PREFIX, T.KIT_PREVIEW))
                                            for n in names), str(sorted(names)))
    check("hand extras exported", names.get(hand.name, {}).get("extras") == {
        T.ROLE: "hand", T.HIDDEN: True, T.META: {"hand": "left", "action": "fire"}},
          str(names.get(hand.name, {}).get("extras")))
    errors, _, _ = T.ca_validate.validate(glb, True)
    check("validator (kit) passes the export", not errors, str(errors))

    settings.kit_station = '{"zoom_max": 2, "laser": true}'
    check("unknown station key refused", any("laser" in p for p in T.kit_values(settings)[1]),
          str(T.kit_values(settings)[1]))
    settings.kit_station = "{oops"
    check("station not JSON refused", any("not JSON" in p for p in T.kit_values(settings)[1]))

    spg9 = open(os.path.join(PACK, "combinedassault/combinedassault/hardware/spg9.json"), encoding="utf-8").read()
    kit = json.loads(spg9)
    same = T.ca_validate.edit_kit_text(spg9, {"station": kit["station"], "fits": kit["fits"], "item": ""})
    check("unchanged kit fields keep their bytes (multi-line station)", same == spg9)
    added = T.ca_validate.edit_kit_text(spg9, {"replaces": "combinedassault:old"})
    at = spg9.rindex("}", 0, spg9.rindex("}")) + 1
    check("new kit key appended after the last, same separator",
          added == spg9[:at] + ',\n  "replaces": "combinedassault:old"' + spg9[at:], added)
    shutil.rmtree(SCRATCH)


def validator_hand_and_kit():
    good = os.path.join(PACK, "assets/combinedassault/models/weapon/m2/m2.glb")
    base, binary = T.ca_validate.read_container(good)
    os.makedirs(SCRATCH, exist_ok=True)

    def run(edit, kit):
        gltf = json.loads(json.dumps(base))
        edit(gltf)
        path = os.path.join(SCRATCH, "case.glb")
        T.ca_validate.write_glb(path, gltf, binary)
        return T.ca_validate.validate(path, kit)[0]

    def hand(meta):
        def edit(g):
            g["nodes"].append({"name": "hand_r", "extras": {T.ROLE: "hand", T.HIDDEN: True, T.META: meta}})
            g["nodes"][0]["children"].append(len(g["nodes"]) - 1)
        return edit
    check("validator passes a right hand", not run(hand({"hand": "right", "action": "shift"}), True))
    errors = run(hand({}), True)
    check("validator refuses a hand without `hand`", any("hand_r" in e and "right / left" in e for e in errors), str(errors))
    errors = run(hand({"hand": "middle"}), True)
    check("validator refuses hand 'middle'", any("middle" in e for e in errors), str(errors))
    errors = run(hand({"hand": "left", "action": 3}), True)
    check("validator refuses a non-string action", any("action" in e for e in errors), str(errors))
    errors = run(hand({"hand": "left", "action": "brake"}), True)
    check("validator refuses hand action brake", any("brake" in e for e in errors), str(errors))

    def foot(meta):
        def edit(g):
            g["nodes"].append({"name": "foot_r", "extras": {T.ROLE: "foot", T.HIDDEN: True, T.META: meta}})
            g["nodes"][0]["children"].append(len(g["nodes"]) - 1)
        return edit
    check("plain validator passes a foot", not run(foot({"foot": "right", "action": "brake"}), False))
    errors = run(foot({}), False)
    check("validator refuses a foot without `foot`", any("foot_r" in e and "right / left" in e for e in errors), str(errors))
    errors = run(foot({"foot": "middle"}), False)
    check("validator refuses foot 'middle'", any("middle" in e for e in errors), str(errors))
    errors = run(foot({"foot": "left", "action": 3}), False)
    check("validator refuses a non-string foot action", any("action" in e for e in errors), str(errors))
    errors = run(foot({"foot": "left", "action": "shift"}), False)
    check("validator refuses foot action shift", any("shift" in e for e in errors), str(errors))
    check("kit validator refuses a foot", any("foot_r" in e and "kit" in e for e in run(foot({"foot": "left"}), True)))

    def light(g):
        g["nodes"].append({"name": "lamp", "extras": {T.ROLE: "light", T.HIDDEN: True}})
        g["nodes"][0]["children"].append(len(g["nodes"]) - 1)
    check("kit validator refuses a light", any("lamp" in e and "kit" in e for e in run(light, True)))
    check("plain validator accepts the light", not run(light, False))

    def belts(*indices):
        def edit(g):
            for i in indices:
                g["nodes"].append({"name": "belt_%s" % i, "extras": {T.ROLE: "belt", T.HIDDEN: True, T.META: {"index": i}}})
                g["nodes"][0]["children"].append(len(g["nodes"]) - 1)
        return edit
    check("kit validator passes belt 0 + 1", not run(belts(0, 1), True))
    for case in ((0,), (0, 0), (0, 2)):
        errors = run(belts(*case), True)
        check("validator refuses belt indices %s" % (case,), any("belt sockets" in e for e in errors), str(errors))
    gltf = json.loads(json.dumps(base))
    belts(0, 1)(gltf)
    path = os.path.join(SCRATCH, "belt.glb")
    T.ca_validate.write_glb(path, gltf, binary)
    _, warnings, _ = T.ca_validate.validate(path, False)
    check("plain validator warns belt sockets outside a kit", any("belt" in w for w in warnings), str(warnings))
    link = {"link": "ma_lmgs:models/link.glb", "pitch": 0.012}
    check("kit belt + belt sockets agree", not T.ca_validate.kit_belt_problems({"belt": link}, path))
    check("belt sockets without kit belt flagged", T.ca_validate.kit_belt_problems({}, path))
    check("kit belt without belt sockets flagged", T.ca_validate.kit_belt_problems({"belt": link}, good))
    check("kit belt pitch 0 refused", any("belt" in p for p in T.ca_validate.kit_problems(
        {"model": "a:b", "weapons": ["a:b"], "fits": ["door"], "belt": dict(link, pitch=0)})))
    text = '{\n  "model": "a:b",\n  "belt": {"link": "a:l", "pitch": 0.01}\n}\n'
    check("empty kit belt removes the key", "belt" not in json.loads(T.ca_validate.edit_kit_text(text, {"belt": None})))
    for name in ("ma_pkm", "ma_m240b", "ma_m249"):
        ma = os.path.join(PACK, "assets/combinedassault/models/weapon/ma/%s.glb" % name)
        with open(os.path.join(PACK, "combinedassault/combinedassault/hardware/%s.json" % name), encoding="utf-8") as f:
            kit = json.load(f)
        errors = T.ca_validate.validate(ma, True)[0] + T.ca_validate.kit_problems(kit) + T.ca_validate.kit_belt_problems(kit, ma)
        check("shipped %s kit + model pass" % name, not errors, str(errors))
    shutil.rmtree(SCRATCH)


if __name__ == "__main__":
    sys.exit(run())
