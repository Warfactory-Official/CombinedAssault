package com.wf.combinedassault.uav.fpv.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.uav.fpv.payload.FpvTrigger;
import com.wf.combinedassault.uav.fpv.payload.PayloadUnit;

/**
 * Only message this feature adds; arming rides the host's vehicle-action packet instead. Server verifies
 * the sender is riding the named drone, unlike a part toggle that trusts the wire id.
 *
 * @param droneId drone the client believed it was flying; guards a stale click from firing a later ride
 * @param release {@link FpvTrigger#RELEASE} instead of {@link FpvTrigger#OPERATOR}
 */
public record FpvTriggerMessage(int droneId, boolean release) implements CustomPacketPayload {

    public static final Type<FpvTriggerMessage> TYPE = new Type<>(CombinedAssault.modLocation("fpv_trigger_payload"));

    public static final StreamCodec<FriendlyByteBuf, FpvTriggerMessage> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> {
                buf.writeVarInt(message.droneId);
                buf.writeBoolean(message.release);
            },
            buf -> new FpvTriggerMessage(buf.readVarInt(), buf.readBoolean())
    );

    public static void handle(FpvTriggerMessage message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer operator)) {
            return;
        }
        Entity ridden = operator.getVehicle();
        if (!(ridden instanceof AbstractVehicle drone) || drone.getId() != message.droneId) {
            return;
        }
        PayloadUnit hardpoint = PayloadUnit.on(drone);
        if (hardpoint == null) {
            return;
        }
        hardpoint.fire(message.release ? FpvTrigger.RELEASE : FpvTrigger.OPERATOR, operator, drone.getDeltaMovement().length());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
