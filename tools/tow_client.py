#!/usr/bin/env python3
"""Look at a tow in the dev client: the coupled pair, the cable, and a lap driven by hand.

Usage (from the combinedassault directory):
  python3 tools/tow_client.py launch     # start the client on the tow world and wait for it
  python3 tools/tow_client.py pair       # the M983 with the MIM-104 on its fifth wheel, from four angles
  python3 tools/tow_client.py cable      # one M983 on the end of another's cable, slack and taut
  python3 tools/tow_client.py drive      # get in, couple with the key, drive a corner, screenshot
  python3 tools/tow_client.py wheels     # the truck's wheels: steered from overhead, rolling from abeam
  python3 tools/tow_client.py all

Screenshots land in run/tow-shots/. The client is driven through naval_client, which knows how to
find its window and confirm that a chat command actually arrived.
"""
import os
import re
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import naval_client as nc  # noqa: E402

# The client's own log rather than gradle's console capture, so command confirmation works
# whether this harness launched the client or found one already up.
nc.LOG = os.path.join(nc.ROOT, "run", "client_1", "logs", "latest.log")
nc.SHOTS = os.path.join(nc.ROOT, "run", "tow-shots")

WORLD = "tow_flats"
# A patch of flat ground in the middle of a superflat world, well clear of spawn.
X, Y, Z = 200, -59, 200


