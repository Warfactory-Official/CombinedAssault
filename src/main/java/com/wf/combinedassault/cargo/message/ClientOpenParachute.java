package com.wf.combinedassault.cargo.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.cargo.ParachutePackItem;

public class ClientOpenParachute implements CustomPacketPayload {

    public static final StreamCodec<FriendlyByteBuf, ClientOpenParachute> STREAM_CODEC =
            StreamCodec.of((buf, message) -> message.encode(buf), ClientOpenParachute::decode);
    public static final Type<ClientOpenParachute> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "open_parachute"));

    public static ClientOpenParachute decode(FriendlyByteBuf buffer) {
        return new ClientOpenParachute();
    }

    public void encode(FriendlyByteBuf buffer) {}

    public static void handle(ClientOpenParachute message, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ParachutePackItem.open(player);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
