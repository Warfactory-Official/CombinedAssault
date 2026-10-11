package com.wf.combinedassault.network.message;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.VehicleMissile;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Operator picks which racked round the station fires next; AIR = any. */
public record ClientSelectRound(int vehicleEntityId, int partUnitIndex, int weaponIndex, Item round)
        implements CustomPacketPayload {

    public static final Type<ClientSelectRound> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "select_round"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientSelectRound> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ClientSelectRound::vehicleEntityId,
            ByteBufCodecs.VAR_INT, ClientSelectRound::partUnitIndex,
            ByteBufCodecs.VAR_INT, ClientSelectRound::weaponIndex,
            ByteBufCodecs.registry(net.minecraft.core.registries.Registries.ITEM), ClientSelectRound::round,
            ClientSelectRound::new);

    public static void handle(ClientSelectRound msg, IPayloadContext ctx) {
        ServerPlayer player = (ServerPlayer) ctx.player();
        if (!(player.level().getEntity(msg.vehicleEntityId) instanceof AbstractVehicle vehicle)
                || !(vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit unit)
                || unit.getIndex() != msg.partUnitIndex || !CrewRules.mayOperate(player, unit)
                || msg.weaponIndex < 0 || msg.weaponIndex >= unit.indexedWeapons.size()
                || !(unit.indexedWeapons.get(msg.weaponIndex) instanceof VehicleMissile missile)) {
            return;
        }
        missile.setSelectedRound(msg.round);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