def launch(fresh=True):
    if nc.client_pid() and not fresh:
        print("a client is already up")
    else:
        if nc.client_pid():
            # A client left over from an earlier session is on someone else's world, and driving it
            # sends every keystroke to a window nobody is looking at. See driving-the-dev-client.
            nc.sh("pkill", "-f", "clientRunVmArg[s]")
            time.sleep(6)
        with open(nc.LOG, "w") as log:
            subprocess.Popen(["./gradlew", "runClient", "--offline", "-PquickPlay=" + WORLD],
                             cwd=nc.ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    nc.ready()
    nc.cmd("/gamemode creative", "/time set noon", "/weather clear", "/gamerule doDaylightCycle false",
           f"/forceload add {X - 64} {Z - 64} {X + 64} {Z + 64}",
           f"/fill {X - 60} {Y} {Z - 60} {X + 60} {Y} {Z + 60} minecraft:gray_concrete")


def clear():
    nc.cmd("/kill @e[type=combinedassault:wheeled_vehicle]", "/kill @e[type=combinedassault:none_vehicle]")


def spawn(vehicle, dx, dz, yaw=0):
    nc.cmd(f"/combinedassault naval spawn combinedassault:{vehicle} {X + dx}.5 {Y + 2} {Z + dz}.5 {yaw}")


def look(dx, dy, dz, yaw, pitch, name):
    nc.cmd(f"/tp @s {X + dx} {Y + dy} {Z + dz} {yaw} {pitch}")
    time.sleep(2.5)
    nc.shot(name)


def pair():
    clear()
    spawn("m983", 0, 0)
    # The kingpin sits over the trailer's own origin and the fifth wheel 2.65 behind the tractor's,
    # so a coupled pair stand exactly that far apart with the same heading.
    spawn("mim104", 0, -3)
    time.sleep(3)
    nc.cmd("/combinedassault tow couple @e[type=combinedassault:wheeled_vehicle,limit=1] "
           "@e[type=combinedassault:none_vehicle,limit=1] fifth_wheel hitch")
    time.sleep(2)
    nc.cmd("/gamemode spectator")
    look(-13, 3, -4, -90, 4, "pair_beam")
    look(0, 3, -19, 0, 6, "pair_astern")
    look(-10, 5, 9, -140, 14, "pair_quarter")
    look(-1, 20, -4, -90, 80, "pair_plan")
    nc.cmd("/combinedassault tow status @e[type=combinedassault:none_vehicle,limit=1]")


def cable():
    clear()
    spawn("m983", 0, 0)
    spawn("m983", 0, -14)
    time.sleep(3)
    nc.cmd("/combinedassault tow couple @e[type=combinedassault:wheeled_vehicle,limit=1,sort=nearest] "
           "@e[type=combinedassault:wheeled_vehicle,limit=1,sort=furthest] pintle cable")
    time.sleep(2)
    nc.cmd("/gamemode spectator")
    look(-16, 4, -7, -90, 6, "cable_slack")
    # Drag the tractor forward until the cable is past its length and has to come tight.
    nc.cmd(f"/tp @e[type=combinedassault:wheeled_vehicle,limit=1,sort=nearest] {X} {Y + 2} {Z + 8} 0")
    time.sleep(3)
    look(-16, 4, -5, -90, 6, "cable_taut")
    nc.cmd("/combinedassault tow status @e[type=combinedassault:wheeled_vehicle,limit=1,sort=furthest]")


def wheels():
    """The truck's wheels: steered, and rolling.

    The camera is placed by aiming the player before mounting, because a third-person camera sits
    behind the player along their own look and a teleport of a rider dismounts it. Aft and down
    puts it high on the truck's front quarter, where a steered front wheel is plain.
    Coordinates are whole numbers: a typed decimal point is the one character seen to go missing.
    The aim also has to be one the camera can back away along: the third-person trace stops at the
    truck's own body, so looking aft, where it backs out over the nose, is what gets it clear.
    """
    clear()
    spawn("m983", 0, 0)
    time.sleep(3)
    wid = nc.window_id()
    # Default window is 854x480, which is not enough pixels to read a wheel by.
    nc.sh("xdotool", "windowsize", wid, "1920", "1080")
    time.sleep(2)
    # Taking a seat resets the rider's yaw to the seat's but leaves the pitch alone, so the pitch is
    # the only part of the camera this can choose: 55 degrees down puts the chase camera high astern,
    # looking at the tops of all eight wheels. V is the vehicle view, not F5: the mod's camera runs
    # only while vanilla's own camera type is still first person. Taking the driver's seat starts
    # the engine by itself.
    nc.cmd("/gamemode creative", f"/tp @s {X + 2} {Y + 2} {Z + 6} 180 55",
           "/ride @s mount @e[type=combinedassault:wheeled_vehicle,limit=1]")
    time.sleep(2)
    nc.cmd("/execute as @s on vehicle run data get entity @s Pos")
    nc.sh("xdotool", "windowactivate", "--sync", wid)
    time.sleep(0.5)
    nc.sh("xdotool", "key", "--window", wid, "v")
    time.sleep(1.5)
    nc.shot("wheels_straight")
    for k, name in (("d", "wheels_right"), ("a", "wheels_left")):
        nc.sh("xdotool", "keydown", "--window", wid, k)
        time.sleep(2.5)
        nc.shot(name)
        nc.sh("xdotool", "keyup", "--window", wid, k)
        time.sleep(2.5)
    # Rolling: three frames a third of a second apart.
    nc.sh("xdotool", "keydown", "--window", wid, "w")
    time.sleep(1.2)
    for i in range(3):
        nc.shot("wheels_roll%d" % i)
        time.sleep(0.35)
    nc.sh("xdotool", "keyup", "--window", wid, "w")
    time.sleep(3)
    nc.shot("wheels_stopped")


def hitbox():
    """One truck seen with the hitbox overlay toggled, so what the mod draws can be told apart."""
    clear()
    spawn("m983", 0, 0)
    time.sleep(3)
    wid = nc.window_id()
    nc.sh("xdotool", "windowsize", wid, "1920", "1080")
    time.sleep(2)
    nc.cmd("/gamemode spectator", f"/tp @s {X + 12} {Y + 6} {Z + 12} 135 20")
    time.sleep(2)
    nc.shot("hitbox_off")
    nc.sh("xdotool", "key", "--window", wid, "F3+b")
    time.sleep(2)
    nc.shot("hitbox_on")
    nc.sh("xdotool", "key", "--window", wid, "F3+b")
    time.sleep(1.5)
    nc.shot("hitbox_off_again")


def course(radius=30, seconds=110, every=1.6):
    """A lap driven by the trial's own driver, watched from beside the ring.

    A rider cannot drive and hold a camera at the same time, and the chase camera cannot be aimed
    without a mouse the game will listen to. The trial harness already has a driver, so the way to
    watch a truck steer and roll is to let it drive itself past a fixed spectator.
    """
    clear()
    nc.cmd("/gamemode creative", f"/tp @s {X} {Y + 2} {Z} 90 0",
           f"/combinedassault tow trial flat {radius} 3 {speed} hitch")
    time.sleep(6)
    # Abeam the ring's eastern point, where the pair passes broadside on.
    nc.cmd("/gamemode spectator", f"/tp @s {X + radius + 9} {Y + 4} {Z} 90 12")
    wid = nc.window_id()
    folder = os.path.join(nc.RUN, "screenshots")
    before = set(os.listdir(folder))
    start = time.time()
    while time.time() - start < seconds:
        nc.sh("xdotool", "key", "--window", wid, "F2")
        time.sleep(every)
    out = os.path.join(nc.SHOTS, "course")
    os.makedirs(out, exist_ok=True)
    for name in sorted(set(os.listdir(folder)) - before):
        shutil.copyfile(os.path.join(folder, name), os.path.join(out, name))
    print("frames:", len(os.listdir(out)), "in", out)
    nc.cmd("/combinedassault tow trial report")


def entity_yaw(selector):
    """The yaw of one entity, in degrees, read back out of the chat log."""
    size = nc.log_size()
    nc.cmd(f"/data get entity {selector} Rotation")
    with open(nc.LOG, errors="ignore") as log:
        log.seek(size)
        tail = log.read()
    found = re.findall(r"entity data: \[([-0-9.]+)f", tail)
    if not found:
        raise SystemExit("could not read a yaw for " + selector)
    return float(found[-1])


def creep(seconds=8):
    """Does anything move while the game is frozen: the pair, or the camera watching them?"""
    trailer = "@e[type=combinedassault:none_vehicle,limit=1]"
    tractor = "@e[type=combinedassault:wheeled_vehicle,limit=1]"
    nc.cmd("/tick freeze")
    for _ in range(3):
        nc.cmd(f"/data get entity {trailer} Pos", f"/data get entity {tractor} Pos",
               "/data get entity @s Pos")
        time.sleep(seconds)
    nc.cmd("/tick unfreeze")


def nudge(radius=30, settle=26, frames=6):
    """Which way a towed wheel turns, settled by moving the tractor a known step at a time.

    The trial is stopped once the pair are coupled and standing, so nothing moves but the nudge: a
    seventh of a block forward per frame, which is 14 degrees of trailer wheel, comfortably less
    than the 45 between one bolt hole and the next. Anything more and forwards and backwards look
    the same.
    """
    clear()
    nc.cmd("/gamemode creative", f"/tp @s {X} {Y + 2} {Z} 90 0",
           f"/combinedassault tow trial flat {radius} 3 0.3 hitch")
    time.sleep(settle)
    # Stopped and then left alone: with no operator the pair coast to a halt in a few seconds, and
    # a pair that is already still is the only way a 0.15 block nudge is the whole of the motion.
    nc.cmd("/combinedassault tow trial stop")
    time.sleep(14)
    nc.cmd("/tick freeze", "/gamemode spectator")
    wid = nc.window_id()
    nc.sh("xdotool", "key", "--window", wid, "F1")
    tractor = "@e[type=combinedassault:wheeled_vehicle,limit=1]"
    trailer = "@e[type=combinedassault:none_vehicle,limit=1]"
    yaw = round(entity_yaw(trailer)) + 90
    nc.cmd(f"/execute at {trailer} rotated as {trailer} run tp @s ^5 ^1 ^-7 {yaw} 4")
    time.sleep(1.5)
    for i in range(frames):
        nc.cmd(f"/data get entity {trailer} Pos")
        nc.shot("nudge%d" % i)
        nc.cmd(f"/execute at {tractor} rotated as {tractor} run tp {tractor} ^0 ^0 ^0.15",
               "/tick step 1")
        time.sleep(0.8)
    nc.sh("xdotool", "key", "--window", wid, "F1")
    nc.cmd("/tick unfreeze")


def creep_shot(radius=30, settle=26, frames=6):
    """The same question as nudge, asked at one tick a second so the roll is watchable.

    Freezing stops the client's interpolation as well as the server, so a stepped tick arrives as a
    jump. At a tick a second the client still interpolates: one 0.3 block nudge of the tractor is
    drawn over about three seconds, which is nine degrees of trailer wheel a second, slow enough to
    photograph and far short of the 45 degrees between bolt holes.
    """
    clear()
    nc.cmd("/gamemode creative", f"/tp @s {X} {Y + 2} {Z} 90 0",
           f"/combinedassault tow trial flat {radius} 3 0.3 hitch")
    time.sleep(settle)
    nc.cmd("/combinedassault tow trial stop")
    time.sleep(14)
    nc.cmd("/gamemode spectator")
    wid = nc.window_id()
    nc.sh("xdotool", "key", "--window", wid, "F1")
    tractor = "@e[type=combinedassault:wheeled_vehicle,limit=1]"
    trailer = "@e[type=combinedassault:none_vehicle,limit=1]"
    yaw = round(entity_yaw(trailer)) + 90
    nc.cmd(f"/execute at {trailer} rotated as {trailer} run tp @s ^4 ^1 ^-7 {yaw} 4")
    time.sleep(2)
    nc.cmd("/tick rate 1")
    nc.cmd(f"/execute at {tractor} rotated as {tractor} run tp {tractor} ^0 ^0 ^0.3")
    for i in range(frames):
        nc.shot("creep%d" % i)
        time.sleep(0.2)
    nc.sh("xdotool", "key", "--window", wid, "F1")
    nc.cmd("/tick rate 20")


def spin(radius=30, settle=26, frames=3):
    """Proof that the tractor's own wheels turn: freeze the world and photograph them anyway.

    A wheeled vehicle's wheel angle comes from its synced forward speed and the wall clock, not from
    how far it has moved, so a truck frozen at speed keeps turning its wheels while everything else
    in the picture stands still. Three frames a second apart should differ in nothing else.
    """
    clear()
    nc.cmd("/gamemode creative", f"/tp @s {X} {Y + 2} {Z} 90 0",
           f"/combinedassault tow trial flat {radius} 3 0.3 hitch")
    time.sleep(settle)
    nc.cmd("/tick freeze", "/gamemode spectator")
    wid = nc.window_id()
    nc.sh("xdotool", "key", "--window", wid, "F1")
    tractor = "@e[type=combinedassault:wheeled_vehicle,limit=1]"
    yaw = round(entity_yaw(tractor)) + 90
    nc.cmd(f"/execute at {tractor} rotated as {tractor} run tp @s ^6 ^1 ^-1 {yaw} 6")
    time.sleep(2)
    for i in range(frames):
        nc.shot("spin%d" % i)
        time.sleep(1.0)
    nc.sh("xdotool", "key", "--window", wid, "F1")
    nc.cmd("/tick unfreeze", "/combinedassault tow trial stop")


def frozen(radius=30, settle=26, speed=0.05, steps=1):
    """The pair stopped mid-corner with /tick freeze, then stepped a tick at a time.

    Freezing is what makes a wheel photographable: the camera can be put anywhere with /execute at,
    and a /tick step at the trial's slowest speed moves the pair 0.05 blocks, which is under five
    degrees of trailer wheel: frames a step or two apart say which way the tread is going without
    any guessing between one bolt hole and the next.
    """
    clear()
    nc.cmd("/gamemode creative", f"/tp @s {X} {Y + 2} {Z} 90 0",
           f"/combinedassault tow trial flat {radius} 3 0.3 hitch")
    time.sleep(settle)
    nc.cmd("/tick freeze", "/gamemode spectator")
    wid = nc.window_id()
    # No nameplate and no chat over the wheels.
    nc.sh("xdotool", "key", "--window", wid, "F1")
    tractor = "@e[type=combinedassault:wheeled_vehicle,limit=1]"
    trailer = "@e[type=combinedassault:none_vehicle,limit=1]"
    # Camera places are in the vehicle's own frame: ^left ^up ^forward, aimed back at its origin,
    # which is at ground level and so puts the wheels in the middle of the picture.
    nc.cmd(f"/execute at {tractor} rotated as {tractor} run tp @s ^5 ^4 ^9 facing entity {tractor}")
    time.sleep(1.5)
    nc.shot("frozen_front_quarter")
    nc.cmd(f"/execute at {tractor} rotated as {tractor} run tp @s ^7 ^2 ^0 facing entity {tractor}")
    time.sleep(1.5)
    nc.shot("frozen_tractor_abeam")
    # Abeam the trailer's own axles, one tick at a time. The camera is re-placed in the trailer's
    # frame once and left there: at this speed a step moves the pair a tenth of a block, which is
    # a pixel or two, so between frames nothing moves in the picture except the wheel:
    # a step is a few degrees of a 0.62 block wheel at this speed. The yaw is read
    # off the trailer and written out in full, because a relative one in /tp turns out to be
    # relative to whoever is being teleported, and so compounds every time round.
    yaw = round(entity_yaw(trailer)) + 90
    nc.cmd(f"/execute at {trailer} rotated as {trailer} run tp @s ^11 ^2 ^-7 {yaw} 6")
    time.sleep(1.5)
    for i in range(5):
        nc.cmd(f"/data get entity {trailer} Pos")
        nc.shot("frozen_step%d" % i)
        nc.cmd(f"/tick step {steps}")
        time.sleep(0.8)
    nc.sh("xdotool", "key", "--window", wid, "F1")
    time.sleep(0.5)
    nc.cmd("/tick unfreeze", "/combinedassault tow trial stop")


def drive(seconds=14):
    clear()
    spawn("m983", 0, 0)
    spawn("mim104", 0, -3)
    time.sleep(3)
    nc.cmd("/gamemode creative", f"/tp @s {X + 2} {Y + 2} {Z + 4} 180 0")
    time.sleep(1)
    wid = nc.window_id()
    # Activate and send bare XTEST events: a click delivered with --window is ignored by the game,
    # and so is a keypress that follows one. See driving-the-dev-client.
    nc.sh("xdotool", "windowactivate", "--sync", wid)
    time.sleep(0.5)
    nc.sh("xdotool", "key", "F5")
    time.sleep(0.5)
    nc.sh("xdotool", "mousedown", "3")
    time.sleep(0.4)
    nc.sh("xdotool", "mouseup", "3")
    time.sleep(2)
    # Start the engine, then hook up with the coupling key.
    for key in ("i", "m"):
        nc.sh("xdotool", "key", key)
        time.sleep(1.5)
    nc.shot("drive_coupled")
    nc.sh("xdotool", "keydown", "w")
    time.sleep(3)
    nc.sh("xdotool", "keydown", "d")
    time.sleep(seconds)
    nc.shot("drive_corner")
    nc.sh("xdotool", "keyup", "d")
    nc.sh("xdotool", "keyup", "w")
    time.sleep(2)
    nc.cmd("/gamemode spectator")
    nc.cmd("/combinedassault tow status @e[type=combinedassault:none_vehicle,limit=1]")


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "pair"
    if mode == "launch":
        launch()
    elif mode == "all":
        pair()
        cable()
        drive()
    else:
        globals()[mode]()
