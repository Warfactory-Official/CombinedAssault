package com.wf.combinedassault.props.message;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.props.DecorationItem;
import com.wf.combinedassault.props.PropsContent;
import com.wf.combinedassault.props.part.DecorationUnit;
import com.wf.combinedassault.props.pojo.DecorationAction;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.lock.VehicleLock;
import com.wf.combinedassault.vehicle.part.data.PartUnitData;
import com.wf.combinedassault.vehicle.part.data.PartUnitPojo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClientDecorationAction extends DecorationAction implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientDecorationAction> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "client_decoration_action"));
    public static final StreamCodec<FriendlyByteBuf, ClientDecorationAction> STREAM_CODEC = StreamCodec.of((buf, msg) -> msg.encode(buf), ClientDecorationAction::decode);

    private static final int MAX_UNIT_ID = 64;
    private static final int MAX_NAME = 256;
    private static final int MAX_DECORATIONS = 256;

    public ClientDecorationAction() {}

    public static ClientDecorationAction decode(FriendlyByteBuf buf) {
        ClientDecorationAction clientDecorationAction = new ClientDecorationAction();
        clientDecorationAction.action = buf.readEnum(Action.class);
        clientDecorationAction.displayId = buf.readUtf(MAX_NAME);
        if (clientDecorationAction.action == Action.UPDATE_ITEM) {
            return clientDecorationAction;
        }
        clientDecorationAction.vehicleId = buf.readInt();
        clientDecorationAction.decorationUnitId = buf.readUtf(MAX_UNIT_ID);
        if (clientDecorationAction.action == Action.REMOVE) {
            return clientDecorationAction;
        }
        clientDecorationAction.baseBoneName = buf.readUtf(MAX_NAME);
        clientDecorationAction.scale = buf.readFloat();
        clientDecorationAction.selfXRot = buf.readFloat();
        clientDecorationAction.selfYRot = buf.readFloat();
        clientDecorationAction.selfZRot = buf.readFloat();
        clientDecorationAction.offsetFromBone = new Vec3(buf.readVector3f());
        return clientDecorationAction;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(displayId);
        if (action == Action.UPDATE_ITEM) {
            return;
        }
        buf.writeInt(vehicleId);
        buf.writeUtf(decorationUnitId);
        if (action == Action.REMOVE) {
            return;
        }
        buf.writeUtf(baseBoneName);
        buf.writeFloat(scale);
        buf.writeFloat(selfXRot);
        buf.writeFloat(selfYRot);
        buf.writeFloat(selfZRot);
        buf.writeVector3f(offsetFromBone.toVector3f());
    }

    public static void handle(ClientDecorationAction message, IPayloadContext ctx) {
        ServerPlayer player = (ServerPlayer) ctx.player();
        if (ResourceLocation.tryParse(message.displayId) == null) {
            return;
        }
        if (message.action == Action.UPDATE_ITEM) {
            ItemStack itemStack = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (itemStack.getItem() instanceof DecorationItem) {
                CustomData customData = itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
                CompoundTag tag = customData.copyTag();
                tag.putString(DecorationItem.TAG_DECORATION_DISPLAY_ID, message.displayId);
                itemStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            }
        } else if (player.level().getEntity(message.vehicleId) instanceof AbstractVehicle vehicle
                && vehicle.inReach(player) && VehicleLock.permits(vehicle, player)) {
            if (message.action == Action.SET) {
                if (!Float.isFinite(message.scale + message.selfXRot + message.selfYRot + message.selfZRot)
                        || !Double.isFinite(message.offsetFromBone.x + message.offsetFromBone.y + message.offsetFromBone.z)) {
                    return;
                }
                message.selfXRot = Mth.clamp(message.selfXRot, -180, 180);
                message.selfYRot = Mth.clamp(message.selfYRot, -180, 180);
                message.selfZRot = Mth.clamp(message.selfZRot, -180, 180);
                DecorationUnit decorationUnit = vehicle.getDecorationUnits().get(message.decorationUnitId);
                if (decorationUnit != null) {
                    decorationUnit.update(message);
                } else {
                    ItemStack itemStack = player.getItemInHand(InteractionHand.MAIN_HAND);
                    if (!(itemStack.getItem() instanceof DecorationItem) || vehicle.getDecorationUnits().size() >= MAX_DECORATIONS) {
                        return;
                    }
                    itemStack.shrink(1);
                    PartUnitPojo pojo = new PartUnitPojo();
                    pojo.id = message.decorationUnitId;
                    decorationUnit = new DecorationUnit(pojo.id.hashCode(), vehicle, new PartUnitData(pojo));
                    decorationUnit.update(message);
                    vehicle.getDecorationUnits().put(message.decorationUnitId, decorationUnit);
                }
                ServerDecorationAction serverDecorationAction = new ServerDecorationAction(message);
                PacketDistributor.sendToPlayersTrackingEntity(vehicle, serverDecorationAction);
            } else if (message.action == Action.REMOVE) {
                if (vehicle.getDecorationUnits().remove(message.decorationUnitId) == null) {
                    return;
                }
                ItemStack itemStack = PropsContent.DECORATION_ITEM.get().getDefaultInstance();
                CompoundTag tag = new CompoundTag();
                tag.putString(DecorationItem.TAG_DECORATION_DISPLAY_ID, message.displayId);
                itemStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                player.level().addFreshEntity(new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), itemStack));
                ServerDecorationAction serverDecorationAction = new ServerDecorationAction(message);
                PacketDistributor.sendToPlayersTrackingEntity(vehicle, serverDecorationAction);
            }
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
