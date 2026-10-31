package com.wf.combinedassault.pack.part;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.vehicle.part.*;
import com.wf.combinedassault.weapon.part.AutoWeaponUnit;
import com.wf.combinedassault.weapon.part.MissileHolderUnit;
import com.wf.combinedassault.weapon.part.WeaponBayUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.part.data.MissileHolderUnitData;
import com.wf.combinedassault.weapon.part.data.MissileHolderUnitPojo;
import com.wf.combinedassault.weapon.part.data.WeaponBayUnitData;
import com.wf.combinedassault.weapon.part.data.WeaponBayUnitPojo;
import com.wf.combinedassault.weapon.part.data.WeaponUnitData;
import com.wf.combinedassault.weapon.part.data.WeaponUnitPojo;
import com.wf.combinedassault.dynamics.part.LandingGearUnit;
import com.wf.combinedassault.dynamics.part.SuspensionUnit;
import com.wf.combinedassault.dynamics.part.TrackUnit;
import com.wf.combinedassault.dynamics.part.data.LandingGearUnitData;
import com.wf.combinedassault.dynamics.part.data.LandingGearUnitPojo;
import com.wf.combinedassault.dynamics.part.data.SuspensionUnitData;
import com.wf.combinedassault.dynamics.part.data.SuspensionUnitPojo;
import com.wf.combinedassault.dynamics.part.data.TrackUnitData;
import com.wf.combinedassault.dynamics.part.data.TrackUnitPojo;
import com.wf.combinedassault.vehicle.part.data.DoorUnitData;
import com.wf.combinedassault.vehicle.part.data.DoorUnitPojo;
import com.wf.combinedassault.vehicle.part.data.LinkedHingeUnitData;
import com.wf.combinedassault.vehicle.part.data.LinkedHingeUnitPojo;
import com.wf.combinedassault.vehicle.part.data.PartUnitData;
import com.wf.combinedassault.vehicle.part.data.PartUnitPojo;
import com.wf.combinedassault.vehicle.part.data.RopeUnitData;
import com.wf.combinedassault.vehicle.part.data.RopeUnitPojo;
import com.wf.combinedassault.vehicle.part.data.RotatableUnitData;
import com.wf.combinedassault.vehicle.part.data.RotatableUnitPojo;

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

    public static final PartUnitType<WeaponBayUnit, WeaponBayUnitData> WEAPON_BAY =
            PartUnitType.Builder.<WeaponBayUnit, WeaponBayUnitData>of(CombinedAssault.modLocation("weapon_bay"))
                    .setFactory(WeaponBayUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, WeaponBayUnitPojo.class);
                        return new WeaponBayUnitData(pojo);
                    })
                    .build();

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
