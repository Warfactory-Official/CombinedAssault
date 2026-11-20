package com.wf.combinedassault.radar;

import com.wf.combinedassault.vehicle.part.data.RotatableUnitData;
import com.wf.combinedassault.vehicle.pojo.ReconRole;
import net.minecraft.world.phys.Vec3;

public class RadarUnitData extends RotatableUnitData {

    protected String radarType;
    protected float scanSectorAngle;
    protected float maxScanDistance;
    protected boolean antiGround;
    protected boolean uiHide;
    protected ReconRole reconRole = ReconRole.NODE;
    protected Vec3 radarOffset;

    public RadarUnitData(String id) {
        super(id);
    }

    public RadarUnitData(RadarUnitPojo pojo) {
        super(pojo);
        this.radarType = pojo.radarType;
        this.scanSectorAngle = pojo.scanSectorAngle;
        this.maxScanDistance = pojo.maxScanDistance;
        this.antiGround = pojo.antiGround;
        this.uiHide = pojo.uiHide;
        this.reconRole = pojo.reconRole == null ? ReconRole.NODE : pojo.reconRole;
        this.radarOffset = pojo.radarOffset;
    }

    public String getRadarType() {
        return radarType;
    }

    public float getScanSectorAngle() {
        return scanSectorAngle;
    }

    public float getMaxScanDistance() {
        return maxScanDistance;
    }

    public boolean isAntiGround() {
        return antiGround;
    }

    public boolean isUiHide() {
        return uiHide;
    }

    public Vec3 getRadarOffset() {
        return radarOffset;
    }

    public ReconRole getReconRole() {
        return reconRole;
    }

}
