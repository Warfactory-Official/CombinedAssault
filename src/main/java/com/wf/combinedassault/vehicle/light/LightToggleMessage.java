package com.wf.combinedassault.vehicle.light;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.crew.CrewRole;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** C2S: flip one light group of the ridden vehicle. {@code HEAD}: driver only. */
public record LightToggleMessage(int vehicleId, VehicleLight.Kind kind) implements CustomPacketPayload {

    public static final Type<LightToggleMessage> TYPE = new Type<>(CombinedAssault.modLocation("vehicle_lights"));

    public static final StreamCodec<FriendlyByteBuf, LightToggleMessage> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> buf.writeVarInt(message.vehicleId).writeEnum(message.kind),
            buf -> new LightToggleMessage(buf.readVarInt(), buf.readEnum(VehicleLight.Kind.class)));

    public static void handle(LightToggleMessage message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.getVehicle() instanceof AbstractVehicle vehicle) || vehicle.getId() != message.vehicleId
                || vehicle.isDestroyed()) {
            return;
        }
        if (message.kind == VehicleLight.Kind.HEAD && !CrewRules.has(vehicle, player, CrewRole.DRIVER)) {
            player.displayClientMessage(Component.translatable("tips.crew.driver_only"), true);
            return;
        }
        if (VehicleLight.has(vehicle, message.kind)) {
            vehicle.toggleLights(message.kind);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
