package com.wf.combinedassault.vehicle.part;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import com.wf.combinedassault.pack.sync.SyncDataSerializers;
import com.wf.combinedassault.vehicle.part.data.PartUnitData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

public class SwitchableUnit<T extends PartUnitData> extends PartUnit<T> {

    protected boolean on;

    public SwitchableUnit(int index, AbstractVehicle vehicle, T data) {
        super(index, vehicle, data);
        this.on = defaultOpen();
        this.getSyncData().define(SyncDataSerializers.BOOLEAN, this::setOn, this::isOn, false);
    }

    public boolean onInteract(Player player, InteractionHand hand) {
        // Doors: access nodes only.
        if (!vehicle.level().isClientSide() && hand == InteractionHand.MAIN_HAND && !AccessActions.isDoor(this)) {
            if (!on) {
                this.on = true;
                return false;
            } else {
                this.on = false;
                return !player.isShiftKeyDown();
            }
        }
        return true;
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean on) {
        this.on = on;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = super.serializeNBT(provider);
        tag.putBoolean("on", this.on);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        this.on = nbt.getBoolean("on");
    }

    public boolean defaultOpen() {
        return false;
    }

}
