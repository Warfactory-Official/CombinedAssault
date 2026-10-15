package com.wf.combinedassault.airdefense;

import com.wf.combinedassault.custom.part.data.AirDefensePojo;

/** Station's pack {@code air_defense}. */
public record AirDefenseSpec(double range, double mast, float minElevation, float maxElevation, int salvoSpacing,
                             int cooldown, int dwell) {

    public static AirDefenseSpec of(AirDefensePojo pojo) {
        return new AirDefenseSpec(pojo.range, pojo.mast, pojo.minElevation, pojo.maxElevation, pojo.salvoSpacing,
                pojo.cooldown, pojo.dwell);
    }
}
