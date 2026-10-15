package com.wf.combinedassault.vehicle.loadout;

import com.google.gson.JsonObject;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.part.PartUnitEntry;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.fpv.payload.FpvPayload;
import com.wf.combinedassault.fpv.payload.FpvPayloadType;
import com.wf.combinedassault.fpv.payload.FpvPayloadTypes;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.fpv.payload.PayloadUnitData;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.LoadoutInfo;
import com.wf.wflib.probe.ProbeActions;
import com.wf.wflib.probe.ProbeInventory;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Vehicle configurations: the modding tool picks one (free, hardware refunded), the probe node fits its hardware
 * (paid), use with an accepted item straps it. Payload hardware = strapped items; weapon hardware = {@code install}
 * cost, weapon selected while fitted.
 */
public final class Loadouts {

    public static final ResourceLocation INSTALL = CombinedAssault.modLocation("loadout_install");
    public static final ResourceLocation REMOVE = CombinedAssault.modLocation("loadout_remove");
    /** Probe reach to the hardpoint's part, blocks. */
    public static final double REACH = 4.0;

    private Loadouts() {
    }

    public static void register() {
        ProbeActions.registerEntity(INSTALL, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && install(player, vehicle));
        ProbeActions.registerEntity(REMOVE, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && remove(player, vehicle));
    }

