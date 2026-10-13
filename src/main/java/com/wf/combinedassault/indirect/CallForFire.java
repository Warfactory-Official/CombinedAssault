package com.wf.combinedassault.indirect;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.indirect.net.FireRequestPacket;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.pda.markers.MarkerStore;
import com.wf.wflib.pda.uplink.Uplink;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * PDA observer -> same-side {@code indirect_fire.map} operators: a request they may accept on their map (firing stays
 * operator-client, {@link com.wf.combinedassault.indirect.client.FireMissions}). Observer: PDA carried + uplink.
 */
public final class CallForFire {
    private CallForFire() {
    }

    /** @return operators reached; -1 = refused (no PDA / no uplink) */
    public static int request(ServerPlayer observer, Vec3 target) {
        if (!Pda.carries(observer) || Uplink.of(observer) == Uplink.Link.NONE) {
            observer.displayClientMessage(Component.translatable("tips.call_for_fire.no_uplink"), true);
            return -1;
        }
        UUID side = MarkerStore.side(observer);
        int sent = 0;
        for (ServerPlayer operator : observer.server.getPlayerList().getPlayers()) {
            if (operator != observer && operator.level() == observer.level() && station(operator) != null
                    && side.equals(MarkerStore.side(operator))) {
                PacketDistributor.sendToPlayer(operator, new FireRequestPacket(observer.getGameProfile().getName(),
                        target));
                sent++;
            }
        }
        observer.displayClientMessage(sent == 0 ? Component.translatable("tips.call_for_fire.none")
                : Component.translatable("tips.call_for_fire.sent", sent), true);
        return sent;
    }

    /** Seated operator's map-ordering weapon unit. */
    @Nullable
    public static WeaponUnit station(ServerPlayer player) {
        if (!(player.getVehicle() instanceof AbstractVehicle vehicle)) {
            return null;
        }
        PartUnit<?> unit = vehicle.getOwnOperatorUnit(player);
        return unit instanceof WeaponUnit weapon && weapon.getIndirectFireSpec() != null
                && weapon.getIndirectFireSpec().map() ? weapon : null;
    }
}
