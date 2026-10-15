#!/usr/bin/env python3
"""Zip the Blender extension for Edit > Preferences > Get Extensions > Install from Disk.

    python3 tools/blender/build_addon.py      (from CombinedAssault/) -> build/combinedassault_tools.zip
"""
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(os.path.dirname(HERE)), "build", "combinedassault_tools.zip")
FILES = {"blender_manifest.toml": "blender_manifest.toml",
         "__init__.py": "combinedassault_tools.py",
         "ca_validate.py": "ca_validate.py",
         "ca_reference.py": "ca_reference.py"}


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, source in FILES.items():
            archive.write(os.path.join(HERE, source), name)
    print(OUT)


if __name__ == "__main__":
    main()
