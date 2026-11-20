package com.wf.combinedassault.aircraft.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.part.MissileHolderUnit;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Engine / wing pools of every aircraft; engines hung on a wing; wing stations. */
@PrefixGameTestTemplate(false)
public class AircraftHitModelGameTest {

    private static final String TEMPLATE = "empty";
    /** Clear of WingLossGameTest (-2100..), CrashGameTest (-2700) and the pylon lane (-900). */
    private static final int LANE_X = -3300;
    private static final int LANE_PITCH = 64;

    /** id -> engine pools. */
    private static final Map<String, Integer> ENGINES = Map.ofEntries(
            Map.entry("a10", 2), Map.entry("av8b", 1), Map.entry("f22a", 2), Map.entry("f35a", 1),
            Map.entry("su33", 2), Map.entry("su47", 2), Map.entry("tornado", 2), Map.entry("c5", 4),
            Map.entry("b2a", 4), Map.entry("mq9", 1), Map.entry("mv22", 2), Map.entry("ah64d", 2),
            Map.entry("mi24", 2), Map.entry("ka52", 2), Map.entry("ah_6", 1), Map.entry("w3", 2),
            Map.entry("mh60l", 2), Map.entry("ch47", 2));
    private static final List<String> WINGED = List.of("a10", "av8b", "f22a", "f35a", "su33", "su47", "tornado", "c5",
            "b2a", "mq9", "mv22");

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void everyAircraftBuildsEnginePoolsAndSidedWings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<AbstractVehicle> spawned = new ArrayList<>();
        List<String> ids = new ArrayList<>(ENGINES.keySet());
        ids.sort(String::compareTo);
        for (int i = 0; i < ids.size(); i++) {
            BlockPos at = lane(helper, i);
            force(level, at, true);
            AbstractVehicle v = spawn(helper, ids.get(i), at);
            if (v == null) {
                return;
            }
            spawned.add(v);
        }
        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (AbstractVehicle v : spawned) {
                        helper.assertTrue(v.hits() != null, v.getDisplayId() + " hit model not built");
                    }
                })
                .thenExecute(() -> {
                    List<String> bad = new ArrayList<>();
                    for (int i = 0; i < ids.size(); i++) {
                        AbstractVehicle v = spawned.get(i);
                        String id = ids.get(i);
                        HullComponents pools = v.hullComponents();
                        int engines = 0;
                        boolean left = false, right = false;
                        for (int p = 0; p < pools.size(); p++) {
                            engines += pools.kind(p) == ComponentKind.ENGINE ? 1 : 0;
                            left |= pools.kind(p) == ComponentKind.WING && pools.id(p).startsWith("left ");
                            right |= pools.kind(p) == ComponentKind.WING && pools.id(p).startsWith("right ");
                        }
                        if (engines != ENGINES.get(id)) {
                            bad.add(id + ": " + engines + " engine pools, want " + ENGINES.get(id));
                        }
                        if (WINGED.contains(id) && !(left && right)) {
                            bad.add(id + ": sided wing pools " + left + "/" + right);
                        }
                        v.discard();
                        force(level, lane(helper, i), false);
                    }
                    if (!bad.isEmpty()) {
                        helper.fail(String.join("; ", bad));
                    }
                })
                .thenSucceed();
    }

    /** C-5: engines 1, 2 hang on the left wing => left wing pool 0 drops them, right pair stays; jets keep discrete engines. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aLostWingTakesTheEnginesHangingOnIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos galaxyAt = lane(helper, 0);
        BlockPos jetAt = lane(helper, 1);
        force(level, galaxyAt, true);
        force(level, jetAt, true);
        AbstractVehicle galaxy = spawn(helper, "c5", galaxyAt);
        AbstractVehicle jet = galaxy == null ? null : spawn(helper, "f22a", jetAt);
        if (galaxy == null || jet == null) {
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(galaxy.hits() != null && jet.hits() != null, "hit models not built"))
                .thenIdle(3)
                .thenExecute(() -> {
                    galaxy.hullComponents().setHealth(galaxy.hullComponents().indexOf("left wing"), 0);
                    jet.hullComponents().setHealth(jet.hullComponents().indexOf("left wing"), 0);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    float[] galaxyEngines = engines(galaxy);
                    float[] jetEngines = engines(jet);
                    float galaxyScale = VehicleSystems.engineScale(galaxy);
                    float jetScale = VehicleSystems.engineScale(jet);
                    boolean galaxyRuns = VehicleSystems.engineWorks(galaxy);
                    galaxy.discard();
                    jet.discard();
                    force(level, galaxyAt, false);
                    force(level, jetAt, false);
                    String state = String.format("c5 engine pools %s scale %.2f runs %s; f22a engine pools %s scale %.2f",
                            java.util.Arrays.toString(galaxyEngines), galaxyScale, galaxyRuns,
                            java.util.Arrays.toString(jetEngines), jetScale);
                    CombinedAssault.LOGGER.info("[aircraft-hits] {}", state);
                    int dead = 0, alive = 0;
                    for (float h : galaxyEngines) {
                        dead += h <= 0 ? 1 : 0;
                        alive += h > 0 ? 1 : 0;
                    }
                    int jetDead = 0;
                    for (float h : jetEngines) {
                        jetDead += h <= 0 ? 1 : 0;
                    }
                    if (dead != 2 || alive != 2 || !galaxyRuns || galaxyScale > 0.55f || galaxyScale < 0.45f
                            || jetDead != 0 || jetScale < 0.99f) {
                        helper.fail(state);
                    }
                })
                .thenSucceed();
    }

    /** One of two engines broken => half thrust and still running; both broken => stopped. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void engineLossScalesThrustAndStopsOnlyWhenAllAreGone(GameTestHelper helper) {
        BlockPos at = lane(helper, 0);
        force(helper.getLevel(), at, true);
        AbstractVehicle jet = spawn(helper, "a10", at);
        if (jet == null) {
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(jet.hits() != null, "hit model not built"))
                .thenExecute(() -> {
                    HullComponents pools = jet.hullComponents();
                    List<Integer> engines = new ArrayList<>();
                    for (int p = 0; p < pools.size(); p++) {
                        if (pools.kind(p) == ComponentKind.ENGINE) {
                            engines.add(p);
                        }
                    }
                    float whole = VehicleSystems.engineScale(jet);
                    pools.setHealth(engines.get(0), 0);
                    float half = VehicleSystems.engineScale(jet);
                    boolean runsOnOne = VehicleSystems.engineWorks(jet);
                    pools.setHealth(engines.get(1), 0);
                    boolean runsOnNone = VehicleSystems.engineWorks(jet);
                    jet.discard();
                    force(helper.getLevel(), at, false);
                    if (engines.size() != 2 || whole < 0.99f || half < 0.45f || half > 0.55f || !runsOnOne || runsOnNone) {
                        helper.fail(String.format("a10 engines %d, scale %.2f -> %.2f, runs on one %s, on none %s",
                                engines.size(), whole, half, runsOnOne, runsOnNone));
                    }
                })
                .thenSucceed();
    }

    /** MQ-9 stations 0 2 4 hang on the left wing: left wing pool 0 => their rounds are gone, the right three stay. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aLostWingLosesTheRoundsOnItsStations(GameTestHelper helper) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("wflib", "missile_cruise");
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return;
        }
        BlockPos at = lane(helper, 0);
        force(helper.getLevel(), at, true);
        AbstractVehicle mq9 = spawn(helper, "mq9", at);
        if (mq9 == null) {
            return;
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(mq9.hits() != null, "hit model not built"))
                .thenIdle(3)
                .thenExecute(() -> {
                    MissileHolderUnit holder = (MissileHolderUnit) mq9.getPartUnit("pylons").orElseThrow();
                    ItemStack round = new ItemStack(BuiltInRegistries.ITEM.get(id));
                    ListTag rounds = new ListTag();
                    for (int slot = 0; slot < 6; slot++) {
                        CompoundTag entry = (CompoundTag) round.save(helper.getLevel().registryAccess());
                        entry.putByte("Slot", (byte) slot);
                        rounds.add(entry);
                    }
                    CompoundTag tag = holder.serializeNBT(helper.getLevel().registryAccess());
                    tag.put("Rounds", rounds);
                    holder.deserializeNBT(helper.getLevel().registryAccess(), tag);
                    mq9.hullComponents().setHealth(mq9.hullComponents().indexOf("left wing"), 0);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    MissileHolderUnit holder = (MissileHolderUnit) mq9.getPartUnit("pylons").orElseThrow();
                    StringBuilder state = new StringBuilder();
                    boolean ok = true;
                    for (int slot = 0; slot < 6; slot++) {
                        boolean loaded = !holder.round(slot).isEmpty();
                        state.append(loaded ? '#' : '.');
                        ok &= loaded == (slot % 2 == 1);
                    }
                    boolean refused = !holder.canLoad(0, new ItemStack(BuiltInRegistries.ITEM.get(id)));
                    mq9.discard();
                    force(helper.getLevel(), at, false);
                    if (!ok || !refused) {
                        helper.fail("stations 0..5 after losing the left wing: " + state + ", slot 0 refuses reload " + refused);
                    }
                })
                .thenSucceed();
    }

    private static float[] engines(AbstractVehicle v) {
        HullComponents pools = v.hullComponents();
        List<Float> out = new ArrayList<>();
        for (int p = 0; p < pools.size(); p++) {
            if (pools.kind(p) == ComponentKind.ENGINE) {
                out.add(pools.health(p));
            }
        }
        float[] arr = new float[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private static BlockPos lane(GameTestHelper helper, int index) {
        return helper.absolutePos(BlockPos.ZERO).offset(LANE_X + LANE_PITCH * index, 120, 0);
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, String id, BlockPos at) {
        ResourceLocation rl = CombinedAssault.modLocation(id);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(rl);
        if (data.isEmpty()) {
            helper.fail(rl + " is not in the roster");
            return null;
        }
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(rl);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(rl + " could not be added to the world");
            return null;
        }
        return vehicle;
    }

    private static void force(ServerLevel level, BlockPos at, boolean forced) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 6; dz++) {
                ForcedChunks.set(level, (at.getX() >> 4) + dx, (at.getZ() >> 4) + dz, forced);
            }
        }
    }
}