    /** Data load: every loadout names a real part of the right kind, a known payload type / one of its weapons. */
    public static void validate(BaseVehicleData<?> data) {
        for (LoadoutInfo loadout : data.getLoadouts()) {
            if (loadout.id == null) {
                throw new IllegalArgumentException("loadout without id");
            }
            if (loadout.part == null) {
                if (loadout.payload != null || loadout.weapon != null) {
                    throw new IllegalArgumentException("loadout " + loadout.id + ": hardware without a part");
                }
                continue;
            }
            PartUnitEntry<?, ?> entry = data.getParts().stream().filter(e -> loadout.part.equals(e.data().getId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("loadout " + loadout.id
                            + ": no part " + loadout.part));
            if ((loadout.payload == null) == (loadout.weapon == null)) {
                throw new IllegalArgumentException("loadout " + loadout.id + ": exactly one of payload / weapon");
            }
            if (loadout.payload != null) {
                if (!(entry.data() instanceof PayloadUnitData)) {
                    throw new IllegalArgumentException("loadout " + loadout.id + ": " + loadout.part + " is no payload part");
                }
                if (payloadType(loadout) == null) {
                    throw new IllegalArgumentException("loadout " + loadout.id + ": unknown payload type");
                }
            } else if (!(entry.data() instanceof WeaponUnitData unit)
                    || unit.getWeapons().stream().noneMatch(info -> loadout.weapon.equals(String.valueOf(info.id)))) {
                throw new IllegalArgumentException("loadout " + loadout.id + ": " + loadout.part + " has no weapon "
                        + loadout.weapon);
            }
        }
    }

    @Nullable
    private static FpvPayloadType payloadType(LoadoutInfo loadout) {
        return FpvPayloadTypes.get(ResourceLocation.tryParse(GsonHelper.getAsString(loadout.payload, "type", "")));
    }

    @Nullable
    public static PartUnit<?> part(AbstractVehicle vehicle, @Nullable LoadoutInfo loadout) {
        return loadout == null || loadout.part == null ? null : vehicle.getPartUnit(loadout.part).orElse(null);
    }

    /** Named by a loadout: its gun changes only through {@link #select} (direct tool swaps would skip the cost). */
    public static boolean manages(AbstractVehicle vehicle, PartUnit<?> part) {
        return vehicle.loadouts().stream().anyMatch(l -> part.getId().equals(l.part));
    }

    /** Parts no loadout names are always fitted; a loadout's weapon part only while its hardware is on. */
    public static boolean fitted(AbstractVehicle vehicle, PartUnit<?> part) {
        List<LoadoutInfo> all = vehicle.loadouts();
        if (all.stream().noneMatch(l -> part.getId().equals(l.part))) {
            return true;
        }
        LoadoutInfo current = vehicle.loadout();
        return current != null && part.getId().equals(current.part) && vehicle.loadoutFitted();
    }

    /**
     * Server: switch to {@code index}; current hardware comes back to {@code refundTo} (null = lost).
     */
    public static void select(AbstractVehicle vehicle, int index, @Nullable Player refundTo) {
        List<LoadoutInfo> all = vehicle.loadouts();
        if (index < 0 || index >= all.size()) {
            return;
        }
        refund(vehicle, refundTo, strip(vehicle));
        LoadoutInfo loadout = all.get(index);
        for (LoadoutInfo other : all) {
            if (other.payload != null && !other.part.equals(loadout.part)
                    && part(vehicle, other) instanceof PayloadUnit idle) {
                idle.equip(null, new JsonObject());
            }
        }
        vehicle.setLoadout(index, false);
        PartUnit<?> part = part(vehicle, loadout);
        if (part instanceof PayloadUnit hardpoint) {
            JsonObject config = loadout.payload.deepCopy();
            hardpoint.equip(payloadType(loadout), config);
        } else if (part instanceof WeaponUnit unit) {
            for (int i = 0; i < unit.weapons.size(); i++) {
                if (loadout.weapon.equals(String.valueOf(unit.weapons.get(i).getData().getWeaponId()))) {
                    unit.selectWeaponIndex(false, i, true);
                }
            }
        }
        if (loadout.flightMode != null && vehicle instanceof RotaryWingVehicle rotor && rotor.multirotor != null) {
            rotor.multirotor.setFpv("fpv".equals(loadout.flightMode));
        }
    }

    /** Takes the current loadout's hardware off; returns it. */
    private static List<ItemStack> strip(AbstractVehicle vehicle) {
        LoadoutInfo current = vehicle.loadout();
        PartUnit<?> part = part(vehicle, current);
        if (part instanceof PayloadUnit hardpoint && hardpoint.getPayload() != null) {
            return hardpoint.getPayload().unload();
        }
        if (part instanceof WeaponUnit && vehicle.loadoutFitted()) {
            vehicle.setLoadout(vehicle.loadoutIndex(), false);
            return costStacks(current);
        }
        return List.of();
    }

    /** One {@code install} cost as stacks: item ids only; a {@code #tag} cost is not refundable. */
    private static List<ItemStack> costStacks(LoadoutInfo loadout) {
        return loadout.install.stream().filter(c -> !c.item.startsWith("#"))
                .map(c -> new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(c.item)), c.count)).toList();
    }

    private static void refund(AbstractVehicle vehicle, @Nullable Player to, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (to == null) {
                vehicle.spawnAtLocation(stack);
            } else if (!to.getAbilities().instabuild && !to.getInventory().add(stack)) {
                to.drop(stack, false);
            }
        }
    }

    public static Predicate<ItemStack> matcher(LoadoutInfo.Cost cost) {
        if (cost.item.startsWith("#")) {
            TagKey<net.minecraft.world.item.Item> tag = TagKey.create(Registries.ITEM,
                    ResourceLocation.parse(cost.item.substring(1)));
            return stack -> stack.is(tag);
        }
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(cost.item));
        return stack -> stack.is(item);
    }

    private static boolean inReach(Player player, PartUnit<?> part) {
        return part.worldPivotPosition().distanceToSqr(player.getEyePosition())
                <= REACH * REACH;
    }

    /** Payload: straps one carried item it accepts. Weapon: pays the full {@code install} cost. */
    public static boolean install(ServerPlayer player, AbstractVehicle vehicle) {
        LoadoutInfo loadout = vehicle.loadout();
        PartUnit<?> part = part(vehicle, loadout);
        if (part == null || !inReach(player, part)) {
            return false;
        }
        if (part instanceof PayloadUnit hardpoint) {
            FpvPayload payload = hardpoint.getPayload();
            ItemStack found = payload == null ? ItemStack.EMPTY : ProbeInventory.find(player, payload::canLoad);
            if (found.isEmpty()) {
                refuse(player, Component.translatable("loadout.combinedassault.need_payload"));
                return false;
            }
            ItemStack one = found.copyWithCount(1);
            if (!ProbeInventory.take(player, payload::canLoad, 1)) {
                return false;
            }
            payload.load(one);
            sound(player, part);
            return true;
        }
        if (vehicle.loadoutFitted()) {
            return false;
        }
        for (LoadoutInfo.Cost cost : loadout.install) {
            if (ProbeInventory.count(player, matcher(cost)) < cost.count) {
                refuse(player, Component.translatable("loadout.combinedassault.need", cost.count, cost.item));
                return false;
            }
        }
        for (LoadoutInfo.Cost cost : loadout.install) {
            ProbeInventory.take(player, matcher(cost), cost.count);
        }
        vehicle.setLoadout(vehicle.loadoutIndex(), true);
        sound(player, part);
        return true;
    }

    /** Loadout a held item would strap into: current if its payload takes it, else first that would; -1 = none. */
    public static int strapIndex(AbstractVehicle vehicle, ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }
        if (part(vehicle, vehicle.loadout()) instanceof PayloadUnit hardpoint && hardpoint.getPayload() != null
                && hardpoint.getPayload().canLoad(stack)) {
            return vehicle.loadoutIndex();
        }
        List<LoadoutInfo> all = vehicle.loadouts();
        for (int i = 0; i < all.size(); i++) {
            LoadoutInfo loadout = all.get(i);
            FpvPayloadType type = loadout.payload == null ? null : payloadType(loadout);
            if (type != null && type.fromJson().create(type, loadout.payload.deepCopy()).canLoad(stack)) {
                return i;
            }
        }
        return -1;
    }

    /** Use with an item: straps it into {@link #strapIndex}'s loadout (switching refunds current hardware). */
    public static boolean strap(Player player, AbstractVehicle vehicle, ItemStack stack) {
        int index = strapIndex(vehicle, stack);
        if (index < 0) {
            return false;
        }
        if (index != vehicle.loadoutIndex()) {
            select(vehicle, index, player);
        }
        PayloadUnit hardpoint = (PayloadUnit) part(vehicle, vehicle.loadout());
        hardpoint.getPayload().load(stack.copyWithCount(1));
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        sound(player, hardpoint);
        return true;
    }

    public static boolean remove(ServerPlayer player, AbstractVehicle vehicle) {
        PartUnit<?> part = part(vehicle, vehicle.loadout());
        if (part == null || !inReach(player, part)) {
            return false;
        }
        List<ItemStack> back = strip(vehicle);
        if (back.isEmpty() && !(part instanceof WeaponUnit)) {
            return false;
        }
        refund(vehicle, player, back);
        sound(player, part);
        return true;
    }

    private static void refuse(Player player, Component why) {
        player.displayClientMessage(why.copy().withStyle(ChatFormatting.RED), true);
    }

    private static void sound(Player player, PartUnit<?> part) {
        var at = part.worldPivotPosition();
        player.level().playSound(null, at.x, at.y, at.z, SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS,
                1.0f, 1.0f);
    }
}
