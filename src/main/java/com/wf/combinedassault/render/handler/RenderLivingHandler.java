package com.wf.combinedassault.render.handler;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

public class RenderLivingHandler {

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre event) {
        if (event.getEntity().getVehicle() instanceof AbstractVehicle vehicle) {
            if (vehicle.uav && vehicle.getDetachedBodyAnchor(event.getEntity()) == null) {
                event.setCanceled(true);
            }
        }
    }

}
