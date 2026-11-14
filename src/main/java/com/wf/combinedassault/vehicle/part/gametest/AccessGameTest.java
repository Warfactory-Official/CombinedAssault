package com.wf.combinedassault.vehicle.part.gametest;

import org.joml.Quaternionf;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;
import com.mojang.authlib.GameProfile;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.dynamics.part.PartClipTracks;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.interact.Interaction;
import com.wf.combinedassault.vehicle.interact.InteractionActions;
import com.wf.combinedassault.vehicle.interact.InteractionContext;
import com.wf.combinedassault.vehicle.part.AccessActions;
import com.wf.combinedassault.vehicle.part.DoorUnit;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

@PrefixGameTestTemplate(false)
public class AccessGameTest {

    private static final String TEMPLATE = "empty";

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aTailgateOpensAndCloses(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        SwitchableUnit<?> tailgate = (SwitchableUnit<?>) humvee.getPartUnit("tailgate").orElseThrow();
        boolean opened = AccessActions.toggle(player(helper), humvee, tailgate.getIndex()) && tailgate.isOn();
        boolean closed = AccessActions.toggle(player(helper), humvee, tailgate.getIndex()) && !tailgate.isOn();
        humvee.discard();
        if (!opened || !closed) {
            helper.fail("tailgate opened " + opened + ", closed " + closed);
            return;
        }
        helper.succeed();
    }

