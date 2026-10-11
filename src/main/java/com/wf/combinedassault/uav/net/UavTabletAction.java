package com.wf.combinedassault.uav.net;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.uav.UavTablet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Tablet screen -> server. {@code index} = channel; ignored by REFRESH. */
public record UavTabletAction(Kind kind, int index) implements CustomPacketPayload {

    public static final Type<UavTabletAction> TYPE = new Type<>(CombinedAssault.modLocation("uav_tablet_action"));

    public enum Kind {
        REFRESH, SELECT, CONNECT, REMOVE
    }

    public static final StreamCodec<FriendlyByteBuf, UavTabletAction> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> {
                buf.writeEnum(message.kind);
                buf.writeVarInt(message.index);
            },
            buf -> new UavTabletAction(buf.readEnum(Kind.class), buf.readVarInt()));

    public static void handle(UavTabletAction message, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> UavTablet.act(player, message.kind, message.index));
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
