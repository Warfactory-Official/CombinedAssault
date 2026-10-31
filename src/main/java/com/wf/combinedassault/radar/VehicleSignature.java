package com.wf.combinedassault.radar;

import com.wf.wflib.recon.api.Signature;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.AABB;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.camo.CamoLayer;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;

/**
 * Signature = f(hull size, datapack {@code radar_cross_section}, engine state, camo layer); no new
 * persisted field. One-way coupling: reads {@link CamoLayer#footprint()}; camo package knows nothing about recon.
 */
final class VehicleSignature {

    /** Reference frontal area (signature=1.0), sq. blocks; matches WFLib SignatureRegistry scale. */
    private static final float REFERENCE_AREA = 4.0f;
    /** Full-coverage camo attenuation. Net range ~ signature^(1/4); 16 = full coverage halves detection range. */
    private static final float CAMO_FULL_ATTENUATION = 16.0f;
    /** Engine speed at full thermal/acoustic terms; matches the vehicle's own engine-speed clamp. */
    private static final float MAX_ENGINE_SPEED = 60.0f;
    /** Cold-hull thermal/sq.block; small by design: engine, not shape, drives IR signature. */
    private static final float HULL_THERMAL = 0.02f;
    /** Full-engine thermal; below rocket motor (WFLib table: 2.25), above a body. */
    private static final float ENGINE_THERMAL = 1.0f;
    /** Full-engine acoustic signature; inert pending an acoustic propagation model, kept correct ahead of one. */
    private static final float ENGINE_ACOUSTIC = 1.5f;
    /**
     * Cavitation acoustic add-on, wet hull only; above the engine term (hydrophone hears the prop, not the
     * machinery).
     */
    private static final float CAVITATION_ACOUSTIC = 2.0f;
    /** Blocks per tick of ground speed at which the cavitation term is at full. */
    private static final float CAVITATION_SPEED = 0.6f;
    /** Seismic per sq.block per block/tick ground speed; ground contact only (helicopter couples to nothing). */
    private static final float GROUND_SEISMIC = 0.02f;
    /** Radiating-radar EM signature; EMCON gates emission, not this constant. */
    private static final float RADAR_EM = 2.0f;

    private VehicleSignature() {
    }

    static Signature of(AbstractVehicle vehicle) {
        double surface = hullSurface(vehicle);
        // Mean projected area over all aspects = surface/4 for any convex body; aspect-unknown default.
        float projected = (float) (surface * 0.25 / REFERENCE_AREA);

        // Datapack RCS multiplies detection distance linearly; net range ~ signature^(1/4) => apply as 4th power here.
        float treatment = vehicle.physicsEngine == null ? 1.0f : vehicle.physicsEngine.radarCrossSection;
        treatment = Math.max(0.0f, treatment);
        float rcs = projected * treatment * treatment * treatment * treatment;

        float heat = Math.min(1.0f, Math.max(0.0f, vehicle.getEngineSpeed()) / MAX_ENGINE_SPEED);
        float thermal = HULL_THERMAL * projected * REFERENCE_AREA + ENGINE_THERMAL * heat;
        float acoustic = ENGINE_ACOUSTIC * heat + cavitation(vehicle);
        float seismic = vehicle.onGround()
                ? (float) (GROUND_SEISMIC * surface * 0.25 * vehicle.getDeltaMovement().length())
                : 0.0f;
        float em = hasEmitter(vehicle) ? RADAR_EM : 0.0f;

        // Camo attenuates both RCS and thermal (physical covering hides both bands).
        float attenuation = camoAttenuation(vehicle, surface);
        return new Signature(Math.max(0.0f, rcs * attenuation), seismic,
                Math.max(0.0f, thermal * attenuation), acoustic, em);
    }

    /**
     * @return screw cavitation noise, 0 when dry/stopped. Speed = last-tick displacement, not
     * {@code getDeltaMovement} (a physics-solver impulse that deadbands away at the speeds a large hull makes).
     */
    private static float cavitation(AbstractVehicle vehicle) {
        if (!vehicle.level().getFluidState(vehicle.blockPosition()).is(FluidTags.WATER)) {
            return 0.0f;
        }
        double dx = vehicle.getX() - vehicle.xo;
        double dz = vehicle.getZ() - vehicle.zo;
        float speed = (float) Math.sqrt(dx * dx + dz * dz);
        return CAVITATION_ACOUSTIC * Math.min(1.0f, speed / CAVITATION_SPEED);
    }

    /**
     * @return camo coverage fraction [0,1]. {@link CamoLayer#footprint()} is a running sum (scale^2),
     * already sq.blocks.
     */
    static float camoCoverage(AbstractVehicle vehicle) {
        return camoCoverage(vehicle, hullSurface(vehicle));
    }

    private static float camoCoverage(AbstractVehicle vehicle, double surface) {
        CamoLayer camo = vehicle.getCamoLayer();
        if (camo == null || camo.isEmpty() || surface <= 0.0) {
            return 0.0f;
        }
        return (float) Math.min(1.0, camo.footprint() / surface);
    }

    private static float camoAttenuation(AbstractVehicle vehicle, double surface) {
        float coverage = camoCoverage(vehicle, surface);
        if (coverage <= 0.0f) {
            return 1.0f;
        }
        return 1.0f / (1.0f + coverage * (CAMO_FULL_ATTENUATION - 1.0f));
    }

    /**
     * @return hull surface, sq.blocks. Prefers the main-cube OBB (turns with the vehicle, yaw-stable area);
     * AABB fallback grows/shrinks with yaw, tolerable only as a coverage-fraction denominator.
     */
    private static double hullSurface(AbstractVehicle vehicle) {
        VehicleCubeOBB hull = vehicle.getMainCubeOBB();
        double w;
        double h;
        double d;
        if (hull != null && hull.width > 0.0 && hull.height > 0.0 && hull.depth > 0.0) {
            w = hull.width;
            h = hull.height;
            d = hull.depth;
        } else {
            AABB box = vehicle.getBoundingBox();
            w = box.getXsize();
            h = box.getYsize();
            d = box.getZsize();
        }
        return Math.max(1.0, 2.0 * (w * h + w * d + h * d));
    }

    /**
     * @return EMCON active: any switched-on, powered, emitting-role radar. Receivers excluded by design;
     * listening is never an emission.
     */
    static boolean radiating(AbstractVehicle vehicle) {
        if (!vehicle.hasPower()) {
            return false;
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof RadarUnit radar && radar.isOn() && radar.getReconRole().emits()) {
                return true;
            }
        }
        return false;
    }

    /** @return true if vehicle carries any emitting-role radar (pre-EMCON). Receiver-only hull => false. */
    private static boolean hasEmitter(AbstractVehicle vehicle) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof RadarUnit radar && radar.getReconRole().emits()) {
                return true;
            }
        }
        return false;
    }

}
