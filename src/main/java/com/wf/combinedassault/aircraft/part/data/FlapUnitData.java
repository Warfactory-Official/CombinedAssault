package com.wf.combinedassault.aircraft.part.data;

import com.wf.combinedassault.vehicle.part.data.RotatableUnitData;

public class FlapUnitData extends RotatableUnitData {

    private final float[] stages;
    private final float liftGain;
    private final float dragK;
    private final float stallShift;
    private final float maxSpeed;

    public FlapUnitData(FlapUnitPojo pojo) {
        super(pojo);
        if (pojo.stages.length < 2 || pojo.stages[0] != 0) {
            throw new IllegalArgumentException(getId() + ": stages needs 0 first and at least one deployed notch");
        }
        this.stages = pojo.stages.clone();
        this.liftGain = pojo.liftGain;
        this.dragK = pojo.dragK;
        this.stallShift = pojo.stallShift;
        this.maxSpeed = pojo.maxSpeed;
        float full = 0;
        for (float stage : stages) {
            full = Math.max(full, Math.abs(stage));
        }
        rotInfo.xRotMin = -full;
        rotInfo.xRotMax = full;
        if (rotInfo.xRotSpeed == 0) {
            rotInfo.xRotSpeed = 1;
        }
    }

    public float[] stages() {
        return stages;
    }

    public float liftGain() {
        return liftGain;
    }

    public float dragK() {
        return dragK;
    }

    public float stallShift() {
        return stallShift;
    }

    public float maxSpeed() {
        return maxSpeed;
    }
}
