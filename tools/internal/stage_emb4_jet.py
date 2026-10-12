#!/usr/bin/env python3
# TODO: writes the pre-role layout (default_vehicle/, Bedrock structure + hull json, display json); restaging needs
# a port to role nodes, or a run through tools/convert_pack.py.
"""Stage EMB4 jets (MCHeli 1.0.3 planes/) as glTF display + structure.

    python3 tools/stage_emb4_jet.py [f35a ...]      (from combinedassault/)

Pivots, axes, angles: EMB4 planes/<src>.txt. MCHeli blocks -> ours: (p + (0, ground, 0)) * scale.
AddPartLG = glRotate(a1, axis1) then glRotate(a2, axis2) while folded; LGRev while down; bays while open.
Node chain per hinge: `<n>_hinge` (+X = axis) -> `<n>` (script setRotation(n, a, 0, 0)); second axis:
`<n>_hinge2` -> `<n>_fold`. glTF: hinge2 local = R1^T R2 => world = Rot(axis1, a1) Rot(axis2, a2).
"""
import hashlib
import json
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mqo  # noqa: E402
import script_clips  # noqa: E402
import strip_cubes  # noqa: E402
from stage_emb4_tank import bone, cube, quaternion  # noqa: E402

SRC = os.path.expanduser("~/projects/mcheli-gigarework/1.7.10-original/mcheli/assets/mcheli")
PACK = "src/main/resources/default_vehicle"
NS = "combinedassault"

