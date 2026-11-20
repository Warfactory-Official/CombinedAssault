package com.wf.combinedassault.dynamics.message;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.dynamics.client.CrashImpactFx;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent before the crater's block updates (tick end).
 *
 * @param dx    unit direction the hull was driven into the ground
 * @param cells blocks excavated
 */
public record ServerCrashImpact(double x, double y, double z, float dx, float dy, float dz, int cells)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerCrashImpact> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "server_crash_impact"));
    public static final StreamCodec<FriendlyByteBuf, ServerCrashImpact> STREAM_CODEC = StreamCodec.of((buf, msg) -> {
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeFloat(msg.dx);
        buf.writeFloat(msg.dy);
        buf.writeFloat(msg.dz);
        buf.writeVarInt(msg.cells);
    }, buf -> new ServerCrashImpact(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readVarInt()));

    public static void handle(ServerCrashImpact msg, IPayloadContext context) {
        context.enqueueWork(() -> CrashImpactFx.play(msg));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
