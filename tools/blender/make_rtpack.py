"""build/bltest/rtpack: directory pack `rttest`, shipped humvee copied as humvee_bl / humvee_bb for re-export in place
by Blender (reexport.py) / Blockbench; textures under assets/combinedassault so imports resolve. In game:
REDACTED build/bltest/rtpack.

    python3 tools/blender/make_rtpack.py
"""
import json
import os
import shutil

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = REPO + "/src/main/resources/combinedassault_packs/combinedassault"
OUT = REPO + "/build/bltest/rtpack"
shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT)
json.dump({"pack": {"pack_format": 34, "description": "[CA] tool round trip"},
           "combinedassault": {"id": "rttest", "requires": ["combinedassault"]}}, open(OUT + "/pack.mcmeta", "w"), indent=1)
tex = "assets/combinedassault/textures/ground/humvee"
shutil.copytree(os.path.join(SRC, tex), os.path.join(OUT, tex))
for v in ("bl", "bb"):
    name = "humvee_" + v
    d = os.path.join(OUT, "assets/rttest/models/ground", name)
    os.makedirs(d)
    g = json.load(open(SRC + "/assets/combinedassault/models/ground/humvee/humvee.gltf"))
    g["buffers"][0]["extras"]["resourceLocation"] = "rttest:models/ground/%s/%s.bin" % (name, name)
    json.dump(g, open(os.path.join(d, name + ".gltf"), "w"))
    shutil.copy(SRC + "/assets/combinedassault/models/ground/humvee/humvee.bin", os.path.join(d, name + ".bin"))
    veh = open(SRC + "/combinedassault/combinedassault/vehicles/humvee.json").read().replace(
        "combinedassault:models/ground/humvee/humvee.gltf", "rttest:models/ground/%s/%s.gltf" % (name, name))
    os.makedirs(os.path.join(OUT, "combinedassault/rttest/vehicles"), exist_ok=True)
    open(os.path.join(OUT, "combinedassault/rttest/vehicles", name + ".json"), "w").write(veh)
print(OUT)
