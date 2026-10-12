#!/usr/bin/env python3
"""Drive a dedicated server on a flat ocean over RCON: spawn ships, sail them, read them back.

Usage (from the combinedassault directory):
  python3 tools/naval_server.py start            # write server.properties for the ocean world and launch
  python3 tools/naval_server.py smoke            # spawn a patrol boat and print its status for a while
  python3 tools/naval_server.py carrier          # the Nimitz scenarios: float, sail, turn, profile, deck, ground
  python3 tools/naval_server.py trial            # the approach course: a maze of channels and shoals, with a crew aboard
  python3 tools/naval_server.py bar              # straight at a bed above the keel: taking the ground and coming off
  python3 tools/naval_server.py torpedo          # a WF-B torpedo into the carrier
  python3 tools/naval_server.py stop             # /stop and restore server.properties

Every scenario prints PASS/FAIL lines and the numbers they were judged on.
"""
import json
import os
import re
import socket
import struct
import subprocess
import sys
import time

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RUN = os.path.join(ROOT, "run", "server")
PROPS = os.path.join(RUN, "server.properties")
BACKUP = PROPS + ".naval-backup"
LOG = os.path.join(ROOT, "run", "naval-server.log")
HOST, PORT, PASSWORD = "127.0.0.1", 25598, "devrcon"
# The port the world itself is served on, and the gradle run that serves it. Both are overridden by
# a harness that wants a server of its own rather than this one.
GAME_PORT = 25565
TASK = "runServer"

LEVEL = "naval_sea"
# Flat layers start at y = -64: bedrock, 63 of stone, then 60 of water, so the bed is the face at y=0
# and the top water block is y=59, a source 8/9 high.
GENERATOR = {"layers": [{"block": "minecraft:bedrock", "height": 1},
                        {"block": "minecraft:stone", "height": 63},
                        {"block": "minecraft:water", "height": 60}],
             "biome": "minecraft:ocean"}
SURFACE = 59 + 8.0 / 9.0
BED = 0.0

STATUS_KEY = re.compile(r"(\w+)=([-\w.]+)")


