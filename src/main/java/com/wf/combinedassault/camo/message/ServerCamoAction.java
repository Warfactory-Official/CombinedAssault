package com.wf.combinedassault.camo.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.camo.pojo.CamoAction;

/** Camo edit the server accepted; broadcast to everyone watching the vehicle. */
public class ServerCamoAction extends CamoAction implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerCamoAction> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "server_camo_action"));
    public static final StreamCodec<FriendlyByteBuf, ServerCamoAction> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> CamoActionCodec.encode(buf, msg), ServerCamoAction::decode);

    public ServerCamoAction() {
    }

    public ServerCamoAction(CamoAction source) {
        this.action = source.action;
        this.vehicleId = source.vehicleId;
        this.bones = source.bones;
        this.palette = source.palette;
        this.pieces = source.pieces;
        this.eraseBone = source.eraseBone;
        this.eraseX = source.eraseX;
        this.eraseY = source.eraseY;
        this.eraseZ = source.eraseZ;
        this.eraseRadius = source.eraseRadius;
    }

    public static ServerCamoAction decode(FriendlyByteBuf buf) {
        ServerCamoAction message = new ServerCamoAction();
        CamoActionCodec.decode(buf, message);
        return message;
    }

    public static void handle(ServerCamoAction message, IPayloadContext ctx) {
        Player player = LocalVehiclePlayer.instance().getPlayer();
        if (player == null) {
            return;
        }
        if (player.level().getEntity(message.vehicleId) instanceof AbstractVehicle vehicle) {
            // No cap here: server already trimmed. Re-trimming client-side risks disagreeing with server's hull.
            vehicle.getCamoLayer().apply(player.registryAccess(), message, Integer.MAX_VALUE);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
