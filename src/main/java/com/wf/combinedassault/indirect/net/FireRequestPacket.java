package com.wf.combinedassault.indirect.net;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.indirect.client.FireRequests;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> map-station operator: {@code observer} requests fire on {@code target}. */
public record FireRequestPacket(String observer, Vec3 target) implements CustomPacketPayload {

    public static final Type<FireRequestPacket> TYPE = new Type<>(CombinedAssault.modLocation("fire_request"));

    public static final StreamCodec<FriendlyByteBuf, FireRequestPacket> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> {
                buf.writeUtf(message.observer, 16);
                buf.writeVec3(message.target);
            },
            buf -> new FireRequestPacket(buf.readUtf(16), buf.readVec3()));

    public static void handle(FireRequestPacket message, IPayloadContext context) {
        context.enqueueWork(() -> FireRequests.accept(message));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
