#!/usr/bin/env python3
"""Add the `flaps` part to a vehicle json: python3 tools/aircraft_flaps_part.py <vehicle> [--stages 0,15,30] [--max-speed 2.0]"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import def_sockets  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("vehicle")
ap.add_argument("--stages", default="0,15,30")
ap.add_argument("--max-speed", type=float)
ap.add_argument("--lift-gain", type=float)
ap.add_argument("--drag-k", type=float)
args = ap.parse_args()
path = def_sockets.defs()["combinedassault:" + args.vehicle]
text = open(path).read()
doc = json.loads(text)
if any(p.get("id") == "flaps" for p in doc["parts"]):
    sys.exit("%s already has a flaps part" % args.vehicle)
part = {"id": "flaps", "name": "part.combinedassault.flaps", "type": "combinedassault:flaps", "is_seat": False,
        "stages": [float(s) for s in args.stages.split(",")], "rot_info": {"x_rot_speed": 1}}
if args.max_speed is not None:
    part["max_speed"] = args.max_speed
if args.lift_gain is not None:
    part["lift_gain"] = args.lift_gain
if args.drag_k is not None:
    part["drag_k"] = args.drag_k
marker = '"parts": ['
at = text.index(marker) + len(marker)
text = text[:at] + "\n" + " " * 8 + json.dumps(part) + "," + text[at:]
json.loads(text)
open(path, "w").write(text)
print("%s: flaps part added" % args.vehicle)
