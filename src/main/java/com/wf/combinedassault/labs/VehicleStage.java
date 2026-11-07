package com.wf.combinedassault.labs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.wflib.armor.api.hull.HullFace;
import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.labs.api.LabContext;
import com.wf.wflib.labs.api.ScenarioStage;
import com.wf.wflib.labs.api.ScenarioState;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * One vehicle plate through {@link VehicleShots#shoot}, the lab's strike path. Deterministic: pPass is 0 or 1.
 * Spec: {@code vehicle}, {@code face} (hull face), optional {@code part} (zone name or part id), {@code angle}
 * (deg from the face normal, 0). speedOut = speed * sqrt(payload fraction); the armour model has no residual velocity.
 */
public final class VehicleStage implements ScenarioStage {

    private static final Set<String> KEYS = Set.of("vehicle", "face", "part", "angle");

    @Override
    public String id() {
        return "vehicle";
    }

    @Override
    public Result apply(LabContext ctx, ScenarioState in, JsonObject spec) {
        for (String key : spec.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown key '" + key + "', valid: " + new java.util.TreeSet<>(KEYS));
            }
        }
        Map<ResourceLocation, BaseVehicleData> armoured = new TreeMap<>();
        VehicleRig.roster(null).forEach((id, data) -> {
            if (data.getArmor() != null && data.getArmor().isDefined()) {
                armoured.put(id, data);
            }
        });
        ResourceLocation id = ResourceLocation.parse(string(spec, "vehicle", null));
        BaseVehicleData data = armoured.get(id);
        if (data == null) {
            throw new IllegalArgumentException("unknown vehicle '" + id + "', valid: " + armoured.keySet());
        }
        HullFace face = face(string(spec, "face", null));
        String part = string(spec, "part", "");
        double angle = spec.has("angle") ? spec.get("angle").getAsDouble() : 0;
        if (!(angle >= 0 && angle < 90)) {
            throw new IllegalArgumentException("angle must be in [0, 90) deg, got " + angle);
        }
        HullProfile profile = data.getArmor();
        HullProfile.Zone zone = null;
        boolean ownFaces = false;
        if (!part.isEmpty()) {
            for (HullProfile.Zone z : profile.zones) {
                if (z.name().equals(part)) {
                    zone = z;
                }
            }
            ownFaces = profile.parts.containsKey(part);
            if (zone == null && !ownFaces) {
                List<String> valid = new ArrayList<>(profile.parts.keySet());
                profile.zones.forEach(z -> valid.add(z.name()));
                throw new IllegalArgumentException("unknown part '" + part + "', valid: " + valid);
            }
        }
        if (zone == null && !ownFaces && profile.nominal(face) <= 0) {
            List<String> valid = new ArrayList<>();
            for (HullFace f : HullFace.values()) {
                if (profile.nominal(f) > 0) {
                    valid.add(f.name().toLowerCase(Locale.ROOT));
                }
            }
            throw new IllegalArgumentException("vehicle '" + id + "' has no armour on face '" + name(face) + "', valid: " + valid);
        }

        VehicleRig rig = VehicleRig.spawn(ctx.level(), id, data, ownFaces || zone != null && zone.part() != null);
        try {
            Vec3 aim = rig.aims.get(0).point();
            String aimPart = ownFaces ? part : zone != null ? zone.part() : null;
            if (aimPart != null) {
                aim = rig.aims.stream().filter(a -> a.name().equals(aimPart)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("part '" + aimPart + "' has no hit anchor on " + id)).point();
            } else if (zone != null) {
                aim = new Vec3((zone.min()[0] + zone.max()[0]) / 2, (zone.min()[1] + zone.max()[1]) / 2, (zone.min()[2] + zone.max()[2]) / 2);
            }
            double[] base = bearing(face, 0);
            VehicleShots.Ray ray = VehicleShots.ray(rig, aim, base[0], base[1]);
            if (angle > 0 && ray.hit() != null) {
                double[] tilted = bearing(face, angle);
                ray = VehicleShots.ray(rig, ray.hit().point(), tilted[0], tilted[1]);
            }
            double perTick = in.speedMs() / 20.0;
            Vec3 arrival = ray.dir().scale(perTick);
            Threat threat = Rounds.threat(in.round(), arrival);
            if (threat.penetrationMm() <= 0) {
                return result(0, 0, "STOP", id, face, part, angle, "NONE", 0, 0, 0, 0, 0, 0, 0, 0, profile.ricochetAngle, "", false);
            }
            VehicleShots.Result s = VehicleShots.shoot(ctx.level(), rig, ray, arrival, threat, 1.0f);
            if (s.verdict().equals("MISS") || s.verdict().equals("NO_MODEL") || s.verdict().equals("UNARMOURED")) {
                throw new IllegalArgumentException(id + " " + name(face) + (part.isEmpty() ? "" : " " + part) + " @ " + angle
                        + " deg: ray resolved to " + s.verdict());
            }
            if (zone != null && !zone.name().equals(s.zone()) || ownFaces && !part.equals(s.struckPart())) {
                throw new IllegalArgumentException("ray for part '" + part + "' struck zone '" + s.zone() + "' part '" + s.struckPart() + "'");
            }
            boolean through = VehicleShots.through(s.verdict());
            double payload = through ? s.hullDamage() : 0;
            String verdict = through ? "PERFORATE" : s.verdict().equals("RICOCHET") ? "RICOCHET" : "STOP";
            return result(through ? in.speedMs() * Math.sqrt(payload) : 0, through ? 1 : 0, verdict, id, face, part, angle,
                    s.verdict(), s.nominal(), s.los(), s.pen(), s.ratio(), s.incidence(), s.surfaceIncidence(), payload,
                    s.verdict().equals("RICOCHET") ? 1 : 0, profile.ricochetAngle, s.zone(), s.plate());
        } finally {
            rig.vehicle.discard();
        }
    }

    private static Result result(double speedOut, double pPass, String verdict, ResourceLocation id,
                                 HullFace face, String part, double angle, String outcome, double nominal, double los,
                                 double pen, double ratio, double incidence, double surfaceIncidence, double payload,
                                 double ricochet, double ricochetAngle, String zone, boolean plate) {
        Map<String, Object> detail = new TreeMap<>();
        detail.put("outcome", outcome);
        detail.put("ke_mm", nominal);
        detail.put("los_mm", los);
        detail.put("pen_mm", pen);
        detail.put("ratio", ratio);
        detail.put("incidence_deg", incidence);
        detail.put("surface_incidence_deg", surfaceIncidence);
        detail.put("payload_fraction", payload);
        detail.put("ricochet", ricochet);
        detail.put("ricochet_angle_deg", ricochetAngle);
        detail.put("zone", zone);
        detail.put("plate", plate ? 1 : 0);
        String label = String.format(Locale.ROOT, "%s %s%s %.0f mm @ %.0f deg", id, name(face), part.isEmpty() ? "" : " " + part,
                nominal, angle);
        return new Result(speedOut, pPass, verdict, label, detail);
    }

    /** Shooter azimuth, elevation (deg) at {@code angle} off the face normal. */
    private static double[] bearing(HullFace face, double angle) {
        return switch (face) {
            case FRONT -> new double[]{angle, 0};
            case SIDE -> new double[]{90 - angle, 0};
            case REAR -> new double[]{180 - angle, 0};
            case TOP -> new double[]{0, 90 - angle};
            case BOTTOM -> new double[]{0, angle - 90};
        };
    }

    private static HullFace face(String name) {
        for (HullFace f : HullFace.values()) {
            if (name(f).equals(name)) {
                return f;
            }
        }
        List<String> valid = new ArrayList<>();
        for (HullFace f : HullFace.values()) {
            valid.add(name(f));
        }
        throw new IllegalArgumentException("unknown face '" + name + "', valid: " + valid);
    }

    private static String name(HullFace f) {
        return f.name().toLowerCase(Locale.ROOT);
    }

    private static String string(JsonObject spec, String key, String fallback) {
        JsonElement e = spec.get(key);
        if (e == null) {
            if (fallback == null) {
                throw new IllegalArgumentException("missing key '" + key + "'");
            }
            return fallback;
        }
        return e.getAsString();
    }
}
