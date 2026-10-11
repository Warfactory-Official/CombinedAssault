package com.wf.combinedassault.vehicle.camo;

/** CUBE/PLATE share one block-state palette: pick a material once, choose per piece how it lands. */
public enum CamoKind {

    /** Baked model, scaled + mounted on the hull. */
    CUBE,
    /** Double-sided textured square (leaf, frond, netting scrap). */
    PLATE;

    private static final CamoKind[] VALUES = values();

    public static CamoKind byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : CUBE;
    }
}
