package com.wf.combinedassault.deck.rail;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.deck.HarnessCarrier;
import com.wf.combinedassault.deck.DeckCarriers;
import com.wf.combinedassault.deck.VehicleHarness;
import com.wf.combinedassault.deck.pojo.MountPointInfo;
import com.wf.wflib.core.api.ServerTicks;
import com.wf.wflib.core.api.TickOrder;

/**
 * Vehicles strapped to IR stock. Inert without IR: nothing registers, {@link FlatcarCarrier} never loads.
 * Strapping only: flatcar has no deck contact => rides via declared mount point, never by parking.
 */
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
            ServerTicks.LEVEL_POST.register(CombinedAssault.modLocation("rail_carried"), TickOrder.CARRIED,
                    RailCompat::tickCarried);
        }
    }

    public static boolean isLoaded() {
        return IS_LOADED;
    }

    /** Null: IR absent or not rolling stock. */
    @Nullable
    public static HarnessCarrier carrierFor(Entity entity) {
        return IS_LOADED ? FlatcarCarrier.of(entity) : null;
    }

    public static boolean mount(AbstractVehicle vehicle, Entity stock, MountPointInfo point) {
        HarnessCarrier carrier = carrierFor(stock);
        return carrier != null && VehicleHarness.mount(vehicle, carrier, point);
    }

    /** Non-self-ticking riders: self-tick keeps interpolation, skip => carrier rolls out from under them. */
    private static void tickCarried(ServerLevel level) {
        for (FlatcarCarrier carrier : FlatcarCarrier.loaded()) {
            if (carrier.level() == level && !carrier.harnessedVehicles().isEmpty()) {
                VehicleHarness.applyChildren(carrier, true);
            }
        }
    }
}
