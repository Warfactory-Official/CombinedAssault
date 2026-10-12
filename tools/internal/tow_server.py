#!/usr/bin/env python3
"""Drive a dedicated server on flat land over RCON and run tow trials on it.

Usage (from the combinedassault directory):
  python3 tools/internal/tow_server.py start      # write server.properties for the land world and launch
  python3 tools/internal/tow_server.py lap        # the mixed ring: flat, washboard, ruts, rough, kerbs, a crest
  python3 tools/internal/tow_server.py flat       # the control: the same circle on level ground
  python3 tools/internal/tow_server.py rough      # a whole ring of whole-block noise
  python3 tools/internal/tow_server.py cable      # a truck towing a truck on a cable, over the mixed ring
  python3 tools/internal/tow_server.py all        # every scene above, with a summary
  python3 tools/internal/tow_server.py stop       # /stop and restore server.properties

The RCON socket only ever hears a command's own reply, so a trial is started and then polled: the
server keeps the verdict after the run ends and `tow trial report` hands it back.
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import naval_server as ns  # noqa: E402

# A server of its own, in its own directory and on its own ports. run/server is where a person
# sits, and a trial that takes it over kills the server that was already there: that has happened
# once, mid-session, to someone else's world.
ns.RUN = os.path.join(ns.ROOT, "run", "tow-server")
ns.PROPS = os.path.join(ns.RUN, "server.properties")
ns.BACKUP = ns.PROPS + ".tow-backup"
ns.PORT = 25596
ns.GAME_PORT = 25597
ns.TASK = "runTowServer"
ns.SERVER_JVM = "build/moddev/towServerRunVmArgs.txt"

# Bedrock, stone, then grass at y = -60, so a course is cut into dry land rather than into an ocean:
# a wheeled vehicle standing in water has its power cut, which would end every run at tick one.
ns.LEVEL = "tow_flats"
ns.GENERATOR = {"layers": [{"block": "minecraft:bedrock", "height": 1},
                           {"block": "minecraft:stone", "height": 3},
                           {"block": "minecraft:grass_block", "height": 1}],
                "biome": "minecraft:plains"}
ns.LOG = os.path.join(ns.ROOT, "run", "tow-server.log")

# One lap of a 44 block ring is 276 blocks; at a third of a block a tick that is about 14 seconds.
SCENES = {
    "solo": dict(course="lap", radius=44, laps=2, speed=0.35, mode="solo"),
    "solo-rough": dict(course="rough", radius=44, laps=2, speed=0.35, mode="solo"),
    "solo-washboard": dict(course="washboard", radius=44, laps=2, speed=0.35, mode="solo"),
    "lap": dict(course="lap", radius=44, laps=2, speed=0.35, mode="hitch"),
    "flat": dict(course="flat", radius=44, laps=2, speed=0.35, mode="hitch"),
    "rough": dict(course="rough", radius=44, laps=2, speed=0.35, mode="hitch"),
    "washboard": dict(course="washboard", radius=44, laps=2, speed=0.35, mode="hitch"),
    "tight": dict(course="lap", radius=26, laps=2, speed=0.3, mode="hitch"),
    "cable": dict(course="lap", radius=44, laps=2, speed=0.3, mode="cable"),
}

results = []


def clear(rcon):
    rcon.cmd("kill @e[type=!minecraft:player]")


def run(name, spec, timeout=420):
    rcon = ns.connect()
    clear(rcon)
    ns.forceload(rcon, -8, -8, 8, 8)
    started = rcon.cmd("combinedassault tow trial {course} {radius} {laps} {speed} {mode}".format(**spec))
    print(f"--- {name}: {started.strip()}")
    deadline = time.time() + timeout
    seen = ""
    while time.time() < deadline:
        time.sleep(5)
        report = rcon.cmd("combinedassault tow trial report") or ""
        if report != seen:
            for line in report.splitlines():
                if line and line not in seen:
                    print("   " + line)
            seen = report
        if "clean" in report.lower():
            break
    ok = "NOT CLEAN" not in seen and "clean" in seen.lower()
    results.append((ok, name))
    print(("PASS " if ok else "FAIL ") + name)
    clear(rcon)
    return seen


def summary():
    print("\n==== tow trials ====")
    for ok, what in results:
        print(("PASS " if ok else "FAIL ") + what)
    bad = [what for ok, what in results if not ok]
    print(f"{len(results) - len(bad)}/{len(results)} clean")
    return 0 if not bad else 1


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else "lap"
    if action == "start":
        ns.start()
    elif action == "stop":
        ns.stop()
    elif action == "all":
        for name, spec in SCENES.items():
            run(name, spec)
        sys.exit(summary())
    elif action in SCENES:
        run(action, SCENES[action])
        sys.exit(summary())
    else:
        raise SystemExit(f"unknown scene {action}; try {sorted(SCENES)} or start/stop/all")
