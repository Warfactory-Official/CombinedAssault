package com.wf.combinedassault.labs;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.labs.api.LabLauncher;
import com.wf.wflib.labs.api.LabProducer;
import com.wf.wflib.labs.api.LabSink;
import com.wf.wflib.labs.api.LabTable;
import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.KineticPresets;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Every vehicle x sweep launcher x range x bearing x aim point; launchers with equal (round, muzzle velocity, damage
 * scale) share one sweep, {@code combinedassault:vehicle_launchers} maps each launcher to its sweep launcher. Over
 * {@code max_rows} (1.5e6) the default grid coarsens: aims -> hull, ranges -> 3, elevation step, azimuth step; explicit
 * grid params are never coarsened; the grid used is {@code combinedassault:vehicle_grid}. Params: {@code vehicles} glob, {@code azimuth_step} (10),
 * {@code elevation_min} (-10), {@code elevation_max} (60), {@code elevation_step} (10), {@code aims} hull|parts (parts),
 * {@code unarmoured} (0), {@code all_rounds} (0: only rounds with armour penetration).
 */
public final class VehicleProducer implements LabProducer {

    static final LabTable TABLE = LabTable.of("combinedassault:vehicle")
            .dimStr("vehicle").dimStr("launcher").dimStr("round").dimNum("range_m", "m")
            .dimNum("azimuth_deg", "deg").dimNum("elevation_deg", "deg").dimStr("aim")
            .measureStr("verdict").measureStr("struck_node").measureStr("struck_part").measureStr("face")
            .measureStr("zone").measure("plate", "").measure("nominal_mm", "mm_rha").measure("los_mm", "mm_rha")
            .measure("pen_mm", "mm_rha").measure("ratio", "").measure("incidence_deg", "deg")
            .measure("surface_incidence_deg", "deg").measure("damage_in", "").measure("hull_damage", "")
            .measure("part_damage", "").measureStr("part_damaged").measureStr("stock")
            .measure("hit_x_m", "m").measure("hit_y_m", "m").measure("hit_z_m", "m")
            .measure("aim_offset_m", "m")
            .build();

    private static final int BATCH = 96;
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    record Arrival(Vec3 velocity, Threat threat, float damage) {
    }

    record Launch(LabLauncher launcher, KineticPreset preset, List<Arrival> byRange) {
    }

    /** Sweep unit: launchers firing the same round at the same speed and damage scale share every verdict. */
    record Key(String round, long velocityMilli, long scaleMicro) {
    }

    record Sweep(List<Launch> launches, Map<String, String> sweepOf) {
    }

    record Grid(double[] azimuths, double[] elevations, double[] ranges, boolean parts, String coarsened) {
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

    record Groups(Map<Key, LabLauncher> reps, Map<String, String> sweepOf) {
    }

    static Groups groups(LabContext ctx) {
        boolean all = ctx.paramNum("all_rounds", 0) != 0;
        Map<Key, LabLauncher> reps = new LinkedHashMap<>();
        Map<String, Key> keyOf = new HashMap<>();
        for (LabLauncher l : ctx.launchers()) {
            KineticPreset preset = KineticPresets.get(ResourceLocation.parse(l.roundId()));
            if (!all && preset.armorPenetrationMm() <= 0) {
                continue;
            }
            Key key = new Key(l.roundId(), Math.round(l.muzzleVelocity() * 1000), Math.round(l.damageScale() * 1.0e6));
            keyOf.put(l.id(), key);
            reps.merge(key, l, (old, next) -> next.id().startsWith("wflib:") && !old.id().startsWith("wflib:") ? next : old);
        }
        Map<String, String> sweepOf = new java.util.TreeMap<>();
        keyOf.forEach((id, key) -> sweepOf.put(id, reps.get(key).id()));
        return new Groups(reps, sweepOf);
    }

    static Sweep sweep(LabContext ctx, double[] ranges) {
        Groups g = groups(ctx);
        List<Launch> out = new ArrayList<>();
        for (LabLauncher l : g.reps().values()) {
            KineticPreset preset = KineticPresets.get(ResourceLocation.parse(l.roundId()));
            Flight flight = new Flight(preset, l.muzzleVelocity());
            List<Arrival> byRange = new ArrayList<>();
            for (double range : ranges) {
                Vec3 v = flight.arrival(range);
                byRange.add(v == null ? null
                        : new Arrival(v, Rounds.threat(preset, v), (float) (Rounds.impactDamage(preset, v) * l.damageScale())));
            }
            out.add(new Launch(l, preset, byRange));
        }
        out.sort(java.util.Comparator.comparing(x -> x.launcher().id()));
        return new Sweep(out, g.sweepOf());
    }

