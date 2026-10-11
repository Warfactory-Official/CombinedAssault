package com.wf.combinedassault.vehicle.part;

import com.wf.wflib.probe.ProbeActions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

/** Access node actions, sent as probe action packets: door toggle ({@code arg} = part index), board ({@code arg} = seat), storage. */
public final class AccessActions {

    public static final ResourceLocation TOGGLE = CombinedAssault.modLocation("toggle_door");
    public static final ResourceLocation BOARD = CombinedAssault.modLocation("board_seat");
    public static final ResourceLocation STORAGE = CombinedAssault.modLocation("open_storage");

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
    }

    /** Doors, hatches, canopies, tailgates; not gear, airbrakes, bays or arming switches (their own subclasses). */
    public static boolean isDoor(PartUnit<?> part) {
        return part instanceof DoorUnit || part.getClass() == SwitchableUnit.class;
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
