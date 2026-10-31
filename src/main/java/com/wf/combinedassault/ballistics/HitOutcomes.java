package com.wf.combinedassault.ballistics;

import java.util.Locale;

import com.wf.wflib.armor.api.hull.HullOutcome;

public final class HitOutcomes {

    private HitOutcomes() {
    }

    public static String translationKey(HullOutcome outcome) {
        return "message.vehicle.ballistics." + outcome.name().toLowerCase(Locale.ROOT);
    }
}
