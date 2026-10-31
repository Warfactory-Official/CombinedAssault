package com.wf.combinedassault.uav.fpv.payload;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.vehicle.part.data.PartUnitPojo;

/** Payload block kept raw (not typed fields): lets a new ordnance kind ship without editing this class. */
public class PayloadUnitPojo extends PartUnitPojo {

    /** e.g. {@code {"type":"combinedassault_fpv:warhead",...}}. Absent = legitimate empty hardpoint. */
    @SerializedName("payload")
    public JsonObject payload = null;

    /** Relative to the airframe; blast origin + effect position. */
    @SerializedName("attach_offset")
    public Vec3 attachOffset = Vec3.ZERO;
}
