package com.wf.combinedassault.vehicle.weapon.gametest;

import com.mojang.math.Axis;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.round.Rounds;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.VehicleCannon;
import com.wf.combinedassault.vehicle.weapon.VehicleGrenade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Humvee roof_mount: hull + shield mount; gun = weapon-scoped mount glTF (Mk 19 / M2), swapped by modding tool. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class HumveeMountGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation M430 = ResourceLocation.fromNamespaceAndPath("wflib", "40x53/he");
    private static final ResourceLocation BMG = ResourceLocation.fromNamespaceAndPath("wflib", "12.7x99/ball");
    private static final ResourceLocation MK19_HUMVEE = CombinedAssault.modLocation("humvee_mk19");
    private static final ResourceLocation M2_HUMVEE = CombinedAssault.modLocation("humvee_m2");
    private static final ResourceLocation PLAIN = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation MK19 = CombinedAssault.modLocation("mk19");
    private static final ResourceLocation M2 = CombinedAssault.modLocation("gun_12.7mm");
    /** stage_emb4_humvee: T(MUZZLE), T(M2_MUZZLE) = (p + (0, 0.34, 0)) * 0.9, vehicle frame. */
    private static final Vec3 MK19_MUZZLE = new Vec3(0, 2.4552, 1.6083);
    private static final Vec3 M2_MUZZLE = new Vec3(0, 2.3904, 1.989);
    /** humvee.gltf roof_mount: the yaw axis. */
    private static final Vec3 YAW_AXIS = new Vec3(0, 0, -0.0162);
    private static final double EPS = 1e-3;

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void eachGunMountPutsTheMuzzleAtItsBarrelTip(GameTestHelper helper) {
        for (var c : List.of(new Object[]{MK19_HUMVEE, MK19_MUZZLE}, new Object[]{M2_HUMVEE, M2_MUZZLE})) {
            WeaponUnit unit = roofMount(helper, (ResourceLocation) c[0]);
            if (unit == null) {
                return;
            }
            Vec3 muzzle = unit.aimContext().from.subtract(unit.getVehicle().position());
            unit.getVehicle().discard();
            if (muzzle.distanceTo((Vec3) c[1]) > EPS) {
                helper.fail(c[0] + " muzzle " + muzzle + " != barrel tip " + c[1]);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theMuzzleTraversesWithTheRing(GameTestHelper helper) {
        for (var c : List.of(new Object[]{MK19_HUMVEE, MK19_MUZZLE}, new Object[]{M2_HUMVEE, M2_MUZZLE})) {
            WeaponUnit unit = roofMount(helper, (ResourceLocation) c[0]);
            if (unit == null) {
                return;
            }
            unit.setYRot(90);
            unit.updateRot();
            Vec3 muzzle = unit.aimContext().from.subtract(unit.getVehicle().position());
            unit.getVehicle().discard();
            Vector3f arm = ((Vec3) c[1]).subtract(YAW_AXIS).toVector3f();
            Axis.YN.rotationDegrees(90).transform(arm);
            Vec3 expected = YAW_AXIS.add(arm.x, arm.y, arm.z);
            if (muzzle.distanceTo(expected) > EPS) {
                helper.fail(c[0] + " muzzle at yaw 90 " + muzzle + " != " + expected);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void bothGunsShareTheShieldStation(GameTestHelper helper) {
        WeaponUnit mk19 = roofMount(helper, MK19_HUMVEE);
        WeaponUnit m2 = mk19 == null ? null : roofMount(helper, M2_HUMVEE);
        if (m2 == null) {
            return;
        }
        String seat = mk19.getData().getSeatOffset() + "/" + m2.getData().getSeatOffset();
        String view = mk19.getData().getOperatorViewOffset() + "/" + m2.getData().getOperatorViewOffset();
        String exit = mk19.getData().getDismountOffset() + "/" + m2.getData().getDismountOffset();
        boolean same = mk19.getData().getSeatOffset().equals(m2.getData().getSeatOffset())
                && mk19.getData().getOperatorViewOffset().equals(m2.getData().getOperatorViewOffset())
                && mk19.getData().getDismountOffset().equals(m2.getData().getDismountOffset())
                && mk19.getData().getSeatOffset().y > 2;
        mk19.getVehicle().discard();
        m2.getVehicle().discard();
        if (!same) {
            helper.fail("humvee_turret.gltf station differs or unread: seat " + seat + ", view " + view + ", exit " + exit);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theMk19FiresAWflibShellFromItsMuzzle(GameTestHelper helper) {
        WeaponUnit unit = roofMount(helper, MK19_HUMVEE);
        if (unit == null) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        if (!(weapon instanceof VehicleGrenade grenade)) {
            helper.fail("roof mount weapon " + weapon + " is not the Mk 19 grenade launcher");
            return;
        }
        grenade.setRemainAmmo(4);
        grenade.setLoadedRound(com.wf.wflib.ammo.AmmoItems.stack(M430, 1));
        var preset = fired(helper, unit, grenade.shoot(new ArrayList<>(List.of(unit.aimContext())), null));
        if (preset == null) {
            return;
        }
        if (!preset.id().equals(M430) || preset.blastSize() != 2.0f) {
            helper.fail("shell flies preset " + preset.id() + ", blast " + preset.blastSize() + "; want " + M430 + ", 2");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theM2FiresA127RoundFromItsMuzzle(GameTestHelper helper) {
        WeaponUnit unit = roofMount(helper, M2_HUMVEE);
        if (unit == null) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        if (!(weapon instanceof VehicleCannon gun)) {
            helper.fail("roof mount weapon " + weapon + " is not the 12.7mm gun");
            return;
        }
        gun.setRemainAmmo(4);
        gun.setLoadedRound(com.wf.wflib.ammo.AmmoItems.stack(BMG, 1));
        var preset = fired(helper, unit, gun.shoot(new ArrayList<>(List.of(unit.aimContext())), null));
        if (preset == null) {
            return;
        }
        if (!preset.id().equals(BMG)) {
            helper.fail("round flies preset " + preset.id() + " != " + BMG);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theModdingToolSwapsTheGunNotTheSeat(GameTestHelper helper) {
        WeaponUnit unit = roofMount(helper, MK19_HUMVEE);
        if (unit == null) {
            return;
        }
        unit.switchWeapon(false, true, false);
        ResourceLocation fromSeat = unit.currentWeaponId();
        unit.switchWeapon(false, true, true);
        ResourceLocation fromTool = unit.currentWeaponId();
        boolean interactive = unit.isInteractive();
        Object name = unit.getVehicle().getDisplayName().getContents() instanceof TranslatableContents t ? t.getKey()
                : unit.getVehicle().getDisplayName().getString();
        unit.getVehicle().discard();
        if (!MK19.equals(fromSeat) || !M2.equals(fromTool) || !interactive || !"entity.combinedassault.humvee_m2".equals(name)) {
            helper.fail("seat switch -> " + fromSeat + " (want " + MK19 + "), modding tool -> " + fromTool + " (want " + M2
                    + "), interactive " + interactive + ", name " + name + " (want the M2 Humvee's)");
            return;
        }
        helper.succeed();
    }

    /** {@code /data merge} reloads a live vehicle: one part set after, the picked part is the listed one. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aReloadedVehicleKeepsOnePartSet(GameTestHelper helper) {
        WeaponUnit before = roofMount(helper, MK19_HUMVEE);
        if (before == null) {
            return;
        }
        AbstractVehicle vehicle = before.getVehicle();
        int parts = vehicle.getPartUnits().size();
        vehicle.load(vehicle.saveWithoutId(new CompoundTag()));
        WeaponUnit unit = (WeaponUnit) vehicle.getPartUnit("roof_mount").orElseThrow();
        unit.switchWeapon(false, true, true);
        long listed = vehicle.getPartUnits().stream().filter(p -> p.getId().equals("roof_mount")).count();
        Object name = vehicle.getDisplayName().getContents() instanceof TranslatableContents t ? t.getKey() : null;
        int after = vehicle.getPartUnits().size();
        vehicle.discard();
        helper.assertTrue(after == parts && listed == 1, "parts " + parts + " -> " + after + ", roof_mount listed " + listed);
        helper.assertTrue("entity.combinedassault.humvee_m2".equals(name), "name after reload + swap " + name);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theSwappedGunBringsItsBoltAndHitMesh(GameTestHelper helper) {
        WeaponUnit unit = roofMount(helper, MK19_HUMVEE);
        if (unit == null) {
            return;
        }
        AbstractVehicle vehicle = unit.getVehicle();
        // Vertical ray through the M2 barrel ahead of the Mk 19 muzzle: only the M2 mount is there.
        double z = (MK19_MUZZLE.z + M2_MUZZLE.z) / 2;
        Vec3 top = vehicle.position().add(0, 3.5, z);
        Vec3 bottom = vehicle.position().add(0, 2.0, z);
        VehicleHits hits = vehicle.hits();
        boolean mk19Hit = hits.trace(top, bottom, i -> hits.part(i) == unit && hits.live(i)) != null;
        Vec3 mk19Muzzle = unit.aimContext().from.subtract(vehicle.position());
        unit.selectWeaponIndex(false, 1, true);
        boolean m2Hit = hits.trace(top, bottom, i -> hits.part(i) == unit && hits.live(i)) != null;
        Vec3 m2Muzzle = unit.aimContext().from.subtract(vehicle.position());
        CompoundTag saved = vehicle.saveWithoutId(new CompoundTag());
        vehicle.discard();
        AbstractVehicle reloaded = spawn(helper, MK19_HUMVEE);
        if (reloaded == null) {
            return;
        }
        reloaded.load(saved);
        ResourceLocation kept = ((WeaponUnit) reloaded.getPartUnit("roof_mount").orElseThrow()).currentWeaponId();
        reloaded.discard();
        if (mk19Hit || !m2Hit || mk19Muzzle.distanceTo(MK19_MUZZLE) > EPS || m2Muzzle.distanceTo(M2_MUZZLE) > EPS
                || !M2.equals(kept)) {
            helper.fail("ray at z " + z + ": Mk 19 mounted hit " + mk19Hit + ", M2 mounted hit " + m2Hit + "; muzzle "
                    + mk19Muzzle + " -> " + m2Muzzle + "; reloaded weapon " + kept);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void thePlainHumveeIsUnarmed(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, PLAIN);
        if (vehicle == null) {
            return;
        }
        List<String> armed = vehicle.getPartUnits().stream()
                .filter(part -> part instanceof WeaponUnit || !part.getData().getMounts().isEmpty())
                .map(part -> part.getId()).toList();
        vehicle.discard();
        if (!armed.isEmpty()) {
            helper.fail("plain Humvee carries " + armed);
            return;
        }
        helper.succeed();
    }

    /** Preset of the one round {@code shot} launched at the muzzle (destroyed); null after failing. Vehicle discarded. */
    @Nullable
    private static KineticPreset fired(GameTestHelper helper, WeaponUnit unit, boolean shot) {
        Vec3 muzzle = unit.aimContext().from;
        unit.getVehicle().discard();
        long[] rounds = Rounds.within(helper.getLevel(), new AABB(muzzle, muzzle).inflate(8));
        if (!shot || rounds.length != 1) {
            helper.fail("shot " + shot + ", " + rounds.length + " WFLib rounds after one shot");
            return null;
        }
        Vec3 at = Rounds.position(helper.getLevel(), rounds[0]);
        KineticPreset preset = Rounds.preset(helper.getLevel(), rounds[0]);
        Rounds.destroy(helper.getLevel(), rounds[0]);
        if (at.distanceTo(muzzle) > EPS) {
            helper.fail("round launched at " + at + ", muzzle " + muzzle);
            return null;
        }
        return preset;
    }

    @Nullable
    private static WeaponUnit roofMount(GameTestHelper helper, ResourceLocation id) {
        AbstractVehicle vehicle = spawn(helper, id);
        if (vehicle == null) {
            return null;
        }
        if (!(vehicle.getPartUnit("roof_mount").orElse(null) instanceof WeaponUnit unit)) {
            helper.fail(id + " has no roof_mount weapon part");
            return null;
        }
        return unit;
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail(id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(id + " could not be added to the world");
            return null;
        }
        return vehicle;
    }
}
