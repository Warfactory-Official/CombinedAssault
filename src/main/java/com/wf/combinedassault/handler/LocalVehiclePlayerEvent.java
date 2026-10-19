package com.wf.combinedassault.handler;

import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import com.wf.combinedassault.ads.StationAds;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.SecondOrderDynamics;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.WeaponUnit;

import static com.wf.combinedassault.util.MathUtil.magnificationToFov;

@EventBusSubscriber(value = Dist.CLIENT)
public class LocalVehiclePlayerEvent {

    private static final SecondOrderDynamics WORLD_FOV_DYNAMICS = new SecondOrderDynamics(1.2f, 1.2f, 0.5f, 0);

    @SubscribeEvent(receiveCanceled = true)
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            if (event.getEntity() == LocalVehiclePlayer.instance.getPlayer()) {
                LocalVehiclePlayer.instance.tick();
            }
        }
    }

    @SubscribeEvent
    public static void applyScopeMagnification(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) {
            return;
        }
        Entity entity = event.getCamera().getEntity();
        if (entity.equals(LocalVehiclePlayer.instance.getPlayer())) {
            if (entity.getVehicle() instanceof AbstractVehicle vehicle
                    && LocalVehiclePlayer.instance.sightUnit() instanceof WeaponUnit weaponUnit) {
                float fov;
                float divisor = StationAds.divisor(weaponUnit, (float) event.getFOV(), (float) event.getPartialTick());
                if (divisor > 0) {
                    fov = WORLD_FOV_DYNAMICS.update((float) event.getFOV() / divisor);
                } else if (LocalVehiclePlayer.instance.sighted()) {
                    float targetFov = (float) magnificationToFov(1 + (weaponUnit.getZoom() - 1), event.getFOV());
                    fov = WORLD_FOV_DYNAMICS.update(targetFov);
                } else if (vehicle.isViewZoomed()) {
                    float targetFov = (float) magnificationToFov(2f, event.getFOV());
                    fov = WORLD_FOV_DYNAMICS.update(targetFov);
                } else {
                    fov = WORLD_FOV_DYNAMICS.update((float) event.getFOV());
                }
                event.setFOV(fov);
            } else {
                float fov = WORLD_FOV_DYNAMICS.update((float) event.getFOV());
                event.setFOV(fov);
            }
        }
    }

}
