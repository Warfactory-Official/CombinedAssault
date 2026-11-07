package com.wf.combinedassault.labs;

import com.wf.wflib.armor.api.hull.HullOutcome;
import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.labs.api.LabProducer;
import com.wf.wflib.labs.api.LabSink;
import com.wf.wflib.labs.api.LabTable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Per vehicle x launcher x range, aimed at the hull centre: share of the sampled sphere (cos(elevation) weighted) per
 * verdict, hull shots-to-kill over cells that do damage ({@code ceil(max health / hull damage)}).
 */
public final class VehicleSummaryProducer implements LabProducer {

    static final LabTable TABLE = LabTable.of("combinedassault:vehicle_summary")
            .dimStr("vehicle").dimStr("launcher").dimStr("round").dimNum("range_m", "m")
            .measure("max_health", "").measure("miss", "").measure("ricochet", "").measure("absorbed", "")
            .measure("partial", "").measure("penetration", "").measure("overmatch", "").measure("no_model", "")
            .measure("mean_hull_damage", "").measure("shots_to_kill_min", "").measure("shots_to_kill_max", "")
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
        double[] ranges = ctx.ranges();
        List<VehicleProducer.Launch> launches = VehicleProducer.sweep(ctx, ranges).launches();
        double[] azimuths = VehicleProducer.steps(ctx.paramNum("azimuth_step", 10), 0, 350);
        double[] elevations = VehicleProducer.steps(ctx.paramNum("elevation_step", 10), ctx.paramNum("elevation_min", -10), ctx.paramNum("elevation_max", 60));
        for (VehicleRig rig : VehicleProducer.rigs(ctx)) {
            List<VehicleShots.Ray> rays = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            for (double az : azimuths) {
                for (double el : elevations) {
                    rays.add(VehicleShots.ray(rig, rig.aims.get(0).point(), az, el));
                    weights.add(Math.cos(Math.toRadians(el)));
                }
            }
            List<Future<Object[]>> futures = new ArrayList<>();
            for (VehicleProducer.Launch launch : launches) {
                for (int r = 0; r < ranges.length; r++) {
                    VehicleProducer.Arrival arrival = launch.byRange().get(r);
                    if (arrival == null) {
                        continue;
                    }
                    double range = ranges[r];
                    futures.add(ctx.executor().submit(() -> row(ctx, rig, launch, range, arrival, rays, weights)));
                }
            }
            for (Future<Object[]> f : futures) {
                try {
                    sink.row(f.get());
                } catch (InterruptedException | ExecutionException e) {
                    throw new IllegalStateException(rig.id + ": " + e.getMessage(), e);
                }
            }
            rig.vehicle.discard();
        }
    }

    private static Object[] row(LabContext ctx, VehicleRig rig, VehicleProducer.Launch launch, double range,
                                VehicleProducer.Arrival arrival, List<VehicleShots.Ray> rays, List<Double> weights) {
        double total = 0, miss = 0, noModel = 0, damage = 0, damageWeight = 0;
        double[] by = new double[HullOutcome.values().length];
        double kmin = Double.POSITIVE_INFINITY, kmax = 0;
        double health = rig.vehicle.getMaxHealth();
        for (int i = 0; i < rays.size(); i++) {
            double w = weights.get(i);
            total += w;
            VehicleShots.Result s = VehicleShots.shoot(ctx.level(), rig, rays.get(i), arrival.velocity(), arrival.threat(), arrival.damage());
            if (s.verdict().equals("MISS")) {
                miss += w;
                continue;
            }
            HullOutcome outcome = null;
            for (HullOutcome o : HullOutcome.values()) {
                if (o.name().equals(s.verdict())) {
                    outcome = o;
                }
            }
            if (outcome == null) {
                noModel += w;
            } else {
                by[outcome.ordinal()] += w;
            }
            damage += s.hullDamage() * w;
            damageWeight += w;
            if (s.hullDamage() > 0) {
                double n = Math.ceil(health / s.hullDamage());
                kmin = Math.min(kmin, n);
                kmax = Math.max(kmax, n);
            }
        }
        return new Object[]{rig.id.toString(), launch.launcher().id(), launch.preset().id().toString(), range, health,
                miss / total, by[HullOutcome.RICOCHET.ordinal()] / total, by[HullOutcome.ABSORBED.ordinal()] / total,
                by[HullOutcome.PARTIAL.ordinal()] / total, by[HullOutcome.PENETRATION.ordinal()] / total,
                by[HullOutcome.OVERMATCH.ordinal()] / total, noModel / total,
                damageWeight > 0 ? damage / damageWeight : 0, Double.isInfinite(kmin) ? null : kmin, kmax == 0 ? null : kmax};
    }
}
