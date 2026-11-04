package com.wf.combinedassault.labs;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.KineticPresets;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Entry for parity tests: the lab's shot on a live vehicle, and the producer's own row. */
public final class VehicleLab {

    /**
     * @param start launch point on the lab ray
     * @param speed blocks/tick on arrival at the range
     */
    public record Shot(Vec3 start, Vec3 dir, double speed, VehicleRig rig, VehicleShots.Ray ray, ResourceLocation round) {
    }

    public record Expected(String verdict, double hullDamage, Vec3 hitWorld, VehicleShots.Result detail, float damageIn) {
    }

    public record Row(String verdict, Vec3 local) {
    }

    private VehicleLab() {
    }

    public static Shot aim(ServerLevel level, ResourceLocation id, AbstractVehicle live, String round, double azimuth,
                           double elevation, double range) {
        VehicleRig rig = VehicleRig.of(id, live, false);
        KineticPreset preset = KineticPresets.get(ResourceLocation.parse(round));
        Vec3 arrival = new Flight(preset, preset.muzzleSpeed() * 20.0).arrival(range);
        VehicleShots.Ray ray = VehicleShots.ray(rig, rig.aims.get(0).point(), azimuth, elevation);
        return new Shot(ray.start(), ray.dir(), arrival.length(), rig, ray, preset.id());
    }

    public static Expected expect(ServerLevel level, Shot shot, Vec3 velocity, Threat threat) {
        KineticPreset preset = KineticPresets.get(shot.round());
        float damage = Rounds.impactDamage(preset, velocity);
        VehicleShots.Result r = VehicleShots.shoot(level, shot.rig(), shot.ray(), velocity, threat, damage);
        return new Expected(r.verdict(), r.hullDamage(), shot.ray().hit() == null ? Vec3.ZERO : shot.ray().hit().point(), r, damage);
    }

    public static Row producerRow(ServerLevel level, ResourceLocation id, String round, double range, double azimuth,
                                  double elevation) {
        List<Object[]> rows = new ArrayList<>();
        Map<String, String> params = Map.of("vehicles", id.toString(), "aims", "hull",
                "azimuth_min", Double.toString(azimuth), "azimuth_max", Double.toString(azimuth),
                "elevation_min", Double.toString(elevation), "elevation_max", Double.toString(elevation));
        LabContext ctx = LabContext.of(level, new double[]{range}, round::equals, params);
        new VehicleProducer().produce(ctx, rows::add);
        if (rows.isEmpty()) {
            return null;
        }
        Object[] r = rows.get(0);
        return new Row((String) r[7], new Vec3((Double) r[24], (Double) r[25], (Double) r[26]));
    }

    /** Nanoseconds per {@code Spall.onPenetration} for the shot, {@code n} calls. */
    public static double spallNanos(ServerLevel level, Shot shot, Vec3 velocity, Threat threat, float damage, int n) {
        StrikeSource source = new StrikeSource(level, threat, shot.ray().start(), velocity);
        Vec3 hit = shot.ray().hit().point();
        com.wf.wflib.armor.api.hull.HullResult r = com.wf.combinedassault.ballistics.Ballistics.resolve(shot.rig().vehicle, source,
                damage, hit, 1.0f);
        if (r == null || !r.outcome().isThrough()) {
            throw new IllegalStateException("shot does not penetrate: " + (r == null ? "no model" : r.outcome()));
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            com.wf.combinedassault.ballistics.spall.Spall.onPenetration(shot.rig().vehicle, source, r, hit, damage, damage);
        }
        return (System.nanoTime() - t0) / (double) n;
    }
}
