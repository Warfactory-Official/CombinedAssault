package com.wf.combinedassault.ballistics.component;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;

/** Running-gear mobility scale. No mobility part/pool declared => {@link #FULL}, unaffected. */
public record VehicleMobility(float speedScale, float turnScale) {

    public static final VehicleMobility FULL = new VehicleMobility(1.0f, 1.0f);

    public static VehicleMobility of(AbstractVehicle vehicle) {
        float speed = 1.0f;
        float turn = 1.0f;
        boolean any = false;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            ComponentKind kind = ComponentDamage.kind(part);
            if (!kind.isMobility()) {
                continue;
            }
            any = true;
            float effectiveness = ComponentDamage.effectiveness(part);
            speed *= effectiveness;
            if (kind.steers()) {
                // Steering: running gear only, not the engine.
                turn *= effectiveness;
            }
        }
        HullComponents systems = vehicle.hullComponents();
        for (int i = 0, size = systems.size(); i < size; i++) {
            ComponentKind kind = systems.kind(i);
            if (!kind.isMobility()) {
                continue;
            }
            any = true;
            float effectiveness = systems.effectiveness(i);
            speed *= effectiveness;
            if (kind.steers()) {
                turn *= effectiveness;
            }
        }
        return any ? new VehicleMobility(speed, turn) : FULL;
    }

}
