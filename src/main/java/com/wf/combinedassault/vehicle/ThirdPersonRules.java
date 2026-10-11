package com.wf.combinedassault.vehicle;

import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.FixedWingVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.entity.vehicle.ShipVehicle;
import com.wf.combinedassault.entity.vehicle.VesselVehicle;

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
        if (AllConfigs.server.thirdPersonDeny.get().contains(id)) {
            return false;
        }
        return AllConfigs.server.thirdPersonAllow.get().contains(id)
                || AllConfigs.server.thirdPersonClasses.get().contains(classOf(vehicle));
    }
}
