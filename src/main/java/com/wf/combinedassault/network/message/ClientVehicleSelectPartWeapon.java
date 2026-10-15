package com.wf.combinedassault.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;

public record ClientVehicleSelectPartWeapon(int vehicleEntityId, int partUnitIndex, int weaponIndex) implements CustomPacketPayload {

    private static final double MAX_INTERACTION_DISTANCE_SQ = 256.0;

    public static final CustomPacketPayload.Type<ClientVehicleSelectPartWeapon> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "vehicle_select_part_weapon"));
    public static final StreamCodec<FriendlyByteBuf, ClientVehicleSelectPartWeapon> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> encode(msg, buf),
            ClientVehicleSelectPartWeapon::decode
    );

    public static void encode(ClientVehicleSelectPartWeapon msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleEntityId);
        buf.writeInt(msg.partUnitIndex);
        buf.writeInt(msg.weaponIndex);
    }

    public static ClientVehicleSelectPartWeapon decode(FriendlyByteBuf buf) {
        return new ClientVehicleSelectPartWeapon(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(ClientVehicleSelectPartWeapon message, IPayloadContext ctx) {
        var player = (ServerPlayer) ctx.player();
        if (!hasModdingTool(player.getItemInHand(InteractionHand.MAIN_HAND), player.getItemInHand(InteractionHand.OFF_HAND))) {
            return;
        }
        if (!(player.level().getEntity(message.vehicleEntityId) instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (player.distanceToSqr(vehicle) > MAX_INTERACTION_DISTANCE_SQ) {
            return;
        }
        vehicle.getPartUnit(message.partUnitIndex).ifPresent(partUnit -> {
            if (partUnit instanceof WeaponUnit weaponUnit
                    && weaponUnit.isInteractive()
                    && !com.wf.combinedassault.vehicle.loadout.Loadouts.manages(vehicle, weaponUnit)
                    && message.weaponIndex >= 0
                    && message.weaponIndex < weaponUnit.weapons.size()) {
                weaponUnit.selectWeaponIndex(false, message.weaponIndex, true);
            }
        });
    }

    private static boolean hasModdingTool(ItemStack mainHand, ItemStack offHand) {
        return mainHand.is(AllItems.MODDING_TOOL.get()) || offHand.is(AllItems.MODDING_TOOL.get());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
