package com.wf.combinedassault.aircraft.part.data;

import com.wf.combinedassault.vehicle.part.data.RotatableUnitData;

public class VariableSweepUnitData extends RotatableUnitData {

    private final float sweepAngle;
    private final float sweepStart;
    private final float sweepEnd;

    public VariableSweepUnitData(VariableSweepUnitPojo pojo) {
        super(pojo);
        if (!(pojo.sweepEnd > pojo.sweepStart)) {
            throw new IllegalArgumentException(getId() + ": sweep_end " + pojo.sweepEnd + " <= sweep_start " + pojo.sweepStart);
        }
        this.sweepAngle = pojo.sweepAngle;
        this.sweepStart = pojo.sweepStart;
        this.sweepEnd = pojo.sweepEnd;
    }

    public float sweepAngle() {
        return sweepAngle;
    }

    public float sweepStart() {
        return sweepStart;
    }

    public float sweepEnd() {
        return sweepEnd;
    }

}