# gear: (node, part, pivot, axis, angle, axis2 | None, angle2, rev). bays: (node, part, pivot, axis, angle).
# weapons: bone -> (pivot, [(bolt, barrel_length)]), MCHeli frame. wheels: gear part -> tyre object =>
# per axle `<gear>_wheel<k>_hinge` (world-aligned) -> `<gear>_wheel<k>`, spun about X by the wheel_rotation binding.
JETS = {
    # span 12.24 -> 10.8 (real 10.7), length 17.2 -> 15.1 (real 15.7)
    "f35a": dict(
        src="f-35a", sha1="e5c17fd9ebcb2c184d9e624e2b38568f5ba9740f", texture="f-35a", scale=0.88, ground=0.29,
        canopy=("$canopy0", (0.00, 2.63, 1.91), (1, 0, 0), 40),
        gear=[("gear_nose", "$lg0", (0.000, 1.455, -0.076), (-1.00, 0.000, 0.00), 102, None, 0, False),
              ("nose_door_l", "$lg1", (0.270, 1.450, 1.840), (-0.05, 0.075, 1.00), 89, None, 0, True),
              ("nose_door_r", "$lg2", (-0.270, 1.450, 1.840), (0.05, 0.075, 1.00), -89, None, 0, True),
              ("gear_l", "$lg3", (2.18, 1.91, -7.23), (-1, 0, -0.05), 90, (0, -1, 0), 90, False),
              ("gear_r", "$lg4", (-2.18, 1.91, -7.23), (-1, 0, -0.05), 90, (0, 1, 0), 90, False)],
        bays=[("bay_door_0", "$wb0", (1.65, 1.43, -2.64), (-0.027, -0.031, 1.000), 80),
              ("bay_door_1", "$wb1", (-1.65, 1.43, -2.64), (0.027, -0.031, 1.000), -80),
              ("bay_door_2", "$wb2", (0.51, 1.15, -2.64), (0.000, -0.011, 1.000), -80),
              ("bay_door_3", "$wb3", (-0.51, 1.15, -2.64), (0.000, -0.011, 1.000), 80),
              ("bay_door_4", "$wb4", (0.65, 1.22, -4.92), (0.000, 0.000, 1.000), -110),
              ("bay_door_5", "$wb5", (-0.65, 1.22, -4.92), (0.000, 0.000, 1.000), 110)],
        stores=[f"$weapon{i}" for i in range(8)],
        wheels={"$lg0": "wheeF", "$lg3": "wheelL", "$lg4": "wheelR"},
        sight=(0.0, 1.39, 2.31),
        weapons={"auto_cannon": ((1.67, 2.37, -1.43), [((1.67, 2.37, -1.43), 0.3)]),
                 "ir_missile": ((0.0, 1.16, -6.53), [((0.95, 1.16, -6.53), 0.1), ((-0.95, 1.16, -6.53), 0.1)]),
                 "guided_bomb": ((0.0, 1.71, -5.85), [((1.06, 1.71, -4.85), 0.1), ((-1.06, 1.71, -4.85), 0.1),
                                                      ((1.06, 1.71, -6.85), 0.1), ((-1.06, 1.71, -6.85), 0.1)]),
                 "decoy_flare": ((0.0, 1.60, -9.5), [((0.5, 1.60, -9.5), 0.1), ((-0.5, 1.60, -9.5), 0.1)])},
        points={"seat": (0.00, 1.92 + 1.2, 0.32), "nozzle": (0.00, 2.20, -10.82), "wingtip": (6.12, 2.30, -9.0)}),
    # span 19.68 -> 17.3 (real 17.5), length 18.55 -> 16.3 (real 16.3)
    "a10": dict(
        src="a-10", sha1="dc3a885e3bd727bee5a7af714fc313b07f53dffa", texture="a-10", scale=0.88, ground=0.30,
        canopy=("$canopy", (0.0, 3.85, -1.97), (-1, 0, 0), 70),
        gear=[("gear_nose", "$lg0", (0.0, 2.17, -0.40), (-1, 0, 0), 90, None, 0, False),
              ("gear_main", "$lg1", (0.0, 2.00, -6.00), (-1, 0, 0), 90, None, 0, False)],
        bays=[], stores=[f"$weapon{i}" for i in range(1, 9)], wheels={"$lg0": "auto", "$lg1": "auto"},
        sight=(0.0, 2.3, 2.8),
        weapons={"auto_cannon": ((0.0, 2.077, 3.88), [((0.0, 2.077, 3.88), 0.3)]),
                 "ag_missile": ((0.0, 1.70, -4.21), [((4.30, 1.70, -4.21), 0.1), ((-4.30, 1.70, -4.21), 0.1)]),
                 "ir_missile": ((0.0, 1.40, -3.13), [((-6.25, 1.40, -3.13), 0.1), ((-6.97, 1.40, -3.13), 0.1)]),
                 "bomb": ((0.0, 1.20, -3.90), [((2.0, 1.10, -3.9), 0.1), ((-2.0, 1.10, -3.9), 0.1),
                                               ((0.65, 1.35, -3.9), 0.1), ((-0.65, 1.35, -3.9), 0.1)]),
                 "rocket": ((0.0, 1.50, -4.40), [((5.43, 1.50, -4.40), 0.3), ((-5.43, 1.50, -4.40), 0.3)]),
                 "decoy_flare": ((0.0, 2.2, -12.0), [((0.5, 2.2, -12.0), 0.1), ((-0.5, 2.2, -12.0), 0.1)])},
        points={"seat": (0.0, 2.80 + 1.2, 0.0), "nozzle": (1.6, 3.9, -10.6), "wingtip": (9.8, 2.3, -4.6)}),
    # span 13.84 -> 14.8 (real 14.7), length 20.5 -> 21.9 (real 21.9)
    "su33": dict(
        src="su-33", sha1="b7f7ed89bda225a3091609a8beff461baec52576", texture="su-33", scale=1.07, ground=0.30,
        canopy=("$canopy0", (0.0, 3.55, 5.46), (-1, 0, 0), 60),
        gear=[("gear_nose", "$lg0", (0.0, 1.70, 5.41), (-1, 0, 0), 100, None, 0, False),
              ("nose_door", "$lg1", (0.0, 1.63, 4.57), (-1, 0, 0), 90, None, 0, False),
              ("gear_l", "$lg2", (1.67, 2.04, -0.04), (-1, 0, 0), 90, (0, -1, 0), 90, False),
              ("gear_r", "$lg3", (-1.67, 2.04, -0.04), (-1, 0, 0), 90, (0, 1, 0), 90, False)],
        bays=[], stores=[f"$weapon{i}" for i in range(4)], wheels={"$lg0": "auto", "$lg2": "auto", "$lg3": "auto"},
        moving=[dict(name="wing_fold_r", part="$wing0", pivot=(-2.85, 2.00, -2.20), axis=(0, -0.07, -1), angle=110, drive="on:wings"),
                dict(name="wing_fold_l", part="$wing1", pivot=(2.85, 2.00, -2.20), axis=(0, 0.07, 1), angle=110, drive="on:wings"),
                dict(name="stab_fold_r", part="$wing2", pivot=(-2.83, 1.85, -4.63), axis=(0, 0, -1), angle=110, drive="on:wings"),
                dict(name="stab_fold_l", part="$wing3", pivot=(2.83, 1.85, -4.63), axis=(0, 0, 1), angle=110, drive="on:wings")],
        sight=(0.0, 3.1, 7.2),
        weapons={"auto_cannon": ((-1.15, 2.11, 5.77), [((-1.15, 2.11, 5.77), 0.3)]),
                 "as_missile": ((0.0, 0.35, -1.34), [((1.01, 0.35, -1.34), 0.1), ((-1.01, 0.35, -1.34), 0.1)]),
                 "ar_missile": ((0.0, 1.66, -0.57), [((2.58, 1.66, -0.57), 0.1), ((-2.58, 1.66, -0.57), 0.1)]),
                 "ir_missile": ((0.0, 1.39, -3.17), [((5.87, 1.39, -3.17), 0.1), ((-5.87, 1.39, -3.17), 0.1)]),
                 "decoy_flare": ((0.0, 2.3, -7.0), [((0.5, 2.3, -7.0), 0.1), ((-0.5, 2.3, -7.0), 0.1)])},
        points={"seat": (0.0, 2.11 + 1.2, 7.21), "nozzle": (0.75, 2.1, -7.7), "wingtip": (6.9, 2.0, -2.0)}),
    # span 17.78 -> 15.6 (real 15.2-16.7), length 25.9 -> 22.8 (real 22.6)
    "su47": dict(
        src="su-47", sha1="6ccf3f50951836953cf4315d85b0e8234c4e649d", texture="su-47", scale=0.88, ground=0.36,
        canopy=("$canopy", (0.0, 3.71, -0.52), (-1, 0, 0), 45),
        gear=[("gear_nose", "$lg0", (0.0, 1.74, 0.10), (-1, 0, 0), 94, None, 0, False),
              ("gear_l", "$lg1", (0.0, 2.05, -8.37), (-1, 0, 0), 95, (0, 0, 1), -20, False),
              ("gear_r", "$lg2", (0.0, 2.05, -8.37), (-1, 0, 0), 95, (0, 0, 1), 20, False),
              ("nose_door", "$lg3", (-0.44, 1.53, -0.24), (0, 0, -1), 89.95, None, 0, True),
              ("main_door_l", "$lg4", (1.86, 2.19, -5.61), (0, 0, 1), 65, None, 0, True),
              ("main_door_r", "$lg5", (-1.86, 2.19, -5.61), (0, 0, -1), 65, None, 0, True),
              ("nose_flap", "$lg6", (0.0, 1.97, 0.93), (1, 0, 0), 45, None, 0, False)],
        bays=[("bay_door_0", "$wb0", (0.47, 1.60, -3.04), (0, 0, 1), 90),
              ("bay_door_1", "$wb1", (-0.47, 1.60, -3.04), (0, 0, -1), 90)],
        stores=["$weapon0", "$weapon1"], wheels={"$lg0": "auto", "$lg1": "auto", "$lg2": "auto"},
        sight=(0.0, 3.1, 0.6),
        weapons={"auto_cannon": ((1.22, 3.15, -2.52), [((1.22, 3.15, -2.52), 0.3)]),
                 "ir_missile": ((0.0, 1.00, -3.52), [((0.21, 1.00, -3.52), 0.1), ((-0.21, 1.00, -3.52), 0.1)]),
                 "ar_missile": ((0.0, 1.00, -3.52), [((0.21, 1.00, -3.52), 0.1), ((-0.21, 1.00, -3.52), 0.1)]),
                 "decoy_flare": ((0.0, 2.4, -14.0), [((0.5, 2.4, -14.0), 0.1), ((-0.5, 2.4, -14.0), 0.1)])},
        points={"seat": (0.0, 2.16 + 1.2, 0.57), "nozzle": (0.85, 2.3, -14.6), "wingtip": (8.8, 2.4, -7.5)}),
    # span 15.52 -> 13.7 (real 13.6), length 21.47 -> 18.9 (real 18.9)
    "f22a": dict(
        src="f22a", sha1="351887758c255ccabd1159e06bde008b9b40c0c2", texture="f22a", scale=0.88, ground=0.30,
        canopy=("$canopy", (0.0, 2.93, -1.42), (-1, 0, 0), 50),
        gear=[("gear_nose", "$lg0", (0.0, 1.33, -1.66), (-1, 0, 0), 100, None, 0, False),
              ("gear_l", "$lg1", (1.10, 1.07, -8.56), (0, 0, 1), 102, None, 0, False),
              ("gear_r", "$lg2", (-1.10, 1.07, -8.56), (0, 0, 1), -102, None, 0, False)],
        bays=[("bay_door_0", "$wb0", (1.29, 1.04, -2.64), (0.0, -0.025, 1.0), 80),
              ("bay_door_1", "$wb1", (0.24, -0.25, -2.64), (0.0, -0.031, 1.0), -56),
              ("bay_door_2", "$wb2", (-1.29, 1.04, -2.64), (0.0, -0.025, 1.0), -80),
              ("bay_door_3", "$wb3", (-0.24, -0.25, -2.64), (0.0, -0.031, 1.0), 56),
              ("bay_door_4", "$wb4", (2.05, 1.78, -5.14), (0, 0, 1), 90),
              ("bay_door_5", "$wb5", (1.64, 1.16, -5.16), (0, 0, 1), -90),
              ("bay_door_6", "$wb6", (-2.05, 1.78, -5.14), (0, 0, 1), -90),
              ("bay_door_7", "$wb7", (-1.64, 1.16, -5.16), (0, 0, 1), 90),
              ("bay_door_8", "$wb8", (1.41, 1.78, -5.74), (0.578, 1.0, 0.0), 7),
              ("bay_door_9", "$wb9", (-1.41, 1.78, -5.74), (-0.578, 1.0, 0.0), -7)],
        stores=[f"$weapon{i}" for i in range(10)], wheels={"$lg0": "Fwheel", "$lg1": "Lwheel", "$lg2": "Rwheel"},
        moving=[dict(name="nozzle_top", part="$throttle0", pivot=(0.0, 2.43, -13.29), axis=(1, 0, 0), angle=20, drive="throttle"),
                dict(name="nozzle_bottom", part="$throttle1", pivot=(0.0, 1.91, -13.29), axis=(1, 0, 0), angle=-20, drive="throttle")],
        sight=(0.0, 3.0, 0.0),
        weapons={"auto_cannon": ((-1.74, 2.45, -5.56), [((-1.74, 2.45, -5.56), 0.3)]),
                 "ir_missile": ((0.0, 1.53, -6.72), [((1.72, 1.53, -6.72), 0.1), ((-1.72, 1.53, -6.72), 0.1)]),
                 "ar_missile": ((0.0, 1.27, -7.28), [((0.84, 1.27, -7.28), 0.1), ((-0.84, 1.27, -7.28), 0.1)]),
                 "guided_bomb": ((0.0, 1.18, -6.47), [((0.37, 1.18, -5.54), 0.1), ((-0.37, 1.18, -5.54), 0.1),
                                                      ((0.37, 1.18, -7.41), 0.1), ((-0.37, 1.18, -7.41), 0.1)]),
                 "decoy_flare": ((0.0, 2.0, -13.0), [((0.5, 2.0, -13.0), 0.1), ((-0.5, 2.0, -13.0), 0.1)])},
        points={"seat": (0.0, 2.01 + 1.2, 0.0), "nozzle": (0.73, 2.06, -13.9), "wingtip": (7.7, 2.3, -10.5)}),
    # span 16.1 -> 13.7 (real 13.9 unswept), length 19.63 -> 16.7 (real 16.7)
    "tornado": dict(
        src="tornado-gr4", sha1="6a9e4ba5ab418d93f6c3d2f21a7d82c2764c23bb", texture="tornado-gr4", scale=0.85,
        ground=0.41, canopy=("$canopy0", (0.0, 3.22, -2.66), (-1, 0, 0), 70),
        gear=[("gear_nose", "$lg0", (0.0, 1.21, 0.20), (1, 0, 0), 100, None, 0, False),
              ("gear_r", "$lg1", (-1.46, 1.98, -5.99), (1, 0, 0), 90, (0, 0, 1), 30, False),
              ("gear_l", "$lg2", (1.46, 1.98, -5.99), (1, 0, 0), 90, (0, 0, -1), 30, False)],
        bays=[], stores=[f"$weapon{i}" for i in range(12)], wheels={"$lg0": "auto", "$lg1": "auto", "$lg2": "auto"},
        # Pylons counter-turn: stores stay aligned with the airflow (AddPartPylon).
        moving=[dict(name="wing_l", part="$wing0", pivot=(1.50, 2.50, -4.57), axis=(0, 1, 0), angle=35, drive="sweep:wing_l:-35",
                     children=[dict(name="pylon_l0", part="$wing0_pylon0", pivot=(6.69, 2.50, -7.18), axis=(0, -1, 0), angle=35),
                               dict(name="pylon_l1", part="$wing0_pylon1", pivot=(3.92, 2.50, -6.34), axis=(0, -1, 0), angle=35)]),
                dict(name="wing_r", part="$wing1", pivot=(-1.50, 2.50, -4.57), axis=(0, -1, 0), angle=35, drive="sweep:wing_r:35",
                     children=[dict(name="pylon_r0", part="$wing1_pylon0", pivot=(-6.69, 2.50, -7.18), axis=(0, 1, 0), angle=35),
                               dict(name="pylon_r1", part="$wing1_pylon1", pivot=(-3.92, 2.50, -6.34), axis=(0, 1, 0), angle=35)])],
        sight=(0.0, 2.0, 3.5),
        weapons={"auto_cannon": ((0.0, 1.23, 1.78), [((0.59, 1.23, 1.78), 0.3), ((-0.59, 1.23, 1.78), 0.3)]),
                 "rocket": ((0.0, 1.51, -5.52), [((6.70, 1.51, -5.52), 0.3), ((-6.70, 1.51, -5.52), 0.3)]),
                 "ir_missile": ((0.0, 1.95, -4.11), [((3.53, 1.95, -4.11), 0.1), ((-3.53, 1.95, -4.11), 0.1)]),
                 "bomb": ((0.0, 0.70, -4.28), [((x, y, z), 0.1) for z in (-2.74, -5.81)
                                               for x, y in ((-0.40, 0.56), (0.40, 0.56), (0.59, 0.87), (0.20, 0.87),
                                                            (-0.20, 0.87), (-0.59, 0.87))]),
                 "decoy_flare": ((0.0, 2.2, -12.5), [((0.5, 2.2, -12.5), 0.1), ((-0.5, 2.2, -12.5), 0.1)])},
        points={"seat": (0.0, 1.65 + 1.2, 0.34), "seat2": (0.0, 1.90 + 1.2, -1.59), "nozzle": (0.55, 2.2, -14.6),
                "wingtip": (8.0, 2.5, -7.5)}),
    # 1:1 already: length 72.15 (real 75.3 C-5M / 72.1 C-5A), span 68.2 (real 67.9)
    # $lg<n> numbering = AddPartSlideRotLG/LG/LGHatch/LGRev in file order; hatches ($lg3/4) left in the body.
    "c5": dict(
        src="c5", sha1="5312ab5f104bf09a37845aab69d172ed107efef4", texture="c5", scale=1.0, ground=0.30,
        canopy=None,
        gear=[("gear_nose", "$lg2", (0.0, 1.4782, -2.7186), (1, 0, 0), 86, None, 0, False),
              ("fairing_r", "$lg9", (-3.7442, 3.6430, 0.0), (0, 0, 1), -30, None, 0, True),
              ("fairing_l", "$lg10", (3.7442, 3.6430, 0.0), (0, 0, -1), -30, None, 0, True)],
        slide_gear=[dict(name="nose_door_l", part="$lg0", slide=(-0.8432, 0.0, 0.0)),
                    dict(name="nose_door_r", part="$lg1", slide=(0.8432, 0.0, 0.0))]
                   + [dict(name=f"strut_{k}", part=f"$lg{5 + k}", slide=(0.0, 1.11, 0.0)) for k in range(4)]
                   + [dict(name=f"bogie_{side}{z}_{k}", part=f"$lg{11 + 6 * (z == 1) + 3 * (side == 'l') + k}",
                           slide=((0.0, 1.131, 0.0), (0.6442 if side == "l" else -0.6442, 2.1464, 0.35), (-2.0, 1.131, 0.6))[k],
                           pivot=(3.337 if side == "l" else -3.337, 0.2079, (-22.8, -28.26)[z]),
                           axis=(0.0, 1.0 if side == "l" else -1.0, 0.0), angle=-90)
                      for z in (0, 1) for side in ("r", "l") for k in range(3)]
                   + [dict(name="nose_wheels_l", part="$lg24", slide=(-0.8432, 1.3, -1.8)),
                      dict(name="nose_wheels_r", part="$lg23", slide=(0.8432, 1.3, -1.8))],
        bays=[], stores=[], body_split={"airframe": ["obj1", "engine"]},
        wheels={f"$lg{n}": "auto" for n in (11, 12, 14, 15, 17, 18, 20, 21, 23, 24)},
        moving=[dict(name=f"fan_{k}", part=f"$blade{k}", pivot=p, axis=a, angle=0, drive="spin:0.06")
                for k, (p, a) in enumerate([((12.0203, 4.1816, -18.57), (0, 0, -1)), ((-12.0729, 4.148, -18.57), (0, 0, 1)),
                                            ((18.6008, 3.4242, -18.57), (0, 0, -1)), ((-18.6008, 3.4242, -18.57), (0, 0, 1))])]
               + [dict(name="visor", part="$wing0", pivot=(0.0, 6.51, 0.0), axis=(-1, 0, 0), angle=90, drive="on:cargo_doors"),
                  dict(name="nose_ramp", part="$wing1", pivot=(0.0, 2.01, -1.619), axis=(1, 0, 0), angle=20, drive="on:cargo_doors"),
                  dict(name="nose_ramp_ext", part="$wing5", pivot=(0.0, -2.0, -1.0), axis=(-1, 0, 0), angle=-48, drive="on:cargo_doors"),
                  dict(name="clamshell_l", part="$wing3", pivot=(1.2585, 5.5188, -53.7885), axis=(1.4513, -3.3656, 14.3613),
                       angle=120, drive="on:cargo_doors"),
                  dict(name="clamshell_r", part="$wing4", pivot=(-1.2585, 5.5188, -53.7885), axis=(-1.4513, -3.3656, 14.3613),
                       angle=-120, drive="on:cargo_doors"),
                  dict(name="ramp_ext", part="$wing6", pivot=(0.0, -4.0, -36.0), axis=(1, 0, 0), angle=-39, drive="on:cargo_doors"),
                  # Server-side hinge (linked_hinge `ramp`, open_x_rot -29): its deck piece is the drive-in slope.
                  # Rest rotation on a collider frame FORBIDDEN: GemRender draws R D R^-1, colliders R D => ramp drawn down, collided up.
                  dict(name="ramp", part="$wing2", pivot=(0.0, 1.81, -34.9667), axis=(1, 0, 0), angle=20, drive="xrot:ramp:1")],
        # Hollow hold (MCHeli units): floor y 2.1 (EMB4 racks), 5.8 wide, 4.2 tall, z 3 .. -35; enter over the ramp.
        hull_boxes=[("floor", "vehicle_body", (-4.3, 0.65, -35.0), (4.3, 1.8, 3.0), True),
                    ("wall l", "vehicle_body", (2.95, 1.8, -35.0), (4.3, 6.3, 3.0), False),
                    ("wall r", "vehicle_body", (-4.3, 1.8, -35.0), (-2.95, 6.3, 3.0), False),
                    ("roof", "vehicle_body", (-4.3, 6.3, -35.0), (4.3, 9.0, 3.0), False),
                    ("nose", "vehicle_body", (-3.3, 1.8, 3.0), (3.3, 8.6, 5.44), False),
                    ("tail", "vehicle_body", (-3.5, 6.3, -61.3), (3.5, 9.3, -35.0), False),
                    # Collider extras world: false (patched in the glTF; this writer has no key): lip under the ground.
                    ("ramp", "ramp", (-2.9, 1.65, -39.5), (2.9, 1.8, -35.0), True)],
        sight=(0.0, 7.0, 3.0), weapons={},
        points={"seat": (0.62, 6.8 + 1.2, 2.4), "seat2": (-0.62, 6.8 + 1.2, 2.4), "nozzle": (12.0, 4.2, -22.0),
                "wingtip": (34.0, 7.5, -30.0)}),
    # span 10.76 -> 9.3 (real 9.25), length 16.7 -> 14.4 (real 14.1)
    "av8b": dict(
        src="harrier", sha1="1ac5b846ebb2cbda483fe1e9514900805acfaa92", texture="harrier", scale=0.865, ground=0.30,
        canopy=("slide", "$canopy", (0.0, 0.3, -0.78)),
        gear=[("gear_nose", "$lg0", (0.0, 1.00, -1.30), (1, 0, 0), 90, None, 0, False),
              ("gear_main", "$lg1", (0.0, 1.35, -5.45), (-1, 0, 0), 90, None, 0, False),
              ("outrigger", "$lg2", (0.0, 1.80, -6.60), (1, 0, 0), 90, None, 0, False)],
        bays=[], stores=[f"$weapon{i}" for i in range(8)], wheels={"$lg0": "auto", "$lg1": "auto", "$lg2": "auto"},
        moving=[dict(name=f"nozzle_{k}", part=f"$nozzle{k}", pivot=p, axis=(-1, 0, 0), angle=0, drive="thrust:thrust")
                for k, p in enumerate([(0.93, 1.70, -2.91), (-0.93, 1.70, -2.91), (0.69, 1.70, -5.39), (-0.69, 1.70, -5.39)])],
        sight=(0.0, 2.3, 2.2),
        weapons={"auto_cannon": ((0.0, 1.1, -2.0), [((0.0, 1.1, -2.0), 0.3)]),
                 "ir_missile": ((0.0, 1.18, -5.5), [((4.85, 1.18, -5.5), 0.1), ((-4.85, 1.18, -5.5), 0.1)]),
                 "ag_missile": ((0.0, 1.22, -5.2), [((3.94, 1.22, -5.2), 0.1), ((-3.94, 1.22, -5.2), 0.1)]),
                 "bomb": ((0.0, 1.14, -4.2), [((2.57, 1.14, -4.2), 0.1), ((-2.57, 1.14, -4.2), 0.1),
                                              ((2.12, 1.14, -4.2), 0.1), ((-2.12, 1.14, -4.2), 0.1)]),
                 "decoy_flare": ((0.0, 2.0, -9.0), [((0.5, 2.0, -9.0), 0.1), ((-0.5, 2.0, -9.0), 0.1)])},
        points={"seat": (0.0, 1.57 + 1.2, 0.22), "nozzle": (0.0, 2.2, -12.4), "wingtip": (5.3, 2.6, -6.6)}),
}


