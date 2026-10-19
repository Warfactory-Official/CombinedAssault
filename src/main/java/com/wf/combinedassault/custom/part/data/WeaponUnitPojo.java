package com.wf.combinedassault.custom.part.data;

import com.wf.combinedassault.vehicle.crew.WeaponLoading;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.vehicle.pojo.Bolt;
import com.wf.combinedassault.vehicle.pojo.Eject;
import com.wf.combinedassault.vehicle.pojo.WeaponInfo;

import java.util.List;

public class WeaponUnitPojo extends RotatableUnitPojo {

    @SerializedName("bolts")
    public List<Bolt> bolts = null;

    @SerializedName("ejects")
    public List<Eject> ejects = List.of();

    @SerializedName("ammo_capacity")
    public int ammoCapacity = -1;

    @SerializedName("firing_mode")
    public WeaponUnitData.FiringMode firingMode = WeaponUnitData.FiringMode.RIPPLE;

    @SerializedName("cold_launch_time_tick")
    public int coldLaunchTimeTick = 0;

    @SerializedName("cold_launch_velocity")
    public Vec3 coldLaunchVelocity = new Vec3(0, -1, 0);

    @SerializedName("parent_weapon_unit_aim")
    public boolean parentWeaponUnitAim = false;

    @SerializedName("optical_sight_offset")
    public Vec3 opticalSightOffset = Vec3.ZERO;

    @SerializedName("operator_view_offset")
    public Vec3 operatorViewOffset = Vec3.ZERO;

    @SerializedName("operator_on_weapon_unit")
    public boolean operatorOnWeaponUnit = true;

    @SerializedName("fire_control_sensor_type")
    public WeaponUnitData.FireControlSensorType fireControlSensorType = WeaponUnitData.FireControlSensorType.NONE;

    @SerializedName("optical_sight_type")
    public WeaponUnitData.OpticalSightType opticalSightType;

    @SerializedName("with_stabilizer")
    public boolean withStabilizer = false;

    @SerializedName("with_focus_locker")
    public boolean withFocusLocker = false;

    @SerializedName("with_thermal_imager")
    public boolean withThermalImager = false;

    @SerializedName("with_night_vision")
    public boolean withNightVision = false;

    @SerializedName("with_ir")
    public boolean withIr = false;

    @SerializedName("loading")
    public WeaponLoading loading = WeaponLoading.SELF;

    @SerializedName("zoom_min")
    public float zoomMin = 1;

    @SerializedName("zoom_max")
    public float zoomMax = 8;

    @SerializedName("crosshair_style")
    public WeaponUnitData.CrosshairStyle crosshairStyle = WeaponUnitData.CrosshairStyle.CIRCLE;

    @SerializedName("handling")
    public WeaponUnitData.Handling handling = WeaponUnitData.Handling.REMOTE;

    /** Absent => no picture-in-picture sight. */
    @SerializedName("optic")
    public OpticSpec optic = null;

    /** Absent => direct fire only. */
    @SerializedName("indirect_fire")
    public IndirectFirePojo indirectFire = null;

    /** Absent => no automatic interception, no interceptor lock pick. */
    @SerializedName("air_defense")
    public AirDefensePojo airDefense = null;

    @SerializedName("weapons")
    public List<WeaponInfo> weapons = List.of();

    /** Kits fitted here ({@code HardwareKits.expand} filled {@code weapons} / {@code mounts} from them). */
    @SerializedName("hardpoint")
    public JsonObject hardpoint = null;

}
