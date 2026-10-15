package com.wf.combinedassault.all;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.misc.ParagliderCanopy;
import com.wf.combinedassault.entity.misc.RadarMarkerEntity;
import com.wf.combinedassault.entity.misc.VehiclePart;
import com.wf.combinedassault.entity.vehicle.*;
import com.wf.combinedassault.entity.vehicle.custom.*;

public class AllEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE, CombinedAssault.MOD_ID);

    /** Capabilities register per entity type; this says which types are vehicles. */
    private static final List<DeferredHolder<EntityType<?>, ? extends EntityType<? extends AbstractVehicle>>> VEHICLE_TYPES = new ArrayList<>();

    public static final DeferredHolder<EntityType<?>, EntityType<ParagliderCanopy>> PARAGLIDER_CANOPY = ENTITIES.register("paraglider_canopy",
            () -> EntityType.Builder.of(ParagliderCanopy::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .sized(1F, 1F)
                    .clientTrackingRange(32)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(true)
                    .build("paraglider_canopy"));

    public static final DeferredHolder<EntityType<?>, EntityType<VehiclePart>> VEHICLE_PART = ENTITIES.register("vehicle_part",
            () -> EntityType.Builder.of(VehiclePart::new, MobCategory.MISC)
                    .noSummon()
                    .sized(1f, 1f)
                    .updateInterval(1)
                    .clientTrackingRange(32)
                    .build("vehicle_part"));

    public static final DeferredHolder<EntityType<?>, EntityType<NoneVehicle>> NONE_VEHICLE = ENTITIES.register("none_vehicle",
            () -> EntityType.Builder.of(NoneVehicle::new, MobCategory.MISC)
                    .noSummon()
                    .sized(1f, 1f)
                    .updateInterval(1)
                    .clientTrackingRange(32)
                    .build("none_vehicle"));

    public static final DeferredHolder<EntityType<?>, EntityType<WheeledVehicle>> WHEELED_VEHICLE = ENTITIES.register("wheeled_vehicle",
            () -> EntityType.Builder.of(WheeledVehicle::new, MobCategory.MISC)
                    .noSummon()
                    .sized(1f, 1f)
                    .updateInterval(1)
                    .clientTrackingRange(32)
                    .build("wheeled_vehicle"));

    public static final DeferredHolder<EntityType<?>, EntityType<TrackedVehicle>> TRACKED_VEHICLE = ENTITIES.register("tracked_vehicle",
            () -> EntityType.Builder.of(TrackedVehicle::new, MobCategory.MISC)
                    .noSummon()
                    .sized(1f, 1f)
                    .updateInterval(1)
                    .clientTrackingRange(32)
                    .build("tracked_vehicle"));

    public static final DeferredHolder<EntityType<?>, EntityType<VesselVehicle>> VESSEL_VEHICLE = ENTITIES.register("vessel_vehicle",
            () -> EntityType.Builder.of(VesselVehicle::new, MobCategory.MISC)
                    .noSummon()
                    .sized(1f, 1f)
                    .updateInterval(1)
                    .clientTrackingRange(32)
                    .build("vessel_vehicle"));

    public static final DeferredHolder<EntityType<?>, EntityType<RotaryWingVehicle>> ROTARY_WING_VEHICLE = ENTITIES.register("rotary_wing_vehicle",
            () -> EntityType.Builder.of(RotaryWingVehicle::new, MobCategory.MISC)
                    .noSummon()
                    .sized(1f, 1f)
                    .updateInterval(1)
                    .clientTrackingRange(32)
                    .build("rotary_wing_vehicle"));

    public static final DeferredHolder<EntityType<?>, EntityType<FixedWingVehicle>> FIXED_WING_VEHICLE = ENTITIES.register("fixed_wing_vehicle",
            () -> EntityType.Builder.of(FixedWingVehicle::new, MobCategory.MISC)
                    .noSummon()
                    .sized(1f, 1f)
                    .updateInterval(1)
                    .clientTrackingRange(32)
                    .build("fixed_wing_vehicle"));

    public static final DeferredHolder<EntityType<?>, EntityType<ShipVehicle>> SHIP = ENTITIES.register("ship",
            () -> EntityType.Builder.of(ShipVehicle::new, MobCategory.MISC)
                    .noSummon()
                    .sized(1f, 1f)
                    .updateInterval(1)
                    .clientTrackingRange(32)
                    .build("ship"));

    public static final DeferredHolder<EntityType<?>, EntityType<M1a2>> M1A2 = registerVehicle("m1a2", M1a2::new);

    public static final DeferredHolder<EntityType<?>, EntityType<Ah64d>> AH64D = registerVehicle("ah64d", Ah64d::new);

    public static final DeferredHolder<EntityType<?>, EntityType<RadarMarkerEntity>> RADAR_MARKER = ENTITIES.register("radar_marker",
            () -> EntityType.Builder.of(RadarMarkerEntity::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .fireImmune()
                    .sized(1F, 1F)
                    .clientTrackingRange(1024)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(true)
                    .build("radar_marker"));

    public static <T extends AbstractVehicle> DeferredHolder<EntityType<?>, EntityType<T>> registerVehicle(
            String name, EntityType.EntityFactory<T> factory
    ) {
        DeferredHolder<EntityType<?>, EntityType<T>> holder = ENTITIES.register(name, () -> EntityType.Builder
                .of(factory, MobCategory.MISC)
                .sized(1f, 1f)
                .updateInterval(1)
                .clientTrackingRange(32)
                .build(name));
        VEHICLE_TYPES.add(holder);
        return holder;
    }

    /** Resolved vehicle types. Only safe once the registry has fired. */
    public static List<EntityType<? extends AbstractVehicle>> vehicleTypes() {
        List<EntityType<? extends AbstractVehicle>> types = new ArrayList<>(VEHICLE_TYPES.size());
        for (DeferredHolder<EntityType<?>, ? extends EntityType<? extends AbstractVehicle>> holder : VEHICLE_TYPES) {
            types.add(holder.get());
        }
        return types;
    }

    static {
        // Built directly above, not via registerVehicle() => must list here manually.
        VEHICLE_TYPES.add(NONE_VEHICLE);
        VEHICLE_TYPES.add(WHEELED_VEHICLE);
        VEHICLE_TYPES.add(TRACKED_VEHICLE);
        VEHICLE_TYPES.add(ROTARY_WING_VEHICLE);
        VEHICLE_TYPES.add(FIXED_WING_VEHICLE);
        VEHICLE_TYPES.add(SHIP);
    }

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }

}
