package com.wf.combinedassault.compat.wflib;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.api.BeamSource;
import com.wf.wflib.missile.SeekerMode;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.countermeasure.Countermeasures;
import com.wf.wflib.countermeasure.FlareEntity;
import com.wf.wflib.countermeasure.SmokeGrenadeEntity;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.VehicleMissile;

import java.util.Map;
import java.util.Optional;

/** WFLib vehicle missiles: seeker mapping, countermeasures, glide, beam riding. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class MissileRoundsGameTest {

    private static final String TEMPLATE = "empty";

    @Nullable
    private static MissilePreset data(GameTestHelper helper, String id) {
        MissilePreset preset = MissilePresetRegistry.get(ResourceLocation.fromNamespaceAndPath("wflib", id));
        if (preset == null) {
            helper.fail("no WFLib missile " + id);
        }
        return preset;
    }

    /** Round at {@code at} flying toward {@code target}, locked on it. */
    private static MissileEntity round(ServerLevel level, MissilePreset data, Vec3 at, Entity target) {
        MissileEntity m = data.builder(level, target.position()).startArmed().build();
        m.moveTo(at.x, at.y, at.z, 0.0f, 0.0f);
        m.setDeltaMovement(target.position().subtract(at).normalize().scale(4.0));
        m.seeker().setDesignatedTarget(target.getUUID());
        level.addFreshEntity(m);
        return m;
    }

    private static ArmorStand stand(ServerLevel level, Vec3 at) {
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
        stand.moveTo(at.x, at.y, at.z);
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        return stand;
    }

    /** Seeker per WFLib vehicle missile. */
    @GameTest(template = TEMPLATE)
    public static void everyMissileWeaponMapsToASeeker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Map<String, SeekerMode> expect = Map.of("aim_9", SeekerMode.INFRARED, "aim_120", SeekerMode.ACTIVE_RADAR,
                "fb_10", SeekerMode.SEMI_ACTIVE_RADAR, "tow", SeekerMode.OPTICAL, "agm_114", SeekerMode.BEAM_RIDING,
                "df_41", SeekerMode.DESIGNATED);
        for (Map.Entry<String, SeekerMode> e : expect.entrySet()) {
            MissilePreset d = data(helper, e.getKey());
            if (d == null) {
                return;
            }
            MissileEntity m = d.build(level, Vec3.ZERO);
            double speed = m.flight().getCruiseSpeed();
            if (m.seeker().mode() != e.getValue() || !(speed > 0.0) || !Double.isFinite(speed)) {
                helper.fail(e.getKey() + ": seeker " + m.seeker().mode() + ", cruise " + speed);
                return;
            }
        }
        MissilePreset df41 = data(helper, "df_41");
        if (df41 != null && df41.build(level, Vec3.ZERO).flight().getCruiseMode()
                != MissileEntity.CruiseMode.HIGH_ALTITUDE) {
            helper.fail("df_41 does not fly its preset high profile");
            return;
        }
        helper.succeed();
    }

    /** A flare beside the target, in the cone, takes an IR round's lock; control: no flare, lock held. */
    @GameTest(template = TEMPLATE)
    public static void aFlareTakesAHeatSeeker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MissilePreset aim9 = data(helper, "aim_9");
        if (aim9 == null) {
            return;
        }
        // Vertical: one column, loaded at every height; neighbour chunks of an arena are not.
        Vec3 from = helper.absoluteVec(new Vec3(1.5, 60.0, 1.5));
        ArmorStand target = stand(level, from.subtract(0.0, 40.0, 0.0));
        MissileEntity control = round(level, aim9, from, target);
        control.tick();
        if (!target.getUUID().equals(control.seeker().getDesignatedTargetId())) {
            helper.fail("no flare, lock lost: " + control.seeker().getDesignatedTargetId());
            return;
        }
        control.kill();
        FlareEntity flare = new FlareEntity(Countermeasures.FLARE.get(), level);
        flare.moveTo(target.getX() + 1.0, target.getY(), target.getZ());
        level.addFreshEntity(flare);
        MissileEntity decoyed = round(level, aim9, from, target);
        decoyed.tick();
        boolean seduced = flare.getUUID().equals(decoyed.seeker().getDesignatedTargetId());
        decoyed.kill();
        flare.discard();
        target.discard();
        if (!seduced) {
            helper.fail("IR round kept " + decoyed.seeker().getDesignatedTargetId() + " past a flare");
            return;
        }
        helper.succeed();
    }

    /** Smoke on the line of sight breaks an optical lock; control: no smoke, lock held. */
    @GameTest(template = TEMPLATE)
    public static void smokeBreaksAnOpticalLock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MissilePreset tow = data(helper, "tow");
        if (tow == null) {
            return;
        }
        Vec3 from = helper.absoluteVec(new Vec3(1.5, 60.0, 1.5));
        ArmorStand target = stand(level, from.subtract(0.0, 40.0, 0.0));
        MissileEntity control = round(level, tow, from, target);
        control.tick();
        boolean locked = target.getUUID().equals(control.seeker().getDesignatedTargetId());
        control.kill();
        if (!locked) {
            target.discard();
            helper.fail("no smoke, lock lost: " + control.seeker().getDesignatedTargetId());
            return;
        }
        SmokeGrenadeEntity smoke = new SmokeGrenadeEntity(Countermeasures.SMOKE_GRENADE.get(), level);
        Vec3 mid = from.add(target.getBoundingBox().getCenter().subtract(from).scale(0.5));
        smoke.moveTo(mid.x, mid.y, mid.z);
        smoke.setNoGravity(true);
        level.addFreshEntity(smoke);
        MissileEntity m = round(level, tow, from, target);
        m.tick();
        boolean lost = m.seeker().getDesignatedTargetId() == null;
        m.kill();
        smoke.discard();
        target.discard();
        if (!lost) {
            helper.fail("optical round saw through smoke");
            return;
        }
        helper.succeed();
    }

    /** Short burn, then guided glide on momentum: no ballistic drop at burn-out. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void anAirToAirRoundGlidesAfterBurnout(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MissilePreset aim9 = data(helper, "aim_9");
        if (aim9 == null) {
            return;
        }
        Vec3 from = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ArmorStand target = stand(level, from.add(0.0, 4000.0, 0.0));
        MissileEntity m = round(level, aim9, from, target);
        int burn = m.motor().getFuel();
        for (int i = 0; i < burn + 20; i++) {
            m.tick();
        }
        double speed = m.getDeltaMovement().length();
        boolean guided = target.getUUID().equals(m.seeker().getDesignatedTargetId());
        boolean downed = m.damage().isDowned();
        m.kill();
        target.discard();
        if (m.motor().getFuel() > 0 || downed || !guided || !(speed > 1.0)) {
            helper.fail(String.format("after burn: fuel %d, downed %s, guided %s, speed %.2f",
                    m.motor().getFuel(), downed, guided, speed));
            return;
        }
        helper.succeed();
    }

    /** A beam rider aims ahead on the guiding unit's line of sight. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aBeamRiderFliesTheLaunchersSight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Optional<BaseVehicleData> heli = CommonAssetsManager.vehicleDataManager()
                .getVehicleData(CombinedAssault.modLocation("ah64d"));
        MissilePreset agm = data(helper, "agm_114");
        if (heli.isEmpty() || agm == null) {
            helper.fail("ah64d / wflib:agm_114 missing");
            return;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = heli.get().construct(level, Vec3.atBottomCenterOf(at), 0, 0);
        vehicle.setDisplayId(CombinedAssault.modLocation("ah64d"));
        boolean added = level.addFreshEntity(vehicle);
        if (!added) {
            helper.fail("ah64d not added");
            return;
        }
        // Same tick: a neighbouring arena's setup clears entities overlapping it, and a helicopter overlaps.
        WeaponUnit unit = null;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit w) {
                for (AbstractVehicleWeapon<?> weapon : w.getIndexedWeapons()) {
                    if (weapon instanceof VehicleMissile vm && vm.isAmmoForWeapon(new net.minecraft.world.item.ItemStack(
                            com.wf.wflib.item.ModItems.missileItem(agm.id()).orElseThrow().get()))) {
                        unit = w;
                    }
                }
            }
        }
        if (unit == null) {
            vehicle.discard();
            helper.fail("no weapon unit carries agm_114");
            return;
        }
        Vec3 from = vehicle.position().add(3.0, 2.0, 5.0);
        MissileEntity m = agm.builder(level, from.add(0.0, 0.0, 200.0)).startArmed().build();
        m.moveTo(from.x, from.y, from.z, 0.0f, 0.0f);
        m.setDeltaMovement(0.0, 0.0, 3.0);
        m.setControlId(vehicle.getUUID());
        level.addFreshEntity(m);
        GuidedRounds.guide(m, unit);
        m.tick();
        BeamSource.Beam beam = GuidedRounds.beam(m);
        Vec3 aim = m.flight().getTarget();
        m.kill();
        vehicle.discard();
        if (beam == null) {
            helper.fail("no beam for a guided round");
            return;
        }
        Vec3 rel = aim.subtract(beam.origin());
        double off = rel.subtract(beam.direction().scale(rel.dot(beam.direction()))).length();
        if (off > 1.0E-3 || rel.dot(beam.direction()) <= 0.0) {
            helper.fail(String.format("aim %.3f off the beam, %.1f along", off, rel.dot(beam.direction())));
            return;
        }
        helper.succeed();
    }
}
