package com.wf.combinedassault.audio;

import com.wf.combinedassault.client.resource.ClientAssetsManager;
import com.wf.combinedassault.client.resource.vehicle.VehicleDisplay;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.DoorUnit;
import com.wf.combinedassault.vehicle.part.PartUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Cabin muffle of the camera's seat, first person only: display {@code cabin_muffle}; unprotected seat => 0; own door
 * open x0.25, others x(1 - 0.5 open fraction).
 */
@OnlyIn(Dist.CLIENT)
public final class CabinAcoustics {
    private CabinAcoustics() {
    }

    public static float muffle(Minecraft mc) {
        Entity cam = mc.getCameraEntity();
        if (!mc.options.getCameraType().isFirstPerson() || !(cam instanceof LivingEntity rider)
                || !(rider.getVehicle() instanceof AbstractVehicle vehicle)) {
            return 0;
        }
        PartUnit<?> seat = vehicle.getOwnOperatorUnit(rider);
        Boolean protect = seat == null ? null : seat.getData().getProtectPassenger();
        if (!(protect == null ? vehicle.protectPassenger : protect)) {
            return 0;
        }
        float m = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId())
                .map(VehicleDisplay::getCabinMuffle).orElse(0F);
        int doors = 0;
        int open = 0;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof DoorUnit door) {
                doors++;
                if (door.isOn()) {
                    if (door.getSeatUnitOfDoor() == seat) {
                        m *= 0.25F;
                    }
                    open++;
                }
            }
        }
        return doors == 0 ? m : m * (1 - 0.5F * open / doors);
    }
}
