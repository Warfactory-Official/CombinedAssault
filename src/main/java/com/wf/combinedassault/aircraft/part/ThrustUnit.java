package com.wf.combinedassault.aircraft.part;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.RotatableUnit;
import com.wf.combinedassault.vehicle.part.data.RotatableUnitData;
import net.minecraft.network.FriendlyByteBuf;

public class ThrustUnit extends RotatableUnit<RotatableUnitData> {

    public ThrustUnit(int index, AbstractVehicle vehicle, RotatableUnitData data) {
        super(index, vehicle, data);
    }

    /** Predicted vehicle: {@link #predictRot} turns the nozzle inside the predicted step. */
    @Override
    protected void tickRot() {
        if (!vehicle.predicted()) {
            super.tickRot();
        }
    }

    public void predictRot() {
        super.tickRot();
    }

    public void writePrediction(FriendlyByteBuf buf) {
        buf.writeFloat(xAimRot);
        buf.writeFloat(yAimRot);
        buf.writeFloat(xRot);
        buf.writeFloat(yRot);
        buf.writeFloat(xRotO);
        buf.writeFloat(yRotO);
    }

    public void readPrediction(FriendlyByteBuf buf) {
        xAimRot = buf.readFloat();
        yAimRot = buf.readFloat();
        xRot = buf.readFloat();
        yRot = buf.readFloat();
        xRotO = buf.readFloat();
        yRotO = buf.readFloat();
        poseRot();
    }

}
