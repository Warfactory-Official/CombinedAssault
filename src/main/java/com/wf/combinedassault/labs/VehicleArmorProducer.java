package com.wf.combinedassault.labs;

import com.wf.wflib.armor.api.hull.HullFace;
import com.wf.wflib.armor.api.hull.HullFaces;
import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.wflib.armor.api.hull.PlateLayer;
import com.wf.wflib.geom.api.OBB;
import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.labs.api.LabProducer;
import com.wf.wflib.labs.api.LabSink;
import com.wf.wflib.labs.api.LabTable;

import java.util.Locale;

/** Armour data the resolver reads: hull faces, part faces, zones, plates (plate = 1; face = facing), main box (lab frame). */
public final class VehicleArmorProducer implements LabProducer {

    static final LabTable TABLE = LabTable.of("combinedassault:vehicle_armor")
            .dimStr("vehicle").dimStr("scope").dimStr("face")
            .measure("ke_mm", "mm_rha").measure("ce_mm", "mm_rha").measure("plate", "")
            .measureStr("part")
            .measure("min_x_m", "m").measure("min_y_m", "m").measure("min_z_m", "m")
            .measure("max_x_m", "m").measure("max_y_m", "m").measure("max_z_m", "m")
            .measure("wear_floor", "").measure("max_health", "")
            .measure("layers", "").measure("gap_m", "m")
            .build();

    @Override
    public String domain() {
        return "vehicle";
    }

    @Override
    public String id() {
        return TABLE.name();
    }

    @Override
    public LabTable table() {
        return TABLE;
    }

    @Override
    public void produce(LabContext ctx, LabSink sink) {
        for (VehicleRig rig : VehicleProducer.rigs(ctx)) {
            String vehicle = rig.id.toString();
            HullProfile p = rig.vehicle.armorProfile;
            float health = rig.vehicle.getMaxHealth();
            OBB box = rig.vehicle.mainBox();
            double[] c = {box.centerX() - rig.vehicle.getX(), box.centerY() - rig.vehicle.getY(), box.centerZ() - rig.vehicle.getZ()};
            sink.row(vehicle, "box", "*", null, null, 0, "", c[0] - box.extents().x, c[1] - box.extents().y, c[2] - box.extents().z,
                    c[0] + box.extents().x, c[1] + box.extents().y, c[2] + box.extents().z, p.wearFloor, health, 0, 0);
            faces(sink, vehicle, "hull", "", p, p, health);
            p.parts.forEach((part, f) -> faces(sink, vehicle, "part:" + part, part, f, p, health));
            for (HullProfile.Zone z : p.zones) {
                sink.row(vehicle, "zone:" + z.name(), z.face() == null ? "*" : z.face().name().toLowerCase(Locale.ROOT),
                        z.ke(), z.ce(), 0, z.part() == null ? "" : z.part(),
                        z.min()[0], z.min()[1], z.min()[2], z.max()[0], z.max()[1], z.max()[2],
                        p.wearFloor, health, 0, 0);
            }
            p.plates.forEach((id, spec) -> sink.row(vehicle, "plate:" + id, spec.facing == null ? "*" : spec.facing,
                    spec.nominal(false), spec.nominal(true), 1, "", null, null, null, null, null, null, p.wearFloor,
                    health, spec.layers == null ? 0 : spec.layers.stream().filter(l -> !l.isGap()).count(),
                    spec.layers == null ? 0 : spec.layers.stream().filter(l -> l.isGap()).mapToDouble(PlateLayer::gap).sum()));
            rig.vehicle.discard();
        }
    }

    private static void faces(LabSink sink, String vehicle, String scope, String part, HullFaces f, HullProfile p, float health) {
        for (HullFace face : HullFace.values()) {
            float ke = f.nominal(face);
            float ce = f.nominal(face, true);
            sink.row(vehicle, scope, face.name().toLowerCase(Locale.ROOT), ke, ce, 0, part, null, null, null, null, null, null,
                    p.wearFloor, health, 0, 0);
        }
    }
}
