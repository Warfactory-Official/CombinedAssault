package com.wf.combinedassault.ballistics.addon;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.EraGrid;
import com.wf.wflib.armor.api.hull.EraType;
import com.wf.wflib.armor.api.hull.HullMesh;
import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.wflib.armor.api.hull.PlateSpec;
import com.wf.wflib.armor.api.hull.WalkResult;
import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.damage.api.ThreatKind;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Integral ERA / NERA arrays: factory-fitted bricks of a plate ({@code armor.plates} {@code era} layer), their spent
 * state in {@link AddonArmorLayer}. The plate walk reads the state and lists the bricks a round sets off; they are
 * spent here, once, when the shot is final. Server side.
 */
public final class IntegralEra {

    /** Hit this far beyond the strike point looks for the struck surface. */
    private static final double REACH = 6.0;

    private IntegralEra() {
    }

    /** Spends the bricks {@code walk} set off, each blast at {@code at}; one hop of sympathetic neighbours. */
    public static void commit(AbstractVehicle vehicle, @Nullable WalkResult walk, Vec3 at) {
        if (walk == null || walk.eraHits().isEmpty() || !(vehicle.level() instanceof ServerLevel level)) {
            return;
        }
        for (WalkResult.EraHit hit : walk.eraHits()) {
            spend(vehicle, level, hit.plate(), hit.cell(), hit.type(), at);
        }
    }

    /**
     * An HE round striking the hull sets off the bricks under it when its calibre reaches the type's HE initiation
     * (autocannon HE strips the array): {@link ThreatKind#HE} / {@link ThreatKind#HESH} only, penetrating rounds
     * are walked.
     */
    public static void strike(AbstractVehicle vehicle, Threat threat, Vec3 start, Vec3 velocity) {
        if ((threat.kind() != ThreatKind.HE && threat.kind() != ThreatKind.HESH)
                || !(vehicle.level() instanceof ServerLevel level) || velocity.lengthSqr() < 1.0e-12) {
            return;
        }
        HullProfile profile = vehicle.hullProfile();
        HullMesh mesh = vehicle.hits();
        if (profile == null || mesh == null || profile.plates.isEmpty()) {
            return;
        }
        HullMesh.Strike hit = mesh.surface(start, start.add(velocity.normalize().scale(velocity.length() + REACH)));
        if (hit == null || hit.plate() == null) {
            return;
        }
        PlateSpec spec = profile.plates.get(hit.plate());
        EraType type = spec == null ? null : spec.era();
        if (type == null || !type.initiates(threat)) {
            return;
        }
        spend(vehicle, level, hit.plate(), spec.eraCell(mesh, hit.node(), hit.point(), hit.normal()), type, hit.point());
    }

    private static void spend(AbstractVehicle vehicle, ServerLevel level, String plate, int cell, EraType type, Vec3 at) {
        AddonArmorLayer layer = vehicle.addonArmor();
        if (!layer.spendArrayCell(plate, cell)) {
            return;
        }
        HullProfile profile = vehicle.hullProfile();
        float backing = EraBlast.backing(profile, plate);
        EraBlast.detonate(level, at, type, backing);
        if (type.sympathetic() <= 0) {
            return;
        }
        int cells = profile.plates.get(plate).eraCells();
        int[] around = new int[8];
        int n = EraGrid.neighbours(cell, cells, around);
        for (int i = 0; i < n; i++) {
            if (level.getRandom().nextFloat() < type.sympathetic() && layer.spendArrayCell(plate, around[i])) {
                EraBlast.detonate(level, at, type, backing);
            }
        }
    }
}