class Jet:
    def __init__(self, jet_id, spec):
        self.id, self.spec = jet_id, spec
        path = os.path.join(SRC, "models", "planes", f"{spec['src']}.mqo")
        digest = hashlib.sha1(open(path, "rb").read()).hexdigest()
        if digest != spec["sha1"]:
            raise SystemExit(f"{path}: sha1 {digest} != EMB4 1.0.3 release")
        self.parts = mqo.parts(mqo.read(path))

    def T(self, p):
        return (np.asarray(p, dtype=float) + [0.0, self.spec["ground"], 0.0]) * self.spec["scale"]

    def bounds(self, part):
        pts = np.vstack([self.T(o.verts) for o in self.parts[part] if len(o.verts)])
        return pts.min(axis=0), pts.max(axis=0)


def frame(axis):
    """Rotation with +X = axis."""
    x = np.asarray(axis, dtype=float)
    x /= np.linalg.norm(x)
    y = np.array([0.0, 1.0, 0.0]) if abs(x[1]) < 0.9 else np.array([0.0, 0.0, 1.0])
    y -= x * (x @ y)
    y /= np.linalg.norm(y)
    return np.column_stack([x, y, np.cross(x, y)])


def tyres(j, part):
    """Tyres fused into a gear object: connected pieces round in (y, z), narrower in x, touching the part's floor."""
    floor = min(o.verts[:, 1].min() for o in j.parts[part] if len(o.verts))
    found, rest = [], []
    for o in j.parts[part]:
        pieces = mqo.pieces(o)
        keep = []
        for piece in pieces:
            used = sorted({i for idx, _ in piece.tris for i in idx})
            lo, hi = o.verts[used].min(axis=0), o.verts[used].max(axis=0)
            dx, dy, dz = hi - lo
            if dy > 0.3 and abs(dy - dz) < 0.2 * max(dy, dz) and dx < 0.8 * dy and lo[1] - floor < 0.05:
                found.append(piece)
            else:
                keep.append(piece)
        rest += keep
    if not found:
        raise SystemExit(f"{j.id} {part}: no tyre found")
    return found, rest


