package com.wf.combinedassault.tow.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.tow.TowCoupler;

/**
 * Client sends only its believed tractor id; hitch/trailer/mode is server-decided (pack can't be asked for a disallowed pairing).
 * @param tractorId client's believed mount; verified server-side against the actual vehicle
 */
public record TowCoupleMessage(int tractorId) implements CustomPacketPayload {

    public static final Type<TowCoupleMessage> TYPE = new Type<>(CombinedAssault.modLocation("tow_couple"));

    public static final StreamCodec<FriendlyByteBuf, TowCoupleMessage> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> buf.writeVarInt(message.tractorId),
            buf -> new TowCoupleMessage(buf.readVarInt())
    );

    public static void handle(TowCoupleMessage message, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof AbstractVehicle tractor) || tractor.getId() != message.tractorId) {
            return;
        }
        if (tractor.towing.hitches.isEmpty()) {
            return;
        }
        player.displayClientMessage(TowCoupler.toggle(tractor), true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
