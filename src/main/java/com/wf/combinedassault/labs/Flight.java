package com.wf.combinedassault.labs;

import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.RoundArc;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Level flight of a round from the muzzle: velocity (blocks/tick) on arrival at a distance. */
final class Flight {

    private static final int MAX_TICKS = 4000;

    private final double[] path;
    private final int steps;

    Flight(KineticPreset preset, double muzzleMps) {
        double v0 = muzzleMps / 20.0;
        int life = Math.min(preset.lifeTicks(), MAX_TICKS);
        double[] out = new double[3 * life];
        this.steps = preset.motorAccel() > 0
                ? RoundArc.predictPowered(preset, 0, 0, 0, v0, 0, 0, 1, 0, 0, preset.lifeTicks(), -1.0e9, life, out)
                : RoundArc.predict(preset, 0, 0, 0, v0, 0, 0, preset.lifeTicks(), -1.0e9, false, life, out);
        this.path = out;
    }

    /** Null: expired before {@code range}. */
    @Nullable
    Vec3 arrival(double range) {
        for (int k = 0; k < steps; k++) {
            if (path[3 * k] >= range) {
                int p = k == 0 ? -1 : k - 1;
                double px = p < 0 ? 0 : path[3 * p], py = p < 0 ? 0 : path[3 * p + 1], pz = p < 0 ? 0 : path[3 * p + 2];
                return new Vec3(path[3 * k] - px, path[3 * k + 1] - py, path[3 * k + 2] - pz);
            }
        }
        return null;
    }
}
