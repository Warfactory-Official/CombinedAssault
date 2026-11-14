package com.wf.combinedassault.vehicle.lock;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** C2S: cycle the lock of the ridden vehicle. */
public record VehicleLockMessage(int vehicleId) implements CustomPacketPayload {

    public static final Type<VehicleLockMessage> TYPE = new Type<>(CombinedAssault.modLocation("vehicle_lock"));

    public static final StreamCodec<FriendlyByteBuf, VehicleLockMessage> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> buf.writeVarInt(message.vehicleId),
            buf -> new VehicleLockMessage(buf.readVarInt()));

    public static void handle(VehicleLockMessage message, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.getVehicle() instanceof AbstractVehicle vehicle
                && vehicle.getId() == message.vehicleId) {
            VehicleLock.cycle(player, vehicle);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
