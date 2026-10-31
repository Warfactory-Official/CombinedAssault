package com.wf.combinedassault.vehicle;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.naval.ShipVehicle;
import com.wf.combinedassault.naval.VesselVehicle;

import java.util.Set;

/** Server config {@code thirdPerson}: deny > allow > class. */
public final class ThirdPersonRules {

    public static final Set<String> CLASSES = Set.of("ground", "aircraft", "naval");

    private ThirdPersonRules() {
    }

    public static String classOf(AbstractVehicle vehicle) {
        if (vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle) {
            return "aircraft";
        }
        if (vehicle instanceof ShipVehicle || vehicle instanceof VesselVehicle) {
            return "naval";
        }
        return "ground";
    }

    public static boolean allows(AbstractVehicle vehicle) {
        String id = vehicle.getVehicleId().toString();
        if (VehicleConfig.server().thirdPersonDeny().contains(id)) {
            return false;
        }
        return VehicleConfig.server().thirdPersonAllow().contains(id)
                || VehicleConfig.server().thirdPersonClasses().contains(classOf(vehicle));
    }
}
