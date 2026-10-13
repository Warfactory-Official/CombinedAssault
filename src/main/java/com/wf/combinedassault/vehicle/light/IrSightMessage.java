package com.wf.combinedassault.vehicle.light;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.wflib.drone.cam.CameraMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** C2S: the sender's station sight entered / left IR. Seat = the sender's (UAV operators included). */
public record IrSightMessage(int vehicleId, boolean on) implements CustomPacketPayload {

    public static final Type<IrSightMessage> TYPE = new Type<>(CombinedAssault.modLocation("ir_sight"));

    public static final StreamCodec<FriendlyByteBuf, IrSightMessage> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> buf.writeVarInt(message.vehicleId).writeBoolean(message.on),
            buf -> new IrSightMessage(buf.readVarInt(), buf.readBoolean()));

    public static void handle(IrSightMessage message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.level().getEntity(message.vehicleId) instanceof AbstractVehicle vehicle)) {
            return;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId != player.getId()) {
                continue;
            }
            boolean fitted = seat.partUnit instanceof WeaponUnit unit && unit.fitted(CameraMode.IR)
                    && unit.getOpticalSightType() == WeaponUnitData.OpticalSightType.CRT;
            vehicle.setIrSight(seat.seatIndex, message.on && fitted);
            return;
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
