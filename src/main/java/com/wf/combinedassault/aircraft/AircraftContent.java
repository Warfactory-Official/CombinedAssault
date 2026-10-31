package com.wf.combinedassault.aircraft;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.VehicleContent;

public final class AircraftContent {

    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, CombinedAssault.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<RotaryWingVehicle>> ROTARY_WING_VEHICLE =
            VehicleContent.registerVehicle(ENTITIES, "rotary_wing_vehicle", RotaryWingVehicle::new, false);

    public static final DeferredHolder<EntityType<?>, EntityType<FixedWingVehicle>> FIXED_WING_VEHICLE =
            VehicleContent.registerVehicle(ENTITIES, "fixed_wing_vehicle", FixedWingVehicle::new, false);

    public static final DeferredHolder<EntityType<?>, EntityType<Ah64d>> AH64D =
            VehicleContent.registerVehicle(ENTITIES, "ah64d", Ah64d::new, true);

    private AircraftContent() {
    }
}
