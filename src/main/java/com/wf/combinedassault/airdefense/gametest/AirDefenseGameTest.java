package com.wf.combinedassault.airdefense.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.airdefense.AirDefense;
import com.wf.combinedassault.airdefense.AirDefenseActions;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.combinedassault.indirect.gametest.HimarsGameTest;
import com.wf.combinedassault.weapon.part.MissileHolderUnit;
import com.wf.combinedassault.weapon.part.StationActions;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.combinedassault.weapon.VehicleMissile;
import com.wf.wflib.missile.api.MissileEntity;
import com.wf.wflib.missile.api.InterceptBattery;
import com.wf.wflib.missile.api.MissilePresets;
import com.wf.wflib.probe.api.ProbeActions;
import com.wf.wflib.recon.api.ReconNet;
import com.wf.wflib.recon.api.ReconOwners;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** MIM-104: large rounds in four canisters; unmanned interception through the WFLib battery; manual lock; probe; station load. */
@PrefixGameTestTemplate(false)
public class AirDefenseGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation MIM104 = CombinedAssault.modLocation("mim104");
    private static final Item INTERCEPTOR = item("missile_interceptor_hypersonic");
    private static final Item BALLISTIC = item("missile_ballistic");
    private static final Item GMLRS = item("missile_gmlrs");
    private static final Item CRUISE = item("missile_cruise");
    /** The trailer runs 9.3 blocks behind its origin. */
    private static final int LANE_Z = -1500, LANE_PITCH = -48, FLOOR_X = 3, FLOOR_Z = 10;
    /** Hostile track straight over its own launcher. */
    private static final Vec3 THREAT_FROM = new Vec3(10, 60, 0);
    private static final Vec3 THREAT_TO = new Vec3(10, 260, 0);
    /** Ticks the battery gets; under the timeout, so a miss still cleans its threat up. */
    private static final int DEADLINE = 250;

    private static Item item(String path) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("wflib", path));
    }

    private static void onLane(GameTestHelper helper, int lane, TestLanes.Body body) {
        TestLanes.run(helper, LANE_Z + lane * LANE_PITCH, FLOOR_X, FLOOR_Z, MIM104, body, at -> {
        });
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void theCanistersTakeFourLargeRounds(GameTestHelper helper) {
        onLane(helper, 0, (seq, v) -> seq.thenExecute(() -> {
            AbstractVehicle vehicle = v.get();
            MissileHolderUnit rack = rack(helper, vehicle);
            boolean pod = MissileHolderUnit.tryLoad(vehicle, new ItemStack(GMLRS));
            boolean medium = MissileHolderUnit.tryLoad(vehicle, new ItemStack(CRUISE));
            int taken = 0;
            for (int i = 0; i < 5; i++) {
                taken += MissileHolderUnit.tryLoad(vehicle, new ItemStack(i % 2 == 0 ? INTERCEPTOR : BALLISTIC)) ? 1 : 0;
            }
            // The trailer settles on its legs: stations are checked against the launcher's bore, not world +Z.
            Vec3 bore = launcher(helper, vehicle).worldVec();
            List<String> stations = new ArrayList<>();
            for (int i = 0; i < rack.getCapacity(); i++) {
                MissileHolderUnit.StationPose station = rack.station(i);
                if (station == null) {
                    stations.add(i + ": none");
                } else if (new Vec3(forward(station)).dot(bore) < 0.999) {
                    stations.add(i + ": faces " + forward(station) + ", bore " + bore);
                }
            }
            helper.assertFalse(pod || medium, "the canisters took a " + (pod ? "GMLRS" : "medium cruise missile"));
            helper.assertTrue(rack.getCapacity() == 4 && taken == 4,
                    "rack of " + rack.getCapacity() + " took " + taken + " of 5 large rounds");
            helper.assertTrue(stations.isEmpty(), "stations off the launcher bore: " + stations);
        }));
    }

    /** AUTO + own radar: lays inside the elevation window and fires an interceptor locked on the threat. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void unmannedItInterceptsAHostileMissile(GameTestHelper helper) {
        MissileEntity[] threat = new MissileEntity[1];
        int[] ticks = {0};
        onLane(helper, 1, (seq, v) -> seq.thenExecute(() -> {
            WeaponUnit launcher = launcher(helper, v.get());
            HimarsGameTest.seed(helper, rack(helper, v.get()), INTERCEPTOR, BALLISTIC);
            launcher.airDefense().setMode(AirDefense.Mode.AUTO);
            launcher.airDefense().setSource(AirDefense.Source.ORGANIC);
            threat[0] = threat(helper, v.get());
        }).thenWaitUntil(() -> {
            // Held still over the lane: a free cruise missile would wander into other tests.
            Vec3 hold = v.get().position().add(THREAT_FROM);
            threat[0].moveTo(hold.x, hold.y, hold.z, 0.0f, 0.0f);
            threat[0].setDeltaMovement(Vec3.ZERO);
            helper.assertTrue(fired(helper, v.get()) != null || ++ticks[0] >= DEADLINE, "waiting");
        }).thenExecute(() -> {
            AbstractVehicle vehicle = v.get();
            WeaponUnit launcher = launcher(helper, vehicle);
            MissileEntity interceptor = fired(helper, vehicle);
            String picture = picture(helper, launcher);
            float elevation = -launcher.worldRot().x;
            UUID lock = interceptor == null ? null : interceptor.interceptor().lockTarget();
            boolean ballisticKept = rack(helper, vehicle).count(BALLISTIC) == 1;
            InterceptBattery.State state = launcher.airDefense().state();
            cleanUp(helper, vehicle, threat[0]);
            helper.assertTrue(interceptor != null, "no interceptor fired; state " + state + ", picture " + picture);
            helper.assertTrue(threat[0].getUUID().equals(lock), "interceptor locked on "
                    + TestLanes.describe(helper, lock, vehicle) + ", not the threat");
            helper.assertTrue(elevation >= 19.5f && elevation <= 80.5f,
                    String.format(Locale.ROOT, "launched at %.1f deg, outside 20..80", elevation));
            helper.assertTrue(ballisticKept, "the battery fired the ballistic round");
        }));
    }

    /** A UAV is an air-defence target: AUTO + own radar locks an interceptor on a hostile quadcopter. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void unmannedItInterceptsAHostileUav(GameTestHelper helper) {
        AbstractVehicle[] uav = new AbstractVehicle[1];
        int[] ticks = {0};
        onLane(helper, 5, (seq, v) -> seq.thenExecute(() -> {
            WeaponUnit launcher = launcher(helper, v.get());
            HimarsGameTest.seed(helper, rack(helper, v.get()), INTERCEPTOR);
            launcher.airDefense().setMode(AirDefense.Mode.AUTO);
            launcher.airDefense().setSource(AirDefense.Source.ORGANIC);
            uav[0] = TestLanes.spawn(helper, CombinedAssault.modLocation("mavic"), v.get().position().add(THREAT_FROM));
        }).thenWaitUntil(() -> {
            Vec3 hold = v.get().position().add(THREAT_FROM);
            uav[0].moveTo(hold.x, hold.y, hold.z, 0.0f, 0.0f);
            uav[0].setDeltaMovement(Vec3.ZERO);
            helper.assertTrue(fired(helper, v.get()) != null || ++ticks[0] >= DEADLINE, "waiting");
        }).thenExecute(() -> {
            WeaponUnit launcher = launcher(helper, v.get());
            MissileEntity interceptor = fired(helper, v.get());
            String picture = picture(helper, launcher);
            UUID lock = interceptor == null ? null : interceptor.interceptor().lockTarget();
            InterceptBattery.State state = launcher.airDefense().state();
            cleanUp(helper, v.get(), uav[0]);
            helper.assertTrue(interceptor != null, "no interceptor fired; state " + state + ", picture " + picture);
            helper.assertTrue(uav[0].getUUID().equals(lock), "interceptor locked on "
                    + TestLanes.describe(helper, lock, v.get()) + ", not the UAV");
        }));
    }

    /** AUTO on the network picture with no radar on the side's net: holds fire. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void networkSourceWithoutCoverageHolds(GameTestHelper helper) {
        MissileEntity[] threat = new MissileEntity[1];
        onLane(helper, 2, (seq, v) -> seq.thenExecute(() -> {
            WeaponUnit launcher = launcher(helper, v.get());
            HimarsGameTest.seed(helper, rack(helper, v.get()), INTERCEPTOR);
            launcher.airDefense().setMode(AirDefense.Mode.AUTO);
            threat[0] = threat(helper, v.get());
        }).thenIdle(80).thenExecute(() -> {
            AbstractVehicle vehicle = v.get();
            MissileEntity interceptor = fired(helper, vehicle);
            InterceptBattery.State state = launcher(helper, vehicle).airDefense().state();
            cleanUp(helper, vehicle, threat[0]);
            helper.assertTrue(interceptor == null, "fired with no radar coverage");
            helper.assertTrue(state == InterceptBattery.State.NO_PICTURE, "state " + state + ", not NO_PICTURE");
        }));
    }

    /** Operator's lock: the racked interceptor homes on it and leaves along its canister. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void aLockedInterceptorLeavesAlongItsCanister(GameTestHelper helper) {
        MissileEntity[] threat = new MissileEntity[1];
        onLane(helper, 3, (seq, v) -> seq.thenExecute(() -> {
            WeaponUnit launcher = launcher(helper, v.get());
            HimarsGameTest.seed(helper, rack(helper, v.get()), BALLISTIC, INTERCEPTOR);
            launcher.setXAimRot(-40);
            threat[0] = threat(helper, v.get());
        }).thenIdle(30).thenExecute(() -> {
            AbstractVehicle vehicle = v.get();
            WeaponUnit launcher = launcher(helper, vehicle);
            MissileHolderUnit rack = rack(helper, vehicle);
            Vector3f canister = forward(rack.station(1));
            VehicleMissile missile = (VehicleMissile) launcher.getIndexedWeapons().get(0);
            missile.selectRound(INTERCEPTOR.getDefaultInstance());
            missile.tick();
            launcher.setLockedEntity(threat[0]);
            AimContext aim = new AimContext();
            aim.position = threat[0].position();
            missile.shoot(new ArrayList<>(List.of(aim)), null);
            MissileEntity interceptor = fired(helper, vehicle);
            // Launch impulse: what the rail added to the trailer's own motion.
            Vec3 impulse = interceptor == null ? Vec3.ZERO
                    : interceptor.getDeltaMovement().subtract(vehicle.getDeltaMovement()).normalize();
            double off = Math.toDegrees(Math.acos(Math.min(1.0, impulse.dot(new Vec3(canister)))));
            UUID lock = interceptor == null ? null : interceptor.interceptor().lockTarget();
            int ballistic = rack.count(BALLISTIC);
            cleanUp(helper, vehicle, threat[0]);
            helper.assertTrue(interceptor != null, "no interceptor left");
            helper.assertTrue(threat[0].getUUID().equals(lock), "interceptor locked on " + lock + ", not the lock");
            helper.assertTrue(off <= 1.0, String.format(Locale.ROOT,
                    "left %.1f deg off its canister: impulse %s, canister %s", off, impulse, canister));
            helper.assertTrue(ballistic == 1, "the ballistic round left instead");
        }));
    }

    /** Probe: claim stamps the player's side on the launcher; another side may not change settings. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void theProbeClaimsForTheSideAndLocksOthersOut(GameTestHelper helper) {
        onLane(helper, 4, (seq, v) -> seq.thenExecute(() -> {
            AbstractVehicle vehicle = v.get();
            WeaponUnit launcher = launcher(helper, vehicle);
            ServerPlayer player = FakePlayerFactory.getMinecraft(helper.getLevel());
            player.setGameMode(GameType.SURVIVAL);
            player.moveTo(vehicle.position().add(2, 0, 0));
            var scoreboard = helper.getLevel().getScoreboard();
            PlayerTeam red = scoreboard.addPlayerTeam("ad_red");
            PlayerTeam blue = scoreboard.addPlayerTeam("ad_blue");
            scoreboard.addPlayerToTeam(player.getScoreboardName(), red);
            UUID redSide = ReconOwners.owningEntity(player);
            ProbeActions.performEntity(player, vehicle, AirDefenseActions.CLAIM, 1);
            UUID claimed = vehicle.getSide();
            scoreboard.addPlayerToTeam(player.getScoreboardName(), blue);
            ProbeActions.performEntity(player, vehicle, AirDefenseActions.MODE,
                    AirDefenseActions.arg(launcher.getIndex(), AirDefense.Mode.AUTO.ordinal()));
            AirDefense.Mode mode = launcher.airDefense().mode();
            scoreboard.removePlayerFromTeam(player.getScoreboardName());
            scoreboard.removePlayerTeam(red);
            scoreboard.removePlayerTeam(blue);
            helper.assertTrue(redSide != null && redSide.equals(claimed),
                    "claimed " + claimed + ", player's side " + redSide);
            helper.assertTrue(mode == AirDefense.Mode.MANUAL, "another side switched the launcher to " + mode);
        }));
    }

    /** Probe on foot: carried round -> that canister only; unload hands it back; a pod round does not fit. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void theProbeLoadsAndEmptiesOneCanister(GameTestHelper helper) {
        onLane(helper, 6, (seq, v) -> seq.thenExecute(() -> {
            AbstractVehicle vehicle = v.get();
            MissileHolderUnit rack = rack(helper, vehicle);
            ServerPlayer player = FakePlayerFactory.getMinecraft(helper.getLevel());
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.moveTo(vehicle.position().add(2, 0, 0));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GMLRS));
            ProbeActions.performEntity(player, vehicle, StationActions.LOAD, StationActions.holder(rack.getIndex(), 2));
            boolean pod = !rack.round(2).isEmpty();
            player.getInventory().add(new ItemStack(INTERCEPTOR, 2));
            ProbeActions.performEntity(player, vehicle, StationActions.LOAD, StationActions.holder(rack.getIndex(), 2));
            boolean[] loaded = {!rack.round(0).isEmpty(), !rack.round(1).isEmpty(), rack.round(2).is(INTERCEPTOR),
                    !rack.round(3).isEmpty()};
            int carried = player.getInventory().countItem(INTERCEPTOR);
            ProbeActions.performEntity(player, vehicle, StationActions.UNLOAD, StationActions.holder(rack.getIndex(), 2));
            boolean emptied = rack.round(2).isEmpty();
            int back = player.getInventory().countItem(INTERCEPTOR);
            player.getInventory().clearContent();
            helper.assertFalse(pod, "canister 2 took a GMLRS pod round");
            helper.assertTrue(!loaded[0] && !loaded[1] && loaded[2] && !loaded[3] && carried == 1,
                    "load: canisters " + java.util.Arrays.toString(loaded) + ", carried " + carried);
            helper.assertTrue(emptied && back == 2, "unload: emptied " + emptied + ", carried " + back);
        }));
    }

    private static MissileEntity threat(GameTestHelper helper, AbstractVehicle vehicle) {
        Vec3 from = vehicle.position().add(THREAT_FROM);
        MissileEntity threat = MissilePresets.get(MissilePresets.rl("cruise"))
                .build(helper.getLevel(), vehicle.position().add(THREAT_TO));
        threat.moveTo(from.x, from.y, from.z, 0.0f, 0.0f);
        helper.getLevel().addFreshEntity(threat);
        return threat;
    }

    private static String picture(GameTestHelper helper, WeaponUnit launcher) {
        return ReconNet.picture(helper.getLevel(), launcher.airDefense().netId()).tracks().stream()
                .map(t -> t.guess() + "/" + t.quality() + "/" + t.iff()).toList().toString();
    }

    /** An interceptor this launcher fired, or null. */
    @Nullable
    private static MissileEntity fired(GameTestHelper helper, AbstractVehicle vehicle) {
        return helper.getLevel().getEntitiesOfClass(MissileEntity.class, vehicle.getBoundingBox().inflate(64),
                        m -> m.interceptor().isActive() && vehicle.getUUID().equals(m.getControlId()))
                .stream().findFirst().orElse(null);
    }

    private static void cleanUp(GameTestHelper helper, AbstractVehicle vehicle, Entity threat) {
        helper.getLevel().getEntitiesOfClass(MissileEntity.class, vehicle.getBoundingBox().inflate(64),
                m -> vehicle.getUUID().equals(m.getControlId())).forEach(Entity::discard);
        threat.discard();
    }

    private static Vector3f forward(MissileHolderUnit.StationPose station) {
        return station.forward();
    }

    private static MissileHolderUnit rack(GameTestHelper helper, AbstractVehicle vehicle) {
        helper.assertTrue(vehicle.getPartUnit("canisters").orElse(null) instanceof MissileHolderUnit,
                "the MIM-104 has no canisters");
        return (MissileHolderUnit) vehicle.getPartUnit("canisters").get();
    }

    private static WeaponUnit launcher(GameTestHelper helper, AbstractVehicle vehicle) {
        helper.assertTrue(vehicle.getPartUnit("launcher").orElse(null) instanceof WeaponUnit unit
                && unit.airDefense() != null, "the MIM-104 has no air defence launcher");
        return (WeaponUnit) vehicle.getPartUnit("launcher").get();
    }
}