SLIDE_RADIUS = 20.0


def slide(j, part, offset):
    """GemRender poses rotation only: a slide by `offset` = |offset|/R radians about an axis R away, normal to it."""
    lo, hi = j.bounds(part)
    d = np.asarray(offset, float) * j.spec["scale"]
    length = np.linalg.norm(d)
    u = d / length
    n = np.cross(u, [1.0, 0.0, 0.0])
    n = n / np.linalg.norm(n) if np.linalg.norm(n) > 1e-6 else np.array([0.0, 1.0, 0.0])
    # a x (C - P) || u with C - P = -R n => a = u x n; pivot in MCHeli units for hinge_of.
    axis = np.cross(u, n)
    pivot = ((lo + hi) / 2 + SLIDE_RADIUS * n) / j.spec["scale"] - [0.0, j.spec["ground"], 0.0]
    return pivot, axis, float(np.degrees(length / SLIDE_RADIUS))


def canopy(j):
    c = j.spec["canopy"]
    if c[0] == "slide":
        pivot, axis, angle = slide(j, c[1], c[2])
        return {"part": c[1], "pivot": pivot, "axis": axis, "angle": round(angle, 3)}
    return {"part": c[0], "pivot": c[1], "axis": c[2], "angle": c[3]}


def hinge_of(j, m):
    """(pivot, axis, axis2) of a moving entry; `slide` entries resolved to their far pivot."""
    if "slide" in m:
        pivot, axis, angle = slide(j, m["part"], m["slide"])
        m.update(pivot=pivot, axis=axis, angle=round(angle, 3))
    return m["pivot"], m["axis"], m.get("axis2")


