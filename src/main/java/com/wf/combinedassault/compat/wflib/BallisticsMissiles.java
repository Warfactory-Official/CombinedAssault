package com.wf.combinedassault.compat.wflib;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.item.MissileItem;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.item.MissilePresetRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;

/** WFLib missile items (their own preset) and command launches. */
final class BallisticsMissiles {

    /** Blocks/tick off the rail; sets the interceptor's boost heading. */
    private static final double INTERCEPTOR_EXIT = 1.0;

    private BallisticsMissiles() {
    }

    /**
     * Fire a racked round.
     *
     * @return true if the round was launched, in which case the caller must consume it. False means this is
     * not a WFLib missile; the caller should leave the round alone and fall back.
     */
    static boolean shootRound(AbstractVehicle vehicle, WeaponUnit unit, AimContext aimContext,
                              ItemStack round, @Nullable Entity locked, @Nullable Entity operator) {
        WeaponUnit rootWeaponUnit = unit.getRootParentWeaponUnit();
        if (!(vehicle.level() instanceof ServerLevel level) || round.isEmpty()
                || !(round.getItem() instanceof MissileItem missileItem)) {
            return false;
        }
        Vec3 target = aimPoint(aimContext, locked);
        if (target == null) {
            return false;
        }
        // Preset (not launcher config) decides flight + arrival; that's the point of loading a particular missile.
        MissileEntity missile = missileItem.preset().build(level, target);
        if (rootWeaponUnit.getIndirectFireSpec() != null && locked == null) {
            // Fire mission point: map height may be a placeholder for unexplored ground.
            missile.setGroundTarget();
        }
        launch(level, vehicle, unit, missile, aimContext, locked, operator);
        GuidedRounds.guide(missile, rootWeaponUnit);
        return true;
    }

    /**
     * @return where the missile is being sent, or null if there is nothing to tell it. A WFLib missile
     * is given its destination at launch and flies its own guidance there: no aim point and no lock means
     * falling back to the vehicle's own missile, not refusing to fire.
     */
    @Nullable
    private static Vec3 aimPoint(AimContext aimContext, @Nullable Entity locked) {
        if (aimContext.from == null) {
            return null;
        }
        if (aimContext.position != null) {
            return aimContext.position;
        }
        return locked == null ? null : locked.position();
    }

    /**
     * Launch a preset from a point at a point, with no vehicle behind it. For commands and tests: the
     * naval harness fires torpedoes at hulls this way.
     */
    static boolean launch(ServerLevel level, ResourceLocation presetId, Vec3 from, Vec3 target) {
        MissilePreset preset;
        try {
            preset = MissilePresetRegistry.get(presetId);
        } catch (RuntimeException unknown) {
            return false;
        }
        if (preset == null) {
            return false;
        }
        MissileEntity missile = preset.build(level, target);
        Vec3 direction = target.subtract(from).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, direction.y))));
        missile.moveTo(from.x, from.y, from.z, yaw, pitch);
        level.addFreshEntity(missile);
        return true;
    }

    /** Team: the operator's side, else the vehicle's; operator's terminal hears the round. */
    static void side(MissileEntity missile, AbstractVehicle vehicle, @Nullable Entity operator) {
        java.util.UUID team = operator == null ? null : com.wf.wflib.recon.ReconOwners.owningEntity(operator);
        missile.setTeamId(team != null ? team : com.wf.wflib.recon.ReconOwners.owningEntity(vehicle));
        if (operator instanceof net.minecraft.world.entity.player.Player) {
            missile.setOperatorId(operator.getUUID());
        }
    }

    private static void launch(ServerLevel level, AbstractVehicle vehicle, WeaponUnit launcher, MissileEntity missile,
                               AimContext aimContext, @Nullable Entity locked, @Nullable Entity operator) {
        // controlId = launcher UUID, not faction: keeps a salvo from self-detonating. Distinct from WF-B team id.
        missile.setControlId(vehicle.getUUID());
        boolean interceptor = missile.interceptor().isActive();
        if (locked != null && interceptor) {
            missile.interceptor().setLock(locked.getUUID());
        } else if (locked != null) {
            missile.seeker().setDesignatedTarget(locked.getUUID());
        }
        missile.moveTo(aimContext.from.x, aimContext.from.y, aimContext.from.z,
                aimContext.direction.y, aimContext.direction.x);
        // Leaves at the launcher's speed; an air_launch round drops on it. Interceptor: boosts along the rail.
        // Cold launch: ejected along the hull axes first.
        Vec3 launch = vehicle.getDeltaMovement();
        if (interceptor) {
            launch = launch.add(Vec3.directionFromRotation(aimContext.direction.x, aimContext.direction.y)
                    .scale(INTERCEPTOR_EXIT));
        } else if (launcher.getColdLaunchTimeTick() > 0 && vehicle.getMainCubeOBB() != null) {
            org.joml.Vector3f[] axes = vehicle.getMainCubeOBB().obb().getAxes();
            Vec3 eject = launcher.getColdLaunchVelocity();
            launch = launch.add(new Vec3(axes[0]).scale(eject.x)).add(new Vec3(axes[1]).scale(eject.y))
                    .add(new Vec3(axes[2]).scale(eject.z));
        }
        missile.setDeltaMovement(launch);
        missile.setOwner(vehicle);
        side(missile, vehicle, operator);
        level.addFreshEntity(missile);
    }

}
