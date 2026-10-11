#!/usr/bin/env python3
"""Stage the cargo parachute canopy: EMB4 MCHeli 1.0.3 models/parachute2.mqo -> models/effect/cargo_parachute.gltf.

    python3 REDACTED      (from CombinedAssault/)

Rigging lines meet at y 0.1 on a 7.9-block square (CargoRenderer.RIG_SPAN); `$seat` dropped.
"""
import hashlib
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mqo  # noqa: E402

SRC = os.path.expanduser("~/projects/mcheli-gigarework/1.7.10-original/mcheli/assets/mcheli")
SHA1 = "10a68227ee24327920b9f0930a06eee9dc609529"
PACK = "src/main/resources/combinedassault_packs/combinedassault"
NS = "combinedassault"


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    path = os.path.join(SRC, "models", "parachute2.mqo")
    digest = hashlib.sha1(open(path, "rb").read()).hexdigest()
    if digest != SHA1:
        raise SystemExit(f"{path}: sha1 {digest} != EMB4 1.0.3 release")
    objects = [o for o in mqo.read(path) if not o.name.startswith("$") and o.tris]
    w = mqo.GltfWriter(f"{NS}:models/effect/cargo_parachute.bin", f"{NS}:textures/effect/cargo_parachute.png",
                       "REDACTED")
    pos, nrm, uv = [], [], []
    for o in objects:
        a, b, c = mqo.triangles(o, lambda v: np.asarray(v, dtype=float))
        pos.append(a)
        nrm.append(b)
        uv.append(c)
    w.node("cargo_parachute", mesh=w.mesh("canopy", np.vstack(pos), np.vstack(nrm), np.vstack(uv)))
    models = os.path.join(PACK, "assets", NS, "models", "effect")
    textures = os.path.join(PACK, "assets", NS, "textures", "effect")
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(SRC, "textures", "parachute2.png"), os.path.join(textures, "cargo_parachute.png"))
    w.write(os.path.join(models, "cargo_parachute.gltf"), os.path.join(models, "cargo_parachute.bin"),
            {"staged_by": "REDACTED", "source": "EMB4 MCHeli 1.0.3 models/parachute2.mqo"})
    print(len(objects), "objects,", sum(len(p) for p in pos) // 3, "triangles")


if __name__ == "__main__":
    main()