def display(j):
    s = j.spec
    w = mqo.GltfWriter(f"{NS}:models/aircraft/{j.id}/{j.id}.bin", f"{NS}:textures/aircraft/{j.id}/{j.id}.png",
                       "tools/stage_emb4_jet.py")
    used = set()

    def mesh_of(name, parts, pivot, rest=np.eye(3), objs=None):
        pos, nrm, uv = [], [], []
        used.update(parts)
        for o in objs if objs is not None else [o for part in parts for o in j.parts[part]]:
            if o.tris:
                p, n, t = mqo.triangles(o, j.T)
                pos.append((p - pivot) @ rest)
                nrm.append(n @ rest)
                uv.append(t)
        return w.mesh(name, np.vstack(pos), np.vstack(nrm), np.vstack(uv))

    root = w.node(j.id)
    wheels = {}

    def hinged(name, part, pivot, axis, axis2=None, parent=None):
        """parent: (node, pivot, rest frame) of the node this hinge rides on; default root."""
        p = j.T(pivot)
        r1 = frame(axis)
        up, up_p, up_r = parent or (root, np.zeros(3), np.eye(3))
        if s["wheels"].get(part) == "auto":
            tyre, leg = tyres(j, part)
        else:
            tyre = [o for o in j.parts[part] if o.name == s["wheels"].get(part)]
            leg = [o for o in j.parts[part] if o not in tyre]
        hinge = w.node(f"{name}_hinge", up_r.T @ (p - up_p), parent=up, rotation=quaternion(up_r.T @ r1))
        solid = any(o.tris for o in leg)
        if axis2 is None:
            rest, carrier = r1, w.node(name, mesh=mesh_of(name, [part], p, r1, leg) if solid else None, parent=hinge)
        else:
            r2 = frame(axis2)
            fold = w.node(name, parent=hinge)
            hinge2 = w.node(f"{name}_hinge2", parent=fold, rotation=quaternion(r1.T @ r2))
            rest, carrier = r2, w.node(f"{name}_fold", mesh=mesh_of(f"{name}_fold", [part], p, r2, leg) if solid else None,
                                       parent=hinge2)
        for k, (centre, radius, objs) in enumerate(mqo.axles(tyre, j.T)):
            wheels[f"{name}_wheel{k}"] = radius
            axle = w.node(f"{name}_wheel{k}_hinge", rest.T @ (centre - p), parent=carrier, rotation=quaternion(rest.T))
            w.node(f"{name}_wheel{k}", mesh=mesh_of(f"{name}_wheel{k}", [], centre, objs=objs), parent=axle)
        return carrier, p, rest

    if s["canopy"]:
        c = canopy(j)
        hinged("canopy", c["part"], *hinge_of(j, c))
    for name, part, pivot, axis, _, axis2, _, _ in s["gear"]:
        hinged(name, part, pivot, axis, axis2)
    for name, part, pivot, axis, _ in s["bays"]:
        hinged(name, part, pivot, axis)
    for m in s.get("moving", []):
        carrier = hinged(m["name"], m["part"], *hinge_of(j, m))
        for c in m.get("children", []):
            hinged(c["name"], c["part"], *hinge_of(j, c), parent=carrier)
    for g in s.get("slide_gear", []):
        pivot, axis, g["slide_angle"] = slide(j, g["part"], g["slide"])
        if g.get("pivot") is None:
            hinged(g["name"], g["part"], pivot, axis)
            continue
        p, r = j.T(pivot), frame(axis)
        outer = w.node(f"{g['name']}_slide_hinge", p, parent=root, rotation=quaternion(r))
        carrier = w.node(f"{g['name']}_slide", parent=outer)
        hinged(g["name"], g["part"], g["pivot"], g["axis"], parent=(carrier, p, r))
    if s["stores"]:
        w.node("stores", mesh=mesh_of("stores", s["stores"], np.zeros(3)), parent=root)
    body = [q for q in j.parts if q not in used]
    split = s.get("body_split", {})
    moved = {n for names in split.values() for n in names}
    for name, names in split.items():
        w.node(name, mesh=mesh_of(name, [], np.zeros(3), objs=[o for q in body for o in j.parts[q] if o.name in names]),
               parent=root)
    w.node("body", mesh=mesh_of("body", body, np.zeros(3), objs=[o for q in body for o in j.parts[q] if o.name not in moved]),
           parent=root)
    models = os.path.join(PACK, "assets", NS, "models", "aircraft", j.id)
    os.makedirs(models, exist_ok=True)
    w.write(os.path.join(models, f"{j.id}.gltf"), os.path.join(models, f"{j.id}.bin"),
            {"staged_by": "tools/stage_emb4_jet.py", "source": f"EMB4 MCHeli 1.0.3 planes/{s['src']}",
             "scale": s["scale"]})
    return body, wheels


