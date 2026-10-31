package com.wf.combinedassault.weapon.barrel;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.barrel.api.BarrelTypes;
import com.wf.wflib.core.api.WFLibContent;
import com.wf.wflib.probe.api.ProbeActions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Barrel change ({@link GunBarrel#change}) at a muzzle probe node ({@code arg = holder unit index << 8 | weapon index}), seated jam clear,
 * reload and barrel change ({@link BarrelPacket}).
 */
public final class BarrelActions {
    public static final ResourceLocation CHANGE = CombinedAssault.modLocation("change_barrel");
    /** Eye to muzzle, blocks. */
    public static final double REACH = 3.5;
    static final String KEY = "barrel.combinedassault.";

    private BarrelActions() {
    }

    /** Mod construction. */
    public static void register() {
        WFLibContent.spareBarrel(CombinedAssault.MOD_ID);
        ProbeActions.registerTimedEntity(CHANGE, new ProbeActions.TimedEntity() {
            @Override
            public int ticks(ServerPlayer player, Entity entity, int arg) {
                Component refusal = refusal(player, entity, arg);
                if (refusal != null) {
                    player.displayClientMessage(refusal.copy().withStyle(ChatFormatting.RED), true);
                    return 0;
                }
                GunBarrel barrel = barrel(entity, arg);
                int ticks = barrel.probeTicks(player);
                barrel.holdProbeSwap(player, ticks);
                return ticks;
            }

            @Override
            public boolean holds(ServerPlayer player, Entity entity, int arg) {
                if (refusal(player, entity, arg) != null) {
                    return false;
                }
                barrel(entity, arg).holdProbeSwap(player, 0);
                return true;
            }

            @Override
            public boolean perform(ServerPlayer player, Entity entity, int arg) {
                if (refusal(player, entity, arg) != null) {
                    return false;
                }
                GunBarrel barrel = barrel(entity, arg);
                barrel.change(player);
                sound(player, barrel.weapon());
                return true;
            }
        });
    }

    public static int arg(WeaponUnit holder, AbstractVehicleWeapon<?> weapon) {
        return holder.getIndex() << 8 | holder.indexedWeapons.indexOf(weapon);
    }

    @Nullable
    public static GunBarrel barrel(Entity entity, int arg) {
        if (!(entity instanceof AbstractVehicle vehicle) || vehicle.isDestroyed()
                || !(vehicle.getPartUnit(arg >> 8).orElse(null) instanceof WeaponUnit unit)) {
            return null;
        }
        int w = arg & 0xFF;
        if (w >= unit.indexedWeapons.size()) {
            return null;
        }
        AbstractVehicleWeapon<?> weapon = unit.indexedWeapons.get(w);
        return weapon.barrel() != null && unit.carries(weapon) && !weapon.getWeaponUnit().isDetached()
                ? weapon.barrel() : null;
    }

    /** Muzzle of the weapon's current bolt. */
    public static Vec3 muzzle(AbstractVehicleWeapon<?> weapon) {
        return weapon.getWeaponUnit().aimContext().from;
    }

    /** Null = {@code player} may change it now, on foot. Same answer for the probe row and the handler. */
    @Nullable
    public static Component refusal(Player player, Entity entity, int arg) {
        GunBarrel barrel = barrel(entity, arg);
        if (barrel == null || !barrel.spec().quickChange()) {
            return Component.translatable(KEY + "fixed");
        }
        if (player.isPassenger()) {
            return Component.translatable(KEY + "on_foot");
        }
        if (barrel.busyFor(player)) {
            return Component.translatable(KEY + "busy");
        }
        Vec3 muzzle = muzzle(barrel.weapon());
        if (player.getEyePosition().distanceTo(muzzle) > REACH) {
            return Component.translatable(KEY + "reach");
        }
        VehicleHits hits = barrel.weapon().getVehicle().hits();
        if (hits != null && hits.hidden(player.getEyePosition(), muzzle)) {
            return Component.translatable(KEY + "hidden");
        }
        if (!barrel.fitted() && barrel.spare(player).isEmpty()) {
            return Component.translatable(KEY + "need", BarrelTypes.name(barrel.spec().type().orElseThrow()));
        }
        return null;
    }

    /** Server: seated operator's key on the station's weapon. */
    public static void seated(ServerPlayer player, AbstractVehicleWeapon<?> weapon, BarrelPacket.Action action) {
        GunBarrel barrel = weapon.barrel();
        switch (action) {
            case RELOAD -> {
                if (barrel != null && !barrel.canFire()) {
                    barrel.startClear();
                } else {
                    weapon.manualReload();
                }
            }
            case CHANGE -> {
                if (barrel == null || !barrel.spec().quickChange()) {
                    player.displayClientMessage(Component.translatable(KEY + "fixed"), true);
                } else if (!barrel.startSeatedSwap(player)) {
                    player.displayClientMessage(barrel.swapping() || barrel.clearing()
                            ? Component.translatable(KEY + "busy")
                            : Component.translatable(KEY + "need", BarrelTypes.name(barrel.spec().type().orElseThrow())),
                            true);
                }
            }
        }
    }

    static void sound(Player player, AbstractVehicleWeapon<?> weapon) {
        Vec3 at = muzzle(weapon);
        player.level().playSound(null, at.x, at.y, at.z, SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS,
                1.0f, 0.8f);
    }
}
