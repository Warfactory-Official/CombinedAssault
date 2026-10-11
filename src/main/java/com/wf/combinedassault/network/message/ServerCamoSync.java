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

/**
 * Vehicle's whole camo layer; same form as the spawn packet and the save file.
 * Backstop for {@link ServerCamoAction} (incremental): any server-side change marks the layer dirty, sent
 * next tick unless already synced. Layer = entity state, not a side effect of one packet, so any mutator
 * (command, future damage system, another mod via {@code getCamoLayer()}) reaches clients for free.
 */
public record ServerCamoSync(int vehicleId, CompoundTag layer) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerCamoSync> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "server_camo_sync"));
    public static final StreamCodec<FriendlyByteBuf, ServerCamoSync> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> {
                buf.writeVarInt(message.vehicleId());
                buf.writeNbt(message.layer());
            },
            buf -> new ServerCamoSync(buf.readVarInt(), buf.readNbt()));

    public static void handle(ServerCamoSync message, IPayloadContext ctx) {
        Player player = LocalVehiclePlayer.instance.getPlayer();
        if (player == null) {
            return;
        }
        if (player.level().getEntity(message.vehicleId()) instanceof AbstractVehicle vehicle) {
            vehicle.getCamoLayer().load(player.registryAccess(), message.layer());
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
