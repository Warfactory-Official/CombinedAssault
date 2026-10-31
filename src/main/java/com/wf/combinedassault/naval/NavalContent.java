package com.wf.combinedassault.naval;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.VehicleContent;

public final class NavalContent {

    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, CombinedAssault.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<VesselVehicle>> VESSEL_VEHICLE =
            VehicleContent.registerVehicle(ENTITIES, "vessel_vehicle", VesselVehicle::new, false);

    public static final DeferredHolder<EntityType<?>, EntityType<ShipVehicle>> SHIP =
            VehicleContent.registerVehicle(ENTITIES, "ship", ShipVehicle::new, false);

    private NavalContent() {
    }
}
