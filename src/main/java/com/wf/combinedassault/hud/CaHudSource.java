package com.wf.combinedassault.hud;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.naval.ShipVehicle;
import com.wf.combinedassault.indirect.client.IndirectView;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.wflib.core.api.WFSystems;
import com.wf.wflib.ui.api.UiHud;
import com.wf.wflib.ui.api.UiInstance;
import com.wf.wflib.ui.api.UiLayouts;
import net.minecraft.resources.ResourceLocation;

/**
 * Feeds the HUD: the hit marker always; on a vehicle the {@code vehicle} preset plus the hull class's
 * preset, and the {@code scope} preset in the back layer. Instances are rebuilt per boarded hull (smoothed
 * readings never sweep from the previous one) and after a resource reload.
 */
public final class CaHudSource {
    private static final CaHudContext CONTEXT = new CaHudContext();
    private static UiInstance hit;
    private static UiInstance vehicle;
    private static UiInstance hull;
    private static UiInstance scope;
    private static UiInstance indirect;
    private static int instancesFor = Integer.MIN_VALUE;

    private CaHudSource() {
    }

    public static void register() {
        HudSignals.register();
        IndirectView.register();
        com.wf.combinedassault.indirect.client.GunComputer.register();
        CaHudPresets.registerWidgets();
        UiHud.addSource(UiHud.Layer.MAIN, CombinedAssault.modLocation("main"), 0, CaHudSource::main);
        UiHud.addSource(UiHud.Layer.BACK, CombinedAssault.modLocation("back"), 0, CaHudSource::back);
    }

    private static void main(UiHud.Collector out) {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance();
        if (local == null || local.getPlayer() == null) {
            return;
        }
        CONTEXT.refresh(local);
        if (hit == null || !hit.current()) {
            hit = UiLayouts.get(CaHudPresets.HIT).instantiate().context(CONTEXT);
        }
        out.add(hit);
        AbstractVehicle ride = CONTEXT.vehicle;
        if (ride == null) {
            return;
        }
        if (ride.getId() != instancesFor || !vehicle.current() || hull != null && !hull.current() || !scope.current()) {
            instancesFor = ride.getId();
            vehicle = UiLayouts.get(CaHudPresets.VEHICLE).instantiate().context(CONTEXT);
            ResourceLocation preset = preset(ride);
            hull = preset == null ? null : UiLayouts.get(preset).instantiate().context(CONTEXT);
            scope = UiLayouts.get(CaHudPresets.SCOPE).instantiate().context(CONTEXT);
            indirect = WFSystems.enabled(CombinedAssault.MOD_ID + ":indirect")
                    ? UiLayouts.get(CaHudPresets.INDIRECT).instantiate().context(CONTEXT) : null;
        }
        out.add(vehicle);
        if (indirect != null) {
            out.add(indirect);
        }
        if (hull != null) {
            out.add(hull);
        }
    }

    private static void back(UiHud.Collector out) {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance();
        if (local == null || local.getPlayer() == null) {
            return;
        }
        CONTEXT.refresh(local);
        if (CONTEXT.vehicle != null && scope != null && CONTEXT.vehicle.getId() == instancesFor) {
            out.add(scope);
        }
    }

    private static ResourceLocation preset(AbstractVehicle ride) {
        if (ride instanceof RotaryWingVehicle) {
            return CaHudPresets.ROTARY_WING;
        }
        if (ride instanceof FixedWingVehicle) {
            return CaHudPresets.FIXED_WING;
        }
        if (ride instanceof ShipVehicle) {
            return CaHudPresets.SHIP;
        }
        return null;
    }
}
