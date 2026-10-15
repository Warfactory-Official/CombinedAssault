package com.wf.combinedassault.fpv.gametest;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.vehicle.loadout.Loadouts;
import com.wf.wflib.ammo.AmmoItems;
import net.minecraft.resources.ResourceLocation;

/** Test seam: Mavic on its kamikaze loadout with a PG-7V strapped on, as the probe would leave it. */
public final class KamikazeKit {

    public static final ResourceLocation PG7V = ResourceLocation.fromNamespaceAndPath("wflib", "rpg7/heat");

    private KamikazeKit() {
    }

    public static PayloadUnit strap(AbstractVehicle drone) {
        int index = -1;
        for (int i = 0; i < drone.loadouts().size(); i++) {
            if ("kamikaze".equals(drone.loadouts().get(i).id)) {
                index = i;
            }
        }
        Loadouts.select(drone, index, null);
        PayloadUnit hardpoint = PayloadUnit.on(drone);
        hardpoint.getPayload().load(AmmoItems.stack(PG7V, 1));
        return hardpoint;
    }
}
