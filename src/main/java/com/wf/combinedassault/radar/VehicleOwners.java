package com.wf.combinedassault.radar;

import com.wf.wflib.recon.api.OwnerSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Folds scoreboard team name (a string) to a stable namespaced UUID, comparable to WFLib's network UUIDs.
 * Registered below WFLib' own WarForge source: WarForge wins when it has an opinion; non-player entities
 * (WarForge has none) fall through to this.
 */
final class VehicleOwners implements OwnerSource {

    /**
     * Namespace prefix keeps folded UUIDs disjoint from real faction UUIDs. Changing it re-homes every
     * team's network.
     */
    private static final String TEAM_NAMESPACE = "combinedassault:team:";

    /** Scoreboard team claims no ground; always null here. */
    @Override
    @Nullable
    public UUID owningAt(ServerLevel level, BlockPos pos) {
        return null;
    }

    @Override
    @Nullable
    public UUID owningEntity(Entity entity) {
        if (entity instanceof AbstractVehicle vehicle) {
            // vehicle.getTeam(): driver's team, or a remote-op assigned team. Null for an unmanned parked hull.
            return vehicle.getSide() != null ? vehicle.getSide() : of(vehicle.getTeam());
        }
        if (entity instanceof Player player) {
            return of(player.getTeam());
        }
        return null;
    }

    /**
     * @return the network identity for a scoreboard team, or null for no team.
     */
    @Nullable
    static UUID of(@Nullable PlayerTeam team) {
        if (team == null) {
            return null;
        }
        return UUID.nameUUIDFromBytes((TEAM_NAMESPACE + team.getName()).getBytes(StandardCharsets.UTF_8));
    }

}
