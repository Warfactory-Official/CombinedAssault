package com.wf.combinedassault.vehicle.lock;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.compat.api.WarforgeCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Who may board, open storage, work doors from outside, or pick up. Riders always pass. Client {@code FACTION}
 * => permit (faction data server-side); server decides.
 */
public enum VehicleLock {
    OPEN, PLAYER, FACTION;

    private static final String KEY = "lock.combinedassault.";

    public static VehicleLock of(AbstractVehicle vehicle) {
        return values()[vehicle.getEntityData().get(AbstractVehicle.LOCK_MODE)];
    }

    public static Optional<UUID> owner(AbstractVehicle vehicle) {
        return vehicle.getEntityData().get(AbstractVehicle.LOCK_OWNER);
    }

    public static boolean permits(AbstractVehicle vehicle, Entity entity) {
        VehicleLock mode = of(vehicle);
        if (mode == OPEN || entity.getVehicle() == vehicle) {
            return true;
        }
        if (!(entity instanceof Player player)) {
            return false;
        }
        if (isOwner(vehicle, player) || player.hasPermissions(2)) {
            return true;
        }
        if (mode == PLAYER) {
            return false;
        }
        if (vehicle.level().isClientSide()) {
            return true;
        }
        UUID faction = vehicle.getEntityData().get(AbstractVehicle.LOCK_FACTION).orElse(null);
        return faction != null && faction.equals(WarforgeCompat.factionOfPlayer(player.getUUID()));
    }

    /** {@link #permits}; else action-bar refusal (server). */
    public static boolean check(AbstractVehicle vehicle, Player player) {
        if (permits(vehicle, player)) {
            return true;
        }
        player.displayClientMessage(Component.translatable(KEY + "denied").withStyle(ChatFormatting.RED), true);
        return false;
    }

    /** Unlocked, or owner, or op. */
    public static boolean mayChange(AbstractVehicle vehicle, Player player) {
        return of(vehicle) == OPEN || isOwner(vehicle, player) || player.hasPermissions(2);
    }

    private static boolean isOwner(AbstractVehicle vehicle, Player player) {
        return owner(vehicle).filter(player.getUUID()::equals).isPresent();
    }

    /** {@code OPEN -> PLAYER -> FACTION -> OPEN}; FACTION skipped without a faction. */
    public static VehicleLock next(VehicleLock mode, boolean hasFaction) {
        return switch (mode) {
            case OPEN -> PLAYER;
            case PLAYER -> hasFaction ? FACTION : OPEN;
            case FACTION -> OPEN;
        };
    }

    public static boolean cycle(ServerPlayer player, AbstractVehicle vehicle) {
        return set(player, vehicle, next(of(vehicle), WarforgeCompat.factionOfPlayer(player.getUUID()) != null));
    }

    public static void open(AbstractVehicle vehicle) {
        var data = vehicle.getEntityData();
        data.set(AbstractVehicle.LOCK_MODE, (byte) OPEN.ordinal());
        data.set(AbstractVehicle.LOCK_OWNER, Optional.empty());
        data.set(AbstractVehicle.LOCK_FACTION, Optional.empty());
    }

    public static boolean set(ServerPlayer player, AbstractVehicle vehicle, VehicleLock mode) {
        if (vehicle.isDestroyed()) {
            return false;
        }
        if (!mayChange(vehicle, player)) {
            player.displayClientMessage(Component.translatable(KEY + "owned").withStyle(ChatFormatting.RED), true);
            return false;
        }
        UUID faction = mode == FACTION ? WarforgeCompat.factionOfPlayer(player.getUUID()) : null;
        if (mode == FACTION && faction == null) {
            player.displayClientMessage(Component.translatable(KEY + "no_faction").withStyle(ChatFormatting.RED), true);
            return false;
        }
        var data = vehicle.getEntityData();
        data.set(AbstractVehicle.LOCK_MODE, (byte) mode.ordinal());
        data.set(AbstractVehicle.LOCK_OWNER, mode == OPEN ? Optional.empty()
                : owner(vehicle).or(() -> Optional.of(player.getUUID())));
        data.set(AbstractVehicle.LOCK_FACTION, Optional.ofNullable(faction));
        Component name = mode == FACTION ? Component.literal(String.valueOf(WarforgeCompat.factionName(faction)))
                : Component.empty();
        player.displayClientMessage(Component.translatable(KEY + mode.name().toLowerCase(java.util.Locale.ROOT), name), true);
        return true;
    }
}
