"""Bake a vehicle script's `isPartOn` snap blocks into glTF part clips, then drop the blocks from the script.

    python3 tools/script_clips.py <vehicle id>...

`clips` takes the same poses as data (stagers without a generated script).

Block forms: `if ([!]context.isPartOn("p")) { setRotation... } [else { setRotation... }]`, literal single-axis angles
only. Clip `p`: t=0 = off pose, t=span = on pose; a node posed on one side only keeps its rest rotation on the other.
Script rotation replaces the node's rest rotation (GemRender), so keys are absolute.
"""
import json
import os
import re
import sys

import numpy as np

import mqo

PACK = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "combinedassault_packs", "combinedassault")
NS = "combinedassault"
# Seconds per part type; landing gear = LandingGearUnit's 20-tick leg travel.
SPAN = {"landing_gear": 1.0, "weapon_bay": 1.0, "door": 2.0}

IF = re.compile(r'^    if \((!?)context\.isPartOn\("([^"]+)"\)\) \{$')
ROT = re.compile(r'^        builder\.setRotation\("([^"]+)", (-?[\d.]+), (-?[\d.]+), (-?[\d.]+)\);$')


def blocks(lines):
    """[(start, end_exclusive, part, {node: on (x, y, z)}, {node: off (x, y, z)})]."""
    out = []
    i = 0
    while i < len(lines):
        m = IF.match(lines[i])
        if not m:
            i += 1
            continue
        start, negated, part = i, m.group(1) == "!", m.group(2)
        branches = [{}, {}]
        branch = 0
        i += 1
        while lines[i] != "    }":
            if lines[i] == "    } else {":
                branch = 1
            else:
                r = ROT.match(lines[i])
                assert r, f"not a literal rotation in a {part} block: {lines[i]!r}"
                branches[branch][r.group(1)] = tuple(map(float, r.group(2, 3, 4)))
            i += 1
        on, off = (branches[1], branches[0]) if negated else branches
        out.append((start, i + 1, part, on, off))
        i += 1
    return out


def axis_angle(x, y, z):
    nonzero = [(a, v) for a, v in zip("xyz", (x, y, z)) if v != 0]
    assert len(nonzero) <= 1, f"multi-axis rotation ({x}, {y}, {z})"
    q = [0.0, 0.0, 0.0, 1.0]
    if nonzero:
        a, v = nonzero[0]
        q["xyz".index(a)] = float(np.sin(np.radians(v) / 2))
        q[3] = float(np.cos(np.radians(v) / 2))
    return q


def slerp_keys(q0, q1, seconds):
    """Keys <= 90 deg apart: glTF slerp keeps the direction."""
    q0, q1 = np.array(q0), np.array(q1)
    if np.dot(q0, q1) < 0:
        q1 = -q1
    angle = 2 * np.arccos(min(1.0, abs(float(np.dot(q0, q1)))))
    steps = max(1, int(np.ceil(np.degrees(angle) / 90.0)))
    times, values = [], []
    for k in range(steps + 1):
        t = k / steps
        if angle < 1e-6:
            q = q0
        else:
            s = np.sin(angle / 2)
            q = (np.sin((1 - t) * angle / 2) * q0 + np.sin(t * angle / 2) * q1) / s
        times.append(seconds * t)
        values.append([float(c) for c in q / np.linalg.norm(q)])
    return times, values


def clips(vid, gltf_path, bin_path, parts):
    """parts: {part: ({node: on (x, y, z) degrees}, {node: off (x, y, z)})} -> one clip per part."""
    with open(os.path.join(PACK, "combinedassault", NS, "vehicles", f"{vid}.json")) as f:
        types = {p["id"]: p["type"].split(":")[-1] for p in json.load(f)["parts"]}
    with open(gltf_path) as f:
        rest = {n.get("name"): n.get("rotation", [0, 0, 0, 1]) for n in json.load(f)["nodes"]}
    for part, (on, off) in parts.items():
        def pose(side, node):
            return axis_angle(*side[node]) if node in side else rest[node]
        channels = [(node, "rotation", *slerp_keys(pose(off, node), pose(on, node), SPAN[types[part]]))
                    for node in sorted(set(on) | set(off))]
        mqo.append_clip(gltf_path, bin_path, part, channels, absolute=True)


def bake(vid, gltf_path, bin_path):
    script_path = os.path.join(PACK, "assets", NS, "scripts", f"{vid}.js")
    with open(script_path) as f:
        lines = f.read().split("\n")
    found = blocks(lines)
    by_part = {}
    for _, _, part, on, off in found:
        entry = by_part.setdefault(part, ({}, {}))
        entry[0].update(on)
        entry[1].update(off)
    posed_elsewhere = {m.group(1) for k, line in enumerate(lines)
                       if not any(s <= k < e for s, e, *_ in found)
                       for m in [re.search(r'setRotation\("([^"]+)"', line)] if m}
    for part, (on, off) in by_part.items():
        clash = (set(on) | set(off)) & posed_elsewhere
        assert not clash, f"{vid}: {sorted(clash)} posed outside the {part} block too"
    clips(vid, gltf_path, bin_path, by_part)
    for start, end, *_ in reversed(found):
        del lines[start:end]
    with open(script_path, "w") as f:
        f.write("\n".join(lines))
    return sorted(by_part)


def model(vid):
    with open(os.path.join(PACK, "combinedassault", NS, "vehicles", f"{vid}.json")) as f:
        location = json.load(f)["model"]
    ns, path = location.split(":")
    gltf = os.path.join(PACK, "assets", ns, path)
    return gltf, gltf[:-len(".gltf")] + ".bin"


if __name__ == "__main__":
    for vid in sys.argv[1:]:
        print(vid, bake(vid, *model(vid)))
