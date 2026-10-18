package com.wf.combinedassault.vehicle.loadout;

import com.google.gson.JsonObject;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.part.PartUnitEntry;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.fpv.payload.FpvPayload;
import com.wf.combinedassault.fpv.payload.FpvPayloadType;
import com.wf.combinedassault.fpv.payload.FpvPayloadTypes;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.fpv.payload.PayloadUnitData;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.pojo.LoadoutInfo;
import com.wf.wflib.probe.ProbeActions;
import com.wf.wflib.probe.ProbeInventory;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Payload configurations: the modding tool picks one (free, hardware refunded), the probe node straps payload
 * hardware, use with an accepted item straps it.
 */
public final class Loadouts {

    public static final ResourceLocation INSTALL = CombinedAssault.modLocation("loadout_install");
    public static final ResourceLocation REMOVE = CombinedAssault.modLocation("loadout_remove");
    private static final String KEY = "loadout.combinedassault.";
    /** Probe reach to the payload part, blocks. */
    public static final double REACH = 4.0;

    private Loadouts() {
    }

    public static void register() {
        ProbeActions.registerEntity(INSTALL, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && install(player, vehicle));
        ProbeActions.registerEntity(REMOVE, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && remove(player, vehicle));
    }

    /** Data load: every loadout with a part names a real payload part and a known payload type. */
    public static void validate(BaseVehicleData<?> data) {
        for (LoadoutInfo loadout : data.getLoadouts()) {
            if (loadout.id == null) {
                throw new IllegalArgumentException("loadout without id");
            }
            if (loadout.part == null) {
                if (loadout.payload != null) {
                    throw new IllegalArgumentException("loadout " + loadout.id + ": hardware without a part");
                }
                continue;
            }
            PartUnitEntry<?, ?> entry = data.getParts().stream().filter(e -> loadout.part.equals(e.data().getId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("loadout " + loadout.id
                            + ": no part " + loadout.part));
            if (loadout.payload == null || !(entry.data() instanceof PayloadUnitData)) {
                throw new IllegalArgumentException("loadout " + loadout.id + ": a part takes a payload part + payload");
            }
            if (payloadType(loadout) == null) {
                throw new IllegalArgumentException("loadout " + loadout.id + ": unknown payload type");
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
        vehicle.setLoadout(index);
        if (part(vehicle, loadout) instanceof PayloadUnit hardpoint) {
            hardpoint.equip(payloadType(loadout), loadout.payload.deepCopy());
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
        return List.of();
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

    private static boolean inReach(Player player, PartUnit<?> part) {
        return part.worldPivotPosition().distanceToSqr(player.getEyePosition())
                <= REACH * REACH;
    }

    /** Payload: straps one carried item it accepts. */
    public static boolean install(ServerPlayer player, AbstractVehicle vehicle) {
        PartUnit<?> part = part(vehicle, vehicle.loadout());
        if (!(part instanceof PayloadUnit hardpoint) || !inReach(player, part)) {
            return false;
        }
        FpvPayload payload = hardpoint.getPayload();
        ItemStack found = payload == null ? ItemStack.EMPTY : ProbeInventory.find(player, payload::canLoad);
        if (found.isEmpty()) {
            refuse(player, Component.translatable(KEY + "need_payload"));
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

    /** Payload: unstraps everything. */
    public static boolean remove(ServerPlayer player, AbstractVehicle vehicle) {
        PartUnit<?> part = part(vehicle, vehicle.loadout());
        if (!(part instanceof PayloadUnit) || !inReach(player, part)) {
            return false;
        }
        List<ItemStack> back = strip(vehicle);
        if (back.isEmpty()) {
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
