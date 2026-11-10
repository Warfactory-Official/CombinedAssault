package com.wf.combinedassault.aircraft;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.VehicleContent;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class AircraftContent {

    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, CombinedAssault.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<RotaryWingVehicle>> ROTARY_WING_VEHICLE =
            VehicleContent.registerVehicle(ENTITIES, "rotary_wing_vehicle", RotaryWingVehicle::new, false);

    public static final DeferredHolder<EntityType<?>, EntityType<FixedWingVehicle>> FIXED_WING_VEHICLE =
            VehicleContent.registerVehicle(ENTITIES, "fixed_wing_vehicle", FixedWingVehicle::new, false);

    public static final DeferredHolder<EntityType<?>, EntityType<Ah64d>> AH64D =
            VehicleContent.registerVehicle(ENTITIES, "ah64d", Ah64d::new, true);

    public static final DeferredHolder<EntityType<?>, EntityType<RotorDebris>> ROTOR_DEBRIS = ENTITIES.register("rotor_debris",
            () -> EntityType.Builder.<RotorDebris>of(RotorDebris::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .sized(1f, 0.5f)
                    .updateInterval(1)
                    .clientTrackingRange(16)
                    .build("rotor_debris"));

    private AircraftContent() {
    }
}
