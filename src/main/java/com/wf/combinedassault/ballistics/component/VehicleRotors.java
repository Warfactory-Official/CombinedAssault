package com.wf.combinedassault.ballistics.component;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;

/**
 * Rotor systems as flight factors, 0..1: product over every ROTOR / TAIL_ROTOR member. Undeclared => 1.
 *
 * @param lift       scales main-rotor thrust and attitude authority
 * @param antiTorque scales yaw authority; {@code 1 - antiTorque} of the torque yaws the airframe
 */
public record VehicleRotors(float lift, float antiTorque) {

    public static final VehicleRotors FULL = new VehicleRotors(1, 1);

    public static VehicleRotors of(AbstractVehicle vehicle) {
        float lift = 1;
        float antiTorque = 1;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            ComponentKind kind = ComponentDamage.kind(part);
            if (kind == ComponentKind.ROTOR) {
                lift *= ComponentDamage.effectiveness(part);
            } else if (kind == ComponentKind.TAIL_ROTOR) {
                antiTorque *= ComponentDamage.effectiveness(part);
            }
        }
        HullComponents systems = vehicle.hullComponents();
        for (int i = 0, size = systems.size(); i < size; i++) {
            ComponentKind kind = systems.kind(i);
            if (kind == ComponentKind.ROTOR) {
                lift *= systems.effectiveness(i);
            } else if (kind == ComponentKind.TAIL_ROTOR) {
                antiTorque *= systems.effectiveness(i);
            }
        }
        return lift == 1 && antiTorque == 1 ? FULL : new VehicleRotors(lift, antiTorque);
    }

}
