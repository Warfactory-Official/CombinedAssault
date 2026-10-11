#!/usr/bin/env python3
"""Per-vehicle pack boilerplate.

    python3 tools/pack_scaffold.py <vid> "<en name>" "<zh name>" [recipe_template_vid]

Writes def display.variants <vid>_<paint> per assets/<ns>/paints/*.json, recipe/<vid>.json (template's materials,
result retargeted), lang combinedassault.<vid> + entity.combinedassault.<vid>.
"""
import glob
import json
import os
import sys

PACK = "src/main/resources/combinedassault_packs/combinedassault"
NS = "combinedassault"
ASSETS = os.path.join(PACK, "assets", NS)
DATA = os.path.join(PACK, "data", NS)
DEFS = os.path.join(PACK, "combinedassault", NS, "vehicles")


def variants(vid):
    path = os.path.join(DEFS, f"{vid}.json")
    vehicle = json.load(open(path))
    display = vehicle.setdefault("display", {})
    for paint in sorted(glob.glob(os.path.join(ASSETS, "paints", "*.json"))):
        name = os.path.basename(paint)[:-5]
        display.setdefault("variants", {})[f"{vid}_{name}"] = {"paint": f"{NS}:{name}"}
    with open(path, "w") as f:
        json.dump(vehicle, f, indent=4)


def recipe(vid, template):
    r = json.load(open(os.path.join(DATA, "recipe", f"{template}.json")))
    r["result"]["components"]["minecraft:custom_data"]["CombinedAssaultId"] = f"{NS}:{vid}"
    with open(os.path.join(DATA, "recipe", f"{vid}.json"), "w") as f:
        json.dump(r, f, indent=2)


def lang(vid, names):
    for code, name in names.items():
        path = os.path.join(ASSETS, "lang", f"{code}.json")
        entries = json.load(open(path))
        entries[f"{NS}.{vid}"] = entries[f"entity.{NS}.{vid}"] = name
        with open(path, "w") as f:
            json.dump(entries, f, indent=4, ensure_ascii=False)
            f.write("\n")


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    vid, en, zh = sys.argv[1:4]
    variants(vid)
    recipe(vid, sys.argv[4] if len(sys.argv) > 4 else "t84")
    lang(vid, {"en_us": en, "zh_cn": zh})


if __name__ == "__main__":
    main()
