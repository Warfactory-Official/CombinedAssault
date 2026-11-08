package com.wf.combinedassault.vehicle.data;

import com.wf.combinedassault.vehicle.VehicleContent;
import com.wf.combinedassault.vehicle.drive.Transmission;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.entity.WheeledVehicle;
import net.minecraft.world.level.Level;

public class WheeledVehicleData extends BaseVehicleData<WheeledVehicle> {

    public float brakeForce;
    public float forwardForce;
    public float backwardForce;
    public float maxSpeedForward;
    public float maxSpeedBackward;
    public float steerFadeSpeed;
    public float turnStep;
    public float maxTurn;
    public float trackSize;
    public Transmission transmission;

    @Override
    public AbstractVehicle fromCustom(Level level) {
        return new WheeledVehicle(VehicleContent.WHEELED_VEHICLE.get(), level);
    }

    public void build(WheeledVehicleDataPojo pojo) {
        super.build(pojo);
        this.brakeForce = pojo.attributes.brakeForce;
        this.forwardForce = pojo.attributes.forwardForce;
        this.backwardForce = pojo.attributes.backwardForce;
        this.maxSpeedForward = pojo.attributes.maxSpeedForward;
        this.maxSpeedBackward = pojo.attributes.maxSpeedBackward;
        this.steerFadeSpeed = Float.isNaN(pojo.attributes.steerFadeSpeed) ? maxSpeedForward : pojo.attributes.steerFadeSpeed;
        this.turnStep = pojo.attributes.turnStep;
        this.maxTurn = pojo.attributes.maxTurn;
        this.trackSize = pojo.attributes.trackSize;
        this.transmission = pojo.attributes.transmission;
        if (transmission != null) {
            transmission.validate();
        }
    }

    public void inject(WheeledVehicle vehicle) {
        vehicle.brakeForce = this.brakeForce;
        vehicle.forwardForce = this.forwardForce;
        vehicle.backwardForce = this.backwardForce;
        vehicle.maxSpeedForward = this.maxSpeedForward;
        vehicle.maxSpeedBackward = this.maxSpeedBackward;
        vehicle.steerFadeSpeed = this.steerFadeSpeed;
        vehicle.turnStep = this.turnStep;
        vehicle.maxTurn = this.maxTurn;
        vehicle.trackSize = this.trackSize;
        vehicle.transmission = this.transmission;
    }

}
