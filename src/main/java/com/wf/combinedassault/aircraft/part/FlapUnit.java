package com.wf.combinedassault.aircraft.part;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import com.wf.combinedassault.pack.sync.SyncDataSerializers;
import com.wf.combinedassault.aircraft.part.data.FlapUnitData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.RotatableUnit;

/** Trailing-edge flaps: notched deflection = part xRot. */
public class FlapUnit extends RotatableUnit<FlapUnitData> {

    private int stage;
    private final float full;

    public FlapUnit(int index, AbstractVehicle vehicle, FlapUnitData data) {
        super(index, vehicle, data);
        float max = 0;
        for (float s : data.stages()) {
            max = Math.max(max, Math.abs(s));
        }
        this.full = max;
        this.getSyncData().define(SyncDataSerializers.INT, this::setStage, this::getStage, 0);
    }

    @Override
    public void tick() {
        if (stage > 0 && !vehicle.level().isClientSide() && vehicle.getDeltaMovement().length() > data.maxSpeed()) {
            stage = 0;
            if (vehicle.getDriver() instanceof ServerPlayer driver) {
                driver.displayClientMessage(Component.translatable("tips.flaps_overspeed"), true);
            }
        }
        setXAimRot(data.stages()[stage]);
        super.tick();
    }

    public boolean step(int direction) {
        int next = Mth.clamp(stage + Integer.signum(direction), 0, data.stages().length - 1);
        if (next == stage) {
            return false;
        }
        stage = next;
        return true;
    }

    /** Deployed share of full deflection, as the surface actually stands now. */
    public float level() {
        return Math.abs(getXRot()) / full;
    }

    public float level(float partialTick) {
        return Math.abs(getViewXRot(partialTick)) / full;
    }

    public int getStage() {
        return stage;
    }

    public void setStage(int stage) {
        this.stage = Mth.clamp(stage, 0, data.stages().length - 1);
    }

    public int stageCount() {
        return data.stages().length;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = super.serializeNBT(provider);
        tag.putInt("stage", stage);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        setStage(nbt.getInt("stage"));
    }
}
