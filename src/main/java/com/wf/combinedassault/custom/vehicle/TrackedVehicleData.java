package com.wf.combinedassault.custom.vehicle;

import net.minecraft.world.level.Level;
import com.wf.combinedassault.all.AllEntities;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.TrackedVehicle;

public class TrackedVehicleData extends BaseVehicleData<TrackedVehicle> {

    public float brakeAcceleration;
    public float forwardAcceleration;
    public float backwardAcceleration;
    public float maxSpeedForward;
    public float maxSpeedBackward;
    public float turnAcceleration;
    public float maxTurn;
    public float trackSize;

    @Override
    public AbstractVehicle fromCustom(Level level) {
        return new TrackedVehicle(AllEntities.TRACKED_VEHICLE.get(), level);
    }

    public void build(TrackedVehicleDataPojo pojo) {
        super.build(pojo);
        this.brakeAcceleration = pojo.attributes.brakeAcceleration;
        this.forwardAcceleration = pojo.attributes.forwardAcceleration;
        this.backwardAcceleration = pojo.attributes.backwardAcceleration;
        this.maxSpeedForward = pojo.attributes.maxSpeedForward;
        this.maxSpeedBackward = pojo.attributes.maxSpeedBackward;
        this.turnAcceleration = pojo.attributes.turnAcceleration;
        this.maxTurn = pojo.attributes.maxTurn;
        this.trackSize = pojo.attributes.trackSize;
    }

    public void inject(TrackedVehicle vehicle) {
        vehicle.brakeAcceleration = this.brakeAcceleration;
        vehicle.forwardAcceleration = this.forwardAcceleration;
        vehicle.backwardAcceleration = this.backwardAcceleration;
        vehicle.maxSpeedForward = this.maxSpeedForward;
        vehicle.maxSpeedBackward = this.maxSpeedBackward;
        vehicle.turnAcceleration = this.turnAcceleration;
        vehicle.maxTurn = this.maxTurn;
        vehicle.trackSize = this.trackSize;
    }

}
