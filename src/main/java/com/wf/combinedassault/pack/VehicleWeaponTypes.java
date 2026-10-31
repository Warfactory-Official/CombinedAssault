package com.wf.combinedassault.pack;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.pack.weapon.VehicleWeaponType;
import com.wf.combinedassault.weapon.data.*;
import com.wf.combinedassault.weapon.*;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.weapon.VehicleAerialBomb;
import com.wf.combinedassault.weapon.VehicleCannon;
import com.wf.combinedassault.weapon.VehicleDecoyFlare;
import com.wf.combinedassault.weapon.VehicleGrenade;
import com.wf.combinedassault.weapon.VehicleMissile;
import com.wf.combinedassault.weapon.VehicleRocket;
import com.wf.combinedassault.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.weapon.data.VehicleAerialBombWeaponData;
import com.wf.combinedassault.weapon.data.VehicleCannonWeaponData;
import com.wf.combinedassault.weapon.data.VehicleGrenadeWeaponData;
import com.wf.combinedassault.weapon.data.VehicleMissileWeaponData;
import com.wf.combinedassault.weapon.data.VehicleRocketWeaponData;

public class VehicleWeaponTypes {

    public static void registerTypes() {
        register(
                "cannon", json -> GsonUtil.GSON.fromJson(json, VehicleCannonWeaponData.class), VehicleCannon::new
        );

        register(
                "grenade", json -> GsonUtil.GSON.fromJson(json, VehicleGrenadeWeaponData.class), VehicleGrenade::new
        );

        register(
                "rocket", json -> GsonUtil.GSON.fromJson(json, VehicleRocketWeaponData.class), VehicleRocket::new
        );

        register(
                "aerial_bomb", json -> GsonUtil.GSON.fromJson(json, VehicleAerialBombWeaponData.class), VehicleAerialBomb::new
        );

        register(
                "missile", json -> GsonUtil.GSON.fromJson(json, VehicleMissileWeaponData.class), VehicleMissile::new
        );

        register(
                "laser", json -> GsonUtil.GSON.fromJson(json, com.wf.combinedassault.weapon.data.VehicleLaserWeaponData.class),
                com.wf.combinedassault.weapon.VehicleLaser::new
        );

        register(
                "decoy_flare", json -> GsonUtil.GSON.fromJson(json, BaseVehicleWeaponData.class), VehicleDecoyFlare::new
        );
    }

    private static <T extends AbstractVehicleWeapon<D>, D extends BaseVehicleWeaponData> void register(
            String name,
            VehicleWeaponType.DataSerializer<D> dataSerializer,
            VehicleWeaponType.WeaponUnitFactory<T, D> factory
    ) {
        VehicleWeaponType<T, D> type = VehicleWeaponType.Builder.<T, D>of(CombinedAssault.modLocation(name))
                .setDataSerializer(dataSerializer)
                .setFactory(factory)
                .build();
        PackTypes.VEHICLE_WEAPONS.register(type.id(), type);
    }

}