    static Grid grid(LabContext ctx, List<VehicleRig> rigs, int keys) {
        boolean explicit = ctx.param("azimuth_step", null) != null || ctx.param("elevation_step", null) != null
                || ctx.param("aims", null) != null;
        double azStep = ctx.paramNum("azimuth_step", 10);
        double elStep = ctx.paramNum("elevation_step", 10);
        double elMin = ctx.paramNum("elevation_min", -10), elMax = ctx.paramNum("elevation_max", 60);
        double azMin = ctx.paramNum("azimuth_min", 0), azMax = ctx.paramNum("azimuth_max", 350);
        double[] ranges = ctx.ranges();
        boolean parts = !"hull".equals(ctx.param("aims", "parts"));
        double budget = ctx.paramNum("max_rows", 1.5e6);
        StringBuilder cut = new StringBuilder();
        for (int guard = 0; guard < 8; guard++) {
            double aims = 0;
            for (VehicleRig rig : rigs) {
                aims += parts ? rig.aims.size() : 1;
            }
            double rows = aims * keys * ranges.length * Math.floor((azMax - azMin) / azStep + 1) * Math.floor((elMax - elMin) / elStep + 1);
            if (explicit || rows <= budget) {
                break;
            }
            if (parts) {
                parts = false;
                cut.append("aims=hull ");
            } else if (ranges.length > 3) {
                ranges = new double[]{ranges[0], ranges[ranges.length / 2], ranges[ranges.length - 1]};
                cut.append("ranges=3 ");
            } else if (elStep < 40) {
                elStep *= 2;
                cut.append("elevation_step=").append(elStep).append(' ');
            } else if (azStep < 45) {
                azStep *= 2;
                cut.append("azimuth_step=").append(azStep).append(' ');
            } else {
                break;
            }
        }
        return new Grid(steps(azStep, azMin, azMax), steps(elStep, elMin, elMax), ranges, parts, cut.toString().trim());
    }

    static double[] steps(double step, double lo, double hi) {
        return java.util.stream.DoubleStream.iterate(lo, x -> x <= hi + 1.0e-9, x -> x + step).toArray();
    }

    static List<VehicleRig> rigs(LabContext ctx) {
        boolean parts = !"hull".equals(ctx.param("aims", "parts"));
        boolean unarmoured = ctx.paramNum("unarmoured", 0) != 0;
        List<VehicleRig> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, BaseVehicleData> e : VehicleRig.roster(ctx.param("vehicles", null)).entrySet()) {
            VehicleRig rig = VehicleRig.spawn(ctx.level(), e.getKey(), e.getValue(), parts);
            if (unarmoured || rig.vehicle.armorProfile != null && rig.vehicle.armorProfile.isDefined()) {
                out.add(rig);
            } else {
                rig.vehicle.discard();
            }
        }
        return out;
    }

    @Override
    public void produce(LabContext ctx, LabSink sink) {
        List<VehicleRig> rigs = rigs(ctx);
        Sweep sweep = sweep(ctx, ctx.ranges());
        Grid grid = grid(ctx, rigs, sweep.launches().size());
        if (grid.ranges().length != ctx.ranges().length) {
            sweep = sweep(ctx, grid.ranges());
        }
        LOG.info("[labs] vehicle grid: {} vehicles, {} sweep keys, {} ranges, {} azimuths, {} elevations, aims {}{}", rigs.size(),
                sweep.launches().size(), grid.ranges().length, grid.azimuths().length, grid.elevations().length,
                grid.parts() ? "parts" : "hull", grid.coarsened().isEmpty() ? "" : ", coarsened: " + grid.coarsened());
        for (VehicleRig rig : rigs) {
            List<double[]> cells = new ArrayList<>();
            for (int a = 0; a < (grid.parts() ? rig.aims.size() : 1); a++) {
                for (double az : grid.azimuths()) {
                    for (double el : grid.elevations()) {
                        cells.add(new double[]{a, az, el});
                    }
                }
            }
            for (int from = 0; from < cells.size(); from += BATCH) {
                List<Future<List<Object[]>>> futures = new ArrayList<>();
                for (int i = from; i < Math.min(cells.size(), from + BATCH); i++) {
                    double[] cell = cells.get(i);
                    Sweep sw = sweep;
                    futures.add(ctx.executor().submit(() -> cell(ctx, rig, sw.launches(), grid.ranges(), cell)));
                }
                for (Future<List<Object[]>> f : futures) {
                    try {
                        for (Object[] row : f.get()) {
                            sink.row(row);
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        throw new IllegalStateException(rig.id + ": " + e.getMessage(), e);
                    }
                }
            }
            rig.vehicle.discard();
        }
    }

    private static List<Object[]> cell(LabContext ctx, VehicleRig rig, List<Launch> launches, double[] ranges, double[] cell) {
        VehicleRig.Aim aim = rig.aims.get((int) cell[0]);
        VehicleShots.Ray ray = VehicleShots.ray(rig, aim.point(), cell[1], cell[2]);
        List<Object[]> rows = new ArrayList<>();
        String vehicle = rig.id.toString();
        for (Launch launch : launches) {
            for (int r = 0; r < ranges.length; r++) {
                Arrival arrival = launch.byRange().get(r);
                if (arrival == null) {
                    continue;
                }
                VehicleShots.Result s = VehicleShots.shoot(ctx.level(), rig, ray, arrival.velocity(), arrival.threat(), arrival.damage());
                rows.add(new Object[]{vehicle, launch.launcher().id(), launch.preset().id().toString(), ranges[r], cell[1], cell[2],
                        aim.name(), s.verdict(), s.node(), s.struckPart(), s.face(), s.zone(), s.plate() ? 1 : 0,
                        s.nominal(), s.los(), s.pen(), s.ratio(), s.incidence(), s.surfaceIncidence(), arrival.damage(),
                        s.hullDamage(), s.partDamage(), s.partDamaged(), s.stock(), s.local().x, s.local().y, s.local().z, ray.offset()});
            }
        }
        return rows;
    }
}
