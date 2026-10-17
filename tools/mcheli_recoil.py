"""MCHeli barrel recoil -> glTF `<part>.fire` clips (CA clip track = time since that part's last shot).

    python3 tools/mcheli_recoil.py [vehicle id]...

MCHeli drew recoil procedurally (MCH_WeaponSet.Recoil + MCH_Render*): offset -rb * r(tick) along the part's +Z,
r from the weapon's `RecoilBufCount = max, speed` (file speed - 1, <= max / 2; default 2, 3). Replayed per tick here,
keyed at 20 Hz; key 0 = rest (MCHeli lerps prev -> r over the firing tick).
"""
import json
import math
import os
import sys

import mqo
from script_clips import model

# vehicle id: (CA weapon part, recoil node, rb from the MCHeli vehicle txt part line, RecoilBufCount max, speed - 1)
RECOIL = {
    "m1a2": ("turret", "barrel", 0.5, 2, 2),        # tanks/m1a2.txt AddPartWeaponChild 0.5; abramsapfsds default
    "krupp_c34": ("gun", "barrel", 0.9, 80, 19),    # vehicles/krupp_c_34.txt child 0.9; 406mm_he 80, 20
    "oto127": ("gun", "barrel", 0.4, 2, 2),         # vehicles/oto127mm.txt child 0.4; 127mmgun default
}


def curve(count_max, speed):
    """r per tick from the firing tick until rest (MCH_WeaponSet.updateRecoil)."""
    count, out = count_max, []
    while count > 0:
        if count_max <= 1:
            r = 1.0
        elif count_max == 2:
            r = 1.0 if count == 2 else 0.6
        else:
            if count > count_max / 2:
                count -= speed
            r = math.sin(count / count_max * math.pi)
        count -= 1
        out.append(r)
    return out + [0.0]


def bake(vid):
    part, node, rb, count_max, speed = RECOIL[vid]
    gltf, bin_path = model(vid)
    with open(gltf) as f:
        rest = next(n.get("translation", [0, 0, 0]) for n in json.load(f)["nodes"] if n.get("name") == node)
    r = [0.0] + curve(count_max, speed)
    times = [k / 20.0 for k in range(len(r))]
    values = [[rest[0], rest[1], rest[2] - rb * v] for v in r]
    mqo.append_clip(gltf, bin_path, f"{part}.fire", [(node, "translation", times, values)])
    return f"{part}.fire on {node}: {len(r)} keys, peak {rb * max(r):.2f} m"


if __name__ == "__main__":
    for vid in sys.argv[1:] or RECOIL:
        print(vid, bake(vid))