def controller(vid, wheels):
    """`<vid>_controller`: script `<vid>` + wheel spin ({node: radius | (radius, axis, sign)}; default x, +)."""
    graph = {"type": "script", "function": "updateBones"}
    if wheels:
        bindings = []
        for name, spec in wheels.items():
            r, axis, sign = spec if isinstance(spec, tuple) else (spec, "x", 1)
            binding = {"bones": [name], "source": "wheel_rotation", "axis": axis, "param": round(float(r), 3)}
            if sign < 0:
                binding["multiplier"] = -1.0
            bindings.append(binding)
        graph = {"type": "merge", "inputs": [graph, {"type": "bone_binding", "special_bindings": bindings}]}
    with open(os.path.join(PACK, "assets", NS, "animation_controllers", f"{vid}_controller.json"), "w") as f:
        json.dump({"name": f"{vid}_controller", "script": f"{NS}:{vid}", "parameters": {}, "graph": graph}, f,
                  indent=4)
        f.write("\n")


def script(j):
    """landing_gear on == gear up; `isPartOn` blocks -> clips (script_clips.bake)."""
    s = j.spec
    up = [f'        builder.setRotation("{n}", {a}, 0, 0);' + (f'\n        builder.setRotation("{n}_fold", {a2}, 0, 0);'
                                                                if ax2 else "")
          for n, _, _, _, a, ax2, a2, rev in s["gear"] if not rev]
    down = [f'        builder.setRotation("{n}", {a}, 0, 0);' for n, _, _, _, a, _, _, rev in s["gear"] if rev]
    bays = [f'        builder.setRotation("{n}", {a}, 0, 0);' for n, _, _, _, a in s["bays"]]
    for g in s.get("slide_gear", []):
        slide_node = g["name"] if g.get("pivot") is None else f"{g['name']}_slide"
        up.append(f'        builder.setRotation("{slide_node}", {round(g["slide_angle"], 3)}, 0, 0);')
        if g.get("pivot") is not None:
            up.append(f'        builder.setRotation("{g["name"]}", {g["angle"]}, 0, 0);')
    lines = [f"// EMB4 {s['src']} (tools/stage_emb4_jet.py).",
             "function updateBones(context) {", "    var builder = createPoseBuilder();",
             '    if (context.isPartOn("landing_gear")) {', *up, "    } else {", *down, "    }"]
    if bays:
        lines += ['    if (context.isPartOn("bay_doors")) {', *bays, "    }"]
    if s["canopy"]:
        lines += ['    if (context.isPartOn("canopy")) {', f'        builder.setRotation("canopy", {canopy(j)["angle"]}, 0, 0);',
                  "    }"]
    for m in s.get("moving", []):
        lines += drive(m)
    lines += ["    return builder;", "}", ""]
    text = "\n".join(lines)
    with open(os.path.join(PACK, "assets", NS, "scripts", f"{j.id}.js"), "w") as f:
        f.write(text)


