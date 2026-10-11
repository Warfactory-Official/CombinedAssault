#!/usr/bin/env python3
"""Drive the dev client through the indirect fire board, one stage per screenshot.

Usage (from the combinedassault directory, with the client already launched):
  python3 tools/REDACTED scene     # platform, mortar, ammo, and the player in the seat
  python3 tools/REDACTED board     # hand laying, the stops, the ladder, a correction
  python3 tools/REDACTED mission   # a fire mission by coordinates, then fire it
  python3 tools/REDACTED shell     # load a rocket-assisted shell and read the reach again
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from naval_client import cmd, ready, shot, sh, window_id  # noqa: E402

# The mortar stands here, on a platform in clear air over the ocean world.
GUN = (0, 97, 0)
SEAT = (0, 97, 4)


def key(*keys, delay=0.25, repeat=1):
    wid = window_id()
    sh("xdotool", "windowfocus", wid)
    time.sleep(0.1)
    for _ in range(repeat):
        for name in keys:
            sh("xdotool", "key", "--window", wid, name)
            time.sleep(delay)


def scene():
    ready()
    cmd("/gamemode creative", "/difficulty peaceful", "/time set noon", "/weather clear",
        "/gamerule doDaylightCycle false", "/kill @e[type=combinedassault:none_vehicle]")
    cmd("/tp @p %d %d %d 180 0" % (GUN[0], GUN[1] + 4, GUN[2]),
        "/fill -8 %d -8 8 %d 8 minecraft:stone" % (GUN[1] - 1, GUN[1] - 1),
        "/tp @p %d %d %d 180 0" % GUN)
    cmd("/combinedassault summon combinedassault:m252a1 combinedassault:m252a1")
    time.sleep(1.5)
    cmd('/data merge entity @e[type=combinedassault:none_vehicle,limit=1] '
        '{Items:[{Slot:0b,id:"combinedassault:ammo_artillery",count:32}]}')
    # Stand off the gun and look down at it, then take the seat.
    cmd("/tp @p %d %d %d 180 18" % SEAT)
    time.sleep(0.8)
    wid = window_id()
    sh("xdotool", "windowfocus", wid)
    time.sleep(0.3)
    sh("xdotool", "click", "3")
    time.sleep(1.5)
    shot("mortar-seated")


def board():
    key("y")                       # off the sight
    time.sleep(1.0)
    shot("board-1-hand")
    key("semicolon", repeat=4)     # ladder round to the coarse degree rung
    key("Prior", repeat=4)         # and up past the bipod's ceiling
    time.sleep(1.5)
    shot("board-2-stop")
    key("Next", repeat=3)
    key("semicolon")               # back down the ladder, one rung at a time
    time.sleep(1.0)
    shot("board-3-ladder")
    key("semicolon", repeat=2)     # onto a rung measured in blocks
    time.sleep(0.5)
    shot("board-4-blocks")
    key("Prior", repeat=2)         # add a hundred blocks of range
    time.sleep(1.0)
    shot("board-5-corrected")
    key("period", repeat=3)        # and shift the fall of shot right
    time.sleep(1.0)
    shot("board-6-shifted")


def mission():
    key("n")
    time.sleep(1.0)
    wid = window_id()
    for value in ("0", str(GUN[1]), "300"):
        sh("xdotool", "key", "--window", wid, "Tab")
        time.sleep(0.2)
        # Not ctrl+a: the box takes the modifier as a letter and types it.
        sh("xdotool", "key", "--window", wid, "End")
        sh("xdotool", "key", "--window", wid, "--repeat", "24", "--repeat-delay", "25", "BackSpace")
        time.sleep(0.2)
        sh("xdotool", "type", "--window", wid, "--delay", "20", value)
        time.sleep(0.3)
    shot("board-7-typing")
    sh("xdotool", "key", "--window", wid, "Return")
    time.sleep(2.0)
    shot("board-8-mission")
    # A focused window is not enough for a click: it has to be the active one, and the button has to be
    # held long enough for a tick to see it down.
    sh("xdotool", "windowactivate", "--sync", wid)
    time.sleep(0.5)
    sh("xdotool", "mousedown", "1")
    time.sleep(0.4)
    sh("xdotool", "mouseup", "1")
    time.sleep(1.5)
    shot("board-9-splash")
    time.sleep(5)
    shot("board-10-inflight")


def chevron():
    """A laid bearing the mount has not caught up with yet, which is the only way the tape's edge shows."""
    key("n")
    time.sleep(1.0)
    wid = window_id()
    for value in ("300", str(GUN[1]), "0"):
        sh("xdotool", "key", "--window", wid, "Tab")
        time.sleep(0.2)
        sh("xdotool", "key", "--window", wid, "End")
        sh("xdotool", "key", "--window", wid, "--repeat", "24", "--repeat-delay", "25", "BackSpace")
        time.sleep(0.2)
        sh("xdotool", "type", "--window", wid, "--delay", "20", value)
        time.sleep(0.3)
    sh("xdotool", "key", "--window", wid, "Return")
    sh("xdotool", "key", "--window", wid, "F2")
    time.sleep(0.15)
    sh("xdotool", "key", "--window", wid, "F2")
    time.sleep(2.0)
    shot("board-15-chevron")


def shell():
    cmd('/data merge entity @e[type=combinedassault:none_vehicle,limit=1] '
        '{Items:[{Slot:0b,id:"wflib:shell_rap",count:16}]}')
    time.sleep(1.0)
    shot("board-11-rap-loaded")


if __name__ == "__main__":
    globals()[sys.argv[1] if len(sys.argv) > 1 else "scene"]()
