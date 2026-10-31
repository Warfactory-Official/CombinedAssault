package com.wf.combinedassault.ballistics.addon;

import com.wf.wflib.damage.api.Threat;
import com.wf.wflib.damage.api.ThreatKind;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/** What one panel does to one round. No world access. */
public final class AddonPhysics {

    /** ERA plates move across the jet: effect ~ sin(obliquity), full from 60 deg. */
    private static final float FULL_EFFECT_SIN = Mth.sin((float) Math.toRadians(60));
    private static final float MIN_OBLIQUITY_EFFECT = 0.3f;
    /** Hit within this fraction of a cell side from its edge: half the plate area engages. */
    public static final float EDGE_BAND = 0.12f;
    private static final float EDGE_EFFECT = 0.5f;

    /** RPG-7 class: ogive crushed between bars shorts the piezo circuit. */
    public static final float RPG_MIN_MM = 60.0f;
    public static final float RPG_MAX_MM = 110.0f;
    public static final float SHORT_CHANCE = 0.8f;
    /** Precursor probe usually survives the wedge. */
    public static final float TANDEM_DUD_CHANCE = 0.1f;
    /** A crushed but still firing liner: jet disrupted. */
    private static final float CRUSHED_JET = 0.5f;
    private static final float TANDEM_SLAT_JET = 0.9f;
    /** Nose radius / body radius. */
    private static final float TIP_FRACTION = 0.15f;
    /** RHA-equivalent a penetrator spends on a bar it strikes. */
    private static final float BAR_RHA_MM = 15.0f;
    /** Single-charge jet past optimum standoff: {@code 1 / (1 + k * gap)}, gap in blocks. */
    private static final float STANDOFF_LOSS = 0.6f;

    private AddonPhysics() {
    }

    /** @param penetrationScale multiplies the jet/rod's penetration behind the brick */
    public record EraOutcome(boolean initiated, float penetrationScale) {
    }

    public static EraOutcome era(EraSpec spec, Threat threat, float cosIncidence, float edgeFraction) {
        boolean initiated = switch (threat.kind()) {
            case HEAT, TANDEM_HEAT -> true;
            case KINETIC -> threat.caliberMm() >= spec.kineticInitiationMm();
            case HE -> threat.caliberMm() >= EraSpec.HE_INITIATION_MM;
            case SMALL_ARMS -> false;
        };
        if (!initiated) {
            return new EraOutcome(false, 1.0f);
        }
        float cut = switch (threat.kind()) {
            case HEAT -> spec.heatCut();
            case KINETIC -> spec.kineticCut();
            // Precursor spends the brick; main jet passes a hole.
            case TANDEM_HEAT, HE, SMALL_ARMS -> 0.0f;
        };
        float cos = Mth.clamp(Math.abs(cosIncidence), 0, 1);
        float sin = Mth.sqrt(1 - cos * cos);
        float effect = Mth.clamp(sin / FULL_EFFECT_SIN, MIN_OBLIQUITY_EFFECT, 1.0f);
        if (edgeFraction < EDGE_BAND) {
            effect *= EDGE_EFFECT;
        }
        return new EraOutcome(true, 1.0f - cut * effect);
    }

    public enum SlatVerdict {
        /** Through a gap untouched. */
        MISS,
        /** Bullet stopped by a bar. */
        STOPPED,
        /** Penetrator broke a bar and kept going. */
        PERFORATED,
        /** Warhead fired on the cage. */
        BURST,
        /** Fuze failed in the cage. */
        DUD
    }

    /**
     * @param lower/upper   present bars (or frame rails) either side of the round; {@code -1} = rail
     * @param contact       bar struck head-on, or -1
     * @param penetrationScale excludes {@link #standoffScale}
     */
    public record SlatOutcome(SlatVerdict verdict, float penetrationScale, int contact, int lower, int upper) {
    }

    /**
     * @param y panel-frame crossing height of the round's axis
     */
    public static SlatOutcome slat(AddonMount mount, AddonMountState state, Threat threat, float y,
                                   RandomSource random) {
        float radius = threat.caliberMm() / 2000.0f;
        float tip = switch (threat.kind()) {
            case HEAT, TANDEM_HEAT, HE -> radius * TIP_FRACTION;
            case KINETIC, SMALL_ARMS -> radius;
        };
        int lower = -1;
        float lowerY = -mount.halfHeight;
        int upper = -1;
        float upperY = mount.halfHeight;
        for (int k = 0; k < mount.bars; k++) {
            if (!state.barPresent(k)) {
                continue;
            }
            float barY = mount.barY(k);
            if (barY <= y && barY >= lowerY) {
                lower = k;
                lowerY = barY;
            } else if (barY > y && barY <= upperY) {
                upper = k;
                upperY = barY;
            }
        }
        int contact = -1;
        float reach = AddonMount.SLAT_BAR / 2 + tip;
        if (lower >= 0 && y - lowerY < reach) {
            contact = lower;
        } else if (upper >= 0 && upperY - y < reach) {
            contact = upper;
        }
        boolean wedged = 2 * radius > upperY - lowerY - AddonMount.SLAT_BAR;
        if (contact < 0 && !wedged) {
            return new SlatOutcome(SlatVerdict.MISS, 1.0f, -1, lower, upper);
        }
        return switch (threat.kind()) {
            case SMALL_ARMS -> contact >= 0
                    ? new SlatOutcome(SlatVerdict.STOPPED, 0.0f, contact, lower, upper)
                    : new SlatOutcome(SlatVerdict.MISS, 1.0f, -1, lower, upper);
            case KINETIC -> new SlatOutcome(SlatVerdict.PERFORATED, threat.penetrationMm() > 0
                    ? Math.max(0, 1 - BAR_RHA_MM / threat.penetrationMm()) : 1.0f,
                    contact >= 0 ? contact : lower, lower, upper);
            case HE -> new SlatOutcome(SlatVerdict.BURST, 0.0f, contact, lower, upper);
            case HEAT -> {
                if (contact >= 0) {
                    yield new SlatOutcome(SlatVerdict.BURST, 1.0f, contact, lower, upper);
                }
                boolean rpgClass = threat.caliberMm() >= RPG_MIN_MM && threat.caliberMm() <= RPG_MAX_MM;
                if (rpgClass && random.nextFloat() < SHORT_CHANCE) {
                    yield new SlatOutcome(SlatVerdict.DUD, 0.0f, -1, lower, upper);
                }
                yield new SlatOutcome(SlatVerdict.BURST, rpgClass ? CRUSHED_JET : 1.0f, -1, lower, upper);
            }
            case TANDEM_HEAT -> contact < 0 && random.nextFloat() < TANDEM_DUD_CHANCE
                    ? new SlatOutcome(SlatVerdict.DUD, 0.0f, -1, lower, upper)
                    : new SlatOutcome(SlatVerdict.BURST, TANDEM_SLAT_JET, contact, lower, upper);
        };
    }

    /** Penetration kept by a jet formed {@code gap} blocks short of the plate. Tandem = designed standoff. */
    public static float standoffScale(ThreatKind kind, double gap) {
        return kind == ThreatKind.HEAT ? (float) (1.0 / (1.0 + STANDOFF_LOSS * Math.max(0, gap))) : 1.0f;
    }
}
