package com.wf.combinedassault.radar.message;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.radar.client.RadarExpanded;
import com.wf.wflib.recon.api.ScopeFrame;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The rider's network picture around the vehicle. */
public record ServerRadarScope(ScopeFrame frame) implements CustomPacketPayload {
    public static final Type<ServerRadarScope> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "radar_scope_frame"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerRadarScope> STREAM_CODEC =
            ScopeFrame.STREAM_CODEC.map(ServerRadarScope::new, ServerRadarScope::frame);

    public static void handle(ServerRadarScope message, IPayloadContext ctx) {
        ctx.enqueueWork(() -> RadarExpanded.accept(message.frame));
    }

    @Override
    public Type<ServerRadarScope> type() {
        return TYPE;
    }
}
