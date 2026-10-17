package com.wf.combinedassault.airdefense;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.probe.ProbeActions;
import com.wf.wflib.probe.ProbeRegistry;
import com.wf.wflib.recon.ReconOwners;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.BiConsumer;

/** Probe settings of {@code air_defense} stations ({@code arg} = part index << 8 | value), side claim, server data. */
public final class AirDefenseActions {

    public static final ResourceLocation MODE = CombinedAssault.modLocation("air_defense_mode");
    public static final ResourceLocation SOURCE = CombinedAssault.modLocation("air_defense_source");
    public static final ResourceLocation SALVO = CombinedAssault.modLocation("air_defense_salvo");
    /** {@code arg} 1 = claim for the player's side, 0 = release. */
    public static final ResourceLocation CLAIM = CombinedAssault.modLocation("air_defense_claim");
    public static final String DATA = "AirDefense";

    private AirDefenseActions() {
    }

    public static void register() {
        ProbeActions.registerEntity(MODE, (player, entity, arg) -> set(player, entity, arg,
                AirDefense.Mode.values().length, (ad, v) -> ad.setMode(AirDefense.Mode.values()[v])));
        ProbeActions.registerEntity(SOURCE, (player, entity, arg) -> set(player, entity, arg,
                AirDefense.Source.values().length, (ad, v) -> ad.setSource(AirDefense.Source.values()[v])));
        ProbeActions.registerEntity(SALVO, (player, entity, arg) -> set(player, entity, arg,
                AirDefense.MAX_SALVO + 1, (ad, v) -> {
                    if (!ad.beam()) {
                        ad.setSalvo(v);
                    }
                }));
        ProbeActions.registerEntity(CLAIM, AirDefenseActions::claim);
        ProbeRegistry.addEntityData(entity -> entity instanceof AbstractVehicle vehicle && defended(vehicle),
                AirDefenseActions::data, ProbeRegistry.CAPABILITY_PRIORITY);
    }

    public static int arg(int part, int value) {
        return part << 8 | value;
    }

    public static boolean defended(AbstractVehicle vehicle) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit && unit.airDefense() != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean set(ServerPlayer player, Entity entity, int arg, int values,
                               BiConsumer<AirDefense, Integer> apply) {
        int value = arg & 0xFF;
        AirDefense ad = entity instanceof AbstractVehicle vehicle && vehicle.mayCommand(player)
                ? station(vehicle, arg >>> 8) : null;
        if (ad == null || value >= values) {
            return false;
        }
        apply.accept(ad, value);
        return true;
    }

    @Nullable
    private static AirDefense station(AbstractVehicle vehicle, int index) {
        return index < vehicle.getPartUnits().size() && vehicle.getPartUnits().get(index) instanceof WeaponUnit unit
                ? unit.airDefense() : null;
    }

    private static boolean claim(ServerPlayer player, Entity entity, int arg) {
        if (!(entity instanceof AbstractVehicle vehicle) || !defended(vehicle) || !vehicle.mayCommand(player)) {
            return false;
        }
        UUID side = arg == 1 ? ReconOwners.owningEntity(player) : null;
        if (arg == 1 && side == null) {
            return false;
        }
        vehicle.setSide(side);
        return true;
    }

    /** {@code DATA}: side, permissions, and per station (key = part index) settings + state. */
    private static void data(CompoundTag data, ServerPlayer player, Entity entity) {
        AbstractVehicle vehicle = (AbstractVehicle) entity;
        CompoundTag out = new CompoundTag();
        UUID owner = ReconOwners.owningEntity(vehicle);
        UUID mine = ReconOwners.owningEntity(player);
        out.putString("Side", owner == null ? "" : sideName(owner, mine, player));
        out.putBoolean("Claimed", vehicle.getSide() != null);
        out.putBoolean("May", vehicle.mayCommand(player));
        out.putBoolean("HasSide", mine != null);
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit && unit.airDefense() != null) {
                AirDefense ad = unit.airDefense();
                CompoundTag station = ad.save();
                station.putString("State", ad.state().name());
                station.putInt("Interceptors", ad.interceptors());
                station.putBoolean("Beam", ad.beam());
                out.put(Integer.toString(unit.getIndex()), station);
            }
        }
        data.put(DATA, out);
    }

    /** WarForge faction name; else the player's own team name when it is theirs; else "?". */
    private static String sideName(UUID owner, @Nullable UUID mine, ServerPlayer player) {
        String faction = WarforgeCompat.factionName(owner);
        if (faction != null) {
            return faction;
        }
        return owner.equals(mine) && player.getTeam() != null ? player.getTeam().getName() : "?";
    }
}
