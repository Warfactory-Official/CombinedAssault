package com.wf.combinedassault.labs;

import com.wf.combinedassault.ballistics.DamageSystem;
import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.labs.api.LabProducer;
import com.wf.wflib.labs.api.LabSink;
import com.wf.wflib.labs.api.LabTable;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real {@code DamageSystem.hurt} on a resting vehicle, one round per aspect and aim, repeated until the CA kill
 * predicates hold (cap {@value #CAP} shots): systems hit by the first shot and shots to mobility / firepower /
 * catastrophic kill. Param {@code range} (500), default launchers only.
 */
public final class VehicleSystemsProducer implements LabProducer {

    static final LabTable TABLE = tableOf();
    static final int CAP = 30;

    private static final String[][] ASPECTS = {{"front", "0", "0"}, {"left", "90", "0"}, {"rear", "180", "0"},
            {"right", "270", "0"}, {"top", "0", "60"}};

    private static LabTable tableOf() {
        LabTable.Builder b = LabTable.of("combinedassault:vehicle_systems")
                .dimStr("vehicle").dimStr("launcher").dimStr("round").dimNum("range_m", "m").dimStr("aspect").dimStr("aim")
                .measureStr("verdict").measure("hull_damage", "").measureStr("struck_system");
        for (var kind : SystemsProbe.KINDS) {
            b.measure("loss_" + kind.code, "");
        }
        return b.measure("mobility_after_first", "").measure("shots_mobility_kill", "").measure("shots_firepower_kill", "")
                .measure("shots_catastrophic", "").build();
    }

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
        double range = ctx.paramNum("range", 500);
        VehicleProducer.Sweep sweep = VehicleProducer.sweep(ctx, new double[]{range});
        for (VehicleRig rig : VehicleProducer.rigs(ctx)) {
            for (VehicleProducer.Launch launch : sweep.launches()) {
                VehicleProducer.Arrival arrival = launch.byRange().get(0);
                if (arrival == null || !launch.launcher().id().startsWith("wflib:")) {
                    continue;
                }
                for (VehicleRig.Aim aim : rig.aims) {
                    for (String[] aspect : ASPECTS) {
                        row(ctx, rig, launch, arrival, aim, aspect, range, sink);
                    }
                }
            }
            rig.vehicle.discard();
        }
    }

    private static void row(LabContext ctx, VehicleRig rig, VehicleProducer.Launch launch, VehicleProducer.Arrival arrival,
                            VehicleRig.Aim aim, String[] aspect, double range, LabSink sink) {
        VehicleShots.Ray ray = VehicleShots.ray(rig, aim.point(), Double.parseDouble(aspect[1]), Double.parseDouble(aspect[2]));
        if (ray.hit() == null) {
            return;
        }
        Vec3 velocity = ray.dir().scale(2 * rig.reach);
        SystemsProbe.reset(rig.vehicle);
        double[][] before = SystemsProbe.totals(rig.vehicle);
        Map<String, Double> labels = SystemsProbe.byLabel(rig.vehicle);
        double[] lost = null;
        String struck = "";
        double mobility = 1;
        double hull = 0;
        int mob = 0, fire = 0, cat = 0;
        VehicleShots.Result first = VehicleShots.shoot(ctx.level(), rig, ray, arrival.velocity(), arrival.threat(), arrival.damage());
        SystemsProbe.reset(rig.vehicle);
        for (int shot = 1; shot <= CAP; shot++) {
            float health = rig.vehicle.getHealth();
            DamageSystem.hurt(new StrikeSource(ctx.level(), arrival.threat(), ray.start(), velocity), arrival.damage(), rig.vehicle);
            if (shot == 1) {
                lost = SystemsProbe.lost(before, SystemsProbe.totals(rig.vehicle));
                struck = SystemsProbe.worstCell(rig.vehicle, labels);
                mobility = com.wf.combinedassault.ballistics.component.VehicleMobility.of(rig.vehicle).speedScale();
                hull = health - rig.vehicle.getHealth();
            }
            if (mob == 0 && SystemsProbe.mobilityKilled(rig.vehicle)) {
                mob = shot;
            }
            if (fire == 0 && SystemsProbe.firepowerKilled(rig.vehicle)) {
                fire = shot;
            }
            if (SystemsProbe.catastrophic(rig.vehicle)) {
                cat = shot;
                break;
            }
        }
        SystemsProbe.reset(rig.vehicle);
        List<Object> row = new ArrayList<>(List.of(rig.id.toString(), launch.launcher().id(), launch.preset().id().toString(), range,
                aspect[0], aim.name(), first.verdict(), hull, struck));
        for (double l : lost) {
            row.add(l);
        }
        row.add((double) mobility);
        row.add(mob == 0 ? null : mob);
        row.add(fire == 0 ? null : fire);
        row.add(cat == 0 ? null : cat);
        sink.row(row.toArray());
    }
}
