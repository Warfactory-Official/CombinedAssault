package com.wf.combinedassault.indirect;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;

/** Package boundary: world-facing calls in, pure arithmetic out. */
public final class IndirectFire {

    /** Degrees. */
    public static final double LAID_TOLERANCE = 0.05;

    /** Far enough the aim point is effectively a direction; pivot it's measured from makes it exact. */
    private static final double AIM_DISTANCE = 1024.0;

    private IndirectFire() {
    }

    /**
     * Aims at a far point along the laid direction rather than setting mount angles directly, so the gun
     * goes through the normal slew/clamp/sync path and stops at its stop if it can't elevate far enough.
     */
    public static void lay(WeaponUnit unit) {
        GunLaying laying = unit.indirectFire();
        if (!laying.isIndirect()) {
            return;
        }
        unit.aimAlong(laying.direction(), AIM_DISTANCE);
    }

    /**
     * Lay on a point: ballistic solution, or for a self-guided round the bearing at the station's launch
     * elevation. False if unreachable or the station has no indirect fire.
     */
    public static boolean solveTo(WeaponUnit unit, Vec3 target) {
        IndirectFireSpec station = unit.getIndirectFireSpec();
        if (station == null) {
            return false;
        }
        if (selfGuided(unit)) {
            unit.indirectFire().designate(muzzleOf(unit), target, station.launchElevation());
            return true;
        }
        RoundSpec spec = specOf(unit);
        return spec != null && unit.indirectFire().solveTo(spec, muzzleOf(unit), target);
    }

    /** Round flies its own guidance to the point: nothing to solve. */
    public static boolean selfGuided(WeaponUnit unit) {
        return unit.getCurrentWeapon().orElse(null) instanceof com.wf.combinedassault.weapon.VehicleMissile;
    }

    /** Null if the current weapon's round isn't one the solver understands. */
    @Nullable
    public static RoundSpec specOf(WeaponUnit unit) {
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        if (weapon == null) {
            return null;
        }
        return com.wf.combinedassault.weapon.ordnance.Munitions.spec(weapon.solutionRound());
    }

    public static Vec3 muzzleOf(WeaponUnit unit) {
        AimContext context = unit.aimContext();
        return context.from;
    }

}
