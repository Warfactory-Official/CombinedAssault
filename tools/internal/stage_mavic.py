#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage warty's DJI Mavic 3 Pro as the quadcopter display (quadcopter, fpv_drone): glTF + structure.

    python3 tools/stage_mavic.py      (from combinedassault/)

Export is glTF +Z forward, 1 unit = 1 m, real size (0.40 span with blades). Root scale SCALE => span 1.5,
the footprint quadcopter/fpv_drone physics were tuned on. Not mirrored: +X is the export's right, ours port;
model symmetric, only FL/FR names and spin sense swap. Textures 2K -> 512 (warty texture ceiling).
"""
import json
import os
import shutil

import numpy as np
from PIL import Image
import mqo
import strip_cubes  # noqa: E402

SRC = os.path.expanduser("~/models/warty/mavic_3_pro")
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"
SCALE = 3.75
TEXTURE = 512


def world_boxes(doc):
    boxes, origins = {}, {}

    def visit(i, t):
        n = doc["nodes"][i]
        here = t + np.array(n.get("translation", [0, 0, 0]), float) * (SCALE if i != root else 1)
        origins[n["name"]] = here
        if "mesh" in n:
            acc = doc["accessors"][doc["meshes"][n["mesh"]]["primitives"][0]["attributes"]["POSITION"]]
            boxes[n["name"].removesuffix("_mesh")] = (here + np.array(acc["min"]) * SCALE,
                                                     here + np.array(acc["max"]) * SCALE)
        for c in n.get("children", []):
            visit(c, here)

    root = doc["scenes"][0]["nodes"][0]
    visit(root, np.zeros(3))
    return boxes, origins


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    doc = json.load(open(os.path.join(SRC, "DJI_Mavic_3_Pro.gltf")))
    root = doc["nodes"][doc["scenes"][0]["nodes"][0]]
    assert not any(k in root for k in ("translation", "rotation", "scale", "matrix"))
    root["scale"] = [SCALE] * 3
    models = os.path.join(PACK, "assets", NS, "models", "aircraft", "mavic")
    textures = os.path.join(PACK, "assets", NS, "textures", "aircraft", "mavic")
    os.makedirs(models, exist_ok=True)
    os.makedirs(textures, exist_ok=True)
    gltf, binary = os.path.join(models, "mavic.gltf"), os.path.join(models, "mavic.bin")
    kept = mqo.read_markers(gltf, binary)
    shutil.copyfile(os.path.join(SRC, doc["buffers"][0]["uri"]), binary)
    doc["buffers"][0] = {"byteLength": doc["buffers"][0]["byteLength"],
                         "extras": {"resourceLocation": f"{NS}:models/aircraft/mavic/mavic.bin"}}
    for image in doc["images"]:
        name = image["uri"].lower().replace("_2k", f"_{TEXTURE}")
        Image.open(os.path.join(SRC, image.pop("uri"))).resize((TEXTURE, TEXTURE), Image.LANCZOS) \
            .save(os.path.join(textures, name))
        image["extras"] = {"resourceLocation": f"{NS}:textures/aircraft/mavic/{name}"}
    doc["asset"]["extras"] = {"staged_by": "tools/stage_mavic.py", "scale": SCALE}
    with open(gltf, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    mqo.append_nodes(gltf, binary, kept)

    boxes, origins = world_boxes(doc)
    lo = np.min([b[0] for b in boxes.values()], axis=0)
    hi = np.max([b[1] for b in boxes.values()], axis=0)
    from stage_emb4_tank import bone
    gimbal = origins["22_GIMBAL_pitch"]
    model = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16},
        "bones": [bone("vehicle_body", []), bone("sighting_system", [], pivot=gimbal)]}]}
    path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", "quadcopter.structure.json")
    with open(path, "w") as f:
        json.dump(model, f, indent=1)
    strip_cubes.split(PACK, "quadcopter")
    print("bounds", np.round(lo, 3).tolist(), np.round(hi, 3).tolist(), "gimbal", np.round(gimbal, 3).tolist())


if __name__ == "__main__":
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    main()