def drive(m):
    """Script lines posing a moving entry and its children. on:<part> | sweep:<part>:<full yaw> | throttle |
    thrust:<part> (nozzle angle = -part pitch) | xrot:<part>:<sign> | spin:<degrees per power unit>."""
    kind, *arg = m["drive"].split(":")
    nodes = [m] + m.get("children", [])
    if kind == "on":
        return [f'    if (context.isPartOn("{arg[0]}")) {{'] + [
            f'        builder.setRotation("{n["name"]}", {n["angle"]}, 0, 0);' for n in nodes] + ["    }"]
    if kind == "sweep":
        f = f'context.getPartYRot("{arg[0]}") / {arg[1]}'
        return [f'    builder.setRotation("{n["name"]}", {n["angle"]} * {f}, 0, 0);' for n in nodes]
    if kind == "throttle":
        return [f'    builder.setRotation("{n["name"]}", {n["angle"]} * Math.min(1, context.getThrottleLevel() / 100), 0, 0);'
                for n in nodes]
    if kind == "thrust":
        return [f'    builder.setRotation("{n["name"]}", -context.getPartXRot("{arg[0]}"), 0, 0);' for n in nodes]
    if kind == "xrot":
        return [f'    builder.setRotation("{n["name"]}", {arg[1]} * context.getPartXRot("{arg[0]}"), 0, 0);' for n in nodes]
    if kind == "spin":
        key = f'"spin_{m["name"]}"'
        return [f'    var {m["name"]} = (context.getFloat({key}, 0) + context.getPower() * {arg[0]}) % 360;',
                f'    context.setFloat({key}, {m["name"]});',
                f'    builder.setRotation("{m["name"]}", {m["name"]}, 0, 0);']
    raise SystemExit(f"unknown drive {m['drive']}")


