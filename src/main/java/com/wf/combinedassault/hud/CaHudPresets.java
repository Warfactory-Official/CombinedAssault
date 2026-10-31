package com.wf.combinedassault.hud;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.hud.widget.AimWidgets;
import com.wf.combinedassault.hud.widget.AmmoList;
import com.wf.combinedassault.hud.widget.DebugDump;
import com.wf.combinedassault.hud.widget.HitWidgets;
import com.wf.combinedassault.radar.client.RadarWidgets;
import com.wf.combinedassault.hud.widget.RangeScale;
import com.wf.combinedassault.hud.widget.ScopeWidgets;
import com.wf.combinedassault.naval.client.ShipWidgets;
import com.wf.wflib.ui.api.json.UiWidgetTypes;
import com.wf.wflib.ui.api.json.WidgetReader;
import net.minecraft.resources.ResourceLocation;

/**
 * CA's HUD presets: JSON layouts {@code assets/combinedassault/wf_ui/layouts/<id>.json} (components under
 * {@code wf_ui/components/hud/}), drawn with wflib primitives plus the Java widgets registered here.
 * A hull shows {@code vehicle} plus its class preset; {@link CaHudSource} picks.
 */
public final class CaHudPresets {
    public static final ResourceLocation VEHICLE = CombinedAssault.modLocation("vehicle");
    public static final ResourceLocation ROTARY_WING = CombinedAssault.modLocation("rotary_wing");
    public static final ResourceLocation FIXED_WING = CombinedAssault.modLocation("fixed_wing");
    public static final ResourceLocation SHIP = CombinedAssault.modLocation("ship");
    public static final ResourceLocation SCOPE = CombinedAssault.modLocation("scope");
    public static final ResourceLocation HIT = CombinedAssault.modLocation("hit");
    public static final ResourceLocation INDIRECT = CombinedAssault.modLocation("indirect");

    private CaHudPresets() {
    }

    static void registerWidgets() {
        add("ammo_list", p -> new AmmoList());
        add("hit_marker", p -> new HitWidgets.HitMarker());
        add("hit_panel", p -> new HitWidgets.HitPanel());
        add("aim_reticle", p -> new AimWidgets.AimReticle(p.expr("color")));
        add("impact_markers", p -> new AimWidgets.ImpactMarkers(p.expr("color")));
        add("lock_markers", p -> new AimWidgets.LockMarkers());
        add("radar_scopes", p -> new RadarWidgets.RadarScopes());
        add("rwr", p -> new RadarWidgets.Rwr());
        add("sounder", p -> new ShipWidgets.Sounder());
        add("depth_gauge", p -> new ShipWidgets.DepthGauge());
        add("optical_scope", p -> new ScopeWidgets.OpticalScope());
        add("scope_sight", p -> new ScopeWidgets.ScopeSight(p.expr("color")));
        add("engagement_envelope", p -> new ScopeWidgets.EngagementEnvelope(p.expr("color")));
        add("vehicle_schematic", p -> new ScopeWidgets.VehicleSchematic());
        add("debug_dump", p -> new DebugDump());
        add("range_scale", p -> new RangeScale(p.number("w"), p.expr("near"), p.expr("far"), p.expr("max"),
                p.expr("mark", "0/0"), p.expr("track", "0"), p.expr("scale", "0"), p.expr("band"), p.expr("mark_color"),
                p.expr("label")));
    }

    private static void add(String name, WidgetReader reader) {
        UiWidgetTypes.register(CombinedAssault.modLocation(name), reader);
    }
}
