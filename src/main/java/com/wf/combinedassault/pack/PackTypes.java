package com.wf.combinedassault.pack;

import com.wf.combinedassault.pack.client.vehicle.VehicleDisplayType;
import com.wf.combinedassault.pack.part.PartUnitType;
import com.wf.combinedassault.vehicle.data.VehicleDataType;
import com.wf.combinedassault.pack.weapon.VehicleWeaponType;
import com.wf.wflib.core.api.WFRegistry;

public final class PackTypes {

    public static final WFRegistry<PartUnitType<?, ?>> PART_UNITS = WFRegistry.create("combinedassault:part_unit_type");
    public static final WFRegistry<VehicleDataType<?>> VEHICLE_DATA = WFRegistry.create("combinedassault:vehicle_data_type");
    public static final WFRegistry<VehicleWeaponType<?, ?>> VEHICLE_WEAPONS = WFRegistry.create("combinedassault:vehicle_weapon_type");
    public static final WFRegistry<VehicleDisplayType<?>> VEHICLE_DISPLAYS = WFRegistry.create("combinedassault:vehicle_display_type");

    private PackTypes() {
    }
}
