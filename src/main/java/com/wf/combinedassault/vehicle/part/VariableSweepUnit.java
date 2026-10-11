package com.wf.combinedassault.vehicle.part;

import net.minecraft.util.Mth;
import com.wf.combinedassault.custom.part.data.VariableSweepUnitData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

/** Wing whose yaw follows airspeed; its structure bone (hull pieces, hits) turns with it. */
public class VariableSweepUnit extends RotatableUnit<VariableSweepUnitData> {

    public VariableSweepUnit(int index, AbstractVehicle vehicle, VariableSweepUnitData data) {
        super(index, vehicle, data);
    }

    @Override
    public void tick() {
        float t = Mth.clamp((float) ((vehicle.getDeltaMovement().length() - data.sweepStart())
                / (data.sweepEnd() - data.sweepStart())), 0, 1);
        setYAimRot(data.sweepAngle() * t);
        super.tick();
    }

}
