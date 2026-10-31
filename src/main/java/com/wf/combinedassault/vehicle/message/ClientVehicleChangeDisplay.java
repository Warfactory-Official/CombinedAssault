package com.wf.combinedassault.vehicle.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.VehicleContent;

/** Modding tool: paint variant. Tool in hand, within 16 blocks. */
public class ClientVehicleChangeDisplay implements CustomPacketPayload {

    private static final double MAX_INTERACTION_DISTANCE_SQ = 256.0;

    public static final StreamCodec<FriendlyByteBuf, ClientVehicleChangeDisplay> STREAM_CODEC = StreamCodec.of((buf, msg) -> msg.encode(buf), ClientVehicleChangeDisplay::decode);
    public static final CustomPacketPayload.Type<ClientVehicleChangeDisplay> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "client_vehicle_change_display"));
    public int vehicleEntityId;
    public ResourceLocation displayId;

    public ClientVehicleChangeDisplay() {}

    public ClientVehicleChangeDisplay(int vehicleEntityId, ResourceLocation displayId) {
        this.vehicleEntityId = vehicleEntityId;
        this.displayId = displayId;
    }

    public static ClientVehicleChangeDisplay decode(FriendlyByteBuf buf) {
        ClientVehicleChangeDisplay clientVehicleChangeDisplay = new ClientVehicleChangeDisplay();
        clientVehicleChangeDisplay.vehicleEntityId = buf.readInt();
        clientVehicleChangeDisplay.displayId = buf.readResourceLocation();
        return clientVehicleChangeDisplay;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(vehicleEntityId);
        buf.writeResourceLocation(displayId);
    }

    public static void handle(ClientVehicleChangeDisplay message, IPayloadContext ctx) {
        ServerPlayer player = (ServerPlayer) ctx.player();
        if (!player.getItemInHand(InteractionHand.MAIN_HAND).is(VehicleContent.MODDING_TOOL.get())
                && !player.getItemInHand(InteractionHand.OFF_HAND).is(VehicleContent.MODDING_TOOL.get())) {
            return;
        }
        if (player.level().getEntity(message.vehicleEntityId) instanceof AbstractVehicle vehicle
                && player.distanceToSqr(vehicle) <= MAX_INTERACTION_DISTANCE_SQ) {
            vehicle.setDisplayId(message.displayId);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
