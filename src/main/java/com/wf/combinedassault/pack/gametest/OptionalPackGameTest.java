package com.wf.combinedassault.pack.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * `-PoptionalPacks` (gradle copies optional_packs/* into the run): every vehicle of a namespace in
 * `combinedassault.optionalPacks` (comma list) dropped 1 block onto its own stone pad settles upright and still.
 * Property absent => vacuous.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class OptionalPackGameTest {

    private static final String TEMPLATE = "empty";
    private static final int LANE_Z = 2400;
    private static final int SPACING = 48;
    private static final int HALF = 10;
    private static final int TICKS = 200;
    private static final int STILL_FROM = 140;

    @GameTest(template = TEMPLATE, timeoutTicks = 600)
    public static void everyOptionalPackVehicleSettles(GameTestHelper helper) {
        String namespaces = System.getProperty("combinedassault.optionalPacks", "");
        if (namespaces.isEmpty()) {
            helper.succeed();
            return;
        }
        List<String> wanted = List.of(namespaces.split(","));
        List<ResourceLocation> ids = new ArrayList<>(CommonAssetsManager.vehicleDataManager().getVehicleData().keySet()
                .stream().filter(id -> wanted.contains(id.getNamespace())).sorted().toList());
        if (ids.isEmpty()) {
            helper.fail("no vehicles from " + namespaces);
            return;
        }
        String only = System.getProperty("combinedassault.optionalPacks.only", "");
        if (!only.isEmpty()) {
            List<String> keep = List.of(only.split(","));
            ids.removeIf(id -> !keep.contains(id.getPath()));
        }
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z);
        int n = ids.size();
        AbstractVehicle[] vehicles = new AbstractVehicle[n];
        float[][] lo = new float[n][3];
        float[][] hi = new float[n][3];
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos c = origin.offset(i * SPACING, 0, 0);
            forced(level, c, true);
            pad(level, c, Blocks.STONE.defaultBlockState().getBlock());
            lo[i] = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            hi[i] = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        }
        var sequence = helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(origin.offset((n - 1) * SPACING, 0, 0)),
                        "lanes not ticking yet"))
                .thenExecute(() -> {
                    for (int i = 0; i < n; i++) {
                        BlockPos c = origin.offset(i * SPACING, 0, 0);
                        try {
                            AbstractVehicle v = CommonAssetsManager.vehicleDataManager().getVehicleData(ids.get(i))
                                    .orElseThrow().construct(level, new Vec3(c.getX() + 0.5, c.getY() + 2, c.getZ() + 0.5), 0, 0);
                            v.setDisplayId(ids.get(i));
                            level.addFreshEntity(v);
                            vehicles[i] = v;
                        } catch (RuntimeException e) {
                            problems.add(ids.get(i) + ": construct " + e);
                        }
                    }
                });
        for (int t = 1; t <= TICKS; t++) {
            int tick = t;
            sequence = sequence.thenIdle(1).thenExecute(() -> {
                if (tick < STILL_FROM) {
                    return;
                }
                for (int i = 0; i < n; i++) {
                    AbstractVehicle v = vehicles[i];
                    if (v == null) {
                        continue;
                    }
                    float[] s = {(float) v.getY(), Mth.wrapDegrees(v.getXRot()), Mth.wrapDegrees(v.getZRot())};
                    for (int k = 0; k < 3; k++) {
                        lo[i][k] = Math.min(lo[i][k], s[k]);
                        hi[i][k] = Math.max(hi[i][k], s[k]);
                    }
                }
            });
        }
        sequence.thenExecute(() -> {
            for (int i = 0; i < n; i++) {
                AbstractVehicle v = vehicles[i];
                BlockPos c = origin.offset(i * SPACING, 0, 0);
                if (v != null) {
                    // fixed wing rests at ground_pitch (nose up = -xRot)
                    float pitch = Mth.wrapDegrees(v.getXRot()
                            + (v instanceof com.wf.combinedassault.aircraft.FixedWingVehicle plane ? plane.groundPitch : 0));
                    float roll = Mth.wrapDegrees(v.getZRot());
                    double drop = c.getY() + 1 - v.getY();
                    if (hi[i][0] - lo[i][0] > 0.01f || hi[i][1] - lo[i][1] > 0.2f || hi[i][2] - lo[i][2] > 0.2f) {
                        problems.add(String.format(Locale.ROOT, "%s still moving: y %.3f pitch %.2f roll %.2f", ids.get(i),
                                hi[i][0] - lo[i][0], hi[i][1] - lo[i][1], hi[i][2] - lo[i][2]));
                    }
                    if (Math.abs(pitch) > 6 || Math.abs(roll) > 3 || drop > 1.5 || !Double.isFinite(v.getY())) {
                        problems.add(String.format(Locale.ROOT, "%s at rest: pitch %.1f roll %.1f, origin %.2f below pad",
                                ids.get(i), pitch, roll, drop));
                    }
                    v.discard();
                }
                pad(level, c, Blocks.AIR);
                forced(level, c, false);
            }
            if (!problems.isEmpty()) {
                helper.fail(n + " vehicles, " + problems.size() + " problems: " + String.join("; ", problems));
            }
        }).thenSucceed();
    }

    /**
     * Every hardpoint part of an optional-pack vehicle: spawns with its kit fitted (gun present), takes it off (silent),
     * refits it. `combinedassault.optionalPacks.only` filters like the settle test.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void everyOptionalPackHardpointFitsTakesOffAndRefits(GameTestHelper helper) {
        String namespaces = System.getProperty("combinedassault.optionalPacks", "");
        if (namespaces.isEmpty()) {
            helper.succeed();
            return;
        }
        List<String> wanted = List.of(namespaces.split(","));
        String only = System.getProperty("combinedassault.optionalPacks.only", "");
        List<String> keep = only.isEmpty() ? List.of() : List.of(only.split(","));
        List<String> problems = new ArrayList<>();
        int checked = 0;
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        for (ResourceLocation id : CommonAssetsManager.vehicleDataManager().getVehicleData().keySet().stream().sorted().toList()) {
            if (!wanted.contains(id.getNamespace()) || (!keep.isEmpty() && !keep.contains(id.getPath()))) {
                continue;
            }
            AbstractVehicle v = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                    .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
            v.setDisplayId(id);
            helper.getLevel().addFreshEntity(v);
            for (var part : v.getPartUnits()) {
                if (!(part instanceof WeaponUnit unit) || unit.hardpoint() == null) {
                    continue;
                }
                checked++;
                ResourceLocation kit = unit.kit();
                String what = id + "/" + unit.getData().getId();
                if (kit == null || unit.currentWeaponId() == null) {
                    problems.add(what + ": spawns without its kit");
                    continue;
                }
                unit.fitKit(-1);
                if (unit.kit() != null || unit.currentWeaponId() != null) {
                    problems.add(what + ": still armed after take off");
                }
                unit.fitKit(unit.hardpoint().indexOf(kit));
                if (!kit.equals(unit.kit()) || unit.currentWeaponId() == null) {
                    problems.add(what + ": refit gave " + unit.kit());
                }
            }
            v.discard();
        }
        if (checked == 0) {
            problems.add("no hardpoint parts found");
        }
        if (!problems.isEmpty()) {
            helper.fail(checked + " hardpoints, " + problems.size() + " problems: " + String.join("; ", problems));
            return;
        }
        CombinedAssault.LOGGER.info("[hardpoints] {} optional-pack hardpoints fit / take off / refit", checked);
        helper.succeed();
    }

    private static void pad(ServerLevel level, BlockPos c, net.minecraft.world.level.block.Block block) {
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                level.setBlockAndUpdate(c.offset(x, 0, z), block.defaultBlockState());
            }
        }
    }

    private static void forced(ServerLevel level, BlockPos c, boolean on) {
        for (int cx = (c.getX() - HALF) >> 4; cx <= (c.getX() + HALF) >> 4; cx++) {
            for (int cz = (c.getZ() - HALF) >> 4; cz <= (c.getZ() + HALF) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, on);
            }
        }
    }
}
