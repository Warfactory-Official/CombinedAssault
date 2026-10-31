package com.wf.combinedassault.weapon.part;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.probe.api.ProbeActions;
import com.wf.wflib.probe.api.ProbeInventory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.part.PartUnit;

/**
 * Exposed ordnance stations, on foot: holder station ({@code arg} = {@link #holder}) or weapon {@code round_bones}
 * station ({@link #weapon}). Load takes one carried round that fits; unload hands the round back.
 * Weapon stations: capacity == bone count only (one round per bone); bones fill from the last, so load lands on
 * the highest empty bone, unload takes the lowest loaded.
 */
public final class StationActions {

    public static final ResourceLocation LOAD = CombinedAssault.modLocation("station_load");
    public static final ResourceLocation UNLOAD = CombinedAssault.modLocation("station_unload");
    private static final int WEAPON = 1 << 30;

    private StationActions() {
    }

    public static void register() {
        ProbeActions.registerEntity(LOAD, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && load(player, vehicle, arg));
        ProbeActions.registerEntity(UNLOAD, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && unload(player, vehicle, arg));
    }

    public static int holder(int part, int slot) {
        return part << 8 | slot;
    }

    public static int weapon(int part, int weapon) {
        return WEAPON | part << 8 | weapon;
    }

    /** Weapon whose bones are its stations, one round each; null otherwise. */
    @Nullable
    public static AbstractVehicleWeapon<?> stationed(PartUnit<?> part, int weapon) {
        if (!(part instanceof WeaponUnit unit) || weapon >= unit.getIndexedWeapons().size()) {
            return null;
        }
        AbstractVehicleWeapon<?> w = unit.getIndexedWeapons().get(weapon);
        return !w.getRoundBones().isEmpty() && w.getRoundBones().size() == w.getMaxCapacity()
                && w.getData().getReload() != null ? w : null;
    }

    /** Weapon station takes {@code stack}: its ammo, room, same round as the one racked. */
    public static boolean fits(AbstractVehicleWeapon<?> weapon, ItemStack stack) {
        return weapon.isAmmoForWeapon(stack) && weapon.getRemainAmmo() < weapon.getMaxCapacity()
                && (weapon.getRemainAmmo() == 0 || ItemStack.isSameItemSameComponents(weapon.getLoadedRound(), stack));
    }

    private static boolean load(ServerPlayer player, AbstractVehicle vehicle, int arg) {
        PartUnit<?> part = part(player, vehicle, arg);
        if (part == null) {
            return false;
        }
        int index = arg & 0xFF;
        if ((arg & WEAPON) != 0) {
            AbstractVehicleWeapon<?> weapon = stationed(part, index);
            ItemStack round = weapon == null ? ItemStack.EMPTY : ProbeInventory.find(player, s -> fits(weapon, s));
            if (round.isEmpty()) {
                return false;
            }
            if (weapon.getRemainAmmo() == 0) {
                weapon.setLoadedRound(round.copyWithCount(1));
            }
            weapon.setRemainAmmo(weapon.getRemainAmmo() + 1);
            consume(player, round);
            return true;
        }
        if (!(part instanceof MissileHolderUnit holder) || index >= holder.getCapacity()) {
            return false;
        }
        ItemStack round = ProbeInventory.find(player, s -> holder.canLoad(index, s));
        if (round.isEmpty()) {
            return false;
        }
        holder.load(index, round.copyWithCount(1));
        consume(player, round);
        return true;
    }

    private static boolean unload(ServerPlayer player, AbstractVehicle vehicle, int arg) {
        PartUnit<?> part = part(player, vehicle, arg);
        if (part == null) {
            return false;
        }
        int index = arg & 0xFF;
        ItemStack round;
        if ((arg & WEAPON) != 0) {
            AbstractVehicleWeapon<?> weapon = stationed(part, index);
            if (weapon == null || weapon.getRemainAmmo() == 0) {
                return false;
            }
            round = weapon.getLoadedRound().copyWithCount(1);
            weapon.setRemainAmmo(weapon.getRemainAmmo() - 1);
            if (weapon.getRemainAmmo() == 0) {
                weapon.setLoadedRound(ItemStack.EMPTY);
            }
        } else if (part instanceof MissileHolderUnit holder && index < holder.getCapacity()
                && !holder.round(index).isEmpty()) {
            round = holder.take(index);
        } else {
            return false;
        }
        if (!round.isEmpty() && !player.getInventory().add(round)) {
            player.drop(round, false);
        }
        return true;
    }

    @Nullable
    private static PartUnit<?> part(ServerPlayer player, AbstractVehicle vehicle, int arg) {
        int part = arg >>> 8 & 0x3FFFFF;
        if (vehicle.isDestroyed() || player.isPassenger() || part >= vehicle.getPartUnits().size()) {
            return null;
        }
        PartUnit<?> unit = vehicle.getPartUnits().get(part);
        return unit.isDetached() ? null : unit;
    }

    private static void consume(ServerPlayer player, ItemStack stack) {
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }
}
