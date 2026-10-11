package com.wf.combinedassault.client.handler;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

@EventBusSubscriber(value = Dist.CLIENT)
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
