package com.wf.combinedassault.network.message;

import net.minecraft.network.chat.Component;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.crew.CrewRole;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.vehicle.crew.WeaponLoading;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;

public record ClientVehicleSwitchWeapon(
        int vehicleEntityId,
        WeaponSwitchType switchType,
        boolean next
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientVehicleSwitchWeapon> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "vehicle_switch_weapon"));
    public static final StreamCodec<FriendlyByteBuf, ClientVehicleSwitchWeapon> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> encode(msg, buf),
            ClientVehicleSwitchWeapon::decode
    );

    public enum WeaponSwitchType {
        PRIMARY,
        SECONDARY,
        MULTI
    }

    public static void encode(ClientVehicleSwitchWeapon msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.vehicleEntityId);
        buf.writeEnum(msg.switchType);
        buf.writeBoolean(msg.next);
    }

    public static ClientVehicleSwitchWeapon decode(FriendlyByteBuf buf) {
        int vehicleEntityId = buf.readInt();
        WeaponSwitchType switchType = buf.readEnum(WeaponSwitchType.class);
        boolean next = buf.readBoolean();
        return new ClientVehicleSwitchWeapon(vehicleEntityId, switchType, next);
    }

    public static void handle(ClientVehicleSwitchWeapon msg, IPayloadContext ctx) {
        var serverPlayer = (ServerPlayer) ctx.player();
        if (serverPlayer.level().getEntity(msg.vehicleEntityId) instanceof AbstractVehicle vehicle) {
            if (msg.switchType == WeaponSwitchType.MULTI && CrewRules.loaderAboard(vehicle)
                    && CrewRules.has(vehicle, serverPlayer, CrewRole.LOADER)) {
                // Loader cycles ammo for every served gun except self-loaded ones.
                for (PartUnit<?> unit : vehicle.getPartUnits()) {
                    if (unit instanceof WeaponUnit served && CrewRules.loading(served) != WeaponLoading.SELF) {
                        served.cycleMultiWeapon(msg.next);
                    }
                }
                return;
            }
            var partUnit = vehicle.getOwnOperatorUnit(serverPlayer);
            if (partUnit instanceof WeaponUnit weaponUnit) {
                switch (msg.switchType) {
                    case MULTI -> {
                        if (CrewRules.maySwitchAmmo(serverPlayer, weaponUnit)) {
                            weaponUnit.cycleMultiWeapon(msg.next);
                        } else {
                            serverPlayer.displayClientMessage(Component.translatable("tips.crew.loader_picks_ammo"), true);
                        }
                    }
                    case PRIMARY -> {
                        if (CrewRules.mayOperate(serverPlayer, weaponUnit)) {
                            weaponUnit.switchWeapon(false, msg.next, false);
                        }
                    }
                    case SECONDARY -> {
                        if (CrewRules.mayOperate(serverPlayer, weaponUnit)) {
                            weaponUnit.switchWeapon(true, msg.next, false);
                        }
                    }
                }
            }
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
