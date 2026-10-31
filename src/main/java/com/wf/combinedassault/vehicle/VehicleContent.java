package com.wf.combinedassault.vehicle;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.entity.M1a2;
import com.wf.combinedassault.vehicle.entity.NoneVehicle;
import com.wf.combinedassault.vehicle.entity.TrackedVehicle;
import com.wf.combinedassault.vehicle.entity.VehiclePart;
import com.wf.combinedassault.vehicle.entity.WheeledVehicle;
import com.wf.combinedassault.vehicle.item.ModdingToolItem;
import com.wf.combinedassault.vehicle.item.VehicleSpawnItem;
import com.wf.combinedassault.vehicle.recipe.VehiclePrintingSerializer;

public final class VehicleContent {

    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, CombinedAssault.MOD_ID);
    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, CombinedAssault.MOD_ID);
    static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, CombinedAssault.MOD_ID);

    /** Capabilities register per entity type; this says which types are vehicles. Filled by every system registering one. */
    private static final List<DeferredHolder<EntityType<?>, ? extends EntityType<? extends AbstractVehicle>>> VEHICLE_TYPES = new ArrayList<>();

    public static final DeferredHolder<Item, VehicleSpawnItem> VEHICLE_SPAWN_ITEM =
            ITEMS.register("vehicle_spawn_item", () -> new VehicleSpawnItem(new Item.Properties().durability(1)));
    public static final DeferredHolder<Item, ModdingToolItem> MODDING_TOOL =
            ITEMS.register("modding_tool", () -> new ModdingToolItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<RecipeSerializer<?>, VehiclePrintingSerializer> VEHICLE_PRINTING_SERIALIZER =
            RECIPE_SERIALIZERS.register("vehicle_printing", VehiclePrintingSerializer::new);

    public static final DeferredHolder<EntityType<?>, EntityType<VehiclePart>> VEHICLE_PART = ENTITIES.register("vehicle_part",
            () -> EntityType.Builder.of(VehiclePart::new, MobCategory.MISC)
                    .noSummon()
                    .sized(1f, 1f)
                    .updateInterval(1)
                    .clientTrackingRange(32)
                    .build("vehicle_part"));

    public static final DeferredHolder<EntityType<?>, EntityType<NoneVehicle>> NONE_VEHICLE =
            registerVehicle(ENTITIES, "none_vehicle", NoneVehicle::new, false);

    public static final DeferredHolder<EntityType<?>, EntityType<WheeledVehicle>> WHEELED_VEHICLE =
            registerVehicle(ENTITIES, "wheeled_vehicle", WheeledVehicle::new, false);

    public static final DeferredHolder<EntityType<?>, EntityType<TrackedVehicle>> TRACKED_VEHICLE =
            registerVehicle(ENTITIES, "tracked_vehicle", TrackedVehicle::new, false);

    public static final DeferredHolder<EntityType<?>, EntityType<M1a2>> M1A2 =
            registerVehicle(ENTITIES, "m1a2", M1a2::new, true);

    private VehicleContent() {
    }

    public static <T extends AbstractVehicle> DeferredHolder<EntityType<?>, EntityType<T>> registerVehicle(
            DeferredRegister<EntityType<?>> entities, String name, EntityType.EntityFactory<T> factory, boolean summonable) {
        DeferredHolder<EntityType<?>, EntityType<T>> holder = entities.register(name, () -> {
            EntityType.Builder<T> builder = EntityType.Builder.of(factory, MobCategory.MISC);
            if (!summonable) builder.noSummon();
            return builder.sized(1f, 1f).updateInterval(1).clientTrackingRange(32).build(name);
        });
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
}
