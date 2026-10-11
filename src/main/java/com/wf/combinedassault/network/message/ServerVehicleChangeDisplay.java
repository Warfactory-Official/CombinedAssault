package com.wf.combinedassault.network.message;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

public class ServerVehicleChangeDisplay implements CustomPacketPayload {

    public static final StreamCodec<FriendlyByteBuf, ServerVehicleChangeDisplay> STREAM_CODEC = StreamCodec.of((buf, msg) -> msg.encode(buf), ServerVehicleChangeDisplay::decode);
    public static final CustomPacketPayload.Type<ServerVehicleChangeDisplay> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "server_vehicle_change_display"));
    public int vehicleEntityId;
    public ResourceLocation displayId;

    public ServerVehicleChangeDisplay() {}

    public ServerVehicleChangeDisplay(int vehicleEntityId, ResourceLocation displayId) {
        this.vehicleEntityId = vehicleEntityId;
        this.displayId = displayId;
    }

    public static ServerVehicleChangeDisplay decode(FriendlyByteBuf buf) {
        ServerVehicleChangeDisplay serverVehicleChangeDisplay = new ServerVehicleChangeDisplay();
        serverVehicleChangeDisplay.vehicleEntityId = buf.readInt();
        serverVehicleChangeDisplay.displayId = buf.readResourceLocation();
        return serverVehicleChangeDisplay;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(vehicleEntityId);
        buf.writeResourceLocation(displayId);
    }

    public static void handle(ServerVehicleChangeDisplay message, IPayloadContext ctx) {
        if (Minecraft.getInstance().level.getEntity(message.vehicleEntityId) instanceof AbstractVehicle vehicle) {
            vehicle.setDisplayId(message.displayId);
            vehicle.initDisplayData();
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
