package com.wf.combinedassault.custom.part.data;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.vehicle.pojo.DefenseStats;
import com.wf.combinedassault.vehicle.pose.SeatPoses;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.List;
import com.wf.combinedassault.vehicle.crew.CrewRole;
import java.util.Map;

public class PartUnitData {

    protected final String id;
    protected String name;
    protected float maxHealth;
    protected DefenseStats defenseStats = new DefenseStats();
    protected String component;
    protected String renderBone;
    protected boolean detachable;
    protected boolean isSeat;
    protected float seatRot;
    protected Vec3 seatOffset = Vec3.ZERO;
    protected Vec3 dismountOffset;
    protected String pose;
    protected Boolean protectPassenger;
    protected boolean passengerCanUseItem;
    protected Vec3 ownerViewOffset = null;
    protected String sensor;
    protected List<CrewRole> crewRoles;
    protected String needsOn;
    protected Vec3 pivotOffset = Vec3.ZERO;
    protected ResourceLocation displayId;
    protected Vec3 displayOffset;
    protected List<PartMount> mounts;
    protected List<String> subPartUnitIds;
    protected VehicleCubeGroup structureGroup;
    protected List<VehicleCubeOBB> partCubeOBBs;

    protected PartUnitData(String id) {
        this.id = id;
    }

    public PartUnitData(PartUnitPojo pojo) {
        this.id = pojo.id;
        this.name = pojo.name;
        this.maxHealth = pojo.maxHealth;
        this.defenseStats = pojo.defenseStats;
        this.component = pojo.component;
        this.renderBone = pojo.renderBone;
        this.detachable = pojo.detachable;
        this.isSeat = pojo.isSeat;
        this.seatRot = pojo.seatRot;
        this.seatOffset = pojo.seatOffset;
        this.dismountOffset = pojo.dismountOffset;
        if (pojo.pose != null && !SeatPoses.SETS.containsKey(pojo.pose)) {
            throw new IllegalArgumentException("part " + pojo.id + ": pose " + pojo.pose + " not in " + SeatPoses.SETS.keySet());
        }
        this.pose = pojo.pose;
        this.sensor = pojo.sensor;
        this.protectPassenger = pojo.protectPassenger;
        this.passengerCanUseItem = pojo.passengerCanUseItem;
        this.ownerViewOffset = pojo.ownerViewOffset;
        this.crewRoles = pojo.crewRoles;
        this.needsOn = pojo.needsOn;
        this.displayId = pojo.displayId;
        this.displayOffset = pojo.displayOffset;
        this.mounts = pojo.mounts;
        this.subPartUnitIds = pojo.subPartUnitIds;
        this.initData(pojo);
    }

    public void initData(PartUnitPojo pojo) {
        if (pojo.seatOffset != null) {
            this.seatOffset = new Vec3(pojo.seatOffset.x, pojo.seatOffset.y, pojo.seatOffset.z);
        }
        if (pojo.ownerViewOffset != null) {
            this.ownerViewOffset = new Vec3(pojo.ownerViewOffset.x, pojo.ownerViewOffset.y, pojo.ownerViewOffset.z);
        }
    }

    /** Rig frames of this part ({@code part} / {@code pitch} role nodes); either may be null. */
    public void initFrames(@org.jetbrains.annotations.Nullable VehicleCubeGroup part,
                           @org.jetbrains.annotations.Nullable VehicleCubeGroup pitch) {
        structureGroup = part;
        partCubeOBBs = part == null ? null : part.cubeOBBs;
        pivotOffset = part == null ? Vec3.ZERO : part.globalTransform().offset();
    }

    public List<VehicleCubeOBB> getRawPartCubeOBBs() {
        if (partCubeOBBs == null) {
            return List.of();
        }
        return partCubeOBBs;
    }

    public VehicleCubeGroup getRawStructureGroup() {
        return structureGroup;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public float getMaxHealth() {
        return maxHealth;
    }

    public void setMaxHealth(float maxHealth) {
        this.maxHealth = maxHealth;
    }

    /** Which vehicle system this part is, or null to let the part type speak for itself. */
    public String getComponent() {
        return component;
    }


    public DefenseStats getDefenseStats() {
        return defenseStats;
    }

    public String getRenderBone() {
        return renderBone;
    }

    public void setRenderBone(String renderBone) {
        this.renderBone = renderBone;
    }

    public boolean isDetachable() {
        return detachable;
    }

    public boolean isSeat() {
        return isSeat;
    }

    public float getSeatRot() {
        return seatRot;
    }

    public Vec3 getSeatOffset() {
        return seatOffset;
    }

    public Vec3 getDismountOffset() {
        return dismountOffset;
    }

    @Nullable
    public String getSensor() {
        return sensor;
    }

    @Nullable
    public String getPose() {
        return pose;
    }

    /** Null => vehicle {@code protect_passenger}. */
    @Nullable
    public Boolean getProtectPassenger() {
        return protectPassenger;
    }

    public boolean passengerCanUseItem() {
        return passengerCanUseItem;
    }

    @Nullable
    public String getNeedsOn() {
        return needsOn;
    }

    public List<CrewRole> getCrewRoles() {
        return crewRoles;
    }

    public Vec3 getOwnerViewOffset() {
        return ownerViewOffset;
    }

    public Vec3 getPivotOffset() {
        return pivotOffset;
    }

    public ResourceLocation getDisplayId() {
        return displayId;
    }

    public Vec3 getDisplayOffset() {
        return displayOffset;
    }

    public List<PartMount> getMounts() {
        return mounts;
    }

    public List<String> getSubPartUnitIds() {
        return subPartUnitIds;
    }

}
