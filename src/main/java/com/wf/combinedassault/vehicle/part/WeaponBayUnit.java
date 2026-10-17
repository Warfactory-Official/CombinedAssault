package com.wf.combinedassault.vehicle.part;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.wf.combinedassault.custom.part.data.WeaponBayUnitData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

public class WeaponBayUnit extends SwitchableUnit<WeaponBayUnitData> {

    public WeaponBayUnit(int index, AbstractVehicle vehicle, WeaponBayUnitData data) {
        super(index, vehicle, data);
    }

    @Override
    public void setOn(boolean on) {
        if (data.needPower() && !vehicle.hasPower()) {
            return;
        }
        super.setOn(on);
        if (vehicle.getDriver() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable(on ? "tips.bay_open" : "tips.bay_close"), true);
        }
    }

    /** {@code need_power: false}: crew-worked, toggled on foot like a door. */
    public boolean handWorked() {
        return !data.needPower();
    }

}
