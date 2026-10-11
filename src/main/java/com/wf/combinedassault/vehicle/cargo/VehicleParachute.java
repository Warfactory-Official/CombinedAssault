package com.wf.combinedassault.vehicle.cargo;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.FixedWingVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.vehicle.PhysicsEngine;
import com.wf.combinedassault.vehicle.parenting.VehicleHarness;

/**
 * Cargo parachute fitted to a vehicle beforehand ({@code cargo_parachute} item). Opens falling, quadratic drag to
 * {@link #TERMINAL}, rights the hull; spent on touchdown. Aircraft never take one.
 */
public final class VehicleParachute {

    private static final String TAG = "Parachute";
    /** Blocks/tick descent under canopy (7 m/s). */
    public static final double TERMINAL = 0.35;
    /** Blocks/tick of fall that opens it. */
    private static final double OPEN_SPEED = 0.6;
    /** Ticks after leaving a hold before a static line opens it. */
    private static final int STATIC_LINE = 10;
    /** Opening shock ceiling, blocks/tick of velocity removed per tick. */
    private static final float MAX_DRAG = 0.5f;
    /** Rad/tick of righting spin per rad of tilt; angular velocity kept per tick. */
    private static final float RIGHTING = 0.08f, SPIN_KEPT = 0.7f;

    private boolean fitted;
    private int staticLine = -1;

    public static boolean accepts(AbstractVehicle vehicle) {
        return !(vehicle instanceof FixedWingVehicle) && !(vehicle instanceof RotaryWingVehicle) && !vehicle.uav;
    }

    public boolean fitted() {
        return fitted;
    }

    public void setFitted(boolean fitted) {
        this.fitted = fitted;
    }

    /** Leaving a hold: opens after {@link #STATIC_LINE} ticks whatever the fall speed. */
    public void armStaticLine() {
        if (fitted) {
            staticLine = STATIC_LINE;
        }
    }

    /** Server tick; sets the synced deployed flag. */
    public void tick(AbstractVehicle vehicle) {
        boolean deployed = vehicle.parachuteDeployed();
        if (!fitted) {
            if (deployed) {
                vehicle.setParachuteDeployed(false);
            }
            return;
        }
        if (deployed && (vehicle.onGround() || vehicle.isInWater() || VehicleHarness.isHarnessed(vehicle))) {
            // Touched down: canopy cut, chute spent.
            fitted = false;
            staticLine = -1;
            vehicle.setParachuteDeployed(false);
            return;
        }
        if (!deployed) {
            if (staticLine > 0) {
                staticLine--;
            }
            boolean falling = !vehicle.onGround() && vehicle.getDeltaMovement().y < -OPEN_SPEED;
            if (staticLine == 0 || falling && !VehicleHarness.isHarnessed(vehicle)) {
                staticLine = -1;
                vehicle.setParachuteDeployed(true);
                vehicle.level().playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                        SoundEvents.WOOL_PLACE, SoundSource.NEUTRAL, 4.0F, 0.6F);
            }
            return;
        }
        drag(vehicle);
    }

    private static void drag(AbstractVehicle vehicle) {
        Vec3 v = vehicle.getDeltaMovement();
        double speed = v.length();
        Vector3f dv = new Vector3f();
        if (speed > 1.0e-4) {
            double loss = Math.min(PhysicsEngine.G * (speed / TERMINAL) * (speed / TERMINAL), Math.min(speed, MAX_DRAG));
            dv.set((float) (-v.x / speed * loss), (float) (-v.y / speed * loss), (float) (-v.z / speed * loss));
        }
        steady(vehicle, dv);
    }

    /**
     * Hanging load (canopy, sling): {@code dv} into deltaMovement, {@code dw} into spin, hull
     * righted toward level, spin damped, fall distance cleared.
     */
    public static void steady(AbstractVehicle vehicle, Vector3f dv) {
        Vector3f up = vehicle.rotYXZ().transform(new Vector3f(0, 1, 0));
        Vector3f axis = up.cross(new Vector3f(0, 1, 0), new Vector3f());
        float sin = axis.length();
        Vector3f dw = new Vector3f(vehicle.physicsEngine.angularVelocity).mul(SPIN_KEPT - 1);
        if (sin > 1.0e-4f) {
            dw.add(axis.mul((float) Math.asin(Math.min(sin, 1f)) * RIGHTING / sin));
        }
        vehicle.setDeltaMovement(vehicle.getDeltaMovement().add(dv.x, dv.y, dv.z));
        vehicle.hasImpulse = true;
        vehicle.physicsEngine.addImpulse(dw);
        vehicle.fallDistance = 0;
    }

    public void save(CompoundTag compound) {
        if (fitted) {
            compound.putBoolean(TAG, true);
        }
    }

    public void load(CompoundTag compound) {
        fitted = compound.getBoolean(TAG);
    }

}
