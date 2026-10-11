package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;

public class PassengerViewRot {

    @SerializedName("rot_by_vehicle_in_third_person")
    public boolean rotByVehicleInThirdPerson = false;

    @SerializedName("rot_by_vehicle_in_operator")
    public boolean rotByVehicleInOperator = true;

    public PassengerViewRot() {}

    public PassengerViewRot(PassengerViewRot passengerViewRot) {
        if (passengerViewRot == null) {
            return;
        }
        this.rotByVehicleInThirdPerson = passengerViewRot.rotByVehicleInThirdPerson;
        this.rotByVehicleInOperator = passengerViewRot.rotByVehicleInOperator;
    }

}
