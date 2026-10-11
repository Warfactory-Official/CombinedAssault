package com.wf.combinedassault.ballistics.addon;

/**
 * One ERA brick type.
 *
 * @param heatCut     fraction of a single shaped-charge jet's penetration removed at >= 60 deg obliquity
 * @param kineticCut  same, against a long-rod or AP penetrator
 * @param kineticInitiationMm smallest kinetic calibre that sets the brick off
 * @param thickness   blocks, along the panel normal
 */
public record EraSpec(float heatCut, float kineticCut, float kineticInitiationMm, float thickness) {

    /** Kontakt-1 class. */
    public static final EraSpec LIGHT = new EraSpec(0.65f, 0.05f, 20.0f, 0.10f);
    /** Kontakt-5 class: heavy flyer plate also shears long rods. */
    public static final EraSpec HEAVY = new EraSpec(0.75f, 0.35f, 35.0f, 0.16f);

    /** HE shells at least this big set any brick off on a direct hit. */
    public static final float HE_INITIATION_MM = 76.0f;
}
