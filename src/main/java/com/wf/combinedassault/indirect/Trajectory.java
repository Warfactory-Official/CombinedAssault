package com.wf.combinedassault.indirect;

import net.minecraft.world.phys.Vec3;

/**
 * Closed form of {@code com.wf.wflib.round.Rounds} flight (KineticPreset contract): recurrence
 * {@code pos += v; v *= 1-drag; v.y -= gravity} solved exactly, not stepped. Ticks + blocks throughout.
 * Quadratic drag => {@link SteppedTrajectory}.
 */
public class Trajectory {

    /** Beyond any round's life; bounds the unreachable-height search. */
    private static final double TICK_CEILING = 200_000;
    private static final int BISECTIONS = 80;

    private final double horizontalSpeed;
    private final double verticalSpeed;
    private final double drag;
    private final double gravity;
    /** Retained speed/tick; every formula below is a power of it. */
    private final double decay;

    public Trajectory(double horizontalSpeed, double verticalSpeed, double drag, double gravity) {
        this.horizontalSpeed = horizontalSpeed;
        this.verticalSpeed = verticalSpeed;
        this.decay = decayOf(drag);
        // drag recomputed from decay, not stored, so the geometric sums below stay exact.
        this.drag = 1 - this.decay;
        this.gravity = gravity;
    }

    /**
     * Entity computes {@code 1-friction} in float then widens: match here or drift grows with flight
     * length (~1e-7/tick).
     */
    public static double decayOf(double drag) {
        return (double) (1.0f - (float) Math.max(drag, 0));
    }

    public static Trajectory of(RoundSpec spec, double elevationDegrees) {
        double radians = Math.toRadians(elevationDegrees);
        if (spec.quadraticDrag() > 0) {
            return new SteppedTrajectory(spec, spec.muzzleSpeed() * Math.cos(radians),
                    spec.muzzleSpeed() * Math.sin(radians));
        }
        return new Trajectory(spec.muzzleSpeed() * Math.cos(radians),
                spec.muzzleSpeed() * Math.sin(radians),
                spec.drag(), spec.gravity());
    }

    /** Real-valued {@code ticks} (not integer): solver bisects between tick boundaries. */
    public static double travelDecay(double decay, double ticks) {
        double drag = 1 - decay;
        if (drag <= 0) {
            return ticks;
        }
        return (1 - Math.pow(decay, ticks)) / drag;
    }

    public static double travel(double drag, double ticks) {
        return travelDecay(decayOf(drag), ticks);
    }

    /** Displacement along an axis gravity does not act on. */
    public static double axisOffsetDecay(double speed, double decay, double ticks) {
        return speed * travelDecay(decay, ticks);
    }

    /** Vertical displacement; gravity applied after the tick's move. */
    public static double riseOfDecay(double speed, double decay, double gravity, double ticks) {
        double drag = 1 - decay;
        if (drag <= 0) {
            // gravity applied after move => first tick unretarded: n(n-1)/2, not n(n+1)/2.
            return speed * ticks - gravity * ticks * (ticks - 1) / 2;
        }
        double travelled = travelDecay(decay, ticks);
        return speed * travelled - gravity / drag * (ticks - travelled);
    }

    public static double riseOf(double speed, double drag, double gravity, double ticks) {
        return riseOfDecay(speed, decayOf(drag), gravity, ticks);
    }

    public static double axisSpeedAtDecay(double speed, double decay, double ticks) {
        return decay >= 1 ? speed : speed * Math.pow(decay, ticks);
    }

    public static double verticalSpeedOfDecay(double speed, double decay, double gravity, double ticks) {
        double drag = 1 - decay;
        if (drag <= 0) {
            return speed - gravity * ticks;
        }
        double retained = Math.pow(decay, ticks);
        return retained * speed - gravity * (1 - retained) / drag;
    }

    public static double verticalSpeedOf(double speed, double drag, double gravity, double ticks) {
        return verticalSpeedOfDecay(speed, decayOf(drag), gravity, ticks);
    }

