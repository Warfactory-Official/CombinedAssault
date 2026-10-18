package com.wf.combinedassault.vehicle.hardware;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.item.HardwareItem;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.wflib.probe.ProbeActions;
import com.wf.wflib.probe.ProbeInventory;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/** Kit fitted / taken off at a hardpoint probe node, timed {@code install_ticks}; arg = part index << 8 | kit index. */
public final class Hardpoints {

    public static final ResourceLocation FIT = CombinedAssault.modLocation("hardpoint_fit");
    public static final ResourceLocation TAKE_OFF = CombinedAssault.modLocation("hardpoint_take_off");
    private static final String KEY = "hardpoint.combinedassault.";
    /** Eye to the hardpoint part's pivot, blocks. */
    public static final double REACH = 4.0;

    private Hardpoints() {
    }

    public static void register() {
        timed(FIT, true);
        timed(TAKE_OFF, false);
    }

    public static int arg(WeaponUnit unit, int kit) {
        return unit.getIndex() << 8 | kit;
    }

    private static void timed(ResourceLocation id, boolean fit) {
        ProbeActions.registerTimedEntity(id, new ProbeActions.TimedEntity() {
            @Override
            public int ticks(ServerPlayer player, Entity entity, int arg) {
                Component refusal = refusal(player, entity, arg, fit);
                if (refusal != null) {
                    player.displayClientMessage(refusal.copy().withStyle(ChatFormatting.RED), true);
                    return 0;
                }
                WeaponUnit unit = unit(entity, arg);
                return unit.hardpoint().kits().get(fit ? arg & 0xFF : unit.kitIndex()).installTicks();
            }

            @Override
            public boolean holds(ServerPlayer player, Entity entity, int arg) {
                return refusal(player, entity, arg, fit) == null;
            }

            @Override
            public boolean perform(ServerPlayer player, Entity entity, int arg) {
                if (refusal(player, entity, arg, fit) != null) {
                    return false;
                }
                if (fit) {
                    fit(player, unit(entity, arg), arg & 0xFF);
                } else {
                    takeOff(player, unit(entity, arg));
                }
                return true;
            }
        });
    }

    @Nullable
    private static WeaponUnit unit(Entity entity, int arg) {
        return entity instanceof AbstractVehicle vehicle && vehicle.getPartUnit(arg >> 8).orElse(null) instanceof WeaponUnit
                unit && unit.isHardpoint() ? unit : null;
    }

    @Nullable
    private static Component refusal(Player player, Entity entity, int arg, boolean fit) {
        WeaponUnit unit = unit(entity, arg);
        if (unit == null) {
            return Component.translatable(KEY + "refused");
        }
        return fit ? fitRefusal(player, unit, arg & 0xFF) : takeOffRefusal(player, unit);
    }

    /** Null = kit {@code kit} may be fitted now. Same answer for the probe row and the handler. */
    @Nullable
    public static Component fitRefusal(Player player, WeaponUnit unit, int kit) {
        if (kit < 0 || kit >= unit.hardpoint().kits().size() || unit.getVehicle().isDestroyed() || unit.isDetached()) {
            return Component.translatable(KEY + "refused");
        }
        if (unit.kit() != null) {
            return Component.translatable(KEY + "occupied");
        }
        Component busy = handsOff(player, unit);
        if (busy != null) {
            return busy;
        }
        ResourceLocation id = unit.hardpoint().kits().get(kit).id();
        if (!player.getAbilities().instabuild && ProbeInventory.count(player, matcher(id)) < 1) {
            return Component.translatable(KEY + "need", Component.translatable(unit.hardpoint().kits().get(kit).nameKey()));
        }
        return null;
    }

    /** Null = the fitted kit may come off now. */
    @Nullable
    public static Component takeOffRefusal(Player player, WeaponUnit unit) {
        if (unit.kit() == null || unit.isDetached()) {
            return Component.translatable(KEY + "refused");
        }
        return handsOff(player, unit);
    }

    @Nullable
    private static Component handsOff(Player player, WeaponUnit unit) {
        if (unit.worldPivotPosition().distanceToSqr(player.getEyePosition()) > REACH * REACH) {
            return Component.translatable(KEY + "reach");
        }
        VehicleHits hits = unit.getVehicle().hits();
        if (hits != null && hits.hidden(player.getEyePosition(), VehicleHits.anchor(hits, unit))) {
            return Component.translatable(KEY + "hidden");
        }
        for (AbstractVehicle.Seat seat : unit.getVehicle().seats) {
            if (seat.partUnit == unit && seat.passengerId != -1) {
                return Component.translatable(KEY + "manned");
            }
        }
        return null;
    }

    public static Predicate<ItemStack> matcher(ResourceLocation kit) {
        return stack -> HardwareItem.kit(stack) != null && kit.equals(HardwareItem.kit(stack).id());
    }

    /** Server, refusal checked: item taken (survival), kit fitted, its first weapon selected. */
    public static void fit(ServerPlayer player, WeaponUnit unit, int kit) {
        if (!player.getAbilities().instabuild) {
            ProbeInventory.take(player, matcher(unit.hardpoint().kits().get(kit).id()), 1);
        }
        unit.fitKit(kit);
        sound(player, unit);
    }

    /** Server, refusal checked: kit item back to {@code player} (survival). */
    public static void takeOff(ServerPlayer player, WeaponUnit unit) {
        ItemStack item = HardwareItem.of(unit.kit());
        unit.fitKit(-1);
        if (!player.getAbilities().instabuild && !player.getInventory().add(item)) {
            player.drop(item, false);
        }
        sound(player, unit);
    }

    private static void sound(Player player, WeaponUnit unit) {
        var at = unit.worldPivotPosition();
        player.level().playSound(null, at.x, at.y, at.z, SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS,
                1.0f, 1.0f);
    }
}
