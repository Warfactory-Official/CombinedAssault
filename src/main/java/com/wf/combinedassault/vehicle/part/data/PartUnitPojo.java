package com.wf.combinedassault.vehicle.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.ballistics.pojo.DefenseStats;
import com.wf.combinedassault.vehicle.crew.CrewRole;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

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

    /** Seat: periscope camera (vehicle frame); magnification key toggles. */
    @SerializedName("periscope_offset")
    public Vec3 periscopeOffset = null;

    /** Seat: weapon part whose sight this seat may look through and slew (spotter / WSO); fires nothing. */
    @SerializedName("sensor")
    public String sensor = null;

    @SerializedName("crew_roles")
    public List<CrewRole> crewRoles = null;

    /** Id of a switchable part that must be on for this part to work (a gun: trails down). */
    @SerializedName("needs_on")
    public String needsOn = null;

    /** Seat: switchable part that must be open (clip run out) to board, leave for another seat or switch into it. */
    @SerializedName("hatch")
    public String hatch = null;

    /** Seat: its turned-out seat (one with a {@code hatch}); turn-out key moves between them, turning in closes it. */
    @SerializedName("turn_out")
    public String turnOut = null;

    /** Seat: `entry` part that also leads here though the seat is not gated by it (gunner via commander hatch). */
    @SerializedName("entry_via")
    public String entryVia = null;

    /** Frame node a part-less seat rides (its seat socket's frame): seat, view, periscope and look follow it. */
    @SerializedName("ride_frame")
    public String rideFrame = null;

    @SerializedName("display_id")
    public ResourceLocation displayId;

    @SerializedName("display_offset")
    public Vec3 displayOffset;

    @SerializedName("mounts")
    public List<PartMount> mounts = List.of();

    @SerializedName("sub_part_unit_ids")
    public List<String> subPartUnitIds = new ArrayList<>();

}
