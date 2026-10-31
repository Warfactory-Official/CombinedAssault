package com.wf.combinedassault.weapon.message;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Round kind a weapon loads (stores) or fires next (rack); empty = rack's any. */
public record ClientSelectRound(int vehicleEntityId, int partUnitIndex, int weaponIndex, ItemStack round)
        implements CustomPacketPayload {

    public static final Type<ClientSelectRound> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "select_round"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientSelectRound> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ClientSelectRound::vehicleEntityId,
            ByteBufCodecs.VAR_INT, ClientSelectRound::partUnitIndex,
            ByteBufCodecs.VAR_INT, ClientSelectRound::weaponIndex,
            ItemStack.OPTIONAL_STREAM_CODEC, ClientSelectRound::round,
            ClientSelectRound::new);

    public static void handle(ClientSelectRound msg, IPayloadContext ctx) {
        ServerPlayer player = (ServerPlayer) ctx.player();
        if (!(player.level().getEntity(msg.vehicleEntityId) instanceof AbstractVehicle vehicle)
                || !(vehicle.getPartUnit(msg.partUnitIndex).orElse(null) instanceof WeaponUnit unit)
                || msg.weaponIndex < 0 || msg.weaponIndex >= unit.indexedWeapons.size()) {
            return;
        }
        if (!CrewRules.maySwitchAmmo(player, unit)) {
            if (CrewRules.mayOperate(player, unit)) {
                player.displayClientMessage(Component.translatable("tips.crew.loader_picks_ammo"), true);
            }
            return;
        }
        unit.indexedWeapons.get(msg.weaponIndex).selectRound(msg.round.copyWithCount(1));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
