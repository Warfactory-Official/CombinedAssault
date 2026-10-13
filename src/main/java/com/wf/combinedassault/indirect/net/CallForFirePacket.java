package com.wf.combinedassault.indirect.net;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.indirect.CallForFire;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** PDA observer's map -> server: call for fire on {@code target} (observer's dimension). */
public record CallForFirePacket(Vec3 target) implements CustomPacketPayload {

    public static final Type<CallForFirePacket> TYPE = new Type<>(CombinedAssault.modLocation("call_for_fire"));

    public static final StreamCodec<FriendlyByteBuf, CallForFirePacket> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> buf.writeVec3(message.target), buf -> new CallForFirePacket(buf.readVec3()));

    public static void handle(CallForFirePacket message, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> CallForFire.request(player, message.target));
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
