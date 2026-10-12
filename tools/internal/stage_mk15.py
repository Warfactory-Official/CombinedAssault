#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage warty's Mk 15 Phalanx (naval repaint of MCHeli mk15.mqo) as mk15: glTF + structure.

    python3 REDACTED      (from combinedassault/)

Export: +Z forward, +X port, MCHeli scale; nodes named after mk15.txt parts: $part0 yaw, $part1 yaw + pitch,
$part1_0 spinning barrels. Divergences staged here:
- $part1 reparented under $part0 (same yaw axis): head pitches only, yaw inherited.
- Fastener material is colour-only => draws white; remapped to the sheet at the nearest texel.
"""
import json
import os
import shutil
import struct

import numpy as np
from PIL import Image
import mqo
import strip_cubes  # noqa: E402

SRC = os.path.expanduser("~/models/warty/mk15_ciws")
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"
NAMES = {"MK15_CIWS_ROOT": "mk15", "$body": "body", "$part0": "mount", "$part1": "head", "$part1_0": "barrels"}


def srgb(linear):
    return np.where(linear <= 0.0031308, linear * 12.92, 1.055 * np.power(linear, 1 / 2.4) - 0.055)


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    doc = json.load(open(os.path.join(SRC, "Mk15_CIWS_Naval_512.gltf")))
    blob = bytearray(open(os.path.join(SRC, doc["buffers"][0]["uri"]), "rb").read())
    nodes = doc["nodes"]
    by_name = {n["name"]: i for i, n in enumerate(nodes)}
    for n in nodes:
        n["name"] = NAMES.get(n["name"], n["name"].lstrip("$"))

    root, mount, head = nodes[by_name["MK15_CIWS_ROOT"]], nodes[by_name["$part0"]], nodes[by_name["$part1"]]
    root["children"].remove(by_name["$part1"])
    mount.setdefault("children", []).append(by_name["$part1"])
    head["translation"] = (np.array(head["translation"]) - mount["translation"]).tolist()

    sheet = np.asarray(Image.open(os.path.join(SRC, "Mk15_Naval_basecolor_512.png")).convert("RGB"), float) / 255
    for mi, material in enumerate(doc["materials"]):
        if "baseColorTexture" in material["pbrMetallicRoughness"]:
            continue
        want = srgb(np.array(material["pbrMetallicRoughness"]["baseColorFactor"][:3]))
        y, x = np.unravel_index(np.argmin(np.sum((sheet - want) ** 2, axis=2)), sheet.shape[:2])
        uv = ((x + 0.5) / sheet.shape[1], (y + 0.5) / sheet.shape[0])
        for mesh in doc["meshes"]:
            for prim in mesh["primitives"]:
                if prim.get("material") != mi:
                    continue
                prim["material"] = 0
                acc = doc["accessors"][prim["attributes"]["TEXCOORD_0"]]
                view = doc["bufferViews"][acc["bufferView"]]
                start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
                stride = view.get("byteStride", 8)
                for k in range(acc["count"]):
                    struct.pack_into("<2f", blob, start + k * stride, *uv)
    doc["materials"] = [m for m in doc["materials"] if "baseColorTexture" in m["pbrMetallicRoughness"]]

    models = os.path.join(PACK, "assets", NS, "models", "emplacement", "mk15")
    textures = os.path.join(PACK, "assets", NS, "textures", "emplacement", "mk15")
    os.makedirs(models, exist_ok=True)
    os.makedirs(textures, exist_ok=True)
    gltf, binary = os.path.join(models, "mk15.gltf"), os.path.join(models, "mk15.bin")
    kept = mqo.read_markers(gltf, binary)
    with open(binary, "wb") as f:
        f.write(blob)
    doc["buffers"][0] = {"byteLength": len(blob), "extras": {"resourceLocation": f"{NS}:models/emplacement/mk15/mk15.bin"}}
    for image in doc["images"]:
        name = image["uri"].lower().replace("mk15_naval_", "mk15_")
        shutil.copyfile(os.path.join(SRC, image.pop("uri")), os.path.join(textures, name))
        image["extras"] = {"resourceLocation": f"{NS}:textures/emplacement/mk15/{name}"}
    doc["asset"]["extras"] = {"staged_by": "REDACTED"}
    with open(gltf, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    mqo.append_nodes(gltf, binary, kept)

    def bounds(node_name, origin):
        n = nodes[by_name[node_name]]
        geo = next(nodes[c] for c in n["children"] if "mesh" in nodes[c])
        acc = doc["accessors"][doc["meshes"][geo["mesh"]]["primitives"][0]["attributes"]["POSITION"]]
        return origin + np.array(acc["min"]), origin + np.array(acc["max"])

    mount_p = np.array(mount["translation"])
    head_p = mount_p + head["translation"]
    barrels_p = head_p + nodes[by_name["$part1_0"]]["translation"]
    head_lo, head_hi = bounds("$part1", head_p)
    gun_lo, gun_hi = bounds("$part1_0", barrels_p)
    from stage_emb4_tank import bone
    model = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16},
        "bones": [bone("vehicle_body", []), bone("ciws", [], pivot=mount_p),
                  bone("ciws_barrel", [], parent="ciws", pivot=barrels_p)]}]}
    path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", "mk15.structure.json")
    with open(path, "w") as f:
        json.dump(model, f, indent=1)
    strip_cubes.split(PACK, "mk15")
    print("mount", mount_p.tolist(), "head", head_p.tolist(), "barrels", barrels_p.tolist(),
          "muzzle z", round(float(gun_hi[2]), 3), "head", np.round(head_lo, 3).tolist(), np.round(head_hi, 3).tolist())


if __name__ == "__main__":
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    main()