    /** Tick a round launched at {@code speed} stops climbing; 0 if it never does. */
    public static double apexOfDecay(double speed, double decay, double gravity) {
        double drag = 1 - decay;
        if (speed <= 0) {
            return 0;
        }
        if (drag <= 0) {
            return speed / gravity;
        }
        double terminal = gravity / drag;
        return Math.log(terminal / (speed + terminal)) / Math.log(decay);
    }

    public static double apexOf(double speed, double drag, double gravity) {
        return apexOfDecay(speed, decayOf(drag), gravity);
    }

    public double horizontalAt(double ticks) {
        return axisOffsetDecay(horizontalSpeed, decay, ticks);
    }

    public double heightAt(double ticks) {
        return riseOfDecay(verticalSpeed, decay, gravity, ticks);
    }

    public double verticalSpeedAt(double ticks) {
        return verticalSpeedOfDecay(verticalSpeed, decay, gravity, ticks);
    }

    public double horizontalSpeedAt(double ticks) {
        return axisSpeedAtDecay(horizontalSpeed, decay, ticks);
    }

    public double speedAt(double ticks) {
        double h = horizontalSpeedAt(ticks);
        double v = verticalSpeedAt(ticks);
        return Math.sqrt(h * h + v * v);
    }

    /**
     * Horizontal asymptote: drag caps range rather than just slowing it (1% drag/tick => asymptote at
     * 100x muzzle speed).
     */
    public double maxHorizontal() {
        return drag <= 0 ? Double.POSITIVE_INFINITY : horizontalSpeed / drag;
    }

    /** Ticks to cover {@code range} horizontally, or NaN if drag stops the round short of it. */
    public double ticksToHorizontal(double range) {
        if (horizontalSpeed <= 0) {
            return Double.NaN;
        }
        if (drag <= 0) {
            return range / horizontalSpeed;
        }
        double remaining = 1 - drag * range / horizontalSpeed;
        if (remaining <= 0) {
            return Double.NaN;
        }
        return Math.log(remaining) / Math.log(decay);
    }

    public double apexTicks() {
        return apexOfDecay(verticalSpeed, decay, gravity);
    }

    public double apexHeight() {
        return heightAt(apexTicks());
    }

    /**
     * NaN if the round never descends to {@code height}. Bisected: height eqn mixes decay power + linear
     * term, no elementary inverse; called once/shot.
     */
    public double ticksToDescendTo(double height) {
        double from = apexTicks();
        if (heightAt(from) < height) {
            return from == 0 && heightAt(0) <= height ? 0 : Double.NaN;
        }
        double high = Math.max(from, 1) * 2;
        while (heightAt(high) > height) {
            high *= 2;
            if (high > TICK_CEILING) {
                return Double.NaN;
            }
        }
        double low = from;
        for (int i = 0; i < BISECTIONS; i++) {
            double mid = (low + high) / 2;
            if (heightAt(mid) > height) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }

    public double horizontalSpeed() {
        return horizontalSpeed;
    }

    public double verticalSpeed() {
        return verticalSpeed;
    }

    public static Vec3 positionAt(Vec3 origin, Vec3 velocity, RoundSpec spec, double ticks) {
        if (spec.quadraticDrag() > 0) {
            return SteppedTrajectory.step(origin, velocity, spec, ticks)[0];
        }
        double decay = spec.decay();
        double flat = travelDecay(decay, ticks);
        return new Vec3(origin.x + velocity.x * flat,
                origin.y + riseOfDecay(velocity.y, decay, spec.gravity(), ticks),
                origin.z + velocity.z * flat);
    }

    public static Vec3 velocityAt(Vec3 velocity, RoundSpec spec, double ticks) {
        if (spec.quadraticDrag() > 0) {
            return SteppedTrajectory.step(Vec3.ZERO, velocity, spec, ticks)[1];
        }
        double decay = spec.decay();
        double retained = axisSpeedAtDecay(1, decay, ticks);
        return new Vec3(velocity.x * retained,
                verticalSpeedOfDecay(velocity.y, decay, spec.gravity(), ticks),
                velocity.z * retained);
    }

}