def structure(j):
    s = j.spec
    gear = [cube(*j.bounds(part)) for _, part, _, _, _, _, _, rev in s["gear"] if not rev]
    gear += [cube(*j.bounds(g["part"])) for g in s.get("slide_gear", []) if s["wheels"].get(g["part"])]
    bones = [bone("vehicle_body", []), bone("landing_gear", gear),
             bone("sighting_system", [], pivot=j.T(s["sight"]))]
    if s["canopy"]:
        bones.append(bone("canopy", [], pivot=j.T(canopy(j)["pivot"])))
    if s["bays"]:
        bones.append(bone("bay_doors", []))
    for m in s.get("moving", []):
        kind, *arg = m["drive"].split(":")
        hinge_of(j, m)
        if kind in ("sweep", "xrot"):
            bones.append(bone(m["name"], [], pivot=j.T(m["pivot"])))
        elif kind in ("on", "thrust") and not any(b["name"] == arg[0] for b in bones):
            bones.append(bone(arg[0], [], pivot=j.T(m["pivot"]) if kind == "thrust" else (0, 0, 0)))
    for name, (pivot, _) in s["weapons"].items():
        bones.append(bone(name, [], pivot=j.T(pivot)))
        bones.append(bone(f"{name}_barrel", [], parent=name, pivot=j.T(pivot)))
    model = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.unknown", "texture_width": 16, "texture_height": 16},
        "bones": bones}]}
    path = os.path.join(PACK, "data", NS, "models", "bedrock", "vehicle", f"{j.id}.structure.json")
    with open(path, "w") as f:
        json.dump(model, f, indent=1)
    if s.get("hull_boxes"):
        # Hand-placed locked boxes (hollow hold); gen_hulls keeps locked pieces.
        hull_path = os.path.join(PACK, "data", NS, "models", "hull", "vehicle", f"{j.id}.hull.json")
        old = json.load(open(hull_path))["pieces"] if os.path.exists(hull_path) else []
        pieces = [p for p in old if not (p.get("locked") and "from" in p and p.get("name", "").startswith("hold "))]
        for name, bone_name, lo, hi, deck in s["hull_boxes"]:
            piece = {"bone": bone_name, "name": f"hold {name}", "locked": True,
                     "from": [round(float(v), 4) for v in j.T(lo)], "to": [round(float(v), 4) for v in j.T(hi)]}
            if deck:
                piece["deck"] = True
            pieces.append(piece)
        with open(hull_path, "w") as f:
            json.dump({"format": 1, "pieces": pieces}, f, indent=1)
    strip_cubes.split(PACK, j.id)


def stage(jet_id):
    j = Jet(jet_id, JETS[jet_id])
    s = j.spec
    textures = os.path.join(PACK, "assets", NS, "textures", "aircraft", jet_id)
    os.makedirs(textures, exist_ok=True)
    shutil.copyfile(os.path.join(SRC, "textures", "planes", f"{s['texture']}.png"),
                    os.path.join(textures, f"{jet_id}.png"))
    models = os.path.join(PACK, "assets", NS, "models", "aircraft", jet_id)
    gltf, binary = os.path.join(models, f"{jet_id}.gltf"), os.path.join(models, f"{jet_id}.bin")
    kept = mqo.read_markers(gltf, binary)
    body, wheels = display(j)
    controller(j.id, wheels)
    script(j)
    structure(j)
    mqo.append_nodes(gltf, binary, kept)
    script_clips.bake(j.id, gltf, binary)
    print(f"== {jet_id} (body: {len(body)} parts)")
    for name, (pivot, bolts) in s["weapons"].items():
        p = j.T(pivot)
        print(f"  {name} pivot", np.round(p, 3).tolist(), "bolts",
              [(np.round(j.T(b) - p, 3).tolist(), round(length * s["scale"], 3)) for b, length in bolts])
    for name, p in s["points"].items():
        print(f"  {name}", np.round(j.T(p), 3).tolist())


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    for jet_id in sys.argv[1:] or JETS:
        stage(jet_id)


if __name__ == "__main__":
    main()
