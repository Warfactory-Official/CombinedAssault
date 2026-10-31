package com.wf.combinedassault.tow.trial;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.control.ControlUnit;

/** Scripted driver, not AI: lap-over-lap comparisons need identical inputs. Holds target SPEED, not
 * throttle, so a lap that loses speed on a rough sector isn't re-tested on an easier corner. */
public final class TowDriver {

    /** Pure-pursuit lookahead, blocks. */
    private static final double LOOKAHEAD = 14.0;

    /** Deadband, degrees. */
    private static final double DEADBAND = 1.5;

    /** Throttle resumes below this fraction of target speed (hysteresis low side). */
    private static final double RESUME = 0.94;

    /** Brakes above this fraction (hysteresis high side). Coasting alone insufficient: turn force
     * adds velocity every tick regardless of throttle. Measured, 44-block ring: 0.35 b/t drifts
     * past 0.8 without this. */
    private static final double BRAKE = 1.06;

    private final TowGround.Built course;
    private final double targetSpeed;

    private double bearingError;
    private double laps;
    private double lastTurn = Double.NaN;

    public TowDriver(TowGround.Built course, double targetSpeed) {
        this.course = course;
        this.targetSpeed = targetSpeed;
    }

    public double bearingError() {
        return bearingError;
    }

    /** Fractional lap count (bearing-accumulated). */
    public double laps() {
        return laps;
    }

    public void tick(AbstractVehicle tractor) {
        double turn = course.bearing(tractor.getX(), tractor.getZ());
        if (!Double.isNaN(lastTurn)) {
            laps += Mth.wrapDegrees(turn - lastTurn) / 360.0;
        }
        lastTurn = turn;

        double ahead = Math.toRadians(turn) + LOOKAHEAD / course.spec().radius();
        double aimX = course.centre().x + course.spec().radius() * Math.sin(ahead);
        double aimZ = course.centre().z + course.spec().radius() * Math.cos(ahead);
        double wanted = Math.toDegrees(-Math.atan2(aimX - tractor.getX(), aimZ - tractor.getZ()));
        bearingError = Mth.wrapDegrees((float) (wanted - tractor.getYRot()));

        ControlUnit controls = tractor.controlUnit;
        Vec3 velocity = tractor.getDeltaMovement();
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        controls.forward = speed < targetSpeed * (controls.forward ? 1.0 : RESUME);
        controls.backward = !controls.forward && speed > targetSpeed * BRAKE;
        controls.right = bearingError > DEADBAND;
        controls.left = bearingError < -DEADBAND;
    }

    public static void stop(AbstractVehicle tractor) {
        tractor.controlUnit.forward = false;
        tractor.controlUnit.backward = false;
        tractor.controlUnit.left = false;
        tractor.controlUnit.right = false;
    }

}
