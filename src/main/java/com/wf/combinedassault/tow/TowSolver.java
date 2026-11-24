package com.wf.combinedassault.tow;

import com.wf.combinedassault.cargo.VehicleParachute;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.physics.step.RigidStep;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
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

    /** deg towed heading may differ from the tractor's on a cable; past it the towed vehicle would wrap round the tractor. */
    public static final double CABLE_YAW_LIMIT = 100.0;

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
        boolean regions = RigidStep.regionHost() != null;
        for (AbstractVehicle vehicle : new ArrayList<>(towed)) {
            if (!regions || onMain(vehicle)) {
                settle(vehicle);
            }
        }
    }

    /**
     * Vehicle tick, region thread, region host bound: pairs ticked in one region settle here (pose is last step's, and
     * only this thread moves either end). Tractor in another partition => not settled, the step holds the towed body.
     */
    public static void tickOwn(AbstractVehicle vehicle, int region) {
        TowLink link = vehicle.towLink();
        if (region >= 0 && link != null && link.tractor().tickRegion() == region) {
            settle(vehicle);
        }
    }

    private static boolean onMain(AbstractVehicle vehicle) {
        TowLink link = vehicle.towLink();
        return vehicle.tickRegion() < 0 && (link == null || link.tractor().tickRegion() < 0);
    }

    private static void settle(AbstractVehicle vehicle) {
        TowLink link = vehicle.towLink();
        if (link == null) {
            return;
        }
        AbstractVehicle tractor = link.tractor();
        HitchInfo hitch = link.hitch();
        if (hitch == null || vehicle.isRemoved() || tractor.isRemoved() || vehicle.level() != tractor.level()
                || !check(tractor, vehicle, link, hitch)) {
            TowLinks.uncouple(vehicle);
        }
    }

    /** @return false when the coupling has parted */
    private static boolean check(AbstractVehicle tractor, AbstractVehicle towed, TowLink link, HitchInfo hitch) {
        double slack = link.mode() == TowMode.HITCH ? 0 : link.cable();
        if (link.mode() == TowMode.SLING) {
            Vec3 hook = TowLinks.hitchPoint(tractor, hitch);
            double open = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < TowLinks.SLING_LEGS; i++) {
                open = Math.max(open, hook.distanceTo(TowLinks.worldPoint(towed, TowLinks.slingLegOffset(towed, i)))
                        - TowLinks.slingLegLength(towed, slack, i));
            }
            link.recordStrain(Math.max(0, open), false);
            if (open > SLING_BREAK) {
                return false;
            }
            if (!towed.onGround()) {
                hang(tractor, towed);
            }
            return true;
        }
        double open = TowLinks.hitchPoint(tractor, hitch).distanceTo(TowLinks.attachPoint(towed, link.mode())) - slack;
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
