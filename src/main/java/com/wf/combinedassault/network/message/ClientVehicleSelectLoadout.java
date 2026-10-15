package com.wf.combinedassault.network.message;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.loadout.Loadouts;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Modding tool: switch loadout (free; fitted hardware back to the player). Tool in hand, within 16 blocks. */
public record ClientVehicleSelectLoadout(int vehicleEntityId, int index) implements CustomPacketPayload {

    private static final double MAX_INTERACTION_DISTANCE_SQ = 256.0;

    public static final Type<ClientVehicleSelectLoadout> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "vehicle_select_loadout"));
    public static final StreamCodec<FriendlyByteBuf, ClientVehicleSelectLoadout> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ClientVehicleSelectLoadout::vehicleEntityId, ByteBufCodecs.VAR_INT,
            ClientVehicleSelectLoadout::index, ClientVehicleSelectLoadout::new);

    public static void handle(ClientVehicleSelectLoadout message, IPayloadContext ctx) {
        ServerPlayer player = (ServerPlayer) ctx.player();
        if (!player.getItemInHand(InteractionHand.MAIN_HAND).is(AllItems.MODDING_TOOL.get())
                && !player.getItemInHand(InteractionHand.OFF_HAND).is(AllItems.MODDING_TOOL.get())) {
            return;
        }
        if (player.level().getEntity(message.vehicleEntityId) instanceof AbstractVehicle vehicle
                && player.distanceToSqr(vehicle) <= MAX_INTERACTION_DISTANCE_SQ && message.index != vehicle.loadoutIndex()) {
            Loadouts.select(vehicle, message.index, player);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
