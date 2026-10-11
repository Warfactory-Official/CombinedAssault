package com.wf.combinedassault.tow;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.rigid.RigidSolver;

/** Towed vehicle holds the coupling (not tractor: many hitches, one trailer-side link) => train = chain. */
public final class TowLink {

    private final AbstractVehicle tractor;
    private final String hitchId;
    private final TowMode mode;

    /** Snapshot from the hitch at couple time; shortening the hitch later doesn't move the trailer. */
    private final double cable;

    /** Consecutive ticks over the strain threshold; feeds the break check. */
    private int strainTicks;

    /** Coupling open past its length after the last step; reported, and what breaks the link past a threshold. */
    private double strain;

    /** Last step's joint impulses (ball x/y/z or rope x, yaw stop, axle, roll): next step's warm start. */
    private final Vector3f impulse = new Vector3f();
    private float yawImpulse, axleImpulse, rollImpulse;

    TowLink(AbstractVehicle tractor, String hitchId, TowMode mode, double cable) {
        this.tractor = tractor;
        this.hitchId = hitchId;
        this.mode = mode;
        this.cable = cable;
    }

    public AbstractVehicle tractor() {
        return tractor;
    }

    public String hitchId() {
        return hitchId;
    }

    public TowMode mode() {
        return mode;
    }

    public double cable() {
        return cable;
    }

    public double strain() {
        return strain;
    }

    public int strainTicks() {
        return strainTicks;
    }

    @Nullable
    public HitchInfo hitch() {
        return tractor.towing.hitch(hitchId);
    }

    /** Step task: one island holds both ends. */
    public void warm(RigidSolver.Joint joint) {
        joint.impulse.set(impulse);
        joint.yawImpulse = yawImpulse;
        joint.axleImpulse = axleImpulse;
        joint.rollImpulse = rollImpulse;
    }

    public void store(RigidSolver.Joint joint) {
        impulse.set(joint.impulse);
        yawImpulse = joint.yawImpulse;
        axleImpulse = joint.axleImpulse;
        rollImpulse = joint.rollImpulse;
    }

    void recordStrain(double distance, boolean straining) {
        this.strain = distance;
        this.strainTicks = straining ? strainTicks + 1 : 0;
    }

}
