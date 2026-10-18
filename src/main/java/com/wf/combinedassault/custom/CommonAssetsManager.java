package com.wf.combinedassault.custom;

import com.google.gson.JsonObject;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.api.custom.IVehicleDataManager;
import com.wf.combinedassault.api.custom.IVehicleWeaponManager;
import com.wf.combinedassault.pack.CombinedPacks;
import com.wf.combinedassault.pack.PackHandshake;
import com.wf.combinedassault.vehicle.hardware.HardwareKits;
import com.wf.combinedassault.vehicle.hit.ModelData;
import com.wf.combinedassault.vehicle.hit.ModelDataManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Pack defs, parsed: weapons + hardware kits -> vehicle models -> vehicles. One per JVM (both sides read the same
 * packs; the handshake keeps a remote client's identical). Built on first read or at common setup; {@code /reload}
 * rescans the packs.
 */
@EventBusSubscriber
public final class CommonAssetsManager {

    private static volatile CommonAssetsManager INSTANCE;
    private static final List<BiConsumer<ResourceLocation, JsonObject>> VEHICLE_DEF_PATCHES = new CopyOnWriteArrayList<>();

    private final Map<ResourceLocation, JsonObject> vehicleDefs;
    private final Map<ResourceLocation, JsonObject> weaponDefs;
    private final Map<ResourceLocation, String> vehiclePacks;
    private final Map<ResourceLocation, ModelData> models;
    private final VehicleWeaponManager weapons;
    private final VehicleDataManager vehicles;

    private CommonAssetsManager(Map<ResourceLocation, JsonObject> vehicleDefs, Map<ResourceLocation, JsonObject> weaponDefs,
                                Map<ResourceLocation, String> vehiclePacks,
                                Map<ResourceLocation, ModelData> models, VehicleWeaponManager weapons,
                                VehicleDataManager vehicles) {
        this.vehicleDefs = vehicleDefs;
        this.weaponDefs = weaponDefs;
        this.vehiclePacks = vehiclePacks;
        this.models = models;
        this.weapons = weapons;
        this.vehicles = vehicles;
    }

    /** Every problem across all packs => one exception. */
    public static synchronized void load() {
        long start = System.nanoTime();
        List<String> problems = new ArrayList<>();
        Map<ResourceLocation, JsonObject> weaponDefs = json(CombinedPacks.defs("weapons"));
        Map<ResourceLocation, CombinedPacks.Def> vehicleFiles = CombinedPacks.defs("vehicles");
        Map<ResourceLocation, JsonObject> vehicleDefs = json(vehicleFiles);
        VEHICLE_DEF_PATCHES.forEach(patch -> vehicleDefs.forEach(patch));
        HardwareKits.load(json(CombinedPacks.defs("hardware")), problems);
        vehicleDefs.entrySet().removeIf(e -> {
            try {
                HardwareKits.expand(e.getValue());
                return false;
            } catch (RuntimeException ex) {
                problems.add("vehicle " + e.getKey() + ": " + ex.getMessage());
                return true;
            }
        });
        Map<ResourceLocation, String> vehiclePacks = new LinkedHashMap<>();
        vehicleFiles.forEach((id, def) -> vehiclePacks.put(id, def.pack().id()));
        VehicleWeaponManager weapons = new VehicleWeaponManager(weaponDefs, problems);
        Map<ResourceLocation, ModelData> models = ModelDataManager.build(vehicleDefs);
        VehicleDataManager vehicles = new VehicleDataManager(vehicleDefs, models, problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("CombinedAssault content:\n  " + String.join("\n  ", problems));
        }
        INSTANCE = new CommonAssetsManager(Map.copyOf(vehicleDefs), Map.copyOf(weaponDefs), Map.copyOf(vehiclePacks), models,
                weapons, vehicles);
        CombinedAssault.LOGGER.info("CombinedAssault content: {} vehicles, {} weapons in {} ms", vehicleDefs.size(),
                weaponDefs.size(), (System.nanoTime() - start) / 1_000_000);
    }

    /** Applied in order to every vehicle def on each load; both sides must register the same patches. */
    public static void addVehicleDefPatch(BiConsumer<ResourceLocation, JsonObject> patch) {
        VEHICLE_DEF_PATCHES.add(patch);
    }

    private static Map<ResourceLocation, JsonObject> json(Map<ResourceLocation, CombinedPacks.Def> defs) {
        Map<ResourceLocation, JsonObject> out = new LinkedHashMap<>();
        defs.forEach((id, def) -> out.put(id, def.json()));
        return out;
    }

    @SubscribeEvent
    public static void onReload(AddReloadListenerEvent event) {
        event.addListener((barrier, resourceManager, preparationProfiler, reloadProfiler, backgroundExecutor, gameExecutor)
                -> barrier.wait(Void.TYPE).thenRunAsync(CommonAssetsManager::reload, gameExecutor));
    }

    /** Rescan + reparse; connected clients told the new pack list. */
    public static void reload() {
        CombinedPacks.rescan();
        load();
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            PackHandshake.broadcast();
        }
    }

    /** First reader loads: the client's first resource reload runs before common setup. */
    private static CommonAssetsManager instance() {
        CommonAssetsManager instance = INSTANCE;
        if (instance == null) {
            ensureLoaded();
            instance = INSTANCE;
        }
        return instance;
    }

    public static synchronized void ensureLoaded() {
        if (INSTANCE == null) {
            load();
        }
    }

    public static Optional<ModelData> modelData(ResourceLocation vehicle) {
        return Optional.ofNullable(instance().models.get(vehicle));
    }

    /** Pack id of a vehicle def, null if none. */
    public static String packOf(ResourceLocation vehicle) {
        return instance().vehiclePacks.get(vehicle);
    }

    /** Vehicle defs after def patches and hardpoint expansion, before glTF socket defaults. */
    public static Map<ResourceLocation, JsonObject> vehicleDefs() {
        return instance().vehicleDefs;
    }

    public static Map<ResourceLocation, JsonObject> weaponDefs() {
        return instance().weaponDefs;
    }

    public static IVehicleWeaponManager vehicleWeaponManager() {
        return instance().weapons;
    }

    public static IVehicleDataManager vehicleDataManager() {
        return instance().vehicles;
    }
}
