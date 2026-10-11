#!/usr/bin/env python3
"""Drive the dev client on the ocean world: find its window, send chat commands, take screenshots.

Usage (from the combinedassault directory, with the client already launched):
  python3 tools/naval_client.py ready              # wait for the world, park the window
  python3 tools/naval_client.py cmd "/say hi" ...   # send chat commands, confirm them in the log
  python3 tools/naval_client.py shot <name>         # F2 screenshot, copied to run/naval-shots/<name>.png
  python3 tools/naval_client.py show <ship>         # spawn a hull ahead of the camera and frame it
  python3 tools/naval_client.py deckwalk            # sprint the flight deck, stopped and under way
"""
import os
import re
import shutil
import subprocess
import sys
import time

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RUN = os.path.join(ROOT, "run", "client_1")
LOG = os.path.join(ROOT, "run", "naval-client.log")
SHOTS = os.path.join(ROOT, "run", "naval-shots")


def sh(*args, check=False):
    return subprocess.run(args, capture_output=True, text=True, check=check).stdout.strip()


def client_pid():
    for line in sh("ps", "-eo", "pid,args").splitlines():
        if "clientRunVmArgs" in line and "java" in line and "GradleWrapperMain" not in line:
            return int(line.split()[0])
    return None


def window_id():
    """The newest client window, or the one COMBINEDASSAULT_CLIENT_WINDOW names.

    By name rather than by pid, because the pid scan matches any java process whose arguments mention
    the run and that includes a gradle daemon owning no window at all. Newest rather than first,
    because a client left over from an earlier session is still a window called Minecraft and driving
    that one instead sends every keystroke somewhere nobody is looking.
    """
    pinned = os.environ.get("COMBINEDASSAULT_CLIENT_WINDOW")
    if pinned:
        return pinned
    best = None
    newest = None
    for wid in sh("xdotool", "search", "--name", "Minecraft").split():
        if "Minecraft" not in sh("xdotool", "getwindowname", wid):
            continue
        pid = sh("xdotool", "getwindowpid", wid)
        started = sh("ps", "-o", "lstart=", "-p", pid) if pid else ""
        if best is None or started > newest:
            best = wid
            newest = started
    return best


def log_has(pattern, since=0):
    if not os.path.exists(LOG):
        return False
    with open(LOG, errors="replace") as f:
        f.seek(since)
        return re.search(pattern, f.read()) is not None


def log_size():
    return os.path.getsize(LOG) if os.path.exists(LOG) else 0