    /**
     * Def interactions: node rides its leaf through the clip; server action refused through the hull, done from behind;
     * eye within 1 m reaches past another part; claimed parts lose their generated door node.
     */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void m113InteractionsFollowClipsAndNeedSight(GameTestHelper helper) {
        AbstractVehicle apc = spawn(helper, "m113");
        if (apc == null) {
            return;
        }
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            VehicleHits hits = apc.hits();
            int panelAt = interaction(apc, "front_panel");
            int doorAt = interaction(apc, "rear_door");
            int panelNode = apc.interactions().get(panelAt).nodes().get(0);
            int doorNode = apc.interactions().get(doorAt).nodes().get(0);
            Vec3 inner = hits.origin(apc.interactions().get(doorAt).nodes().get(1));
            Vec3 shut = hits.origin(panelNode);
            Vec3 rear = hits.origin(doorNode);
            Vec3 forward = shut.subtract(rear).multiply(1, 0, 1).normalize();
            // Panel shut: open, the decor engine bay (no hit surface) lets sight through the glacis hole.
            SwitchableUnit<?> door = (SwitchableUnit<?>) apc.getPartUnit("rear_door").orElseThrow();
            FakePlayer player = player(helper);
            stand(player, rear.add(forward.scale(6)));
            boolean through = InteractionActions.perform(player, apc, InteractionActions.arg(doorAt, 0, 0)) || door.isOn();
            stand(player, rear.subtract(forward.scale(2)));
            boolean innerHidden = !hits.probeReaches(player.getEyePosition(), inner, VehicleHits.NONE);
            boolean behind = InteractionActions.perform(player, apc, InteractionActions.arg(doorAt, 0, 0)) && door.isOn();
            apc.getPartUnit("front_panel").map(p -> (SwitchableUnit<?>) p).orElseThrow().setOn(true);
            settle(apc);
            Vec3 open = hits.origin(panelNode);
            boolean followed = open.y < shut.y - 1.2 && open.subtract(shut).dot(forward) > 0.6;
            Vec3 close = closeBlocked(hits, rear, VehicleHits.NONE);
            boolean claimed = apc.interactionClaims("rear_door") && apc.interactionClaims("front_panel");
            apc.discard();
            if (!followed || through || !behind || !innerHidden || close == null || !claimed) {
                helper.fail("panel node " + shut + " -> " + open + " followed " + followed + "; rear door toggled through hull "
                        + through + ", from behind " + behind + "; inner handle hidden from behind " + innerHidden
                        + "; blocked eye within 1 m " + close + "; claimed " + claimed);
                return;
            }
            helper.succeed();
        });
    }

    /** Humvee door handle: rides the door, toggled from outside, not through the cab. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void humveeInteractionsFollowClipsAndNeedSight(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            VehicleHits hits = humvee.hits();
            int leftAt = interaction(humvee, "door_fl");
            int leftNode = humvee.interactions().get(leftAt).nodes().get(0);
            Vec3 shut = hits.origin(leftNode);
            Vec3 side = shut.subtract(hits.origin(humvee.interactions().get(interaction(humvee, "door_fr")).nodes().get(0)))
                    .multiply(1, 0, 1).normalize();
            SwitchableUnit<?> left = (SwitchableUnit<?>) humvee.getPartUnit("door_fl").orElseThrow();
            FakePlayer player = player(helper);
            stand(player, shut.subtract(side.scale(4)));
            boolean through = InteractionActions.perform(player, humvee, InteractionActions.arg(leftAt, 0, 0)) || left.isOn();
            stand(player, shut.add(side.scale(1.5)));
            boolean outside = InteractionActions.perform(player, humvee, InteractionActions.arg(leftAt, 0, 0)) && left.isOn();
            settle(humvee);
            Vec3 open = hits.origin(leftNode);
            humvee.discard();
            if (!outside || through || open.distanceTo(shut) < 0.4) {
                helper.fail("door_fl toggled outside " + outside + ", through cab " + through + ", handle moved "
                        + open.distanceTo(shut));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Rear entrance behind the door: shut => hidden from behind and offers nothing; door or ramp open => boards; the
     * rider then closes the door from the seat (toggle is a rider action, board is not).
     */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void m113EntranceNeedsItsOpening(GameTestHelper helper) {
        AbstractVehicle apc = spawn(helper, "m113");
        if (apc == null) {
            return;
        }
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            FakePlayer player = player(helper);
            String log = entrance(helper, apc, player, "rear_entrance", "rear_door", "rear_door", "ramp");
            apc.ejectPassengers();
            apc.discard();
            if (log != null) {
                helper.fail(log);
                return;
            }
            helper.succeed();
        });
    }

    /** Every troop seat: shut rear door's inner handle seen, outer not; closes the open rear door, raises the lowered ramp. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void m113TroopsCloseTheRearBehindThem(GameTestHelper helper) {
        AbstractVehicle apc = spawn(helper, "m113");
        if (apc == null) {
            return;
        }
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            FakePlayer rider = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "access_troop")) {
                @Override
                public Entity getVehicle() {
                    return apc;
                }
            };
            VehicleHits hits = apc.hits();
            StringBuilder log = new StringBuilder();
            for (String leaf : List.of("rear_door", "ramp")) {
                int at = interaction(apc, leaf);
                SwitchableUnit<?> part = (SwitchableUnit<?>) apc.getPartUnit(leaf).orElseThrow();
                List<Integer> nodes = apc.interactions().get(at).nodes();
                for (AbstractVehicle.Seat seat : apc.seats) {
                    if (!seat.partUnit.getId().startsWith("troop_")) {
                        continue;
                    }
                    Vec3 eye = seat.partUnit.worldPositionWithSelfRot(seat.partUnit.getSeatOffset(), 1);
                    stand(rider, eye);
                    if (leaf.equals("rear_door") && (hits.probeReaches(eye, hits.origin(nodes.get(0)), VehicleHits.NONE)
                            || !hits.probeReaches(eye, hits.origin(nodes.get(1)), VehicleHits.NONE))) {
                        log.append(' ').append(seat.partUnit.getId()).append(" shut: outer seen or inner hidden");
                    }
                    part.setOn(true);
                    settle(apc);
                    if (!InteractionActions.perform(rider, apc, InteractionActions.arg(at, 0, 0)) || part.isOn()) {
                        log.append(' ').append(seat.partUnit.getId()).append(" -> ").append(leaf);
                        for (int node : nodes) {
                            Vec3 to = hits.origin(node);
                            VehicleHits.Hit block = hits.trace(eye, to, i -> hits.live(i)
                                    && !hits.model().nodes().get(i).mesh().module());
                            log.append(String.format(" [%.2f m by %s]", eye.distanceTo(to), block == null ? "-"
                                    : hits.model().nodes().get(block.node()).mesh().name()
                                    + String.format("@%.2f", (1 - block.fraction()) * eye.distanceTo(to))));
                        }
                    }
                    part.setOn(false);
                    settle(apc);
                }
            }
            apc.discard();
            if (!log.isEmpty()) {
                helper.fail(log.toString().trim());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * M1A2 CWS + loader MG traverse about their own mounts: muzzle keeps its distance from a pivot within 0.6 m of the
     * gun, operator seat (base frame) does not move; CWS seat inside under the cupola, commander_out in it.
     */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void m1a2StationsTraverseOnTheirOwnMounts(GameTestHelper helper) {
        AbstractVehicle tank = spawn(helper, "m1a2");
        if (tank == null) {
            return;
        }
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            StringBuilder log = new StringBuilder();
            for (String id : List.of("anti_aircraft_machine_gun", "loader_machine_gun")) {
                com.wf.combinedassault.weapon.part.WeaponUnit unit =
                        (com.wf.combinedassault.weapon.part.WeaponUnit) tank.getPartUnit(id).orElseThrow();
                Vec3 pivot = unit.worldPivotPosition();
                Vec3 seat = unit.worldSeatPosition();
                Vec3 muzzle = unit.aimContext().from;
                unit.setYRot(90);
                unit.updateRot();
                Vec3 turnedSeat = unit.worldSeatPosition();
                Vec3 turned = unit.aimContext().from;
                double arm = muzzle.subtract(pivot).horizontalDistance();
                double turnedArm = turned.subtract(unit.worldPivotPosition()).horizontalDistance();
                if (turnedSeat.distanceTo(seat) > 1e-3 || Math.abs(arm - turnedArm) > 1e-3 || arm > 1.6
                        || turned.distanceTo(muzzle) < 0.1) {
                    log.append(String.format(" %s: seat moved %.3f, muzzle arm %.3f -> %.3f, muzzle moved %.3f;", id,
                            turnedSeat.distanceTo(seat), arm, turnedArm, turned.distanceTo(muzzle)));
                }
            }
            PartUnit<?> cws = tank.getPartUnit("anti_aircraft_machine_gun").orElseThrow();
            PartUnit<?> out = tank.getPartUnit("commander_out").orElseThrow();
            PartUnit<?> hatch = tank.getPartUnit("commander_hatch").orElseThrow();
            Vec3 cupola = hatch.worldPivotPosition();
            Vec3 inside = cws.worldPositionWithBaseRot(cws.getSeatOffset(), 1);
            Vec3 outside = out.worldPositionWithSelfRot(out.getSeatOffset(), 1);
            if (inside.y > cupola.y - 0.2 || outside.y < cupola.y + 0.3
                    || inside.multiply(1, 0, 1).distanceTo(cupola.multiply(1, 0, 1)) > 0.3) {
                log.append(" cupola ").append(cupola).append(", CWS eye ").append(inside).append(", commander_out eye ")
                        .append(outside);
            }
            tank.discard();
            if (!log.isEmpty()) {
                helper.fail(log.toString().trim());
                return;
            }
            helper.succeed();
        });
    }

    /** Each Humvee door closed from the seat behind it. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void humveeRidersCloseTheirDoors(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            FakePlayer rider = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "access_crew")) {
                @Override
                public Entity getVehicle() {
                    return humvee;
                }
            };
            StringBuilder log = new StringBuilder();
            for (String[] pair : new String[][]{{"door_fl", "driver"}, {"door_fr", "passenger"}, {"door_rl", "rear_left"},
                    {"door_rr", "rear_right"}}) {
                SwitchableUnit<?> door = (SwitchableUnit<?>) humvee.getPartUnit(pair[0]).orElseThrow();
                PartUnit<?> seat = humvee.getPartUnit(pair[1]).orElseThrow();
                door.setOn(true);
                settle(humvee);
                stand(rider, seat.worldPositionWithSelfRot(seat.getSeatOffset(), 1));
                if (!InteractionActions.perform(rider, humvee, InteractionActions.arg(interaction(humvee, pair[0]), 0, 0))
                        || door.isOn()) {
                    log.append(' ').append(pair[1]).append(" -> ").append(pair[0]);
                }
                door.setOn(false);
                settle(humvee);
            }
            humvee.discard();
            if (!log.isEmpty()) {
                helper.fail("not closed:" + log);
                return;
            }
            helper.succeed();
        });
    }

    /** Humvee front left seat entrance behind door_fl; rider closes the door after boarding. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void humveeEntranceNeedsItsDoor(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            FakePlayer player = player(helper);
            String log = entrance(helper, humvee, player, "front_left_seat", "door_fl", "door_fl", null);
            humvee.ejectPassengers();
            humvee.discard();
            if (log != null) {
                helper.fail(log);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Player 2 m out from {@code entranceId}'s node, away from its handle. Null = passed. {@code alternate}: other
     * opening that also admits (opened with the door shut), or null. FakePlayer fails the mount gate: the offered seat
     * is boarded by an armor stand through {@code board}; the rider is a FakePlayer reporting the vehicle, eye at the seat.
     */
    @Nullable
    private static String entrance(GameTestHelper helper, AbstractVehicle vehicle, FakePlayer player, String entranceId, String doorId,
                                   String handleId, @Nullable String alternate) {
        VehicleHits hits = vehicle.hits();
        int entranceAt = interaction(vehicle, entranceId);
        int handleAt = interaction(vehicle, handleId);
        Interaction entrance = vehicle.interactions().get(entranceAt);
        SwitchableUnit<?> door = (SwitchableUnit<?>) vehicle.getPartUnit(doorId).orElseThrow();
        Vec3 at = hits.origin(entrance.nodes().get(0));
        Vec3 handle = hits.origin(vehicle.interactions().get(handleAt).nodes().get(0));
        Vec3 out = handle.subtract(at).multiply(1, 0, 1).normalize();
        stand(player, at.add(out.scale(2)));
        InteractionContext ctx = new InteractionContext(player, vehicle, entrance, at, p -> Component.literal(p.getId()));
        boolean shutSeen = hits.probeReaches(player.getEyePosition(), at, VehicleHits.NONE);
        boolean shutOffered = entrance.actions().get(0).available(ctx);
        String alt = "";
        if (alternate != null) {
            SwitchableUnit<?> other = (SwitchableUnit<?>) vehicle.getPartUnit(alternate).orElseThrow();
            other.setOn(true);
            settle(vehicle);
            boolean altSeen = hits.probeReaches(player.getEyePosition(), at, VehicleHits.NONE);
            boolean altOffered = entrance.actions().get(0).available(ctx);
            other.setOn(false);
            settle(vehicle);
            if (!altSeen || !altOffered) {
                alt = "; " + alternate + " open: seen " + altSeen + " offered " + altOffered;
            }
        }
        door.setOn(true);
        settle(vehicle);
        boolean openSeen = hits.probeReaches(player.getEyePosition(), at, VehicleHits.NONE);
        boolean openOffered = entrance.actions().get(0).available(ctx);
        List<Integer> seats = new ArrayList<>();
        entrance.actions().get(0).offer(ctx, (arg, label, refusal) -> seats.add(arg));
        ArmorStand stand = stand(helper, player.position());
        boolean boarded = !seats.isEmpty() && vehicle.board(stand, seats.get(0)) && stand.getVehicle() == vehicle;
        stand.discard();
        FakePlayer rider = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "access_rider")) {
            @Override
            public Entity getVehicle() {
                return vehicle;
            }
        };
        // Rider in the offered seat nearest the handle: closes the door behind them.
        Vec3 eye = null;
        for (int index : seats) {
            AbstractVehicle.Seat seat = vehicle.seats.get(index);
            Vec3 seatEye = seat.partUnit.worldPositionWithSelfRot(seat.partUnit.getSeatOffset(), 1);
            eye = eye == null || seatEye.distanceTo(handle) < eye.distanceTo(handle) ? seatEye : eye;
        }
        stand(rider, eye == null ? at : eye);
        boolean riderBoardRefused = !seats.isEmpty()
                && !InteractionActions.perform(rider, vehicle, InteractionActions.arg(entranceAt, 0, seats.get(seats.size() - 1)));
        boolean closed = boarded && InteractionActions.perform(rider, vehicle, InteractionActions.arg(handleAt, 0, 0))
                && !door.isOn();
        if (shutSeen || shutOffered || !alt.isEmpty() || !openSeen || !openOffered || !boarded || !riderBoardRefused
                || !closed) {
            return vehicle.getVehicleId() + " " + entranceId + ": shut seen " + shutSeen + " offered " + shutOffered + alt
                    + "; open seen " + openSeen + " offered " + openOffered + "; boarded " + boarded
                    + "; rider board refused " + riderBoardRefused + "; rider closed " + closed;
        }
        return null;
    }

    private static int interaction(AbstractVehicle vehicle, String id) {
        for (int i = 0; i < vehicle.interactions().size(); i++) {
            if (vehicle.interactions().get(i).id().equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException(vehicle.getVehicleId() + " has no interaction " + id);
    }

    /** Eye at {@code eye}. */
    private static void stand(FakePlayer player, Vec3 eye) {
        player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
    }

    private static void settle(AbstractVehicle vehicle) {
        for (int i = 0; i < 120; i++) {
            vehicle.clipTracks().tick();
        }
        vehicle.hits().refreshClips();
    }

    /** Eye 0.9 m from {@code at} with another surface in the way, reached; null if none found or refused. */
    @Nullable
    private static Vec3 closeBlocked(VehicleHits hits, Vec3 at, java.util.function.IntPredicate own) {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    Vec3 eye = at.add(new Vec3(x, y, z).normalize().scale(0.9));
                    if (!hits.probeReaches(eye, at, own, 0)) {
                        return hits.probeReaches(eye, at, own) ? eye : null;
                    }
                }
            }
        }
        return null;
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aHatchIsADoor(GameTestHelper helper) {
        AbstractVehicle tank = spawn(helper, "m1a2");
        if (tank == null) {
            return;
        }
        SwitchableUnit<?> hatch = (SwitchableUnit<?>) tank.getPartUnit("loader_hatch").orElseThrow();
        boolean opened = AccessActions.toggle(player(helper), tank, hatch.getIndex()) && hatch.isOn();
        tank.discard();
        if (!opened) {
            helper.fail("loader hatch did not open");
            return;
        }
        helper.succeed();
    }

    /** Storage node on foot opens the hold; ride-inventory from a remote UAV link is refused. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void storageOpensOnFootNotOverAUavLink(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        AbstractVehicle ciws = spawn(helper, "mk15");
        if (humvee == null || ciws == null) {
            return;
        }
        int[] opened = new int[1];
        // FakePlayer#openMenu is a no-op: count the calls.
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "access_storage")) {
            @Override
            public OptionalInt openMenu(@Nullable MenuProvider provider, @Nullable Consumer<RegistryFriendlyByteBuf> extra) {
                opened[0]++;
                return OptionalInt.empty();
            }
        };
        humvee.openCustomInventoryScreen(player);
        boolean onFoot = opened[0] == 1;
        ciws.setDetachedBodyAnchor(player, player.position());
        ciws.openCustomInventoryScreen(player);
        boolean remote = opened[0] == 2;
        ciws.setDetachedBodyAnchor(player, null);
        humvee.discard();
        ciws.discard();
        if (!onFoot || remote) {
            helper.fail("on foot opened " + onFoot + ", over the link opened " + remote);
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void landingGearIsNotADoor(GameTestHelper helper) {
        AbstractVehicle jet = spawn(helper, "a10");
        if (jet == null) {
            return;
        }
        PartUnit<?> gear = jet.getPartUnit("landing_gear").orElseThrow();
        boolean before = ((SwitchableUnit<?>) gear).isOn();
        boolean toggled = AccessActions.toggle(player(helper), jet, gear.getIndex());
        boolean after = ((SwitchableUnit<?>) gear).isOn();
        boolean canopy = AccessActions.isDoor(jet.getPartUnit("canopy").orElseThrow());
        jet.discard();
        if (toggled || before != after || !canopy) {
            helper.fail("gear toggled " + toggled + " (" + before + " -> " + after + "), canopy a door " + canopy);
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aDestroyedDoorIsJammed(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        SwitchableUnit<?> door = (SwitchableUnit<?>) humvee.getPartUnit("door_fl").orElseThrow();
        door.setHealth(0);
        boolean toggled = AccessActions.toggle(player(helper), humvee, door.getIndex());
        boolean open = door.isOn();
        humvee.discard();
        if (!door.isDestroyed() || toggled || open) {
            helper.fail("destroyed " + door.isDestroyed() + ", toggled " + toggled + ", open " + open);
            return;
        }
        helper.succeed();
    }

    /** Port (+X) side: eye on door_fl => door_fl; nearest fallback door_fl; aimed at the centre => never starboard. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aPortClickOpensThePortDoor(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        helper.runAfterDelay(5, () -> {
            FakePlayer player = player(helper);
            Vec3 base = humvee.position();
            player.setPos(base.x + 2.6, base.y, base.z + 0.5);
            Vec3 eye = player.getEyePosition();
            Vec3 door = humvee.getPartUnit("door_fl").orElseThrow().worldPosition(new Vec3(1.2, 1.2, 0.5));
            Vec3 aim = door.subtract(eye).normalize();
            PartUnit<?> hit = VectorUtil.pickPartUnit(humvee, eye, eye.add(aim.scale(4)));
            DoorUnit nearest = humvee.getNearestDoorUnit(player);
            // Through the B-pillar gap, across the cab: the far door's inside is the first part surface.
            Vec3 centre = humvee.getBoundingBox().getCenter();
            PartUnit<?> centred = VectorUtil.pickPartUnit(humvee, eye, eye.add(centre.subtract(eye).normalize().scale(4)));
            humvee.discard();
            String hitId = hit == null ? null : hit.getId();
            String centredId = centred == null ? null : centred.getId();
            if (!"door_fl".equals(hitId) || nearest == null || !"door_fl".equals(nearest.getId())
                    || centredId != null && centredId.endsWith("r")) {
                helper.fail("hit " + hitId + ", nearest " + (nearest == null ? null : nearest.getId())
                        + ", centre pick " + centredId + ", door world " + door + ", eye " + eye);
                return;
            }
            helper.succeed();
        });
    }

    /** Clip-driven hit node follows its part: C5 visor (clip nose_doors) swings up with the doors. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aClipMovesItsHitGeometry(GameTestHelper helper) {
        AbstractVehicle c5 = spawn(helper, "c5");
        if (c5 == null) {
            return;
        }
        // Tracks + hits stepped by hand: a 70 m hull spills into neighbouring arenas and the world may stop ticking it.
        PartClipTracks tracks = c5.clipTracks();
        int visor = c5.hits().model().indexOf("visor");
        tracks.tick();
        c5.hits().refreshClips();
        Vec3 closed = c5.hits().centre(visor).subtract(c5.position());
        SwitchableUnit<?> doors = (SwitchableUnit<?>) c5.getPartUnit("nose_doors").orElseThrow();
        boolean toggled = AccessActions.toggle(player(helper), c5, doors.getIndex());
        for (int t = 0; t < 60; t++) {
            tracks.tick();
            c5.hits().refreshClips();
        }
        Vec3 open = c5.hits().centre(visor).subtract(c5.position());
        float progress = tracks.track("nose_doors").progress(1);
        c5.discard();
        if (!toggled || progress != 1 || open.distanceTo(closed) < 1) {
            helper.fail("toggled " + toggled + ", progress " + progress + ", visor hit centre " + closed + " -> " + open);
            return;
        }
        helper.succeed();
    }

    /**
     * C-5 nose + rear doors toggle apart; nose refused while the pilot sits, boarding the pilot seat shuts it;
     * leaving opens neither.
     */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aGalaxyNoseOpensOnlyWithTheCockpitEmpty(GameTestHelper helper) {
        AbstractVehicle c5 = spawn(helper, "c5");
        if (c5 == null) {
            return;
        }
        SwitchableUnit<?> nose = (SwitchableUnit<?>) c5.getPartUnit("nose_doors").orElseThrow();
        SwitchableUnit<?> rear = (SwitchableUnit<?>) c5.getPartUnit("cargo_doors").orElseThrow();
        int pilot = c5.seats.stream().filter(seat -> seat.partUnit.getId().equals("pilot")).findFirst().orElseThrow().seatIndex;
        FakePlayer player = player(helper);
        boolean rearOpened = AccessActions.toggle(player, c5, rear.getIndex()) && rear.isOn() && !nose.isOn();
        boolean noseOpened = AccessActions.toggle(player, c5, nose.getIndex()) && nose.isOn();
        Vec3 at = c5.position();
        ArmorStand rider = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        helper.getLevel().addFreshEntity(rider);
        boolean boarded = c5.board(rider, pilot);
        boolean shutByPilot = !nose.isOn();
        boolean refused = !AccessActions.toggle(player, c5, nose.getIndex()) && !nose.isOn();
        boolean rearWithPilot = AccessActions.toggle(player, c5, rear.getIndex()) && rear.isOn();
        rider.stopRiding();
        boolean leftShut = !nose.isOn() && rear.isOn();
        boolean noseAfter = AccessActions.toggle(player, c5, nose.getIndex()) && nose.isOn();
        c5.discard();
        rider.discard();
        if (!rearOpened || !noseOpened || !boarded || !shutByPilot || !refused || !rearWithPilot || !leftShut || !noseAfter) {
            helper.fail("rear alone " + rearOpened + ", nose " + noseOpened + ", boarded " + boarded + ", shut by pilot "
                    + shutByPilot + ", refused " + refused + ", rear toggles with pilot " + rearWithPilot
                    + ", leaving changed none " + leftShut + ", nose after " + noseAfter);
            return;
        }
        helper.succeed();
    }

    /** Humvee top hatch: one clip keys both leaves; leaf b nests under leaf a's hinge, inside the roof_mount yaw frame. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aTopHatchFoldsBothLeaves(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        PartClipTracks tracks = humvee.clipTracks();
        int a = humvee.hits().model().indexOf("top_hatch_leaf_a");
        int b = humvee.hits().model().indexOf("top_hatch_leaf_b");
        tracks.tick();
        humvee.hits().refreshClips();
        Vec3 closedA = humvee.hits().centre(a).subtract(humvee.position());
        Vec3 closedB = humvee.hits().centre(b).subtract(humvee.position());
        SwitchableUnit<?> hatch = (SwitchableUnit<?>) humvee.getPartUnit("top_hatch").orElseThrow();
        boolean toggled = AccessActions.toggle(player(helper), humvee, hatch.getIndex());
        for (int t = 0; t < 40; t++) {
            tracks.tick();
            humvee.hits().refreshClips();
        }
        Vec3 openA = humvee.hits().centre(a).subtract(humvee.position());
        Vec3 openB = humvee.hits().centre(b).subtract(humvee.position());
        float progress = tracks.track("top_hatch").progress(1);
        humvee.discard();
        // Closed: both leaves level over the ring; open: stacked outboard, leaf b on top.
        if (!toggled || progress != 1 || openA.distanceTo(closedA) < 0.3 || openB.distanceTo(closedB) < 0.7
                || openB.y <= openA.y || Math.abs(closedA.y - closedB.y) > 0.01) {
            helper.fail("toggled " + toggled + ", progress " + progress + ", leaf a " + closedA + " -> " + openA
                    + ", leaf b " + closedB + " -> " + openB);
            return;
        }
        helper.succeed();
    }

    /** Roof seat ({@code hatch: top_hatch}): boarding refused while closed; open + clip run out => boards. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aClosedHatchRefusesBoarding(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        int roof = roofSeat(humvee);
        // Rear door: the entry the roof seat is boarded through. Settles the hatch track closed too.
        humvee.getPartUnit("door_rl").map(p -> (SwitchableUnit<?>) p).orElseThrow().setOn(true);
        settle(humvee);
        SwitchableUnit<?> hatch = (SwitchableUnit<?>) humvee.getPartUnit("top_hatch").orElseThrow();
        Vec3 at = humvee.position();
        ArmorStand rider = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        helper.getLevel().addFreshEntity(rider);
        boolean closedBoarded = humvee.board(rider, roof);
        hatch.setOn(true);
        boolean openingBoarded = humvee.board(rider, roof);
        for (int t = 0; t < 40; t++) {
            humvee.clipTracks().tick();
        }
        boolean openBoarded = humvee.board(rider, roof);
        PartUnit<?> seat = humvee.getOwnOperatorUnit(rider);
        humvee.discard();
        rider.discard();
        if (closedBoarded || openingBoarded || !openBoarded || seat == null || !seat.getId().equals("roof_mount")) {
            helper.fail("boarded closed " + closedBoarded + ", opening " + openingBoarded + ", open " + openBoarded
                    + " into " + (seat == null ? null : seat.getId()));
            return;
        }
        helper.succeed();
    }

    /** Rear seat -> roof seat: the switch opens the hatch, the rider moves only once its clip has run out. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aSeatSwitchOpensTheHatchFirst(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        int roof = roofSeat(humvee);
        int rear = humvee.seats.stream().filter(seat -> seat.partUnit.getId().equals("rear_left"))
                .findFirst().orElseThrow().seatIndex;
        SwitchableUnit<?> hatch = (SwitchableUnit<?>) humvee.getPartUnit("top_hatch").orElseThrow();
        Vec3 at = humvee.position();
        ArmorStand rider = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        rider.setInvulnerable(true);
        helper.getLevel().addFreshEntity(rider);
        String[] state = new String[1];
        // Boarded after the hull's first tick: that tick ejects riders attached before it.
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    humvee.getPartUnit("door_rl").map(p -> (SwitchableUnit<?>) p).orElseThrow().setOn(true);
                    settle(humvee);
                    boolean boarded = humvee.board(rider, rear);
                    boolean movedAtOnce = humvee.changeSeat(rider, roof);
                    state[0] = "boarded " + boarded + ", moved at once " + movedAtOnce + ", hatch on " + hatch.isOn()
                            + ", seat " + seatOf(humvee, rider);
                    helper.assertTrue(boarded && !movedAtOnce && hatch.isOn() && "rear_left".equals(seatOf(humvee, rider)),
                            state[0]);
                })
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue("rear_left".equals(seatOf(humvee, rider)),
                        state[0] + "; after 10 ticks " + seatOf(humvee, rider) + ", riding " + rider.getVehicle()))
                .thenWaitUntil(() -> helper.assertTrue("roof_mount".equals(seatOf(humvee, rider)),
                        "still in " + seatOf(humvee, rider)))
                .thenExecute(() -> {
                    humvee.discard();
                    rider.discard();
                })
                .thenSucceed();
    }

    /**
     * Centauro {@code entry}: rear door closed => refused; open but far (bow) => refused; open at the door => driver seat
     * (default pick), then the turret seat by index.
     */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void theRearDoorIsTheOnlyWayIn(GameTestHelper helper) {
        AbstractVehicle centauro = spawn(helper, "centauro");
        if (centauro == null) {
            return;
        }
        SwitchableUnit<?> door = switchable(centauro, "rear_door");
        Vec3 frame = door.worldPosition(door.getPivotOffset());
        ArmorStand rider = stand(helper, frame.subtract(0, 1.5, 0));
        ArmorStand gunner = stand(helper, frame.subtract(0, 1.5, 0));
        ArmorStand far = stand(helper, centauro.position().add(centauro.position().subtract(frame).multiply(1, 0, 1)));
        String[] state = new String[1];
        // Boarded after the hull's first tick: that tick ejects riders attached before it.
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    centauro.clipTracks().tick();
                    boolean closed = centauro.board(rider, -1);
                    door.setOn(true);
                    for (int t = 0; t < 40; t++) {
                        centauro.clipTracks().tick();
                    }
                    boolean fromBow = centauro.board(far, -1);
                    boolean atDoor = centauro.board(rider, -1);
                    boolean turret = centauro.board(gunner, seatIndex(centauro, "turret"));
                    state[0] = "closed " + closed + ", bow " + fromBow + ", door " + atDoor + " -> " + seatOf(centauro, rider)
                            + ", turret " + turret + " -> " + seatOf(centauro, gunner);
                    helper.assertTrue(!closed && !fromBow && atDoor && "driver".equals(seatOf(centauro, rider))
                            && turret && "turret".equals(seatOf(centauro, gunner)), state[0]);
                })
                .thenExecute(() -> {
                    centauro.discard();
                    rider.discard();
                    gunner.discard();
                    far.discard();
                })
                .thenSucceed();
    }

    /**
     * Centauro commander hatch open, rider beside it: driver refused (not its seat), commander boarded; turn out
     * (mg_r) then back in => commander again, hatch closed.
     */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aRoofHatchLeadsToItsSeatsAndTurningInClosesIt(GameTestHelper helper) {
        AbstractVehicle centauro = spawn(helper, "centauro");
        if (centauro == null) {
            return;
        }
        SwitchableUnit<?> hatch = switchable(centauro, "commander_hatch");
        Vec3 frame = hatch.worldPosition(hatch.getPivotOffset());
        ArmorStand rider = stand(helper, frame.subtract(0, 1.5, 0));
        String[] state = new String[1];
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    hatch.setOn(true);
                    for (int t = 0; t < 40; t++) {
                        centauro.clipTracks().tick();
                    }
                    boolean driver = centauro.board(rider, seatIndex(centauro, "driver"));
                    boolean commander = centauro.board(rider, seatIndex(centauro, "commander"));
                    boolean out = centauro.changeSeat(rider, seatIndex(centauro, "mg_r"));
                    String outSeat = seatOf(centauro, rider);
                    boolean in = centauro.changeSeat(rider, seatIndex(centauro, "commander"));
                    state[0] = "driver " + driver + ", commander " + commander + ", out " + out + " -> " + outSeat + ", in " + in
                            + " -> " + seatOf(centauro, rider) + ", hatch on " + hatch.isOn();
                    helper.assertTrue(!driver && commander && out && "mg_r".equals(outSeat) && in
                            && "commander".equals(seatOf(centauro, rider)) && !hatch.isOn(), state[0]);
                })
                .thenExecute(() -> {
                    centauro.discard();
                    rider.discard();
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void m1a2CrewBoardsThroughItsHatches(GameTestHelper helper) {
        crewThroughHatches(helper, "m1a2");
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void t90CrewBoardsThroughItsHatches(GameTestHelper helper) {
        crewThroughHatches(helper, "t90");
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void t84CrewBoardsThroughItsHatches(GameTestHelper helper) {
        crewThroughHatches(helper, "t84");
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void merkavaCrewBoardsThroughItsHatches(GameTestHelper helper) {
        crewThroughHatches(helper, "merkava");
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void m113CrewBoardsThroughItsHatches(GameTestHelper helper) {
        crewThroughHatches(helper, "m113");
    }

    /**
     * Every seat: some entry leads to it; rider at that entry's frame boards it; a {@code turn_out} seat goes out
     * (hatch seat) and back in, hatch closed after.
     */
    private static void crewThroughHatches(GameTestHelper helper, String id) {
        AbstractVehicle vehicle = spawn(helper, id);
        if (vehicle == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    StringBuilder log = new StringBuilder();
                    boolean ok = true;
                    for (AbstractVehicle.Seat seat : vehicle.seats) {
                        SwitchableUnit<?> own = seat.partUnit.hatch() != null ? seat.partUnit.hatch()
                                : seat.partUnit.turnOut() != null ? seat.partUnit.turnOut().hatch() : null;
                        SwitchableUnit<?> entry = vehicle.entries().stream().filter(e -> vehicle.serves(e, seat))
                                .min(java.util.Comparator.comparingInt(e -> e == own ? 0 : 1)).orElse(null);
                        if (entry == null) {
                            log.append(seat.partUnit.getId()).append(": no entry; ");
                            ok = false;
                            continue;
                        }
                        vehicle.entries().forEach(e -> e.setOn(e == entry));
                        for (int t = 0; t < 60; t++) {
                            vehicle.clipTracks().tick();
                        }
                        ArmorStand rider = stand(helper, entry.worldPosition(entry.getPivotOffset()).subtract(0, 1.5, 0));
                        boolean boarded = vehicle.board(rider, seat.seatIndex) && seat.partUnit.getId().equals(seatOf(vehicle, rider));
                        String trip = "";
                        PartUnit<?> out = seat.partUnit.turnOut();
                        if (boarded && out != null) {
                            boolean wentOut = vehicle.changeSeat(rider, seatIndex(vehicle, out.getId())) && out.getId().equals(seatOf(vehicle, rider));
                            boolean cameIn = vehicle.changeSeat(rider, seat.seatIndex) && seat.partUnit.getId().equals(seatOf(vehicle, rider));
                            boolean closed = !out.hatch().isOn();
                            trip = " out " + wentOut + " in " + cameIn + " closed " + closed;
                            ok &= wentOut && cameIn && closed;
                        }
                        log.append(seat.partUnit.getId()).append(" via ").append(entry.getId()).append(": ").append(boarded).append(trip).append("; ");
                        ok &= boarded;
                        rider.stopRiding();
                        rider.discard();
                    }
                    helper.assertTrue(ok, log.toString());
                })
                .thenExecute(vehicle::discard)
                .thenSucceed();
    }

    /** Centauro commander rides the turret frame: turret yawed 90 deg => seat swung 90 deg about the turret pivot. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aTurretSeatTurnsWithTheTurret(GameTestHelper helper) {
        AbstractVehicle centauro = spawn(helper, "centauro");
        if (centauro == null) {
            return;
        }
        PartUnit<?> commander = centauro.getPartUnit("commander").orElseThrow();
        VehicleCubeGroup turret = centauro.getPartUnit("turret").orElseThrow().getStructureGroup();
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    Vec3 seat = commander.getSeatOffset();
                    Quaternionf hull = centauro.rotYXZ().invert();
                    Vec3 centre = centauro.position().add(centauro.centerOffset);
                    java.util.function.Function<Vec3, Vec3> local = w -> new Vec3(hull.transform(w.subtract(centre).toVector3f()));
                    Vec3 before = local.apply(commander.worldPosition(seat));
                    Quaternionf rest = new Quaternionf(turret.rotation);
                    turret.rotation.rotateY((float) Math.toRadians(90));
                    Vec3 after = local.apply(commander.worldPosition(seat));
                    turret.rotation.set(rest);
                    Vec3 pivot = turret.globalTransform().offset().subtract(centauro.centerOffset);
                    double r0 = Math.hypot(before.x - pivot.x, before.z - pivot.z);
                    double r1 = Math.hypot(after.x - pivot.x, after.z - pivot.z);
                    double turned = Math.toDegrees(Math.atan2(after.x - pivot.x, after.z - pivot.z)
                            - Math.atan2(before.x - pivot.x, before.z - pivot.z));
                    turned = ((turned % 360) + 540) % 360 - 180;
                    helper.assertTrue(Math.abs(r0 - r1) < 1e-3 && Math.abs(Math.abs(turned) - 90) < 1 && r0 > 0.2,
                            "before " + before + " after " + after + " pivot " + pivot + " turned " + turned);
                })
                .thenExecute(centauro::discard)
                .thenSucceed();
    }

    private static SwitchableUnit<?> switchable(AbstractVehicle vehicle, String id) {
        return vehicle.getPartUnits().stream().filter(part -> part.getId().equals(id)).map(part -> (SwitchableUnit<?>) part)
                .findFirst().orElseThrow();
    }

    private static int seatIndex(AbstractVehicle vehicle, String id) {
        return vehicle.seats.stream().filter(seat -> seat.partUnit.getId().equals(id)).findFirst().orElseThrow().seatIndex;
    }

    private static ArmorStand stand(GameTestHelper helper, Vec3 at) {
        ArmorStand stand = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        stand.setInvulnerable(true);
        helper.getLevel().addFreshEntity(stand);
        return stand;
    }

    private static int roofSeat(AbstractVehicle vehicle) {
        return vehicle.seats.stream().filter(seat -> seat.partUnit.getId().equals("roof_mount"))
                .findFirst().orElseThrow().seatIndex;
    }

    @Nullable
    private static String seatOf(AbstractVehicle vehicle, ArmorStand rider) {
        PartUnit<?> seat = vehicle.getOwnOperatorUnit(rider);
        return seat == null ? null : seat.getId();
    }

    /** Seat node: rear_right boarded though driver free; taken seat refused. Armour stands: FakePlayer fails the mount gate. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aSeatNodeBoardsThatSeat(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "humvee");
        if (humvee == null) {
            return;
        }
        int rear = humvee.seats.stream().filter(seat -> seat.partUnit.getId().equals("rear_right"))
                .findFirst().orElseThrow().seatIndex;
        Vec3 at = humvee.position();
        ArmorStand first = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        ArmorStand second = new ArmorStand(helper.getLevel(), at.x, at.y, at.z);
        helper.getLevel().addFreshEntity(first);
        helper.getLevel().addFreshEntity(second);
        humvee.getPartUnit("door_rr").map(p -> (SwitchableUnit<?>) p).orElseThrow().setOn(true);
        settle(humvee);
        boolean boarded = humvee.board(first, rear);
        boolean taken = humvee.board(second, rear);
        PartUnit<?> seat = humvee.getOwnOperatorUnit(first);
        String id = seat == null ? null : seat.getId();
        boolean secondAboard = second.isPassenger();
        humvee.discard();
        first.discard();
        second.discard();
        if (!boarded || taken || !"rear_right".equals(id) || secondAboard) {
            helper.fail("boarded " + boarded + " into " + id + ", taken seat boarded " + taken);
            return;
        }
        helper.succeed();
    }

    private static FakePlayer player(GameTestHelper helper) {
        return FakePlayerFactory.getMinecraft(helper.getLevel());
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, String id) {
        ResourceLocation rl = CombinedAssault.modLocation(id);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(rl);
        if (data.isEmpty()) {
            helper.fail(rl + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(rl);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(rl + " could not be added to the world");
            return null;
        }
        return vehicle;
    }
}
