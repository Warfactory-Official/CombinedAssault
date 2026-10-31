package com.wf.combinedassault.vehicle;

import net.minecraft.client.player.LocalPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import com.wf.combinedassault.vehicle.audio.CabinAcoustics;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.client.VehicleNodes;
import com.wf.combinedassault.vehicle.pose.client.SeatPoseAnim;
import com.wf.wflib.audio.api.client.AudioMixer;

public final class VehicleClient {

    private VehicleClient() {
    }

    static void start(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, event -> {
            AudioMixer.addCabin(CabinAcoustics::muffle);
            event.enqueueWork(SeatPoseAnim::register);
            event.enqueueWork(VehicleNodes::register);
        });
        NeoForge.EVENT_BUS.register(VehicleClient.class);
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof LocalPlayer) {
            LocalVehiclePlayer.instance().clear();
        }
    }

    /** Seated quit/disconnect: {@code player} goes null while {@code vehicle} stays => client tick NPE. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LocalVehiclePlayer.instance().clear();
    }

    @SubscribeEvent
    public static void onInteractionKeyMappingTriggered(InputEvent.InteractionKeyMappingTriggered event) {
        AbstractVehicle.Seat seat = LocalVehiclePlayer.instance().seat;
        if (seat != null && !seat.partUnit.getData().passengerCanUseItem()) {
            event.setCanceled(true);
        }
    }
}
