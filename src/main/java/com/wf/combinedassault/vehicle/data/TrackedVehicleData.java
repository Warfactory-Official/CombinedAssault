package com.wf.combinedassault.vehicle.data;

import com.wf.combinedassault.vehicle.VehicleContent;
import com.wf.combinedassault.vehicle.drive.Transmission;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.entity.TrackedVehicle;
import net.minecraft.world.level.Level;

public class TrackedVehicleData extends BaseVehicleData<TrackedVehicle> {

    public float brakeAcceleration;
    public float forwardAcceleration;
    public float backwardAcceleration;
    /** W. */
    public float enginePower;
    public float maxSpeedForward;
    public float maxSpeedBackward;
    public float turnAcceleration;
    public float maxTurn;
    public float trackSize;
    public Transmission transmission;

    @Override
    public AbstractVehicle fromCustom(Level level) {
        return new TrackedVehicle(VehicleContent.TRACKED_VEHICLE.get(), level);
    }

    public void build(TrackedVehicleDataPojo pojo) {
        super.build(pojo);
        this.brakeAcceleration = pojo.attributes.brakeAcceleration;
        this.forwardAcceleration = pojo.attributes.forwardAcceleration;
        this.backwardAcceleration = pojo.attributes.backwardAcceleration;
        this.enginePower = pojo.attributes.enginePower * 1000;
        this.maxSpeedForward = pojo.attributes.maxSpeedForward;
        this.maxSpeedBackward = pojo.attributes.maxSpeedBackward;
        this.turnAcceleration = pojo.attributes.turnAcceleration;
        this.maxTurn = pojo.attributes.maxTurn;
        this.trackSize = pojo.attributes.trackSize;
        this.transmission = pojo.attributes.transmission;
        if (transmission != null) {
            transmission.validate();
        }
    }

    public void inject(TrackedVehicle vehicle) {
        vehicle.brakeAcceleration = this.brakeAcceleration;
        vehicle.forwardAcceleration = this.forwardAcceleration;
        vehicle.backwardAcceleration = this.backwardAcceleration;
        vehicle.enginePower = this.enginePower;
        vehicle.maxSpeedForward = this.maxSpeedForward;
        vehicle.maxSpeedBackward = this.maxSpeedBackward;
        vehicle.turnAcceleration = this.turnAcceleration;
        vehicle.maxTurn = this.maxTurn;
        vehicle.trackSize = this.trackSize;
        vehicle.transmission = this.transmission;
    }

}
