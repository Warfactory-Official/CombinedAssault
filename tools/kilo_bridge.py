#!/usr/bin/env python3
"""Kilo: helm view socket = sail-top bridge, forward of the large sensor mast (z 6.09..7.41); planesman `sight` socket = periscope eye. Idempotent.

    python3 tools/kilo_bridge.py
Mast 10_MAST_periscope y max 16.62.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import def_sockets  # noqa: E402

BRIDGE = (0.0, 10.8, 8.0)
PERISCOPE = (0.48, 16.8, 3.77)


def main():
    model = def_sockets.Model("combinedassault:models/ships/kilo/kilo.glb")
    node = next(n for n in model.gltf.nodes if n["name"] == "socket_view_helm")
    m = node["matrix"]
    m[12], m[13], m[14] = BRIDGE
    if not model.has("sight", "planesman"):
        model.socket("sight", "planesman", def_sockets.translation(PERISCOPE))
    model.gltf.save(model.path, lambda r, data: open(def_sockets.asset(r), "wb").write(data))


if __name__ == "__main__":
    main()
