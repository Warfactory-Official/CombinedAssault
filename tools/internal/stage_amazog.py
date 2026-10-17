#!/usr/bin/env python3
"""Stage the WFLib Amazog octocopter as a CA vehicle model: role nodes, rotor units, texture.

    python3 tools/internal/stage_amazog.py     (then tools/gen_hulls.py amazog for colliders)

Source: 1 unit = 1 block, ground y 0, root already Ry(+90) => forward +Z; no scale.
"""
import json
import math
import os
import shutil

WFLIB = os.path.expanduser("~/projects/wf2-21/WF-Ballistics/src/main/resources/assets/wflib")
PACK = "src/main/resources/combinedassault_packs/combinedassault/assets/combinedassault"
NS = "combinedassault"
# Spin sense alternates around the ring (ROTOR_01..08).
SPIN = ["cw", "ccw"]


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    doc = json.load(open(os.path.join(WFLIB, "raw_models/drones/amazog.gltf")))
    models = os.path.join(PACK, "models/aircraft/amazog")
    textures = os.path.join(PACK, "textures/aircraft/amazog")
    os.makedirs(models, exist_ok=True)
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(WFLIB, "raw_models/drones/amazog.bin"), os.path.join(models, "amazog.bin"))
    shutil.copyfile(os.path.join(WFLIB, "textures/models/drones/amazog.png"), os.path.join(textures, "amazog.png"))
    doc["buffers"][0]["extras"] = {"resourceLocation": f"{NS}:models/aircraft/amazog/amazog.bin"}
    for image in doc["images"]:
        image["extras"] = {"resourceLocation": f"{NS}:textures/aircraft/amazog/amazog.png"}

    nodes = doc["nodes"]
    by_name = {n["name"]: i for i, n in enumerate(nodes)}
    for k in range(8):
        rotor = nodes[by_name[f"ROTOR_{k + 1:02d}_SPIN"]]
        rotor["extras"] = {"combinedassault": {"system": "rotor", "unit": f"R{k + 1}", "spin": SPIN[k % 2]}}

    root = by_name["AMAZOG_ROOT"]
    s = math.sqrt(0.5)
    assert all(math.isclose(a, b, abs_tol=1e-9) for a, b in zip(nodes[root]["rotation"], [0, s, 0, s])) and not any(t in nodes[root] for t in ("translation", "scale"))
    # ATTACH_FORWARD_CAMERA (-0.78, 0.925, 0) under Ry(+90).
    cam = nodes[by_name["ATTACH_FORWARD_CAMERA"]]["translation"]
    nodes.append({"name": "sight_sighting_system", "translation": [0, 0, 0.1],
                  "extras": {"gemrenderHidden": True, "combinedassaultRole": "sight",
                             "combinedassaultInterface": "sighting_system"}})
    nodes.append({"name": "sighting_system", "translation": [-cam[2], cam[1], -cam[0]], "children": [len(nodes) - 1],
                  "extras": {"combinedassaultRole": "part", "combinedassaultInterface": "sighting_system"}})
    sight = len(nodes) - 1
    nodes.append({"name": "socket_center_of_mass", "translation": [0, 1.0, 0],
                  "extras": {"gemrenderHidden": True, "combinedassaultRole": "center_of_mass"}})
    com = len(nodes) - 1
    nodes.append({"name": "amazog", "children": [root, sight, com], "extras": {"combinedassaultRole": "body"}})
    doc["scenes"] = [{"name": "amazog", "nodes": [len(nodes) - 1]}]
    doc["scene"] = 0
    doc["asset"]["extras"] = {"staged_by": "tools/internal/stage_amazog.py"}
    with open(os.path.join(models, "amazog.gltf"), "w") as f:
        json.dump(doc, f, separators=(",", ":"))


if __name__ == "__main__":
    main()
