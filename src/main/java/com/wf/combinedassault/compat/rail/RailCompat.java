package com.wf.combinedassault.compat.rail;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.parenting.DeckCarrier;
import com.wf.combinedassault.vehicle.parenting.DeckCarriers;
import com.wf.combinedassault.vehicle.parenting.VehicleHarness;
import com.wf.combinedassault.vehicle.pojo.MountPointInfo;

/**
 * Vehicles strapped to IR stock. Inert without IR: nothing registers, {@link FlatcarCarrier} never loads.
 * Strapping only: flatcar has no deck contact => rides via declared mount point, never by parking.
 */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID)
public final class RailCompat {

    private static final String MOD_ID = "immersiverailroading";
    private static boolean IS_LOADED = false;

    private RailCompat() {
    }

    public static void init() {
        IS_LOADED = ModList.get().isLoaded(MOD_ID);
        if (IS_LOADED) {
            // Guarded: avoids classloading an absent type.
            DeckCarriers.register(FlatcarCarrier::of);
        }
    }

    public static boolean isLoaded() {
        return IS_LOADED;
    }

    /** Null: IR absent or not rolling stock. */
    @Nullable
    public static DeckCarrier carrierFor(Entity entity) {
        return IS_LOADED ? FlatcarCarrier.of(entity) : null;
    }

    public static boolean mount(AbstractVehicle vehicle, Entity stock, MountPointInfo point) {
        DeckCarrier carrier = carrierFor(stock);
        return carrier != null && VehicleHarness.mount(vehicle, carrier, point);
    }

    /** Non-self-ticking riders: self-tick keeps interpolation, skip => carrier rolls out from under them. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!IS_LOADED) {
            return;
        }
        Level level = event.getLevel();
        for (FlatcarCarrier carrier : FlatcarCarrier.loaded()) {
            if (carrier.level() == level && !carrier.harnessedVehicles().isEmpty()) {
                VehicleHarness.applyChildren(carrier, true);
            }
        }
    }
}
