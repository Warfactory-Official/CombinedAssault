package com.wf.combinedassault.vehicle.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.pack.part.PartUnitEntry;
import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.combinedassault.ballistics.addon.AddonMountInfo;
import com.wf.combinedassault.vehicle.pojo.AutonomyInfo;
import com.wf.combinedassault.weapon.pojo.LoadoutInfo;
import com.wf.combinedassault.ballistics.pojo.DefenseStats;
import com.wf.combinedassault.energy.pojo.EnergyInfo;
import com.wf.combinedassault.dynamics.pojo.PhysicsInfo;
import com.wf.combinedassault.vehicle.screen.ScreenInfo;
import com.wf.combinedassault.tow.TowInfo;
import com.wf.combinedassault.deck.pojo.MountPointInfo;
import com.wf.combinedassault.cargo.pojo.CargoInfo;
import com.wf.combinedassault.vehicle.pojo.MountableInfo;
import com.wf.combinedassault.cargo.pojo.StorageInfo;
import com.wf.combinedassault.vehicle.pojo.ViewInfo;

import java.util.List;
import java.util.Map;

public class BaseVehicleDataPojo {

    @SerializedName("max_health")
    public float maxHealth = 100f;

    @SerializedName("view_info")
    public ViewInfo viewInfo = new ViewInfo();

    @SerializedName("energy_info")
    public EnergyInfo energyInfo = new EnergyInfo();

    @SerializedName("physics_info")
    public PhysicsInfo physicsInfo = new PhysicsInfo();

    @SerializedName("defense_stats")
    public DefenseStats defenseStats = new DefenseStats();

    @SerializedName("armor")
    public HullProfile armor = new HullProfile();

    @SerializedName("storage")
    public StorageInfo storage = new StorageInfo();

    /** ERA and slat mount points. */
    @SerializedName("addon_armor")
    public List<AddonMountInfo> addonArmor = List.of();

    /** Places on this vehicle where other vehicles can be bolted down. */
    @SerializedName("mount_points")
    public List<MountPointInfo> mountPoints = List.of();

    /** Whether this vehicle can itself be bolted onto someone else's mount point. */
    @SerializedName("mountable")
    public MountableInfo mountable = new MountableInfo();

    /** What this vehicle can tow and what can tow it. */
    @SerializedName("towing")
    public TowInfo towing = new TowInfo();

    /** Model screen id => binding ({@link ScreenInfo}). */
    @SerializedName("screens")
    public Map<String, ScreenInfo> screens = Map.of();

    @SerializedName("cargo")
    public CargoInfo cargo = CargoInfo.NONE;

    @SerializedName("uav")
    public boolean uav = false;

    @SerializedName("pickup")
    public boolean pickup = false;

    @SerializedName("autonomy")
    public AutonomyInfo autonomy;

    @SerializedName("loadouts")
    public List<LoadoutInfo> loadouts = List.of();

    @SerializedName("can_wade")
    public boolean canWade = false;

    @SerializedName("with_warning_receiver")
    public boolean withWarningReceiver = false;

    @SerializedName("protect_passenger")
    public boolean protectPassenger = true;

    @SerializedName("experimental")
    public boolean experimental = false;

    @SerializedName("center_offset")
    public Vec3 centerOffset = Vec3.ZERO;

    @SerializedName("structure_model")
    public ResourceLocation structureModel = null;

    @SerializedName("parts")
    public List<PartUnitEntry<?, ?>> parts = List.of();

}
