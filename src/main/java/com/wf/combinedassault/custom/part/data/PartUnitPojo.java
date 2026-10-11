package com.wf.combinedassault.custom.part.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.vehicle.pojo.DefenseStats;

import java.util.ArrayList;
import java.util.List;
import com.wf.combinedassault.vehicle.crew.CrewRole;

public class PartUnitPojo {

    @SerializedName("id")
    public String id;

    @SerializedName("name")
    public String name = "part.combinedassault.default_name";

    @SerializedName("max_health")
    public float maxHealth = -1;

    @SerializedName("defense_stats")
    public DefenseStats defenseStats = new DefenseStats();

    @SerializedName("component")
    public String component = null;


    @SerializedName("render_bone")
    public String renderBone = null;


    @SerializedName("detachable")
    public boolean detachable = true;

    @SerializedName("is_seat")
    public boolean isSeat = true;

    @SerializedName("seat_rot")
    public float seatRot;

    @SerializedName("seat_offset")
    public Vec3 seatOffset = Vec3.ZERO;

    @SerializedName("dismount_offset")
    public Vec3 dismountOffset = null;

    @SerializedName("pose")
    public String pose = null;

    @SerializedName("protect_passenger")
    public Boolean protectPassenger = null;

    @SerializedName("passenger_can_use_item")
    public boolean passengerCanUseItem = false;

    @SerializedName("owner_view_offset")
    public Vec3 ownerViewOffset = null;

    @SerializedName("crew_roles")
    public List<CrewRole> crewRoles = null;

    @SerializedName("display_id")
    public ResourceLocation displayId;

    @SerializedName("display_offset")
    public Vec3 displayOffset;

    @SerializedName("mounts")
    public List<PartMount> mounts = List.of();

    @SerializedName("sub_part_unit_ids")
    public List<String> subPartUnitIds = new ArrayList<>();

}
