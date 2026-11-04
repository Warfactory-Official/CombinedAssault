package com.wf.combinedassault.labs;

import com.wf.combinedassault.ballistics.DamageSystem;
import com.wf.combinedassault.vehicle.VehicleDamageTypes;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.labs.api.LabProducer;
import com.wf.wflib.labs.api.LabSink;
import com.wf.wflib.labs.api.LabTable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

/**
 * What reaches a seated occupant (a mob: vanilla health; players' wounds and armour run in WFMedical in game), through
 * the real paths: a ballistic hit {@code DamageSystem.hurt} (debris behind a defeated plate), blast and breech fire
 * on the occupant under {@code protect_passenger}, the wreck's dismount + blast + fire.
 */
public final class CrewExposureProducer implements LabProducer {

    private static final Set<String> ROUNDS = Set.of("wflib:5.56x45/ball", "wflib:7.62x51/ball", "wflib:12.7x99/ball",
            "wflib:125mm/apfsds", "wflib:125mm/heat", "wflib:rpg7/heat");
    private static final String[][] ASPECTS = {{"front", "0"}, {"left", "90"}};

    static final LabTable TABLE = LabTable.of("combinedassault:crew_exposure")
            .dimStr("vehicle").dimStr("threat").dimStr("source").dimNum("intensity", "").dimStr("aspect")
            .measure("modelled", "").measureStr("verdict").measure("occupant_damage", "hp").measure("occupant_dead", "")
            .measure("burning", "").measureStr("reason")
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
        double range = ctx.paramNum("range", 100);
        VehicleProducer.Sweep sweep = VehicleProducer.sweep(ctx, new double[]{range});
        for (Map.Entry<ResourceLocation, BaseVehicleData> e : VehicleRig.roster(ctx.param("vehicles", null)).entrySet()) {
            VehicleRig probe = VehicleRig.spawn(ctx.level(), e.getKey(), e.getValue(), false);
            boolean armoured = probe.vehicle.armorProfile != null && probe.vehicle.armorProfile.isDefined();
            probe.vehicle.discard();
            if (!armoured && ctx.paramNum("unarmoured", 0) == 0) {
                continue;
            }
            String vehicle = e.getKey().toString();
            for (VehicleProducer.Launch launch : sweep.launches()) {
                VehicleProducer.Arrival arrival = launch.byRange().get(0);
                if (arrival == null || !ROUNDS.contains(launch.preset().id().toString()) || !launch.launcher().id().startsWith("wflib:")) {
                    continue;
                }
                for (String[] aspect : ASPECTS) {
                    ballistic(ctx, e.getKey(), e.getValue(), launch, arrival, aspect, range, sink);
                }
            }
            for (double amount : new double[]{20, 100}) {
                direct(ctx, e.getKey(), e.getValue(), "blast", amount, sink, (v, z) -> VehicleDamageTypes.Sources.explosion(
                        v.level().registryAccess(), null, null, v.position().add(0, 1, 0)));
            }
            direct(ctx, e.getKey(), e.getValue(), "breech_fire", 6, sink, (v, z) -> VehicleDamageTypes.Sources.breechFire(
                    v.level().registryAccess(), v, null));
            wreck(ctx, e.getKey(), e.getValue(), sink);
            sink.row(vehicle, "emp", "", 0.0, "", 0, "", 0.0, 0, 0, "no EMP handler touches vehicles or occupants");
        }
    }

    private static Zombie seat(AbstractVehicle vehicle, Vec3 at) {
        Zombie z = EntityType.ZOMBIE.create(vehicle.level());
        z.setNoAi(true);
        z.setPos(at);
        vehicle.level().addFreshEntity(z);
        if (!z.startRiding(vehicle, true)) {
            z.discard();
            return null;
        }
        z.setPos(at);
        return z;
    }

    private static void ballistic(LabContext ctx, ResourceLocation id, BaseVehicleData data, VehicleProducer.Launch launch,
                                  VehicleProducer.Arrival arrival, String[] aspect, double range, LabSink sink) {
        VehicleRig rig = VehicleRig.spawnLive(ctx.level(), id, data, false);
        try {
            VehicleShots.Ray ray = VehicleShots.ray(rig, rig.aims.get(0).point(), Double.parseDouble(aspect[1]), 0);
            if (ray.hit() == null) {
                return;
            }
            VehicleShots.Result lab = VehicleShots.shoot(ctx.level(), rig, ray, arrival.velocity(), arrival.threat(), arrival.damage());
            Zombie z = seat(rig.vehicle, ray.hit().point().add(ray.dir().scale(1.2)));
            if (z == null) {
                sink.row(id.toString(), "ballistic", launch.preset().id().toString(), range, aspect[0], 1, lab.verdict(), 0.0, 0, 0,
                        "occupant could not be seated");
                return;
            }
            SystemsProbe.reset(rig.vehicle);
            DamageSystem.hurt(new StrikeSource(ctx.level(), arrival.threat(), ray.start(), ray.dir().scale(2 * rig.reach)),
                    arrival.damage(), rig.vehicle);
            double loss = z.getMaxHealth() - z.getHealth();
            boolean through = VehicleShots.through(lab.verdict());
            sink.row(id.toString(), "ballistic", launch.preset().id().toString(), range, aspect[0], 1, lab.verdict(), loss,
                    z.isAlive() ? 0 : 1, z.isOnFire() ? 1 : 0, through ? (loss > 0 ? "debris behind the defeated plate" : "debris missed the occupant")
                            : "no penetration: nothing reaches crew");
            z.discard();
        } finally {
            rig.vehicle.discard();
        }
    }

    private static void direct(LabContext ctx, ResourceLocation id, BaseVehicleData data, String threat, double amount, LabSink sink,
                               java.util.function.BiFunction<AbstractVehicle, Zombie, DamageSource> source) {
        VehicleRig rig = VehicleRig.spawnLive(ctx.level(), id, data, false);
        try {
            Zombie z = seat(rig.vehicle, rig.vehicle.position().add(0, 1.2, 0));
            if (z == null) {
                sink.row(id.toString(), threat, "", amount, "", 1, "", 0.0, 0, 0, "occupant could not be seated");
                return;
            }
            z.hurt(source.apply(rig.vehicle, z), (float) amount);
            double loss = z.getMaxHealth() - z.getHealth();
            sink.row(id.toString(), threat, "", amount, "", 1, "", loss, z.isAlive() ? 0 : 1, z.isOnFire() ? 1 : 0,
                    loss > 0 ? "reaches seated crew" : rig.vehicle.protectPassenger ? "protect_passenger: occupants take no damage" : "no damage");
            z.discard();
        } finally {
            rig.vehicle.discard();
        }
    }

    private static void wreck(LabContext ctx, ResourceLocation id, BaseVehicleData data, LabSink sink) {
        VehicleRig rig = VehicleRig.spawnLive(ctx.level(), id, data, false);
        try {
            Zombie z = seat(rig.vehicle, rig.vehicle.position().add(0, 1.2, 0));
            if (z == null) {
                sink.row(id.toString(), "wreck", "", 0.0, "", 1, "", 0.0, 0, 0, "occupant could not be seated");
                return;
            }
            rig.vehicle.hurt(VehicleDamageTypes.Sources.explosion(rig.vehicle.level().registryAccess(), null, null,
                    rig.vehicle.position().add(0, 1, 0)), rig.vehicle.getMaxHealth() * 100);
            double loss = z.getMaxHealth() - z.getHealth();
            sink.row(id.toString(), "wreck", "", 0.0, "", 1, "", loss, z.isAlive() ? 0 : 1, z.isOnFire() ? 1 : 0,
                    rig.vehicle.isDestroyed() ? "dismounts into the wreck blast; burning accrues per tick (not simulated)"
                            : "vehicle survived");
            z.discard();
        } finally {
            rig.vehicle.discard();
        }
    }
}
