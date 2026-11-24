package com.wf.combinedassault.deck.rail;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.deck.DeckCarriers;
import com.wf.combinedassault.deck.HarnessCarrier;
import com.wf.combinedassault.deck.VehicleHarness;
import com.wf.combinedassault.deck.pojo.MountPointInfo;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.core.api.ServerTicks;
import com.wf.wflib.core.api.TickOrder;
import com.wf.wflib.physics.step.RigidStep;
import com.wf.wflib.physics.api.DeckSnapshot;
import com.wf.wflib.physics.api.TickClock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
        boolean regions = RigidStep.regionHost() != null;
        for (FlatcarCarrier carrier : FlatcarCarrier.loaded()) {
            if (carrier.level() != level || carrier.harnessedVehicles().isEmpty()) {
                continue;
            }
            if (regions) {
                applyOffRegion(carrier);
            } else {
                VehicleHarness.applyChildren(carrier, true);
            }
        }
    }

    /** Region-owned ticking children pose themselves in their own tick ({@code follow}); iterates the copy-on-write snapshot. */
    private static void applyOffRegion(FlatcarCarrier carrier) {
        DeckSnapshot deck = carrier.deckSnapshot();
        if (deck.count() == 0) {
            return;
        }
        long gameTime = TickClock.now(carrier.level());
        List<AbstractVehicle> children = carrier.harnessedVehicles();
        for (AbstractVehicle child : children) {
            if (VehicleHarness.stale(carrier, child)) {
                children.remove(child);
                VehicleHarness.dropStale(carrier, child);
            } else if (child.tickRegion() < 0 || child.lastTickTime() < gameTime - 1) {
                VehicleHarness.applyChild(child, deck, true, gameTime);
            }
        }
    }
}