def log_wait(pattern, timeout=12.0):
    """Waits for a line to reach the log, which is not the same as waiting for it to happen.

    Two things make reading from an offset useless. The client's output goes through gradle, which
    buffers it, so a command that has already run takes seconds to appear; and a client left running
    from an earlier session writes to the same file at its own offset, so the size of the file is
    not where the next line lands. Every probe therefore carries its own nonce and the whole file is
    searched for it.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        if log_has(pattern):
            return True
        time.sleep(0.4)
    return False


def ready(timeout=420):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if window_id() and log_has(r"Loaded \d+ advancements|joined the game|\[CHAT\]"):
            break
        time.sleep(3)
    wid = window_id()
    if not wid:
        raise SystemExit("no client window")
    sh("hyprctl", "dispatch", "movetoworkspacesilent", "13,pid:" + str(client_pid()))
    time.sleep(1)
    sh("xdotool", "windowfocus", wid)
    time.sleep(1)
    # The first keystrokes after focus are eaten; a throwaway command absorbs them.
    cmd("/say w")
    print("ready:", wid)


def cmd(*commands, settle=0.6, tries=3):
    """Types each command into chat and confirms it arrived, retrying when it did not.

    The window eats the first keystroke after it takes focus often enough that a command sent once
    is a command that may never have been sent, and a scenario built on that reports numbers for a
    ship that never got the order. The marker after each command is what proves it landed; the
    command itself is confirmed by its own echo where it has one, and by the marker otherwise.
    """
    wid = window_id()
    for command in commands:
        for attempt in range(tries):
            marker = f"MARK{int(time.time() * 1000) % 1000000}"
            sh("xdotool", "windowfocus", wid)
            time.sleep(0.15)
            # Absorbs the eaten keystroke: open chat and close it again before the real one.
            sh("xdotool", "key", "--window", wid, "t")
            time.sleep(0.2)
            sh("xdotool", "key", "--window", wid, "Escape")
            time.sleep(0.2)
            sh("xdotool", "key", "--window", wid, "t")
            time.sleep(0.3)
            sh("xdotool", "type", "--window", wid, "--delay", "25", command)
            sh("xdotool", "key", "--window", wid, "Return")
            time.sleep(settle)
            sh("xdotool", "key", "--window", wid, "t")
            time.sleep(0.3)
            sh("xdotool", "type", "--window", wid, "--delay", "25", "/say " + marker)
            sh("xdotool", "key", "--window", wid, "Return")
            if log_wait(marker):
                print("  sent:", command)
                break
            if attempt == tries - 1:
                print("  WARNING: command not confirmed in the log:", command)


def shot(name):
    wid = window_id()
    os.makedirs(SHOTS, exist_ok=True)
    folder = os.path.join(RUN, "screenshots")
    before = set(os.listdir(folder)) if os.path.isdir(folder) else set()
    sh("xdotool", "windowfocus", wid)
    sh("xdotool", "key", "--window", wid, "F2")
    for _ in range(20):
        time.sleep(0.5)
        now = set(os.listdir(folder)) if os.path.isdir(folder) else set()
        new = sorted(now - before)
        if new:
            src = os.path.join(folder, new[-1])
            dst = os.path.join(SHOTS, name + ".png")
            shutil.copyfile(src, dst)
            print("shot:", dst)
            return dst
    print("no screenshot appeared")
    return None


# Deck stations along the hull from its origin, and how near counts as standing at one. The flight
# deck runs -166 to 166, so a run that starts at the stern has the whole of it ahead.
STATIONS = [-160, -130, -100, -70, -40, -10, 20]
STATION_RADIUS = 16


def station(label):
    """Where on the deck the player is standing, asked and answered inside one server tick.

    A chat round trip takes a second and a half and a carrier covers thirty blocks in that time, so
    nothing that reads two positions and subtracts them means anything. Each order below is one
    question the server answers without the answer going stale: is the player within sixteen blocks
    of that point on the deck, with the point taken in the hull's own frame.
    """
    nonce = int(time.time() * 1000) % 1000000
    orders = ["/execute at @e[type=combinedassault:ship,limit=1] positioned ^0 ^20 ^%d "
              "run execute if entity @p[distance=..%d] run say AT%dN%d"
              % (along, STATION_RADIUS, along, nonce) for along in STATIONS]
    cmd(*orders, settle=0.3)
    time.sleep(2.5)
    with open(LOG, errors="replace") as f:
        text = f.read()
    hits = [along for along in STATIONS if "AT%dN%d" % (along, nonce) in text]
    print("  %-22s deck stations %s" % (label + ":", hits or "none, off the deck"))
    return hits


def deckwalk(seconds=12, jump=1):
    """Sprint-jump the length of the flight deck, stopped and then under way, and say how far it got.

    The defect: on a deck with any trim or heel the hull clip came back with a horizontal difference
    that was nothing but the frame going in and coming out again, Entity.move read it as a wall, and
    the player's horizontal velocity was zeroed every tick. Walking was halved and a sprint jump had
    no run-up. A carrier trims 0.3 degrees at full ahead and heels two in a turn, so the deck of a
    moving carrier was always over the threshold and the deck of a stopped one never was.
    """
    wid = window_id()
    cmd("/gamemode creative", "/difficulty peaceful", "/time set noon", "/weather clear",
        "/gamerule fallDamage false", "/kill @e[type=combinedassault:ship]")
    time.sleep(2)
    cmd("/combinedassault naval spawn combinedassault:nimitz 0.5 61.9 0.5 0")
    time.sleep(8)
    for phase, helm in (("stopped", None), ("under way", "1 0")):
        if helm:
            cmd("/combinedassault naval engine on", "/combinedassault naval helm " + helm)
            # Long enough to be making her speed and carrying her trim.
            time.sleep(60)
        cmd("/gamemode creative",
            "/execute at @e[type=combinedassault:ship,limit=1] run tp @s ^0 ^20 ^-150 0 0",
            "/gamemode survival",
            "/effect give @s minecraft:saturation 120 5 true",
            "/effect give @s minecraft:resistance 120 4 true")
        time.sleep(2)
        cmd("/combinedassault naval status")
        station(phase + ", before")
        sh("xdotool", "windowfocus", wid)
        time.sleep(0.5)
        # Forward and sprint held down, jumping throughout: the run-up is what a zeroed velocity costs.
        # Through the window, the way the chat orders go: under XWayland the focus-following
        # XTEST path reports no active window at all.
        sh("xdotool", "keydown", "--window", wid, "w")
        sh("xdotool", "keydown", "--window", wid, "ctrl")
        for _ in range(seconds * 2):
            if jump:
                sh("xdotool", "key", "--window", wid, "space")
            time.sleep(0.5)
        sh("xdotool", "keyup", "--window", wid, "ctrl")
        sh("xdotool", "keyup", "--window", wid, "w")
        time.sleep(0.5)
        station("%s, after %ds %s" % (phase, seconds, "sprint jumping" if jump else "sprinting"))
        shot("deckwalk_" + phase.replace(" ", "_") + ("_jump" if jump else "_sprint"))


def show(ship, distance=None, height=None, yaw=90):
    """Spawn a hull east of a fixed point, put the camera abeam of it, screenshot."""
    # Within the twelve-chunk render distance and under the cloud layer at 192.
    sizes = {"nimitz": (150, 40), "kilo": (60, 12), "riverine": (14, 4), "hiace": (8, 2)}
    d, h = sizes.get(ship, (40, 10))
    if distance:
        d = distance
    if height:
        h = height
    x, z = 0, 0
    cmd("/gamemode spectator", "/difficulty peaceful", "/time set noon", "/weather clear",
        f"/combinedassault naval spawn combinedassault:{ship} {x + 0.5} 60.9 {z + 0.5} 0")
    time.sleep(4)
    # Camera abeam to starboard (-x), looking +x (yaw -90) at the hull's centre, slightly above.
    cmd(f"/tp @s {x - d} {59 + h} {z} -90 {min(40, int(h * 0.35))}")
    time.sleep(3)
    shot(ship + "_beam")
    cmd(f"/tp @s {x} {59 + h * 1.5} {z - d} 0 {min(45, int(h * 0.45))}")
    time.sleep(3)
    shot(ship + "_astern")
    cmd(f"/tp @s {x} {min(185, 59 + d * 0.9)} {z} 0 90")
    time.sleep(3)
    shot(ship + "_plan")


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "ready":
        ready()
    elif mode == "cmd":
        cmd(*sys.argv[2:])
    elif mode == "shot":
        shot(sys.argv[2])
    elif mode == "show":
        show(sys.argv[2])
    elif mode == "deckwalk":
        deckwalk(*[int(a) for a in sys.argv[2:]])
