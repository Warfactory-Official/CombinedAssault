package com.wf.combinedassault.custom.part;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.part.data.*;
import com.wf.combinedassault.custom.serialize.GsonUtil;
import com.wf.combinedassault.fpv.payload.FpvPayloadUnitType;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.fpv.payload.PayloadUnitData;
import com.wf.combinedassault.vehicle.part.*;

public class PartUnitTypes {

    public static final PartUnitType<PartUnit<PartUnitData>, PartUnitData> GENERIC =
            PartUnitType.Builder.of(CombinedAssault.modLocation("generic"))
                    .setFactory(PartUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, PartUnitPojo.class);
                        return new PartUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<SwitchableUnit<PartUnitData>, PartUnitData> SWITCHABLE =
            PartUnitType.Builder.<SwitchableUnit<PartUnitData>, PartUnitData>of(CombinedAssault.modLocation("switchable"))
                    .setFactory(SwitchableUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, PartUnitPojo.class);
                        return new PartUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<RotatableUnit<RotatableUnitData>, RotatableUnitData> ROTATABLE =
            PartUnitType.Builder.<RotatableUnit<RotatableUnitData>, RotatableUnitData>of(CombinedAssault.modLocation("rotatable"))
                    .setFactory(RotatableUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, RotatableUnitPojo.class);
                        return new RotatableUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<DoorUnit, DoorUnitData> DOOR =
            PartUnitType.Builder.<DoorUnit, DoorUnitData>of(CombinedAssault.modLocation("door"))
                    .setFactory(DoorUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, DoorUnitPojo.class);
                        return new DoorUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<LandingGearUnit, LandingGearUnitData> LANDING_GEAR =
            PartUnitType.Builder.<LandingGearUnit, LandingGearUnitData>of(CombinedAssault.modLocation("landing_gear"))
                    .setFactory(LandingGearUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, LandingGearUnitPojo.class);
                        return new LandingGearUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<AirbrakeUnit, AirbrakeUnitData> AIRBRAKE =
            PartUnitType.Builder.<AirbrakeUnit, AirbrakeUnitData>of(CombinedAssault.modLocation("airbrake"))
                    .setFactory(AirbrakeUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, AirbrakeUnitPojo.class);
                        return new AirbrakeUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<WeaponBayUnit, PartUnitData> WEAPON_BAY =
            PartUnitType.Builder.<WeaponBayUnit, PartUnitData>of(CombinedAssault.modLocation("weapon_bay"))
                    .setFactory(WeaponBayUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, PartUnitPojo.class);
                        return new PartUnitData(pojo);
                    })
                    .build();

    /** Drone ordnance hardpoint; implementation lives in com.wf.combinedassault.fpv.payload. */
    public static final PartUnitType<PayloadUnit, PayloadUnitData> PAYLOAD = FpvPayloadUnitType.PAYLOAD;

    public static final PartUnitType<MissileHolderUnit, MissileHolderUnitData> MISSILE_HOLDER =
            PartUnitType.Builder.<MissileHolderUnit, MissileHolderUnitData>of(CombinedAssault.modLocation("missile_holder"))
                    .setFactory(MissileHolderUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, MissileHolderUnitPojo.class);
                        return new MissileHolderUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<WeaponUnit, WeaponUnitData> WEAPON =
            PartUnitType.Builder.<WeaponUnit, WeaponUnitData>of(CombinedAssault.modLocation("weapon"))
                    .setFactory(WeaponUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, WeaponUnitPojo.class);
                        return new WeaponUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<AutoWeaponUnit, WeaponUnitData> AUTO_WEAPON =
            PartUnitType.Builder.<AutoWeaponUnit, WeaponUnitData>of(CombinedAssault.modLocation("auto_weapon"))
                    .setFactory(AutoWeaponUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, WeaponUnitPojo.class);
                        return new WeaponUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<RadarUnit, RadarUnitData> RADAR =
            PartUnitType.Builder.<RadarUnit, RadarUnitData>of(CombinedAssault.modLocation("radar"))
                    .setFactory(RadarUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, RadarUnitPojo.class);
                        return new RadarUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<ThrustUnit, RotatableUnitData> THRUST =
            PartUnitType.Builder.<ThrustUnit, RotatableUnitData>of(CombinedAssault.modLocation("thrust"))
                    .setFactory(ThrustUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, RotatableUnitPojo.class);
                        return new RotatableUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<VariableSweepUnit, VariableSweepUnitData> VARIABLE_SWEEP =
            PartUnitType.Builder.<VariableSweepUnit, VariableSweepUnitData>of(CombinedAssault.modLocation("variable_sweep"))
                    .setFactory(VariableSweepUnit::new)
                    .setDataSerializer(json -> new VariableSweepUnitData(GsonUtil.GSON.fromJson(json, VariableSweepUnitPojo.class)))
                    .build();

    public static final PartUnitType<FlapUnit, FlapUnitData> FLAPS =
            PartUnitType.Builder.<FlapUnit, FlapUnitData>of(CombinedAssault.modLocation("flaps"))
                    .setFactory(FlapUnit::new)
                    .setDataSerializer(json -> new FlapUnitData(GsonUtil.GSON.fromJson(json, FlapUnitPojo.class)))
                    .build();

    public static final PartUnitType<LinkedHingeUnit, LinkedHingeUnitData> LINKED_HINGE =
            PartUnitType.Builder.<LinkedHingeUnit, LinkedHingeUnitData>of(CombinedAssault.modLocation("linked_hinge"))
                    .setFactory(LinkedHingeUnit::new)
                    .setDataSerializer(json -> new LinkedHingeUnitData(GsonUtil.GSON.fromJson(json, LinkedHingeUnitPojo.class)))
                    .build();

    public static final PartUnitType<RopeUnit, RopeUnitData> ROPE =
            PartUnitType.Builder.<RopeUnit, RopeUnitData>of(CombinedAssault.modLocation("rope"))
                    .setFactory(RopeUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, RopeUnitPojo.class);
                        return new RopeUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<SuspensionUnit<SuspensionUnitData>, SuspensionUnitData> SUSPENSION =
            PartUnitType.Builder.<SuspensionUnit<SuspensionUnitData>, SuspensionUnitData>of(CombinedAssault.modLocation("suspension"))
                    .setFactory(SuspensionUnit::new)
                    .setDataSerializer(json -> new SuspensionUnitData(GsonUtil.GSON.fromJson(json, SuspensionUnitPojo.class)))
                    .build();

    public static final PartUnitType<TrackUnit, TrackUnitData> TRACK =
            PartUnitType.Builder.<TrackUnit, TrackUnitData>of(CombinedAssault.modLocation("track"))
                    .setFactory(TrackUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, TrackUnitPojo.class);
                        return new TrackUnitData(pojo);
                    })
                    .build();

}
