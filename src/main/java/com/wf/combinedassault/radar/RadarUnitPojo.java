package com.wf.combinedassault.radar;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.pojo.ReconRole;
import com.wf.combinedassault.vehicle.part.data.RotatableUnitPojo;

public class RadarUnitPojo extends RotatableUnitPojo {

    @SerializedName("radar_type")
    public String radarType = "";

    @SerializedName("scan_sector_angle")
    public float scanSectorAngle = 45;

    @SerializedName("max_scan_distance")
    public float maxScanDistance = 256;

    @SerializedName("anti_ground")
    public boolean antiGround = false;

    @SerializedName("ui_hide")
    public boolean uiHide = false;

    @SerializedName("recon_role")
    public ReconRole reconRole = ReconRole.NODE;

}
