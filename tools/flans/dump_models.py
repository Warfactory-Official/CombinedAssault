#!/usr/bin/env python3
"""Flan's Mod 1.12.2 TMT model classes -> per-model geometry JSON (FlanDump.java).

usage: dump_models.py <FlansMod checkout> <out dir> [model id ...]   (ids as in configs: ww2.Sherman; none = every config's)
"""
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACKAGES = {"mw": "modernweapons"}
BASES = ["ModelDriveable", "ModelVehicle", "ModelPlane", "ModelAAGun", "ModelMecha"]
TMT_SKIP = {"TmtTessellator.java", "ModelPool.java", "ModelPoolEntry.java", "ModelPoolObjEntry.java"}
CONFIG_DIRS = ("vehicles", "planes", "aaguns", "mechas")


def model_ids(flan: Path):
    ids = set()
    for d in CONFIG_DIRS:
        for txt in (flan / "run/Flan").glob(f"*/{d}/*.txt"):
            for line in txt.read_text(errors="replace").splitlines():
                m = re.match(r"\s*Model\s+(\S+)", line)
                if m:
                    ids.add(m.group(1))
    return sorted(ids)


def class_of(model_id: str) -> str:
    pack, name = model_id.split(".", 1)
    return f"com.flansmod.{PACKAGES.get(pack, pack)}.client.model.Model{name}"


def strip_methods(src: str, keep) -> str:
    """Drop every method whose name fails keep(name); brace matched (no braces in strings here)."""
    out, i = [], 0
    pat = re.compile(r"\n(\s*(?:@\w+\s*)*(?:public|protected|private)[^;={}()]*?\s(\w+)\s*\([^)]*\)[^;{]*)\{")
    while True:
        m = pat.search(src, i)
        if not m:
            out.append(src[i:])
            return "".join(out)
        depth, j = 1, m.end()
        while depth:
            depth += {"{": 1, "}": -1}.get(src[j], 0)
            j += 1
        name = m.group(2)
        cls = re.search(r"class\s+(\w+)", src).group(1)
        out.append(src[i:m.start()] if not keep(name) and name != cls else src[i:j])
        i = j


def shim(src: str) -> str:
    src = strip_methods(src, lambda n: n.startswith(("flip", "translate", "registerGunModel")))
    src = re.sub(r"^import (?!java\.|com\.flansmod\.client\.tmt\.|com\.flansmod\.common\.vector\.|net\.minecraft\.client\.model\.ModelBase).*\n",
                 "", src, flags=re.M)
    return re.sub(r"@Override\s*\n(\s*public void (?:render|setRotationAngles))", r"\1", src)


def main():
    flan, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    ids = sys.argv[3:] or model_ids(flan)
    java = flan / "src/main/java"
    build = out / "_build"
    shutil.rmtree(build, ignore_errors=True)
    src = build / "src"
    shutil.copytree(HERE / "dumper/stubs", src)
    tmt = src / "com/flansmod/client/tmt"
    for f in (java / "com/flansmod/client/tmt").glob("*.java"):
        if f.name not in TMT_SKIP:
            (tmt / f.name).write_text(no_override(f.read_text()))
    vec = src / "com/flansmod/common/vector"
    vec.mkdir(parents=True)
    for f in (java / "com/flansmod/common/vector").glob("*.java"):
        if f.name != "Matrix2f.java":
            shutil.copy(f, vec / f.name)
    model = src / "com/flansmod/client/model"
    model.mkdir(parents=True)
    for b in BASES:
        (model / f"{b}.java").write_text(no_override(shim((java / f"com/flansmod/client/model/{b}.java").read_text())))
    classes = []
    for mid in ids:
        cls = class_of(mid)
        rel = cls.replace(".", "/") + ".java"
        dst = src / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(no_override(shim_model((java / rel).read_text())))
        classes.append(cls)
    shutil.copy(HERE / "dumper/FlanDump.java", src / "FlanDump.java")
    files = [str(p) for p in src.rglob("*.java")]
    subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "-d", str(build / "classes"), *files], check=True)
    subprocess.run(["java", "-cp", str(build / "classes"), "FlanDump", str(out), *classes], check=True)


def no_override(src: str) -> str:
    return re.sub(r"@Override\b", "", src)


def shim_model(src: str) -> str:
    """Model subclasses: drop render overrides (MC entity / GL calls); geometry is all in the constructor."""
    src = strip_methods(src, lambda n: not n.startswith(("render", "setRotationAngles")))
    return re.sub(r"^import (?!java\.|com\.flansmod\.client\.(?:tmt|model)\.|com\.flansmod\.common\.vector\.|net\.minecraft\.client\.model\.ModelBase).*\n",
                  "", src, flags=re.M)


if __name__ == "__main__":
    main()
