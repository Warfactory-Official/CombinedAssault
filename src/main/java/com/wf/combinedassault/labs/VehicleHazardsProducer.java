package com.wf.combinedassault.labs;

import com.wf.combinedassault.ballistics.DamageSystem;
import com.wf.combinedassault.ballistics.ThermalAttacks;
import com.wf.combinedassault.ballistics.component.VehicleMobility;
import com.wf.combinedassault.vehicle.VehicleDamageTypes;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.damage.api.DamageClass;
import com.wf.wflib.damage.api.WFDamageSources;
import com.wf.wflib.damage.api.event.ThermalAttackEvent;
import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.labs.api.LabProducer;
import com.wf.wflib.labs.api.LabSink;
import com.wf.wflib.labs.api.LabTable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Non-ballistic threats CA applies to a vehicle, each through its real entry point on a live (never ticked) vehicle:
 * blast {@code DamageSystem.hurt} explosion source, thermite {@code ThermalAttacks.onThermal}, collision
 * {@code DamageSystem.impactHurt}, fall {@code DamageSystem.landingHurt}, laser {@code DamageSystem.hurt} laser source.
 * Rows with {@code modelled} 0 name threats CA has no code for. Applications repeat to {@value #CAP}.
 */
public final class VehicleHazardsProducer implements LabProducer {

    static final int CAP = 30;

    static final LabTable TABLE = tableOf();

    private static LabTable tableOf() {
        LabTable.Builder b = LabTable.of("combinedassault:vehicle_hazards")
                .dimStr("vehicle").dimStr("threat").dimNum("intensity", "").dimStr("aspect")
                .measure("modelled", "").measureStr("note").measure("hull_damage", "").measureStr("struck_system");
        for (var kind : SystemsProbe.KINDS) {
            b.measure("loss_" + kind.code, "");
        }
        return b.measure("mobility_after_first", "").measure("applications_to_disable", "")
                .measure("applications_to_destroy", "").build();
    }

    private static final String[][] ASPECTS = {{"front", "0"}, {"left", "90"}, {"rear", "180"}, {"right", "270"}};

    private static final Object[][] NOT_MODELLED = {
            {"fire_over_time", "no burning state on a vehicle; fire only reaches crew (thermite, breech)"},
            {"emp", "no electronics system or EMP handler on vehicles"},
            {"water", "no drowning or flooding damage; only wading flag"},
            {"mine", "no mine handler; mines are wflib explosions, see blast"}};

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

    private record Threat(String name, double[] intensities, boolean aspected,
                          Applier applier) {
    }

    @FunctionalInterface
    private interface Applier {
        void apply(AbstractVehicle vehicle, Vec3 at, double intensity);
    }

    private static List<Threat> threats() {
        return List.of(
                new Threat("blast", new double[]{5, 20, 50, 100, 200, 500}, true, (v, at, i) ->
                        DamageSystem.hurt(VehicleDamageTypes.Sources.explosion(v.level().registryAccess(), null, null, at), (float) i, v)),
                new Threat("thermite", new double[]{10, 50, 200}, true, (v, at, i) ->
                        ThermalAttacks.onThermal(new ThermalAttackEvent(v, at, (float) i, true, 0, null, v))),
                new Threat("laser", new double[]{10, 50, 200}, true, (v, at, i) -> {
                    DamageSource s = WFDamageSources.create(v.level(), DamageClass.LASER, null, null);
                    DamageSystem.hurt(s, (float) i, v);
                }),
                new Threat("collision", new double[]{2, 5, 10, 20}, false, (v, at, i) -> DamageSystem.impactHurt(i / 20.0, at, v)),
                new Threat("fall", new double[]{10, 15, 20, 30}, false, (v, at, i) -> DamageSystem.landingHurt(v, i / 20.0)));
    }

    @Override
    public void produce(LabContext ctx, LabSink sink) {
        for (Map.Entry<ResourceLocation, BaseVehicleData> e : VehicleRig.roster(ctx.param("vehicles", null)).entrySet()) {
            VehicleRig probe = VehicleRig.spawn(ctx.level(), e.getKey(), e.getValue(), false);
            boolean armoured = probe.vehicle.armorProfile != null && probe.vehicle.armorProfile.isDefined();
            probe.vehicle.discard();
            if (!armoured && ctx.paramNum("unarmoured", 0) == 0) {
                continue;
            }
            for (Object[] n : NOT_MODELLED) {
                sink.row(e.getKey().toString(), n[0], 0.0, "", 0, n[1], 0, "", nan(), nan(), nan(), nan(), nan(), nan(), nan(),
                        nan(), nan(), nan(), nan(), nan(), nan(), null, null);
            }
            for (Threat t : threats()) {
                for (double intensity : t.intensities()) {
                    for (String[] aspect : t.aspected() ? ASPECTS : new String[][]{{"hull", "0"}}) {
                        row(ctx, e.getKey(), e.getValue(), t, intensity, aspect, sink);
                    }
                }
            }
        }
    }

    private static double nan() {
        return Double.NaN;
    }

    private static void row(LabContext ctx, ResourceLocation id, BaseVehicleData data, Threat t, double intensity, String[] aspect,
                            LabSink sink) {
        VehicleRig rig = VehicleRig.spawnLive(ctx.level(), id, data, false);
        try {
            AbstractVehicle v = rig.vehicle;
            Vec3 at = t.aspected()
                    ? VehicleShots.ray(rig, rig.aims.get(0).point(), Double.parseDouble(aspect[1]), 0).hit() == null ? v.position()
                    : VehicleShots.ray(rig, rig.aims.get(0).point(), Double.parseDouble(aspect[1]), 0).hit().point()
                    : v.position().add(0, 0.2, 0);
            double[][] before = SystemsProbe.totals(v);
            Map<String, Double> labels = SystemsProbe.byLabel(v);
            double[] lost = new double[SystemsProbe.KINDS.length];
            String struck = "";
            double mobility = 1;
            double hull = 0;
            int disable = 0, destroy = 0;
            String note = "";
            for (int n = 1; n <= CAP && !v.isDestroyed(); n++) {
                float health = v.getHealth();
                try {
                    t.applier().apply(v, at, intensity);
                } catch (RuntimeException ex) {
                    note = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                    break;
                }
                if (n == 1) {
                    lost = SystemsProbe.lost(before, SystemsProbe.totals(v));
                    struck = SystemsProbe.worstCell(v, labels);
                    mobility = VehicleMobility.of(v).speedScale();
                    hull = health - v.getHealth();
                }
                if (disable == 0 && SystemsProbe.mobilityKilled(v)) {
                    disable = n;
                }
                if (v.isDestroyed() || SystemsProbe.catastrophic(v)) {
                    destroy = n;
                    break;
                }
            }
            List<Object> row = new ArrayList<>(List.of(id.toString(), t.name(), intensity, aspect[0], 1, note, hull, struck));
            for (double l : lost) {
                row.add(l);
            }
            row.add((double) mobility);
            row.add(disable == 0 ? null : disable);
            row.add(destroy == 0 ? null : destroy);
            sink.row(row.toArray());
        } finally {
            rig.vehicle.discard();
        }
    }

}
