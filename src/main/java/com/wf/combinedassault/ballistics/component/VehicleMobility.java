package com.wf.combinedassault.ballistics.component;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;

/** Running-gear mobility scale. No mobility part/pool declared => {@link #FULL}, unaffected. */
public record VehicleMobility(float speedScale, float turnScale) {

    public static final VehicleMobility FULL = new VehicleMobility(1.0f, 1.0f);

    /** Redundant kinds: n units share the load, so losing one of n costs 1/n; running gear multiplies. */
    private static boolean redundant(ComponentKind kind) {
        return kind == ComponentKind.ENGINE || kind == ComponentKind.PROPELLER || kind == ComponentKind.RUDDER;
    }

    public static VehicleMobility of(AbstractVehicle vehicle) {
        float[] sum = new float[ComponentKind.values().length];
        int[] count = new int[sum.length];
        float speed = 1.0f;
        float turn = 1.0f;
        boolean any = false;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            ComponentKind kind = ComponentDamage.kind(part);
            if (!kind.isMobility() && !kind.steers()) {
                continue;
            }
            any = true;
            float effectiveness = ComponentDamage.effectiveness(part);
            if (redundant(kind)) {
                sum[kind.ordinal()] += effectiveness;
                count[kind.ordinal()]++;
                continue;
            }
            if (kind.isMobility()) {
                speed *= effectiveness;
            }
            if (kind.steers()) {
                // Steering: running gear only, not the engine.
                turn *= effectiveness;
            }
        }
        HullComponents systems = vehicle.hullComponents();
        for (int i = 0, size = systems.size(); i < size; i++) {
            ComponentKind kind = systems.kind(i);
            if (!kind.isMobility() && !kind.steers()) {
                continue;
            }
            any = true;
            float effectiveness = systems.effectiveness(i);
            if (redundant(kind)) {
                sum[kind.ordinal()] += effectiveness;
                count[kind.ordinal()]++;
                continue;
            }
            if (kind.isMobility()) {
                speed *= effectiveness;
            }
            if (kind.steers()) {
                turn *= effectiveness;
            }
        }
        for (ComponentKind kind : ComponentKind.values()) {
            int n = count[kind.ordinal()];
            if (n == 0) {
                continue;
            }
            float mean = sum[kind.ordinal()] / n;
            if (kind.isMobility()) {
                speed *= mean;
            }
            if (kind.steers()) {
                turn *= mean;
            }
        }
        return any ? new VehicleMobility(speed, turn) : FULL;
    }

}
