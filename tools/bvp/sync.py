#!/usr/bin/env python3
"""Finished vehicles from a scratch pack -> optional_packs/bvp, merging the shared files.

    python3 tools/bvp/sync.py <scratch>/bvp <ids ...>

Per id: def, model dir, track link, textures, slot icon, controller, script, recipe, referenced sound files.
Shared (key merge, scratch wins): assets/bvp/lang/en_us.json, assets/bvp/sounds.json; pylon weapons: max capacity.
Same flock files as convert.py `locked`.
"""
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from convert import NS, ROOT, locked  # noqa: E402

DEST = ROOT / "optional_packs" / NS


def copy(src, dst):
    if src.is_dir():
        shutil.copytree(src, dst, dirs_exist_ok=True)
    elif src.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp = dst.with_name(dst.name + ".sync")
        shutil.copy2(src, tmp)
        tmp.replace(dst)


def merge(rel, src_root):
    src = src_root / rel
    if not src.exists():
        return
    with locked(DEST / rel) as path:
        doc = json.loads(path.read_text()) if path.exists() else {}
        doc.update(json.loads(src.read_text()))
        path.write_text(json.dumps(dict(sorted(doc.items())), indent=2, ensure_ascii=False) + "\n")


def main():
    src_root = Path(sys.argv[1]).resolve()
    ids = sys.argv[2:]
    assert (DEST / "pack.mcmeta").exists(), f"{DEST}: no pack (first convert.py run writes it)"
    a = Path("assets") / NS
    for vid in ids:
        definition = src_root / "combinedassault" / NS / "vehicles" / f"{vid}.json"
        assert definition.exists(), f"{vid}: not converted in {src_root}"
        for rel in (Path("combinedassault") / NS / "vehicles" / f"{vid}.json", a / "models/vehicle" / vid,
                    a / "models/effect" / f"{vid}_track_link.glb", a / "textures/vehicle" / vid,
                    a / "textures/slot" / f"{vid}.png", a / "animation_controllers" / f"{vid}_controller.json",
                    a / "scripts" / f"{vid}.js", Path("data") / NS / "recipe" / f"{vid}.json"):
            copy(src_root / rel, DEST / rel)
    copy(src_root / a / "sounds", DEST / a / "sounds")
    merge(a / "lang/en_us.json", src_root)
    merge(a / "sounds.json", src_root)
    for weapon in (src_root / "combinedassault" / NS / "weapons").glob("*.json"):
        dst = DEST / "combinedassault" / NS / "weapons" / weapon.name
        with locked(dst) as path:
            doc = json.loads(weapon.read_text())
            if path.exists():
                doc["max_capacity"] = max(doc["max_capacity"], json.loads(path.read_text())["max_capacity"])
            path.write_text(json.dumps(doc, indent=2) + "\n")
    print(f"synced {len(ids)} -> {DEST}")


if __name__ == "__main__":
    main()
