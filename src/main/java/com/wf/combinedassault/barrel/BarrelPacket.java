package com.wf.combinedassault.barrel;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Seated operator: reload / clear jam, or change barrel, on a station weapon. */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID)
public record BarrelPacket(int vehicleEntityId, int partUnitIndex, int weaponIndex, Action action)
        implements CustomPacketPayload {

    public enum Action {
        RELOAD, CHANGE;

        static final StreamCodec<ByteBuf, Action> STREAM_CODEC =
                ByteBufCodecs.idMapper(i -> values()[i], Action::ordinal);
    }

    public static final Type<BarrelPacket> TYPE = new Type<>(CombinedAssault.modLocation("barrel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BarrelPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BarrelPacket::vehicleEntityId,
            ByteBufCodecs.VAR_INT, BarrelPacket::partUnitIndex,
            ByteBufCodecs.VAR_INT, BarrelPacket::weaponIndex,
            Action.STREAM_CODEC, BarrelPacket::action,
            BarrelPacket::new);

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(CombinedAssault.PROTOCOL).playToServer(TYPE, STREAM_CODEC, BarrelPacket::handle);
    }

    static void handle(BarrelPacket msg, IPayloadContext ctx) {
        ServerPlayer player = (ServerPlayer) ctx.player();
        if (!(player.level().getEntity(msg.vehicleEntityId) instanceof AbstractVehicle vehicle)
                || !(vehicle.getPartUnit(msg.partUnitIndex).orElse(null) instanceof WeaponUnit unit)
                || msg.weaponIndex < 0 || msg.weaponIndex >= unit.indexedWeapons.size()) {
            return;
        }
        var weapon = unit.indexedWeapons.get(msg.weaponIndex);
        if (weapon.getWeaponUnit().getOwner() != player || !CrewRules.mayOperate(player, unit)) {
            return;
        }
        BarrelActions.seated(player, weapon, msg.action);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
