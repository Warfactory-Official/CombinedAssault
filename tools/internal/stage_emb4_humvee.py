#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage the EMB4 (McHeliCE addon) Humvee: glTF display + structure, and its Mk 19 as a separate mount model.

    python3 tools/internal/stage_emb4_humvee.py      (from combinedassault/)
    python3 tools/internal/stage_emb4_humvee.py m2   M2 mount from humveewithweapon (same pivots); role layout, shipped pack

MCHeli frame == vehicle frame (+X port, +Z forward); pivots from humvee_mk19.yml. Ours = (p + (0, GROUND, 0)) * SCALE.
Roof: humvee = open ring only ($body1 roof plug dropped). humvee_mk19 part MOUNT_PART carries two mounts: shield +
gunner markers (yaw node roof_mount), Mk 19 (MOUNT_BONE, pitch node). socket_* in a mount target the carrying part.
"""
import hashlib
import json
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mqo  # noqa: E402

SRC = os.path.expanduser("~/projects/mcheli-gigarework/McHeliCE/run/mcheli_addons/everything/assets/mcheli")
SHA1 = "4e99a5570061f37e0538d8b72cf34fb7aecd79c9"
M2_SHA1 = "dbcdd2c1a332ea2cec4dbcd7c3ae930735d2e3f5"
PACK = "src/main/resources/default_vehicle"
SHIPPED = "src/main/resources/combinedassault_packs/combinedassault"
NS = "combinedassault"
# length 5.17 -> 4.65 (M1151 4.93), height 1.84; wheel bottoms at MCHeli y -0.34
SCALE, GROUND = 0.9, 0.34
YAW_PIVOT, PITCH_PIVOT = (0.0, 1.01, -0.018), (0.0, 2.273, 0.869)
MUZZLE = (0.0, 2.388, 1.787)
M2_MUZZLE = (0.0, 2.316, 2.21)
MOUNT_BONE = "roof_mount_pitch"
MOUNT_PART = "roof_mount"
# node: (parts, hinge pivot, axis); doors swing about +Y at their front edge, hatches about +X
HINGED = {
    "door_fl": (["$canopy0", "$canopy5"], (1.19, 0.93, 1.028)),
    "door_fr": (["$canopy4", "$canopy8"], (-1.19, 0.93, 1.028)),
    "door_rl": (["$canopy2", "$canopy6"], (1.19, 0.93, 0.008)),
    "door_rr": (["$canopy3", "$canopy7"], (-1.19, 0.93, 0.008)),
    "rear_hatch": (["$hatch0"], (0.0, 1.642, -1.024)),
    "tailgate": (["$hatch1"], (0.0, 0.6, -2.407)),
}
# yml Rotation * MaxRotation: canopy MaxRotation is in quarter turns
SWING = {"door_fl": ("y", -60.0), "door_fr": ("y", 60.0), "door_rl": ("y", -60.0), "door_rr": ("y", 60.0),
         "rear_hatch": ("x", 60.0), "tailgate": ("x", -92.4)}
WHEELS = {"wheel_fl": ("$wheel1", (1.0, 0.168, 2.066)), "wheel_fr": ("$wheel0", (-1.0, 0.168, 2.066)),
          "wheel_rl": ("$wheel3", (1.0, 0.168, -1.626)), "wheel_rr": ("$wheel2", (-1.0, 0.168, -1.626))}
STEERING = ("$steering_wheel0", (0.754, 1.062, 0.682), (0.0, 1.0, -2.145))
BODY = ["$body", "$throttle0", "$canopy9"]
# $weapon0 objects: ring + open lid stay on the hull's roof_mount node; the rest is the gun shield mount.
RING = {"obj58", "Escotilla1", "obj103", "Escotilla2", "obj59"}


def T(p):
    return (np.asarray(p, dtype=float) + [0.0, GROUND, 0.0]) * SCALE


def frame_y(axis):
    """Rotation whose +Y is `axis`."""
    y = np.asarray(axis, float) / np.linalg.norm(axis)
    x = np.cross(y, [0.0, 0.0, 1.0])
    x /= np.linalg.norm(x)
    return np.column_stack([x, y, np.cross(x, y)])


def quaternion(m):
    w = np.sqrt(max(0.0, 1 + np.trace(m))) / 2
    x = np.copysign(np.sqrt(max(0.0, 1 + m[0, 0] - m[1, 1] - m[2, 2])) / 2, m[2, 1] - m[1, 2])
    y = np.copysign(np.sqrt(max(0.0, 1 - m[0, 0] + m[1, 1] - m[2, 2])) / 2, m[0, 2] - m[2, 0])
    z = np.copysign(np.sqrt(max(0.0, 1 - m[0, 0] - m[1, 1] + m[2, 2])) / 2, m[1, 0] - m[0, 1])
    return [round(float(q), 6) for q in (x, y, z, w)]


class Source:
    def __init__(self, name="humvee_mk19", sha1=SHA1):
        path = os.path.join(SRC, "models", "tanks", name + ".mqo")
        digest = hashlib.sha1(open(path, "rb").read()).hexdigest()
        if digest != sha1:
            raise SystemExit(f"{path}: sha1 {digest} != staged McHeliCE {name}")
        self.parts = mqo.parts(mqo.read(path))

    def objs(self, names, keep=lambda o: True):
        return [o for n in names for o in self.parts[n] if len(o.verts) and keep(o)]

    def bounds(self, names):
        pts = T(np.vstack([o.verts for o in self.objs(names)]))
        return pts.min(axis=0), pts.max(axis=0)


def mesh_of(w, name, objs, pivot, frame=np.eye(3)):
    pos, nrm, uv = [], [], []
    for o in objs:
        if o.tris:
            p, n, u = mqo.triangles(o, T)
            pos.append((p - pivot) @ frame)
            nrm.append(n @ frame)
            uv.append(u)
    return w.mesh(name, np.vstack(pos), np.vstack(nrm), np.vstack(uv))


def display(src):
    w = mqo.GltfWriter(f"{NS}:models/ground/humvee/humvee.bin", f"{NS}:textures/ground/humvee/humvee.png",
                       "tools/internal/stage_emb4_humvee.py")
    root = w.node("humvee")
    w.node("body", mesh=mesh_of(w, "body", src.objs(BODY), np.zeros(3)), parent=root)
    for name, (parts, pivot) in HINGED.items():
        p = T(pivot)
        w.node(name, p, mesh_of(w, name, src.objs(parts), p), root)
    for name, (part, pivot) in WHEELS.items():
        p = T(pivot)
        w.node(name, p, mesh_of(w, name, src.objs([part]), p), root)
    part, pivot, axis = STEERING
    p, frame = T(pivot), frame_y(axis)
    column = w.node("steering_column", p, parent=root, rotation=quaternion(frame))
    w.node("steering_wheel", mesh=mesh_of(w, "steering_wheel", src.objs([part]), p, frame), parent=column)
    yaw_p, pitch_p = T(YAW_PIVOT), T(PITCH_PIVOT)
    ring = src.objs(["$weapon0"], lambda o: o.name in RING)
    yaw = w.node("roof_mount", yaw_p, mesh_of(w, "roof_mount", ring, yaw_p), root)
    w.node(MOUNT_BONE, pitch_p - yaw_p, parent=yaw)
    models = os.path.join(PACK, "assets", NS, "models", "ground", "humvee")
    os.makedirs(models, exist_ok=True)
    gltf, binary = os.path.join(models, "humvee.gltf"), os.path.join(models, "humvee.bin")
    kept = mqo.read_markers(gltf, binary) if os.path.exists(gltf) else []
    # Gunner markers belong to the weapon station (turret mount), not the unarmed hull.
    station = [m for m in kept if m["name"].endswith("_" + MOUNT_PART)]
    kept = [m for m in kept if m not in station]
    w.write(gltf, binary, {"staged_by": "tools/internal/stage_emb4_humvee.py", "source": "EMB4 McHeliCE humvee_mk19",
                           "scale": SCALE})
    mqo.append_nodes(gltf, binary, kept)
    return station


def turret(src, station):
    """Yaw-pivot frame: pintle + gun shield. `station` = hull markers of MOUNT_PART, renamed for the mount."""
    models = os.path.join(PACK, "assets", NS, "models", "ground", "humvee")
    gltf, binary = os.path.join(models, "humvee_turret.gltf"), os.path.join(models, "humvee_turret.bin")
    kept = mqo.read_markers(gltf, binary) if os.path.exists(gltf) else []
    for m in station:
        kept.append({**m, "name": m["name"][:-len("_" + MOUNT_PART)], "parent": "humvee_turret"})
    w = mqo.GltfWriter(f"{NS}:models/ground/humvee/humvee_turret.bin", f"{NS}:textures/ground/humvee/humvee.png",
                       "tools/internal/stage_emb4_humvee.py")
    yaw_p = T(YAW_PIVOT)
    w.node("humvee_turret", mesh=mesh_of(w, "humvee_turret", src.objs(["$weapon0"], lambda o: o.name not in RING), yaw_p))
    w.write(gltf, binary, {"staged_by": "tools/internal/stage_emb4_humvee.py", "source": "EMB4 McHeliCE humvee_mk19 $weapon0",
                           "scale": SCALE})
    mqo.append_nodes(gltf, binary, kept)


def mk19(src):
    """Pitch-pivot frame. Bolt: breech over the trunnion on the bore line, Z scale = barrel length."""
    w = mqo.GltfWriter(f"{NS}:models/weapon/mk19/mk19.bin", f"{NS}:textures/ground/humvee/humvee.png",
                       "tools/internal/stage_emb4_humvee.py")
    pitch_p = T(PITCH_PIVOT)
    w.node("mk19", mesh=mesh_of(w, "mk19", src.objs(["$weapon0_0"]), pitch_p))
    muzzle = T(MUZZLE)
    breech = np.array([muzzle[0], muzzle[1], pitch_p[2]])
    models = os.path.join(PACK, "assets", NS, "models", "weapon", "mk19")
    os.makedirs(models, exist_ok=True)
    gltf, binary = os.path.join(models, "mk19.gltf"), os.path.join(models, "mk19.bin")
    w.write(gltf, binary, {"staged_by": "tools/internal/stage_emb4_humvee.py", "source": "EMB4 McHeliCE humvee_mk19 $weapon0_0",
                           "scale": SCALE})
    mqo.append_nodes(gltf, binary, [{"name": "socket_bolt_0", "parent": "mk19",
                                     "translation": [round(float(v), 5) for v in breech - pitch_p],
                                     "rotation": [0, 0, 0, 1],
                                     "scale": [1, 1, round(float(muzzle[2] - breech[2]), 5)]}])


def m2(src):
    """Pitch-pivot frame, role nodes as convert_pack writes them."""
    w = mqo.GltfWriter(f"{NS}:models/weapon/m2/m2.bin", f"{NS}:textures/weapon/m2/m2.png",
                       "tools/internal/stage_emb4_humvee.py")
    pitch_p = T(PITCH_PIVOT)
    root = w.node("m2", mesh=mesh_of(w, "m2", src.objs(["$weapon0_0"]), pitch_p))
    muzzle = T(M2_MUZZLE)
    bolt = w.node("socket_bolt_0", [round(float(v), 5) for v in (muzzle[0], muzzle[1] - pitch_p[1], 0.0)], parent=root)
    w.doc["nodes"][bolt].update({"scale": [1.0, 1.0, round(float(muzzle[2] - pitch_p[2]), 5)], "extras": {
        "combinedassaultRole": "bolt", "combinedassault": {"index": 0}, "gemrenderHidden": True}})
    models = os.path.join(SHIPPED, "assets", NS, "models", "weapon", "m2")
    textures = os.path.join(SHIPPED, "assets", NS, "textures", "weapon", "m2")
    os.makedirs(models, exist_ok=True)
    os.makedirs(textures, exist_ok=True)
    w.write(os.path.join(models, "m2.gltf"), os.path.join(models, "m2.bin"),
            {"staged_by": "tools/internal/stage_emb4_humvee.py", "source": "EMB4 McHeliCE humveewithweapon $weapon0_0",
             "scale": SCALE})
    shutil.copyfile(os.path.join(SRC, "textures", "tanks", "humveewithweapon.png"), os.path.join(textures, "m2.png"))


def cube(lo, hi):
    lo, hi = np.asarray(lo, float), np.asarray(hi, float)
    size = np.maximum(hi - lo, 0.05)
    # SBM mirrors Bedrock X on load: author it mirrored.
    return {"origin": [round(float(v) * 16, 3) for v in (-hi[0], lo[1], lo[2])],
            "size": [round(float(v) * 16, 3) for v in size], "uv": [0, 0]}


def bone(name, cubes, parent=None, pivot=(0, 0, 0)):
    out = {"name": name, "pivot": [round(float(v) * 16, 3) for v in (-pivot[0], pivot[1], pivot[2])],
           "cubes": cubes}
    if parent:
        out["parent"] = parent
    return out


def structure(src):
    import strip_cubes  # deleted with Bedrock: see TODO
    body_lo, body_hi = src.bounds(["$body"])
    bones = [bone("vehicle_body", [])]
    for name, (part, pivot) in WHEELS.items():
        bones.append(bone(name, [cube(*src.bounds([part]))], pivot=T(pivot)))
    for name, (parts, pivot) in HINGED.items():
        bones.append(bone(name, [], pivot=T(pivot)))
    yaw_p, pitch_p, muzzle = T(YAW_PIVOT), T(PITCH_PIVOT), T(MUZZLE)
    bones.append(bone("roof_mount", [], pivot=yaw_p))
    bones.append(bone("roof_mount_barrel", [], parent="roof_mount", pivot=pitch_p))
    model = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16},
        "bones": bones}]}
    path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", "humvee.structure.json")
    with open(path, "w") as f:
        json.dump(model, f, indent=1)
    strip_cubes.split(PACK, "humvee")
    return dict(body=(body_lo, body_hi), wheel_radius=(src.bounds(["$wheel0"])[1][1] - src.bounds(["$wheel0"])[0][1]) / 2,
                wheels={k: T(p).round(4).tolist() for k, (_, p) in WHEELS.items()}, yaw=yaw_p, pitch=pitch_p,
                muzzle=muzzle)


def clips():
    models = os.path.join(PACK, "assets", NS, "models", "ground", "humvee")
    for name, (axis, degrees) in SWING.items():
        mqo.append_clip(os.path.join(models, "humvee.gltf"), os.path.join(models, "humvee.bin"), name,
                        [(name, "rotation", *mqo.hinge_keys(axis, degrees, 0.6))])


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    if sys.argv[1:] == ["m2"]:
        m2(Source("humveewithweapon", M2_SHA1))
        return
    src = Source()
    textures = os.path.join(PACK, "assets", NS, "textures", "ground", "humvee")
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(SRC, "textures", "tanks", "humvee_mk19.png"), os.path.join(textures, "humvee.png"))
    turret(src, display(src))
    mk19(src)
    dims = structure(src)
    clips()
    for k, v in dims.items():
        print(k, np.round(v, 3).tolist() if isinstance(v, (np.ndarray, tuple)) else v)


if __name__ == "__main__":
    main()
