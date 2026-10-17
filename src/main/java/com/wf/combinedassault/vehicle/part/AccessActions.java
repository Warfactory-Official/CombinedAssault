package com.wf.combinedassault.vehicle.part;

import com.wf.wflib.probe.ProbeActions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.item.VehicleSpawnItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Access node actions, sent as probe action packets: door toggle ({@code arg} = part index), board ({@code arg} = seat), storage, pick up. */
public final class AccessActions {

    public static final ResourceLocation TOGGLE = CombinedAssault.modLocation("toggle_door");
    public static final ResourceLocation BOARD = CombinedAssault.modLocation("board_seat");
    public static final ResourceLocation STORAGE = CombinedAssault.modLocation("open_storage");
    public static final ResourceLocation PICK_UP = CombinedAssault.modLocation("pick_up");

    private AccessActions() {
    }

    public static void register() {
        ProbeActions.registerEntity(TOGGLE, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && toggle(player, vehicle, arg));
        ProbeActions.registerEntity(BOARD, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && !vehicle.uav && !vehicle.isDestroyed() && !player.isPassenger() && !player.isShiftKeyDown()
                && vehicle.board(player, arg));
        ProbeActions.registerEntity(STORAGE, (player, entity, arg) -> {
            if (!(entity instanceof AbstractVehicle vehicle) || !vehicle.hasContainer() || player.isPassenger()) {
                return false;
            }
            vehicle.openCustomInventoryScreen(player);
            return true;
        });
        ProbeActions.registerEntity(PICK_UP, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && pickUp(player, vehicle));
    }

    /** Crewed, operated, carried or wrecked => refused. Autonomy state (route, orders) not kept; engine off. */
    public static boolean pickUp(ServerPlayer player, AbstractVehicle vehicle) {
        if (!vehicle.pickup || vehicle.isDestroyed() || player.isPassenger()) {
            return false;
        }
        if (vehicle.isVehicle() || vehicle.isPassenger()) {
            player.displayClientMessage(Component.translatable("access.combinedassault.pick_up.occupied"), true);
            return false;
        }
        CompoundTag state = new CompoundTag();
        vehicle.addAdditionalSaveData(state);
        state.remove("Drone");
        state.putBoolean("Engine", false);
        ItemStack stack = AllItems.VEHICLE_SPAWN_ITEM.get().createInstance(vehicle.getVehicleId());
        CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.put(VehicleSpawnItem.TAG_STATE, state);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        vehicle.discard();
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        return true;
    }

    /** Doors, hatches, canopies, tailgates, hand-worked bays; not gear, airbrakes, powered bays or arming switches. */
    public static boolean isDoor(PartUnit<?> part) {
        return part instanceof DoorUnit || part.getClass() == SwitchableUnit.class
                || part instanceof WeaponBayUnit bay && bay.handWorked();
    }

    public static boolean toggle(ServerPlayer player, AbstractVehicle vehicle, int index) {
        if (vehicle.isDestroyed() || index < 0 || index >= vehicle.getPartUnits().size()
                || !(vehicle.getPartUnits().get(index) instanceof SwitchableUnit<?> door) || !isDoor(door)) {
            return false;
        }
        if (door.isDestroyed()) {
            player.displayClientMessage(Component.translatable("door.combinedassault.jammed"), true);
            return false;
        }
        if (vehicle.cargoHold() != null && !vehicle.cargoHold().mayClose(door, player)) {
            return false;
        }
        door.setOn(!door.isOn());
        return true;
    }
}
