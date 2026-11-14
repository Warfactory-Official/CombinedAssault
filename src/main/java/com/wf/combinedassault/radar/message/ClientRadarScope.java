package com.wf.combinedassault.radar.message;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.radar.RadarScopeFeed;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Expanded scope open at {@code range} blocks; 0 = closed. */
public record ClientRadarScope(float range) implements CustomPacketPayload {
    public static final Type<ClientRadarScope> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "radar_scope"));
    public static final StreamCodec<ByteBuf, ClientRadarScope> STREAM_CODEC =
            ByteBufCodecs.FLOAT.map(ClientRadarScope::new, ClientRadarScope::range);

    public static void handle(ClientRadarScope message, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            RadarScopeFeed.subscribe(player, message.range);
        }
    }

    @Override
    public Type<ClientRadarScope> type() {
        return TYPE;
    }
}
