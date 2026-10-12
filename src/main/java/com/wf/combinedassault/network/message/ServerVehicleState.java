package com.wf.combinedassault.network.message;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> operator: snapshot after input {@code ack} (docs/client-prediction.md). */
public record ServerVehicleState(int vehicleId, int ack, byte[] state) implements CustomPacketPayload {

    public static final StreamCodec<FriendlyByteBuf, ServerVehicleState> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeVarInt(msg.vehicleId);
                buf.writeInt(msg.ack);
                buf.writeByteArray(msg.state);
            },
            buf -> new ServerVehicleState(buf.readVarInt(), buf.readInt(), buf.readByteArray()));
    public static final CustomPacketPayload.Type<ServerVehicleState> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "vehicle_state"));

    public static void handle(ServerVehicleState message, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player().level().getEntity(message.vehicleId) instanceof AbstractVehicle vehicle) {
                vehicle.predictionState(message.ack, message.state);
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
