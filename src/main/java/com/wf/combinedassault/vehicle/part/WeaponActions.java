package com.wf.combinedassault.vehicle.part;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.probe.ProbeActions;
import com.wf.wflib.probe.ProbeInventory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Probe row: select weapon of an interactive weapon part ({@code arg} = {@link #arg}). Modding tool carried. */
public final class WeaponActions {

    public static final ResourceLocation SELECT = CombinedAssault.modLocation("select_part_weapon");

    private WeaponActions() {
    }

    public static void register() {
        ProbeActions.registerEntity(SELECT, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && select(player, vehicle, arg >>> 8, arg & 0xFF));
    }

    public static int arg(int part, int weapon) {
        return part << 8 | weapon;
    }

    public static boolean select(ServerPlayer player, AbstractVehicle vehicle, int part, int weapon) {
        if (vehicle.isDestroyed() || part >= vehicle.getPartUnits().size()
                || !(vehicle.getPartUnits().get(part) instanceof WeaponUnit unit) || !unit.isInteractive()
                || weapon >= unit.weapons.size() || weapon == unit.getCurrentWeaponIndex()
                || ProbeInventory.find(player, stack -> stack.is(AllItems.MODDING_TOOL.get())).isEmpty()) {
            return false;
        }
        unit.selectWeaponIndex(false, weapon, true);
        return true;
    }
}
