package com.wf.combinedassault.network.message;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;

/** A vehicle's whole ERA/slat layer, save-file form; sent the tick after any change. */
public record ServerAddonArmorSync(int vehicleId, CompoundTag layer) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerAddonArmorSync> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "server_addon_armor_sync"));
    public static final StreamCodec<FriendlyByteBuf, ServerAddonArmorSync> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> {
                buf.writeVarInt(message.vehicleId());
                buf.writeNbt(message.layer());
            },
            buf -> new ServerAddonArmorSync(buf.readVarInt(), buf.readNbt()));

    public static void handle(ServerAddonArmorSync message, IPayloadContext ctx) {
        Player player = LocalVehiclePlayer.instance.getPlayer();
        if (player != null && player.level().getEntity(message.vehicleId()) instanceof AbstractVehicle vehicle) {
            vehicle.addonArmor().load(message.layer());
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
