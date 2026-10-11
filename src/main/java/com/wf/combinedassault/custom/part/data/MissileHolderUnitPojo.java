package com.wf.combinedassault.custom.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.pojo.MissileSize;

import java.util.List;

public class MissileHolderUnitPojo extends PartUnitPojo {

    /** Null/empty: all four size classes (generic holder, not broken). */
    @SerializedName("accepts")
    public List<MissileSize> accepts = null;

    @SerializedName("capacity")
    public int capacity = 1;

}
