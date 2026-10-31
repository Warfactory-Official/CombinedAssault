package com.wf.combinedassault.vehicle.api;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.core.api.OrderedRegistry;

/** CombinedAssault's vehicle roster for other mods: ids, names, footprints, summon, def patches. Vehicles are pack data, not entity types. */
public final class VehicleCatalogue {

    /**
     * Edit each vehicle def JSON before parse, on every (re)load, priority descending. Register at mod construction
     * or startup scripts, before load complete; client and server defs must agree.
     */
    public static final OrderedRegistry<BiConsumer<ResourceLocation, JsonObject>> DEF_PATCHES =
            OrderedRegistry.create("combinedassault:vehicle_def_patch");

    private VehicleCatalogue() {
    }

    /** Sorted; empty until the first content load. */
    public static List<ResourceLocation> ids() {
        return CommonAssetsManager.vehicleDataManager().getVehicleData().keySet().stream().sorted().toList();
    }

    public static boolean isKnown(ResourceLocation id) {
        return data(id).isPresent();
    }

    public static Optional<Component> name(ResourceLocation id) {
        return data(id).map(BaseVehicleData::getName);
    }

    /** Volume the vehicle occupies at {@code at} facing {@code yRot}; vertical extent = {@code height}. */
    public static Optional<AABB> footprint(ResourceLocation id, Vec3 at, float yRot, double height) {
        return data(id).map(d -> d.footprint(at, yRot, height));
    }

    /** Spawned display id = vehicle id; false = unknown vehicle or not added. */
    public static boolean summon(Level level, Vec3 at, float yRot, ResourceLocation id) {
        Optional<BaseVehicleData> data = data(id);
        if (data.isEmpty()) {
            return false;
        }
        AbstractVehicle vehicle = data.get().construct(level, at, 0, yRot);
        vehicle.setDisplayId(id);
        return level.addFreshEntity(vehicle);
    }

    @SuppressWarnings("rawtypes")
    private static Optional<BaseVehicleData> data(ResourceLocation id) {
        return CommonAssetsManager.vehicleDataManager().getVehicleData(id);
    }
}