class Rcon:
    def __init__(self, timeout=60.0):
        self.sock = socket.create_connection((HOST, PORT), timeout=timeout)
        self.sock.settimeout(timeout)
        self.rid = 0
        if self._send(3, PASSWORD) is None:
            raise SystemExit("rcon auth failed")

    def _pack(self, rid, kind, body):
        payload = struct.pack("<ii", rid, kind) + body.encode("utf8") + b"\x00\x00"
        return struct.pack("<i", len(payload)) + payload

    def _readn(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("rcon closed")
            buf += chunk
        return buf

    def _send(self, kind, body):
        self.rid += 1
        self.sock.sendall(self._pack(self.rid, kind, body))
        size = struct.unpack("<i", self._readn(4))[0]
        payload = self._readn(size)
        got, _ = struct.unpack("<ii", payload[:8])
        if got == -1:
            return None
        return payload[8:-2].decode("utf8", "replace")

    def cmd(self, command):
        return self._send(2, command)


def connect(deadline_s=300):
    deadline = time.time() + deadline_s
    while True:
        try:
            return Rcon()
        except (OSError, ConnectionError):
            if time.time() > deadline:
                raise SystemExit("rcon never came up; see " + LOG)
            time.sleep(2)


# The gradle wrapper starts the server JVM in its own session, so killing the wrapper leaves the
# JVM running and still holding the RCON port. Match the JVM itself, by the args file the moddev
# run config hands it, or a "fresh" server is really the previous one answering with the old build.
SERVER_JVM = "build/moddev/serverRunVmArgs.txt"


def _java_matching(pattern):
    """Java PIDs whose command line contains pattern.

    Restricted to java, because `pgrep -f` also matches whatever shell happens to be running a
    command with the pattern spelled out in it, including this harness's own. Killing that is how
    a start once shot itself.
    """
    found = []
    for pid in subprocess.run(["pgrep", "-x", "java"], capture_output=True, text=True).stdout.split():
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as f:
                if pattern.encode() in f.read().replace(b"\0", b" "):
                    found.append(int(pid))
        except OSError:
            pass
    return sorted(found)


def jvms():
    """PIDs of the actual server JVMs. One running server means exactly one of these."""
    return _java_matching(SERVER_JVM)


def running():
    """Every process a running server owns: the JVM, and the gradle wrapper that launched it."""
    return sorted(set(jvms() + _java_matching("GradleWrapperMain " + TASK)))


def start():
    # A server left over from an earlier run still holds the RCON port, so `start` would launch a
    # second one and every command would go to the old build while the new one sat idle. That read
    # as a 90 us improvement once. Never start on top of one.
    stale = running()
    if stale:
        print(f"killing {len(stale)} server(s) already up: {stale}")
        for pid in stale:
            subprocess.run(["pkill", "-9", "-P", str(pid)], capture_output=True)
            subprocess.run(["kill", "-9", str(pid)], capture_output=True)
        for _ in range(40):
            if not running():
                break
            time.sleep(0.5)
    os.makedirs(RUN, exist_ok=True)
    eula = os.path.join(RUN, "eula.txt")
    if not os.path.exists(eula):
        with open(eula, "w") as f:
            f.write("eula=true\n")
    if not os.path.exists(BACKUP) and os.path.exists(PROPS):
        os.rename(PROPS, BACKUP)
    lines = [
        f"level-name={LEVEL}",
        "level-type=minecraft\\:flat",
        "generator-settings=" + json.dumps(GENERATOR, separators=(",", ":")).replace(":", "\\:"),
        "enable-rcon=true",
        f"rcon.port={PORT}",
        f"server-port={GAME_PORT}",
        f"rcon.password={PASSWORD}",
        "online-mode=false",
        "spawn-protection=0",
        "view-distance=10",
        "simulation-distance=10",
        "max-tick-time=-1",
        "spawn-monsters=false",
        "spawn-animals=false",
        "difficulty=peaceful",
        "gamemode=creative",
        "enable-command-block=true",
        "sync-chunk-writes=false",
    ]
    with open(PROPS, "w") as f:
        f.write("\n".join(lines) + "\n")
    with open(LOG, "w") as log:
        subprocess.Popen(["./gradlew", TASK, "--offline"], cwd=ROOT,
                         stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    rcon = connect()
    live = jvms()
    if len(live) != 1:
        raise SystemExit(f"expected exactly one server JVM, found {live}")
    print(f"server up (jvm {live[0]}):", rcon.cmd("time query gametime"))


def stop():
    try:
        Rcon(timeout=10).cmd("stop")
    except Exception as e:  # noqa: BLE001
        print("stop:", e)
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            Rcon(timeout=3)
            time.sleep(2)
        except Exception:  # noqa: BLE001
            break
    if os.path.exists(BACKUP):
        os.replace(BACKUP, PROPS)
    print("server stopped, properties restored")


SHIP_ID = None
ID_FILE = os.path.join(ROOT, "run", "naval-ship-id")


def status(rcon):
    reply = rcon.cmd("combinedassault naval status" + (f" {SHIP_ID}" if SHIP_ID else ""))
    if not reply or not reply.startswith("ship"):
        return None
    out = {}
    for key, value in STATUS_KEY.findall(reply):
        value = value[:-2] if value.endswith("us") else value
        try:
            out[key] = float(value) if value not in ("true", "false") else value == "true"
        except ValueError:
            out[key] = value
    out["raw"] = reply
    return out


def wait_ticks(rcon, ticks):
    """Sleeps real time for the ticks, then confirms the clock moved."""
    before = int(re.search(r"(\d+)", rcon.cmd("time query gametime")).group(1))
    time.sleep(ticks / 20.0 + 0.2)
    after = int(re.search(r"(\d+)", rcon.cmd("time query gametime")).group(1))
    return after - before


def forceload(rcon, x0, z0, x1, z1):
    """Force-loads in boxes of at most 16x16 chunks, the command's ceiling."""
    step = 239
    x = x0
    while x <= x1:
        z = z0
        while z <= z1:
            rcon.cmd(f"forceload add {x} {z} {min(x + step, x1)} {min(z + step, z1)}")
            z += step + 1
        x += step + 1


results = []


def check(ok, what):
    results.append((ok, what))
    print(("PASS " if ok else "FAIL ") + what)


def kill_ships(rcon):
    rcon.cmd("kill @e[type=combinedassault:ship]")
    rcon.cmd("kill @e[type=!minecraft:player]")


def wait_loaded(rcon, x, z, seconds=30):
    """Waits until a block at the point answers, so a hull is not spawned over unloaded sea."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        if "The time is" in (rcon.cmd(f"execute if block {int(x)} 30 {int(z)} minecraft:water run time query gametime") or ""):
            return True
        time.sleep(1)
    return False


def smoke():
    rcon = connect(30)
    kill_ships(rcon)
    forceload(rcon, -200, -200, 200, 200)
    wait_loaded(rcon, 0, 0)
    print(rcon.cmd(f"combinedassault naval spawn combinedassault:riverine 0.5 {SURFACE + 3:.2f} 0.5 0"))
    for i in range(12):
        wait_ticks(rcon, 20)
        s = status(rcon)
        print(s["raw"] if s else "no status")
    rcon.cmd("combinedassault naval engine on")
    rcon.cmd("combinedassault naval helm 1 0")
    for i in range(10):
        wait_ticks(rcon, 20)
        s = status(rcon)
        print(s["raw"] if s else "no status")


def spawn_ship(rcon, vehicle, x, y, z, yaw):
    global SHIP_ID
    reply = rcon.cmd(f"combinedassault naval spawn {vehicle} {x:.2f} {y:.2f} {z:.2f} {yaw}")
    print(reply)
    SHIP_ID = int(re.search(r"id=(\d+)", reply).group(1))
    with open(ID_FILE, "w") as f:
        f.write(str(SHIP_ID))
    return SHIP_ID


def ship_cmd(rcon, command):
    return rcon.cmd(f"combinedassault naval {command}" + (f" {SHIP_ID}" if SHIP_ID else ""))


def carrier():
    rcon = connect(30)
    kill_ships(rcon)
    forceload(rcon, -500, -300, 500, 700)
    for z in (-170, 0, 170, 400, 650):
        print("  loaded", z, wait_loaded(rcon, 0, z))
    spawn_ship(rcon, "combinedassault:nimitz", 0.5, SURFACE + 2, 0.5, 0)
    wait_ticks(rcon, 300)
    s = status(rcon)
    print(s["raw"])
    check(s["afloat"], "carrier reads afloat")
    y = float(re.search(r"pos=([-\d.]+) ([-\d.]+) ([-\d.]+)", s["raw"]).group(2))
    check(abs(y - SURFACE) < 0.2, f"carrier waterline on the surface: y={y:.3f} vs {SURFACE:.3f}")
    check(abs(s["speed"]) < 0.01, f"carrier at rest: speed {s['speed']}")

    # Full ahead for 60 s: the speed climbs toward tanh(1) of v_max.
    ship_cmd(rcon, "engine on")
    wait_ticks(rcon, 100)
    ship_cmd(rcon, "helm 1 0")
    speeds = []
    for i in range(4):
        wait_ticks(rcon, 200)
        s = status(rcon)
        speeds.append(s["surge"])
        print(f"  t={(i + 1) * 10}s surge={s['surge']:.4f} throttle={s['throttle']:.2f} tickCost={s['tickCost']:.1f}us pitch={s['pitch']:.2f}")
    check(all(b > a for a, b in zip(speeds, speeds[1:])), "speed rises monotonically under full ahead")
    check(0.35 < speeds[-1] < 0.77, f"after 40 s at full ahead: {speeds[-1]:.3f} b/t (tanh(2/3) of 0.77 is 0.45)")

    # Full starboard rudder: heading swings, slowly, and the hull heels.
    yaw0 = s["yaw"]
    ship_cmd(rcon, "helm 0.6 1")
    yaws = []
    for i in range(4):
        wait_ticks(rcon, 200)
        s = status(rcon)
        yaws.append(s["yaw"] - yaw0)
        print(f"  t={(i + 1) * 10}s yaw={s['yaw'] - yaw0:.2f} yawRate={s['yawRate']:.4f} roll={s['roll']:.2f} surge={s['surge']:.3f} tickCost={s['tickCost']:.1f}us")
    check(yaws[-1] > 20, f"full rudder for 40 s turned {yaws[-1]:.1f} degrees")
    check(all(b > a for a, b in zip(yaws, yaws[1:])), "the turn keeps developing")

    # Cost: the ship's own tick, and the server profile around it.
    rcon.cmd("debug start")
    wait_ticks(rcon, 400)
    print(rcon.cmd("debug stop"))
    s = status(rcon)
    print(f"  ship tick cost {s['tickCost']:.1f} us")
    check(s["tickCost"] < 2000, f"carrier tick under 2 ms: {s['tickCost']:.1f} us")
    report_profile()

    # Stop and steady for the deck test.
    ship_cmd(rcon, "helm 0 0")


def report_profile():
    debug = os.path.join(RUN, "debug")
    if not os.path.isdir(debug):
        debug = os.path.join(ROOT, "debug")
    if not os.path.isdir(debug):
        print("  no debug directory under run/server or the project")
        return
    files = sorted(os.listdir(debug))
    if not files:
        return
    path = os.path.join(debug, files[-1])
    wanted = ("vehicle_physics", "travel", "push", "vehicle_riders", "vehicle_obb", "entities", "tick")
    with open(path, errors="replace") as f:
        for line in f:
            stripped = line.strip()
            if any(stripped.startswith(w) or ("[" + w + "]") in stripped for w in wanted) and "%" in stripped:
                print("  profile:", stripped[:140])


def adopt_nearest(rcon):
    """The ship the last scenario spawned, else the nearest to spawn."""
    global SHIP_ID
    SHIP_ID = None
    if os.path.exists(ID_FILE):
        SHIP_ID = int(open(ID_FILE).read().strip())
        s = status(rcon)
        if s:
            return s
        SHIP_ID = None
    reply = rcon.cmd("execute as @e[type=combinedassault:ship,limit=1] at @s run combinedassault naval status")
    if reply and reply.startswith("ship"):
        SHIP_ID = int(re.search(r"id=(\d+)", reply).group(1))
        with open(ID_FILE, "w") as f:
            f.write(str(SHIP_ID))
        return status(rcon)
    return None


def deck():
    """A hiace set down on the flight deck rides the carrier."""
    rcon = connect(30)
    s = adopt_nearest(rcon)
    if not s:
        print("no carrier; run carrier first")
        return
    x, y, z = map(float, re.search(r"pos=([-\d.]+) ([-\d.]+) ([-\d.]+)", s["raw"]).groups())
    yaw = s["yaw"]
    forceload(rcon, int(x) - 450, int(z) - 450, int(x) + 450, int(z) + 450)
    print("  loaded around the carrier:", wait_loaded(rcon, x, z), wait_loaded(rcon, x + 170, z + 170))
    ship_cmd(rcon, "helm 0 0")
    wait_ticks(rcon, 40)
    # Deck at local y 18.5; put the truck at the carrier's centre plus a block.
    reply = rcon.cmd(f"combinedassault naval spawn combinedassault:hiace {x:.2f} {y + 19.5:.2f} {z:.2f} {yaw:.1f}")
    print(reply)
    truck = int(re.search(r"id=(\d+)", reply).group(1))
    wait_ticks(rcon, 100)

    def truck_pos():
        r = rcon.cmd(f"data get entity @e[type=combinedassault:hiace,limit=1] Pos")
        vals = re.findall(r"([-\d.]+)d", r)
        return list(map(float, vals[:3]))

    def carrier_pos():
        st = status(rcon)
        return list(map(float, re.search(r"pos=([-\d.]+) ([-\d.]+) ([-\d.]+)", st["raw"]).groups())), st["yaw"]

    t0 = truck_pos()
    c0, yaw0 = carrier_pos()
    off0 = [t0[i] - c0[i] for i in range(3)]
    print(f"  truck at {t0}, carrier at {c0}, offset {off0}")
    check(abs(off0[1] - 18.5) < 1.5, f"truck rests on the flight deck: {off0[1]:.2f} above the carrier origin")
    ship_cmd(rcon, "engine on")
    ship_cmd(rcon, "helm 1 0.5")
    wait_ticks(rcon, 400)
    t1 = truck_pos()
    c1, yaw1 = carrier_pos()
    moved = ((c1[0] - c0[0]) ** 2 + (c1[2] - c0[2]) ** 2) ** 0.5
    off1 = [t1[i] - c1[i] for i in range(3)]
    print(f"  carrier moved {moved:.1f} and turned {yaw1 - yaw0:.1f}; truck offset now {off1}")
    check(moved > 5, f"the carrier moved: {moved:.1f} blocks")
    drift = ((off1[0] - off0[0]) ** 2 + (off1[2] - off0[2]) ** 2) ** 0.5
    check(abs(off1[1] - off0[1]) < 0.5, f"truck stayed at deck height: {off1[1]:.2f} vs {off0[1]:.2f}")
    check(drift < moved * 0.5 + 2, f"truck came along: offset drift {drift:.2f} (in the carrier's frame it should turn with it)")
    ship_cmd(rcon, "helm 0 0")


def ground():
    """A shelf ahead of a fresh carrier; it stops on the shelf face and reads aground."""
    rcon = connect(30)
    kill_ships(rcon)
    ox, oz = 2000, 0
    forceload(rcon, ox - 200, oz - 200, ox + 200, oz + 500)
    for z in (oz - 100, oz + 100, oz + 300):
        print("  loaded", z, wait_loaded(rcon, ox, z))
    # Stone to y 54 across z 300..340: keel at 48.5 hits it, water 5 deep over it.
    for y0 in range(1, 55, 5):
        y1 = min(y0 + 4, 54)
        rcon.cmd(f"fill {ox - 60} {y0} {oz + 300} {ox + 60} {y1} {oz + 340} minecraft:stone")
    spawn_ship(rcon, "combinedassault:nimitz", ox + 0.5, SURFACE + 1, oz + 100.5, 0)
    wait_ticks(rcon, 200)
    ship_cmd(rcon, "engine on")
    wait_ticks(rcon, 100)
    ship_cmd(rcon, "helm 1 0")
    last = None
    for i in range(9):
        wait_ticks(rcon, 200)
        last = status(rcon)
        z = float(re.search(r"pos=([-\d.]+) ([-\d.]+) ([-\d.]+)", last["raw"]).group(3)) - oz
        print(f"  t={(i + 1) * 10}s z={z:.2f} surge={last['surge']:.4f} keel={last['keel']:.2f} aground={last['aground']} y={last['raw'].split('pos=')[1].split()[1]}")
    z = float(re.search(r"pos=([-\d.]+) ([-\d.]+) ([-\d.]+)", last["raw"]).group(3)) - oz
    check(z <= 300 - 167 + 1.5, f"the bow stopped at the shelf face: origin z {z:.2f} (bow at {z + 167:.1f}, shelf at 300)")
    check(z > 110, f"the hull moved toward the shelf: z {z:.2f}")
    check(last["aground"], "reads aground")
    check(abs(last["surge"]) < 0.02, f"no way on against the shelf: surge {last['surge']:.4f}")
    y = float(re.search(r"pos=([-\d.]+) ([-\d.]+) ([-\d.]+)", last["raw"]).group(2))
    check(y < SURFACE + 0.5, f"did not climb the shelf: y {y:.2f}")


def torpedo():
    """A fresh carrier, a torpedo from a hundred blocks abeam into its side at the waterline."""
    rcon = connect(30)
    kill_ships(rcon)
    ox, oz = 4000, 0
    forceload(rcon, ox - 450, oz - 450, ox + 450, oz + 450)
    print("  loaded:", wait_loaded(rcon, ox, oz), wait_loaded(rcon, ox + 150, oz))
    spawn_ship(rcon, "combinedassault:nimitz", ox + 0.5, SURFACE + 1, oz + 0.5, 0)
    wait_ticks(rcon, 200)

    def health():
        r = rcon.cmd(f"data get entity @e[type=combinedassault:ship,limit=1] Health")
        m = re.search(r"([-\d.]+)f", r)
        return float(m.group(1)) if m else float("nan")

    def missile():
        r = rcon.cmd("data get entity @e[type=wflib:missile,limit=1] Pos")
        vals = re.findall(r"([-\d.]+)d", r)
        return list(map(float, vals[:3])) if len(vals) >= 3 else None

    health0 = health()
    print(f"  carrier health {health0:.1f}")
    for preset in ("wflib:torpedo", "wflib:torpedo_fast"):
        print("  ", rcon.cmd(f"combinedassault naval fire {preset} {ox + 100:.1f} {SURFACE - 6:.1f} {oz + 0.5:.1f} {ox + 24:.1f} {SURFACE - 5:.1f} {oz + 0.5:.1f}"))
        hit = False
        for i in range(40):
            wait_ticks(rcon, 20)
            pos = missile()
            h = health()
            if pos:
                print(f"    t={i + 1}s torpedo at x={pos[0] - ox:+.1f} y={pos[1]:.1f} z={pos[2] - oz:+.1f}  health {h:.1f}")
            else:
                print(f"    t={i + 1}s torpedo gone  health {h:.1f}")
            if h < health0 - 0.5:
                hit = True
                break
            if not pos and i > 2:
                break
        check(hit, f"{preset} hurt the carrier: {health0:.1f} -> {health():.1f}")
        if hit:
            break


# Virgin sea, well clear of the other scenarios. A course is cut where nothing has been cut before:
# the builder puts the channel back to water where an older course left a bank, but it measures the
# surface at the start mark, and a start mark inside an older course measures that one's surface.
TRIAL_ORIGIN = (65000.5, SURFACE + 2, 65000.5)


def trial(course="approach", ship="combinedassault:nimitz", crew=12, parked="combinedassault:atv_mrzr4",
          parked_count=2, patience=3600):
    """Cuts a course sized for the hull, sails it under autopilot, and reads back the passage.

    The course is built where nothing else has been, so a rerun writes the same blocks over the
    same blocks and costs nothing. Everything the verdict rests on is in the CSV the report names.
    """
    rcon = connect(30)
    kill_ships(rcon)
    x, y, z = TRIAL_ORIGIN
    order = f"combinedassault naval trial run {ship} {course} {x} {y} {z} 0 {crew}"
    if parked:
        order += f" {parked} {parked_count}"
    print(rcon.cmd(order))

    deadline = time.time() + patience
    last = ""
    while time.time() < deadline:
        line = rcon.cmd("combinedassault naval trial status") or ""
        if line != last:
            print("  " + line)
            last = line
        if line.startswith(("COMPLETED", "AGROUND", "LOST", "TIMED_OUT", "ABANDONED")):
            break
        if "could not be cut" in line or "the hull was removed" in line:
            check(False, "the course was cut: " + line)
            return
        time.sleep(5)

    report = rcon.cmd("combinedassault naval trial report") or ""
    print(report)
    verdict = re.search(r"(COMPLETED|AGROUND|LOST|TIMED_OUT|ABANDONED)", report)
    check(verdict is not None and verdict.group(1) in ("COMPLETED", "AGROUND"),
          "the passage ended with a verdict, not a timeout: "
          + (verdict.group(1) if verdict else "none"))
    if course == "approach":
        check("COMPLETED" in report, "the carrier made the whole approach under autopilot")
    aboard = re.search(r"crew: (\d+) aboard at muster, fewest (\d+)", report)
    if aboard:
        check(int(aboard.group(2)) >= int(aboard.group(1)),
              f"every hand stayed aboard: {aboard.group(2)} of {aboard.group(1)}")
    drift = re.search(r"worst drift ([\d.]+) blocks", report)
    if drift:
        check(float(drift.group(1)) < 2.0, f"the parked vehicles held station: {drift.group(1)} blocks")
    dragging = re.search(r"worst keel clearance ([-\d.]+), (\d+) ticks dragging", report)
    if dragging:
        check(int(dragging.group(2)) > 0,
              f"the shoals were shallow enough to matter: {dragging.group(2)} ticks dragging, "
              f"worst clearance {dragging.group(1)}")
    rcon.cmd("combinedassault naval trial stop")


def bar():
    """Too shallow for her: a bed above the keel, and whether astern gets her off it."""
    trial(course="bar", crew=6, parked=None, parked_count=0, patience=1800)


# One course spec, three hulls, no crew and nothing parked, so the only thing that differs between
# the runs is how big the hull is. Courses are sized in hull units, so each hull sails the same
# passage in its own units; what changes is the number of blocks that passage is made of.
HULLS = [("combinedassault:nimitz", 65000.5), ("combinedassault:kilo", 73000.5),
         ("combinedassault:riverine", 81000.5)]


def hullsize():
    """Does the solve cost what the hull is, or what is near it? Same course, three hull sizes."""
    rcon = connect(30)
    x, y = TRIAL_ORIGIN[0], TRIAL_ORIGIN[1]
    for ship, z in HULLS:
        kill_ships(rcon)
        print(f"\n=== {ship} ===")
        print(rcon.cmd(f"combinedassault naval trial run {ship} approach {x} {y} {z} 0 0"))
        deadline = time.time() + 2400
        last = ""
        while time.time() < deadline:
            line = rcon.cmd("combinedassault naval trial status") or ""
            if line != last:
                print("  " + line)
                last = line
            if line.startswith(("COMPLETED", "AGROUND", "LOST", "TIMED_OUT", "ABANDONED")):
                break
            if "could not be cut" in line or "the hull was removed" in line:
                check(False, f"{ship}: the course was cut: " + line)
                break
            time.sleep(5)
        report = rcon.cmd("combinedassault naval trial report") or ""
        print(report)
        check("us per tick" in report, f"{ship} reported a tick split")
        rcon.cmd("combinedassault naval trial stop")


def bare():
    """The carrier's passage with an empty deck: no crew AI, so the track repeats tick for tick.

    This is the before-and-after for a change to the solve. Compare the CSV's x, z and yaw columns
    against the previous run's: a change that only makes the tick cheaper must not move the hull.
    """
    trial(course="approach", ship="combinedassault:nimitz", crew=0, parked=None, parked_count=0,
          patience=2400)


def summary():
    failed = [w for ok, w in results if not ok]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed")
    for w in failed:
        print("  FAIL", w)


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "smoke"
    {"start": start, "stop": stop, "smoke": smoke, "carrier": carrier, "deck": deck,
     "ground": ground, "torpedo": torpedo, "trial": trial, "bar": bar, "hullsize": hullsize, "bare": bare}[mode]()
    if mode not in ("start", "stop", "smoke"):
        summary()
