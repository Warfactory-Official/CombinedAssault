package com.wf.combinedassault.vehicle.part.data;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class DoorUnitPojo extends RotatableUnitPojo {

    @SerializedName("door_for_seat_id")
    public String doorForSeatId;

    /** Dismount opens this door when nearest; default true. */
    @SerializedName("opens_on_dismount")
    public Boolean opensOnDismount;

    /** Seats that need this door shut: taken => opening refused; boarding => closed. */
    @SerializedName("closed_with")
    public List<String> closedWith;

}
