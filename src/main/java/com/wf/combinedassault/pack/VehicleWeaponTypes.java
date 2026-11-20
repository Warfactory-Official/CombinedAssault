package com.wf.combinedassault.pack;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.pack.weapon.VehicleWeaponType;
import com.wf.combinedassault.weapon.VehicleLaser;
import com.wf.combinedassault.weapon.data.VehicleLaserWeaponData;
import com.wf.combinedassault.weapon.*;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import com.wf.combinedassault.weapon.data.*;

public class VehicleWeaponTypes {

    public static final ResourceLocation CANNON = CombinedAssault.modLocation("cannon");
    public static final ResourceLocation ROCKET = CombinedAssault.modLocation("rocket");
    public static final ResourceLocation GRENADE = CombinedAssault.modLocation("grenade");
    public static final ResourceLocation AERIAL_BOMB = CombinedAssault.modLocation("aerial_bomb");
    public static final ResourceLocation DECOY_FLARE = CombinedAssault.modLocation("decoy_flare");

    public static void registerTypes() {
        register(
                "missile", json -> GsonUtil.GSON.fromJson(json, VehicleMissileWeaponData.class), VehicleMissile::new
        );

        register(
                "laser", json -> GsonUtil.GSON.fromJson(json, VehicleLaserWeaponData.class),
                VehicleLaser::new
        );

        register(
                "spray_pod", json -> GsonUtil.GSON.fromJson(json, VehicleSprayPodWeaponData.class), VehicleSprayPod::new
        );

        for (ResourceLocation type : List.of(CANNON, ROCKET, GRENADE, AERIAL_BOMB, DECOY_FLARE)) {
            VehicleWeaponTypes.<VehicleLoadedWeapon, BaseVehicleWeaponData>register(
                    type.getPath(), json -> GsonUtil.GSON.fromJson(json, BaseVehicleWeaponData.class),
                    (vehicle, unit, index, data, serializeId) ->
                            new VehicleLoadedWeapon(type, vehicle, unit, index, data, serializeId)
            );
        }
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
