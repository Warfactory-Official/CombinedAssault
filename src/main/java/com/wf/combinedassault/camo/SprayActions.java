package com.wf.combinedassault.camo;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.probe.api.ProbeActions;
import com.wf.wflib.probe.api.ProbeInventory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.List;

/** Probe row: repaint to {@code arg} = index into {@link Finishes#of}. Spray can carried. */
public final class SprayActions {

    public static final ResourceLocation SPRAY = CombinedAssault.modLocation("spray");

    private SprayActions() {
    }

    static void register() {
        ProbeActions.registerEntity(SPRAY, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && spray(player, vehicle, arg));
    }

    private static boolean spray(ServerPlayer player, AbstractVehicle vehicle, int index) {
        List<ResourceLocation> finishes = Finishes.of(vehicle.getDisplayId());
        if (vehicle.isDestroyed() || index < 0 || index >= finishes.size()
                || finishes.get(index).equals(vehicle.getDisplayId())
                || ProbeInventory.find(player, stack -> stack.is(CamoContent.SPRAY_CAN.get())).isEmpty()) {
            return false;
        }
        vehicle.setDisplayId(finishes.get(index));
        player.level().playSound(null, vehicle.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 0.8f, 1.8f);
        return true;
    }
}
