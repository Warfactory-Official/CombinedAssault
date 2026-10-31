package com.wf.combinedassault.radar.message;

import com.wf.wflib.sim.api.SimTargets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.radar.RadarUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;

public class ClientRadarAction implements CustomPacketPayload {

    public static final StreamCodec<FriendlyByteBuf, ClientRadarAction> STREAM_CODEC = StreamCodec.of((buf, msg) -> msg.encode(buf), ClientRadarAction::decode);
    public static final CustomPacketPayload.Type<ClientRadarAction> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "radar_action"));
    public Action action;
    public int toEntityId;

    public ClientRadarAction() {}

    public static ClientRadarAction decode(FriendlyByteBuf buf) {
        ClientRadarAction clientRadarAction = new ClientRadarAction();
        clientRadarAction.action = buf.readEnum(Action.class);
        clientRadarAction.toEntityId = buf.readInt();
        return clientRadarAction;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeInt(toEntityId);
    }

    public static void handle(ClientRadarAction message, IPayloadContext ctx) {
        ServerPlayer player = (ServerPlayer) ctx.player();
        if (player == null) {
            return;
        }
        if (player.getVehicle() instanceof AbstractVehicle vehicle) {
            PartUnit<?> partUnit = vehicle.getOwnOperatorUnit(player);
            if (partUnit instanceof WeaponUnit weaponUnit) {
                RadarUnit mainRadarUnit = weaponUnit.getMainRadarUnit();
                if (mainRadarUnit == null) {
                    return;
                }
                Level level = player.level();
                if (message.action == Action.LOCK) {
                    mainRadarUnit.setLockedEntity(SimTargets.resolve(player, message.toEntityId));
                } else if (message.action == Action.DETECT) {
                    Entity toEntity = level.getEntity(message.toEntityId);
                    if (toEntity != null) {
                        mainRadarUnit.detect(toEntity);
                    }
                }
            }
        }
    }

    public enum Action {
        DETECT, LOCK
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
