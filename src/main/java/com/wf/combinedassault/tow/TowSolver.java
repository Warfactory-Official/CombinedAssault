package com.wf.combinedassault.tow;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.cargo.VehicleParachute;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * After the rigid step (which holds every coupling as joint rows: ball / rope, towed axle, fifth-wheel stop): parting,
 * strain readout, a hanging load's heading and sway.
 */
public final class TowSolver {

    /** deg tractor<->trailer at fifth-wheel stops; past this = cab-and-trailer collision (jackknife). */
    public static final double JACKKNIFE_LIMIT = 78.0;

    /** Blocks over nominal length => break; a step finding a coupling this far open leaves it out. */
    public static final double BREAK_MARGIN = 4.0;

    /** Ticks strained before break. */
    private static final int BREAK_TICKS = 100;

    /** Blocks open = strain, not terrain noise. Washboard roads open/close ~1 block. */
    private static final double STRAIN = 1.2;

    /** Blocks past the sling's length in one tick => parted. */
    private static final double SLING_BREAK = 3.0;

    /** deg/tick a hanging load's heading follows the aircraft's. */
    private static final double SLING_YAW_RATE = 2.0;

    /** Hanging load's horizontal velocity relative to the aircraft lost per tick (air drag on the swing). */
    private static final double SLING_SWAY_DAMPING = 0.04;

    private TowSolver() {
    }

    public static void tick(ServerLevel level) {
        List<AbstractVehicle> towed = TowLinks.towedIn(level);
        if (towed.isEmpty()) {
            return;
        }
        for (AbstractVehicle vehicle : new ArrayList<>(towed)) {
            TowLink link = vehicle.towLink();
            if (link == null) {
                continue;
            }
            AbstractVehicle tractor = link.tractor();
            HitchInfo hitch = link.hitch();
            if (hitch == null || vehicle.isRemoved() || tractor.isRemoved() || vehicle.level() != tractor.level()
                    || !check(tractor, vehicle, link, hitch)) {
                TowLinks.uncouple(vehicle);
            }
        }
    }

    /** @return false when the coupling has parted */
    private static boolean check(AbstractVehicle tractor, AbstractVehicle towed, TowLink link, HitchInfo hitch) {
        double slack = link.mode() == TowMode.HITCH ? 0 : link.cable();
        double open = TowLinks.hitchPoint(tractor, hitch).distanceTo(TowLinks.attachPoint(towed, link.mode())) - slack;
        if (link.mode() == TowMode.SLING) {
            link.recordStrain(Math.max(0, open), false);
            if (open > SLING_BREAK) {
                return false;
            }
            if (!towed.onGround()) {
                hang(tractor, towed);
            }
            return true;
        }
        link.recordStrain(Math.max(0, open), open > STRAIN);
        return open <= BREAK_MARGIN && link.strainTicks() <= BREAK_TICKS;
    }

    /** Hanging: heading follows the aircraft, swing damped, hull steadied. */
    private static void hang(AbstractVehicle tractor, AbstractVehicle towed) {
        double turn = Mth.clamp(Mth.wrapDegrees(tractor.getYRot() - towed.getYRot()), -SLING_YAW_RATE, SLING_YAW_RATE);
        towed.setYRot(towed.getYRot() + (float) turn);
        Vec3 sway = towed.getDeltaMovement().subtract(tractor.getDeltaMovement()).scale(-SLING_SWAY_DAMPING);
        VehicleParachute.steady(towed, new Vector3f((float) sway.x, 0, (float) sway.z));
        towed.updateOBBs();
    }

}
