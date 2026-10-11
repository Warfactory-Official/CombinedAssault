package com.wf.combinedassault.custom.vehicle;

import net.minecraft.world.level.Level;
import com.wf.combinedassault.all.AllEntities;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.WheeledVehicle;

public class WheeledVehicleData extends BaseVehicleData<WheeledVehicle> {

    public float brakeForce;
    public float forwardForce;
    public float backwardForce;
    public float maxSpeedForward;
    public float maxSpeedBackward;
    public float turnStep;
    public float maxTurn;
    public float trackSize;

    @Override
    public AbstractVehicle fromCustom(Level level) {
        return new WheeledVehicle(AllEntities.WHEELED_VEHICLE.get(), level);
    }

    public void build(WheeledVehicleDataPojo pojo) {
        super.build(pojo);
        this.brakeForce = pojo.attributes.brakeForce;
        this.forwardForce = pojo.attributes.forwardForce;
        this.backwardForce = pojo.attributes.backwardForce;
        this.maxSpeedForward = pojo.attributes.maxSpeedForward;
        this.maxSpeedBackward = pojo.attributes.maxSpeedBackward;
        this.turnStep = pojo.attributes.turnStep;
        this.maxTurn = pojo.attributes.maxTurn;
        this.trackSize = pojo.attributes.trackSize;
    }

    public void inject(WheeledVehicle vehicle) {
        vehicle.brakeForce = this.brakeForce;
        vehicle.forwardForce = this.forwardForce;
        vehicle.backwardForce = this.backwardForce;
        vehicle.maxSpeedForward = this.maxSpeedForward;
        vehicle.maxSpeedBackward = this.maxSpeedBackward;
        vehicle.turnStep = this.turnStep;
        vehicle.maxTurn = this.maxTurn;
        vehicle.trackSize = this.trackSize;
    }

}
