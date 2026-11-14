package com.wf.combinedassault.pack.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.dynamics.part.LandingGearUnit;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.wf.combinedassault.ballistics.component.ComponentDamage;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * `-PoptionalPacks -PoptionalOnly=<ids>`: the ids {@link OptionalPackGameTest} selects all exist (count logged), and every
 * hardpoint weapon part of those vehicles spawns with a kit fitted and a current weapon.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class OptionalAircraftGameTest {

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void optionalOnlyIdsMatchAndHardpointsAreFitted(GameTestHelper helper) {
        String namespaces = System.getProperty("combinedassault.optionalPacks", "");
        String only = System.getProperty("combinedassault.optionalPacks.only", "");
        if (namespaces.isEmpty() || only.isEmpty()) {
            helper.succeed();
            return;
        }
        List<String> wanted = List.of(namespaces.split(","));
        List<String> keep = List.of(only.split(","));
        List<ResourceLocation> ids = CommonAssetsManager.vehicleDataManager().getVehicleData().keySet().stream()
                .filter(id -> wanted.contains(id.getNamespace()) && keep.contains(id.getPath())).sorted().toList();
        CombinedAssault.LOGGER.info("[optional-only] settle filter matches {} of {} ids", ids.size(), keep.size());
        List<String> problems = new ArrayList<>();
        if (ids.size() != keep.size()) {
            problems.add("matched " + ids.size() + " of " + keep.size());
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        for (ResourceLocation id : ids) {
            AbstractVehicle v = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                    .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
            v.setDisplayId(id);
            helper.getLevel().addFreshEntity(v);
            for (PartUnit<?> unit : v.getPartUnits()) {
                if (unit instanceof WeaponUnit weapon && weapon.kit() != null) {
                    boolean armed = weapon.currentWeaponId() != null;
                    CombinedAssault.LOGGER.info("[optional-only] {} {} kit {} weapon {}", id, unit.getId(), weapon.kit(),
                            weapon.currentWeaponId());
                    if (!armed) {
                        problems.add(id + "/" + unit.getId() + " kit " + weapon.kit() + " has no weapon");
                    }
                }
            }
            v.discard();
        }
        if (!problems.isEmpty()) {
            helper.fail(String.join("; ", problems));
            return;
        }
        helper.succeed();
    }

    /** flans_ww2:spitfire (optionalPacks): left wing pool 0 => `gun_l` BROKEN, `gun_r` not; repaired => both working. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aLostSpitfireWingSilencesOnlyItsGuns(GameTestHelper helper) {
        if (!System.getProperty("combinedassault.optionalPacks", "").contains("flans_ww2")) {
            helper.succeed();
            return;
        }
        ResourceLocation id = ResourceLocation.parse("flans_ww2:spitfire");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle v = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        v.setDisplayId(id);
        helper.getLevel().addFreshEntity(v);
        List<String> report = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(v.hullComponents().indexOf("left wing") >= 0, "no left wing pool"))
                .thenExecute(() -> {
                    HullComponents pools = v.hullComponents();
                    int left = pools.indexOf("left wing");
                    PartUnit<?> gl = v.getPartUnit("gun_l").orElseThrow();
                    PartUnit<?> gr = v.getPartUnit("gun_r").orElseThrow();
                    report.add("whole " + ComponentDamage.state(gl) + "/" + ComponentDamage.state(gr));
                    pools.setHealth(left, 0);
                    ComponentState lostL = ComponentDamage.state(gl);
                    ComponentState lostR = ComponentDamage.state(gr);
                    report.add("lost " + lostL + "/" + lostR);
                    pools.setHealth(left, pools.maxHealth(left));
                    ComponentState fixedL = ComponentDamage.state(gl);
                    ComponentState fixedR = ComponentDamage.state(gr);
                    report.add("repaired " + fixedL + "/" + fixedR);
                    CombinedAssault.LOGGER.info("[optional-only] spitfire wing loss: {}", report);
                    v.discard();
                    if (lostL != ComponentState.BROKEN || lostR == ComponentState.BROKEN || fixedL == ComponentState.BROKEN
                            || fixedR == ComponentState.BROKEN) {
                        helper.fail(report.toString());
                        return;
                    }
                    helper.succeed();
                });
    }

    /** flans_modern:a10: wing pylon pairs share a salvo group: 2 members, selection never lands on the second one. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aWingPylonPairIsOneSalvoSelection(GameTestHelper helper) {
        if (!System.getProperty("combinedassault.optionalPacks", "").contains("flans_modern")) {
            helper.succeed();
            return;
        }
        ResourceLocation id = ResourceLocation.parse("flans_modern:a10");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle v = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        v.setDisplayId(id);
        helper.getLevel().addFreshEntity(v);
        WeaponUnit pilot = (WeaponUnit) v.getPartUnit("pilot").orElseThrow();
        int size = pilot.secondaryWeapons.size();
        List<Integer> groupSizes = new ArrayList<>();
        for (var weapon : pilot.secondaryWeapons) {
            groupSizes.add(pilot.salvo(weapon).size());
        }
        List<Integer> landed = new ArrayList<>();
        for (int i = 0; i < 2 * size; i++) {
            pilot.switchWeapon(true, true, false);
            landed.add(pilot.getCurrentSecondaryWeaponIndex());
        }
        String report = "secondary " + size + ", salvo sizes " + groupSizes + ", landed " + landed;
        CombinedAssault.LOGGER.info("[optional-only] a10 pylon pairs: {}", report);
        v.discard();
        boolean pairs = size == 6 && groupSizes.stream().allMatch(n -> n == 2);
        boolean firstOnly = landed.stream().allMatch(n -> n % 2 == 0);
        if (!pairs || !firstOnly) {
            helper.fail(report);
            return;
        }
        helper.succeed();
    }

    /**
     * flans_modern:a10 parked twins: one loses its left wing => its left wing-gear part is severed (feet 3 -> 2). Tip drop is
     * logged only: a parked plane on gear has locked rotation (FixedWingVehicle.lockCenterRot), so it does not tip.
     * Gear up (setAll) drives all three landing_gear parts to their raised feet.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aLostWingDropsItsGearSideAndGearUpRaisesEveryPart(GameTestHelper helper) {
        if (!System.getProperty("combinedassault.optionalPacks", "").contains("flans_modern")) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(BlockPos.ZERO).offset(2300, 0, 0);
        BlockPos a = base;
        BlockPos b = base.offset(64, 0, 0);
        for (BlockPos c : List.of(a, b)) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    ForcedChunks.set(level, (c.getX() >> 4) + dx, (c.getZ() >> 4) + dz, true);
                }
            }
            for (int dx = -20; dx <= 20; dx++) {
                for (int dz = -20; dz <= 20; dz++) {
                    level.setBlockAndUpdate(c.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
                }
            }
        }
        ResourceLocation id = ResourceLocation.parse("flans_modern:a10");
        FixedWingVehicle[] twins = new FixedWingVehicle[2];
        List<String> report = new ArrayList<>();
        helper.startSequence()
                .thenExecute(() -> {
                    for (int i = 0; i < 2; i++) {
                        BlockPos c = i == 0 ? a : b;
                        twins[i] = (FixedWingVehicle) CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                                .construct(level, new Vec3(c.getX() + 0.5, c.getY() + 2, c.getZ() + 0.5), 0, 0);
                        twins[i].setDisplayId(id);
                        level.addFreshEntity(twins[i]);
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(twins[0].hits() != null && twins[1].hits() != null
                        && twins[1].hullComponents().indexOf("left wing") >= 0, "hit models not built"))
                .thenIdle(80)
                .thenExecute(() -> {
                    report.add("parked drop " + String.format(Locale.ROOT, "%.3f/%.3f", tipDrop(twins[0]), tipDrop(twins[1])));
                    HullComponents pools = twins[1].hullComponents();
                    pools.setHealth(pools.indexOf("left wing"), 0);
                    twins[1].holdAwake();
                    report.add("feet " + feet(twins[0]) + "/" + feet(twins[1]));
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    double intact = tipDrop(twins[0]);
                    double lost = tipDrop(twins[1]);
                    report.add(String.format(Locale.ROOT, "after loss intact %.3f lost %.3f; y %.3f/%.3f roll %.2f/%.2f", intact, lost,
                            twins[0].getY(), twins[1].getY(), twins[0].getZRot(), twins[1].getZRot()));
                    int parts = 0;
                    for (PartUnit<?> unit : twins[0].getPartUnits()) {
                        if (unit instanceof LandingGearUnit) {
                            parts++;
                        }
                    }
                    LandingGearUnit.setAll(twins[0], true);
                    for (int t = 0; t < 40; t++) {
                        for (PartUnit<?> unit : twins[0].getPartUnits()) {
                            if (unit instanceof LandingGearUnit gear) {
                                gear.tick();
                            }
                        }
                    }
                    double maxLevel = 0;
                    for (PartUnit<?> unit : twins[0].getPartUnits()) {
                        if (unit instanceof LandingGearUnit gear) {
                            maxLevel = Math.max(maxLevel, gear.level());
                        }
                    }
                    report.add("gear parts " + parts + ", max level after up " + maxLevel);
                    CombinedAssault.LOGGER.info("[optional-only] a10 wing gear: {}", report);
                    int f0 = feet(twins[0]);
                    int f1 = feet(twins[1]);
                    twins[0].discard();
                    twins[1].discard();
                    if (f0 != 3 || f1 != 2 || parts != 3 || maxLevel > 0.01) {
                        helper.fail(report.toString());
                        return;
                    }
                    helper.succeed();
                });
    }

    /**
     * flans_ww2 taildraggers (mustang, bf109, spitfire) on a stone strip: settle (tail-down attitude logged), taxi
     * 60 ticks at 1 b/t (20 m/s, over WingLoss.STRIKE_SPEED), then a three-point touchdown => both wing pools intact.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aTaildraggerTaxiingAtTakeOffSpeedKeepsBothWings(GameTestHelper helper) {
        if (!System.getProperty("combinedassault.optionalPacks", "").contains("flans_ww2")) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        List<String> ids = List.of("mustang", "bf109", "spitfire");
        BlockPos base = helper.absolutePos(BlockPos.ZERO).offset(2500, 0, 0);
        FixedWingVehicle[] planes = new FixedWingVehicle[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            BlockPos c = base.offset(48 * i, 0, 0);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 10; dz++) {
                    ForcedChunks.set(level, (c.getX() >> 4) + dx, (c.getZ() >> 4) + dz, true);
                }
            }
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 140; dz++) {
                    level.setBlockAndUpdate(c.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
                }
            }
        }
        List<String> report = new ArrayList<>();
        helper.startSequence()
                .thenExecute(() -> {
                    for (int i = 0; i < ids.size(); i++) {
                        BlockPos c = base.offset(48 * i, 0, 0);
                        ResourceLocation id = ResourceLocation.parse("flans_ww2:" + ids.get(i));
                        planes[i] = (FixedWingVehicle) CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                                .construct(level, new Vec3(c.getX() + 0.5, c.getY() + 3, c.getZ() + 0.5), 0, 0);
                        planes[i].setDisplayId(id);
                        level.addFreshEntity(planes[i]);
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(java.util.Arrays.stream(planes)
                        .allMatch(v -> v.hits() != null && v.hullComponents().indexOf("left wing") >= 0), "hit models not built"))
                .thenIdle(80)
                .thenExecute(() -> {
                    for (int i = 0; i < ids.size(); i++) {
                        report.add(String.format(Locale.ROOT, "%s parked pitch %.1f (ground_pitch %.1f)", ids.get(i),
                                -planes[i].getXRot(), planes[i].groundPitch));
                    }
                })
                .thenExecuteFor(60, () -> {
                    for (FixedWingVehicle v : planes) {
                        Vec3 look = v.getLookAngle();
                        Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
                        v.setDeltaMovement(flat.x, v.getDeltaMovement().y, flat.z);
                        v.holdAwake();
                    }
                })
                .thenExecute(() -> {
                    for (int i = 0; i < ids.size(); i++) {
                        HullComponents pools = planes[i].hullComponents();
                        report.add(String.format(Locale.ROOT, "%s taxied to z %.0f: wings %.0f/%.0f", ids.get(i),
                                planes[i].getZ() - base.getZ(), pools.health(pools.indexOf("left wing")),
                                pools.health(pools.indexOf("right wing"))));
                        // three-point touchdown: 3 blocks up at ground_pitch, 0.9 b/t forward, 0.15 b/t sink
                        planes[i].setPos(planes[i].getX(), planes[i].getY() + 3, base.getZ() + 20);
                        planes[i].setXRot(-planes[i].groundPitch);
                    }
                })
                .thenExecuteFor(5, () -> {
                    for (FixedWingVehicle v : planes) {
                        Vec3 look = v.getLookAngle();
                        Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
                        v.setDeltaMovement(flat.x * 0.9, -0.15, flat.z * 0.9);
                        v.holdAwake();
                    }
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    boolean ok = true;
                    for (int i = 0; i < ids.size(); i++) {
                        HullComponents pools = planes[i].hullComponents();
                        float l = pools.health(pools.indexOf("left wing"));
                        float r = pools.health(pools.indexOf("right wing"));
                        report.add(String.format(Locale.ROOT, "%s after touchdown y %.2f pitch %.1f: wings %.0f/%.0f",
                                ids.get(i), planes[i].getY() - base.getY(), -planes[i].getXRot(), l, r));
                        ok &= l > 0 && r > 0;
                        planes[i].discard();
                    }
                    CombinedAssault.LOGGER.info("[optional-only] taildragger taxi: {}", report);
                    if (!ok) {
                        helper.fail(report.toString());
                        return;
                    }
                    helper.succeed();
                });
    }

    private static int feet(AbstractVehicle v) {
        int n = 0;
        for (PartUnit<?> unit : v.getPartUnits()) {
            if (unit instanceof LandingGearUnit gear && !ComponentDamage.severed(gear)) {
                n += gear.footCount();
            }
        }
        return n;
    }

    /** Right wing centre y minus left wing centre y (+ = left tip low). */
    private static double tipDrop(FixedWingVehicle v) {
        return wingCentre(v, "right wing").y - wingCentre(v, "left wing").y;
    }

    private static Vec3 wingCentre(FixedWingVehicle v, String pool) {
        VehicleHits hits = v.hits();
        List<HitModel.Node> nodes = hits.model().nodes();
        Matrix4f pose = new Matrix4f();
        Vector3f sum = new Vector3f();
        int n = 0;
        for (int i = 0; i < nodes.size(); i++) {
            HitModel.Node node = nodes.get(i);
            if (node.kind() != ComponentKind.WING || node.mesh().mesh() == null || !pool.equals(node.systemId())) {
                continue;
            }
            float[] bb = node.bounds();
            hits.pose(i, v.position(), pose);
            sum.add(pose.transformPosition(new Vector3f((bb[0] + bb[3]) / 2, (bb[1] + bb[4]) / 2, (bb[2] + bb[5]) / 2)));
            n++;
        }
        if (n == 0) {
            throw new IllegalStateException(v.getDisplayId() + " has no drawn " + pool + " nodes");
        }
        sum.div(n);
        return v.position().add(sum.x, sum.y, sum.z);
    }
}
