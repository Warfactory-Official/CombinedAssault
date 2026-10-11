import com.wf.combinedassault.naval.ShipHydrodynamics;
import com.wf.combinedassault.naval.ShipHydrodynamics.Controls;
import com.wf.combinedassault.naval.ShipHydrodynamics.Environment;
import com.wf.combinedassault.naval.ShipHydrodynamics.Params;
import com.wf.combinedassault.naval.ShipHydrodynamics.Pose;

/**
 * Standalone verification of the ship model: drives the shipped ShipHydrodynamics through the same
 * tick shape the entity uses, on a carrier and a patrol boat, and checks the properties the design
 * promises (size ordering, tanh acceleration, L/v turn establishment, heave without overshoot).
 *
 * Run:
 *   javac -d /tmp/shipcheck src/main/java/com/wf/combinedassault/naval/ShipHydrodynamics.java tools/ShipCheck.java
 *   java -cp /tmp/shipcheck ShipCheck
 */
public class ShipCheck {

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    static Params carrier() {
        Params p = new Params();
        p.waterline = 0;
        p.draft = 11.4;
        p.maxSpeedForward = 0.77;
        p.maxSpeedBackward = 0.25;
        p.accelerationTime = 60;
        p.throttleRate = 0.25;
        p.rudderRate = 0.5;
        p.turnRate = 2.0;
        p.turnLag = 1.0;
        p.drift = 0.15;
        p.heel = 1.0;
        p.trim = 0.5;
        p.heavePeriod = 6.0;
        p.rightingRate = 1.0;
        return p;
    }

    static Params boat() {
        Params p = new Params();
        p.waterline = 0.36;
        p.draft = 0.4;
        p.maxSpeedForward = 1.0;
        p.maxSpeedBackward = 0.3;
        p.accelerationTime = 4;
        p.throttleRate = 1.0;
        p.rudderRate = 2.0;
        p.turnRate = 40.0;
        p.turnLag = 1.0;
        p.lowSpeedTurn = 0.5;
        p.drift = 0.5;
        p.heel = -0.5;
        p.trim = 4.0;
        p.heavePeriod = 1.5;
        p.rightingRate = 30.0;
        return p;
    }

    static Params sub() {
        Params p = new Params();
        p.waterline = 3.0;
        p.draft = 7.6;
        p.maxSpeedForward = 0.31;
        p.maxSpeedBackward = 0.12;
        p.accelerationTime = 25;
        p.turnRate = 3.0;
        p.submarine = true;
        p.maxDepth = 40;
        p.crushDepth = 60;
        p.diveRate = 0.15;
        p.maxSpeedSubmerged = 0.44;
        p.diveAngle = 12;
        p.heavePeriod = 4.0;
        p.rightingRate = 3.0;
        return p;
    }

    /** One entity tick: the model, then the engine's gravity (dry only). */
    static void tick(ShipHydrodynamics model, Params p, Pose pose, Controls c, Environment env,
                     double mass, double length) {
        model.step(p, pose, c, env, mass, mass, length, 30.0, d -> {
            pose.yaw += (float) d;
            return d;
        });
        if (!env.afloat) {
            pose.vy -= ShipHydrodynamics.G;
        }
        pose.y += pose.vy;
    }

    static Environment sea(double surface, double bed) {
        Environment env = new Environment();
        env.afloat = true;
        env.surfaceY = surface;
        env.waterDepth = surface - bed;
        env.keelClearance = 100;
        return env;
    }

    /** Ticks to reach a fraction of top speed from rest at full throttle, telegraph already set. */
    static int ticksToSpeed(Params p, double length, double fraction) {
        ShipHydrodynamics m = new ShipHydrodynamics();
        m.throttle = 1;
        Pose pose = new Pose();
        pose.y = 63 - p.waterline;
        Controls c = new Controls();
        Environment env = sea(63, 30);
        for (int t = 1; t < 20000; t++) {
            tick(m, p, pose, c, env, 100, length);
            if (m.surge >= fraction * p.maxSpeedForward) {
                return t;
            }
        }
        return -1;
    }

    static void acceleration() {
        System.out.println("acceleration");
        int carrierHalf = ticksToSpeed(carrier(), 331, 0.5);
        int boatHalf = ticksToSpeed(boat(), 8.5, 0.5);
        int carrier90 = ticksToSpeed(carrier(), 331, 0.9);
        int boat90 = ticksToSpeed(boat(), 8.5, 0.9);
        System.out.printf("   carrier 50%% at %d ticks, 90%% at %d; boat 50%% at %d, 90%% at %d%n",
                carrierHalf, carrier90, boatHalf, boat90);
        check(carrierHalf > 5 * boatHalf, "the carrier takes far longer to reach half speed than the boat");
        check(carrier90 > 0 && boat90 > 0, "both reach 90% of top speed");
        // v(tau) = tanh(1) v_max for pure quadratic drag; the linear share pulls it a little lower.
        ShipHydrodynamics m = new ShipHydrodynamics();
        m.throttle = 1;
        Params p = carrier();
        Pose pose = new Pose();
        pose.y = 63;
        Environment env = sea(63, 30);
        for (int t = 0; t < 60 * 20; t++) {
            tick(m, p, pose, c(), env, 100, 331);
        }
        double frac = m.surge / p.maxSpeedForward;
        System.out.printf("   carrier at t = acceleration_time: %.3f of v_max%n", frac);
        check(frac > 0.65 && frac < 0.85, "speed at one time constant is near tanh(1)");
        for (int t = 0; t < 600 * 20; t++) {
            tick(m, p, pose, c(), env, 100, 331);
        }
        System.out.printf("   carrier steady: %.4f of %.4f%n", m.surge, p.maxSpeedForward);
        check(Math.abs(m.surge - p.maxSpeedForward) < 0.01, "full throttle settles at max_speed_forward");
    }

    static Controls c() {
        return new Controls();
    }

    static void coasting() {
        System.out.println("coasting");
        double[] halves = new double[2];
        Params[] ps = {carrier(), boat()};
        double[] ls = {331, 8.5};
        for (int i = 0; i < 2; i++) {
            ShipHydrodynamics m = new ShipHydrodynamics();
            Params p = ps[i];
            Pose pose = new Pose();
            pose.y = 63 - p.waterline;
            pose.vz = p.maxSpeedForward;
            Environment env = sea(63, 30);
            double distance = 0;
            int t = 0;
            while (m.surge > p.maxSpeedForward / 2 || t == 0) {
                tick(m, p, pose, c(), env, 100, ls[i]);
                distance += pose.vz;
                t++;
                if (t > 100000) break;
            }
            halves[i] = distance;
            int stop = t;
            while (m.surge > 0.01 && stop < 200000) {
                tick(m, p, pose, c(), env, 100, ls[i]);
                stop++;
            }
            System.out.printf("   %s: half speed after %.0f blocks (%d ticks), under 0.01 b/t at %d ticks%n",
                    i == 0 ? "carrier" : "boat", distance, t, stop);
            check(stop < 200000, "the coast ends");
        }
        check(halves[0] > 5 * halves[1], "a carrier coasts much further than a boat");
    }

    static void turning() {
        System.out.println("turning");
        Params[] ps = {carrier(), boat()};
        double[] ls = {331, 8.5};
        double[] establish = new double[2];
        for (int i = 0; i < 2; i++) {
            ShipHydrodynamics m = new ShipHydrodynamics();
            Params p = ps[i];
            m.throttle = 1;
            m.rudder = 1;
            Pose pose = new Pose();
            pose.y = 63 - p.waterline;
            pose.vz = p.maxSpeedForward;
            Controls ctl = new Controls();
            ctl.ahead = true;
            ctl.starboard = true;
            Environment env = sea(63, 30);
            double nominal = p.turnRate / 20.0;
            double[] rates = new double[20000];
            double maxRoll = 0;
            for (int t = 1; t < 20000; t++) {
                tick(m, p, pose, ctl, env, 100, ls[i]);
                rates[t] = m.yawRate;
                maxRoll = Math.max(maxRoll, Math.abs(pose.roll));
            }
            double steady = m.yawRate;
            int t90 = -1;
            for (int t = 1; t < 20000; t++) {
                if (rates[t] >= 0.9 * steady) {
                    t90 = t;
                    break;
                }
            }
            double tau = p.turnLag * ls[i] / p.maxSpeedForward;
            establish[i] = t90;
            System.out.printf("   %s: 90%% of steady yaw at %d ticks (L/v = %.0f), steady %.4f of nominal %.4f deg/tick, speed %.2f of v_max, heel %.1f deg, drift %.1f deg%n",
                    i == 0 ? "carrier" : "boat", t90, tau, steady, nominal, m.surge / p.maxSpeedForward, maxRoll,
                    Math.toDegrees(Math.atan2(m.sway, m.surge)));
            check(steady > 0.5 * nominal && steady <= nominal * 1.001, "steady yaw is turn_rate scaled by the speed kept in the turn");
            check(t90 >= 0.5 * tau && t90 < 6.0 * tau, "turn establishes in a few L/v");
            check(maxRoll <= 30.0, "heel is capped");
        }
        check(establish[0] > 20 * establish[1], "the carrier answers the helm far slower than the boat");
        // No steerage way: a carrier at rest cannot turn, a jet boat can.
        for (int i = 0; i < 2; i++) {
            ShipHydrodynamics m = new ShipHydrodynamics();
            Params p = ps[i];
            m.rudder = 1;
            Pose pose = new Pose();
            pose.y = 63 - p.waterline;
            Controls ctl = new Controls();
            ctl.starboard = true;
            Environment env = sea(63, 30);
            for (int t = 0; t < 400; t++) {
                tick(m, p, pose, ctl, env, 100, ls[i]);
            }
            System.out.printf("   %s at rest with full rudder: %.4f deg/tick%n", i == 0 ? "carrier" : "boat", m.yawRate);
            check(i == 0 ? Math.abs(m.yawRate) < 1.0e-4 : m.yawRate > 0.5, i == 0
                    ? "a stopped carrier has no steerage" : "a stopped jet boat still turns");
        }
        // A refused turn kills the yaw rate rather than winding it up against the obstacle.
        ShipHydrodynamics m = new ShipHydrodynamics();
        Params p = boat();
        m.throttle = 1;
        m.rudder = 1;
        Pose pose = new Pose();
        pose.y = 63 - p.waterline;
        pose.vz = 1.0;
        Environment env = sea(63, 30);
        Controls held = new Controls();
        held.ahead = true;
        held.starboard = true;
        for (int t = 0; t < 40; t++) {
            m.step(p, pose, held, env, 100, 100, 8.5, 30, d -> 0.0);
        }
        check(m.turnBlocked && m.yawRate == 0, "a blocked turn reports itself and drops the rate");
    }

    static void heave() {
        System.out.println("heave");
        Params[] ps = {carrier(), boat()};
        double[] ls = {331, 8.5};
        for (int i = 0; i < 2; i++) {
            ShipHydrodynamics m = new ShipHydrodynamics();
            Params p = ps[i];
            Pose pose = new Pose();
            double line = 63 - p.waterline;
            pose.y = line + 3;
            Environment env = sea(63, 30);
            double lowest = Double.MAX_VALUE;
            int settled = -1;
            for (int t = 1; t < 4000; t++) {
                tick(m, p, pose, c(), env, 100, ls[i]);
                lowest = Math.min(lowest, pose.y);
                if (settled < 0 && Math.abs(pose.y - line) < 0.01 && Math.abs(pose.vy) < 0.001) {
                    settled = t;
                }
            }
            System.out.printf("   %s dropped 3 blocks: settled at %d ticks, lowest %.3f below the line, rests %.4f off%n",
                    i == 0 ? "carrier" : "boat", settled, line - lowest, pose.y - line);
            check(settled > 0, "settles onto the waterline");
            check(line - lowest < 0.15, "no bounce below the line");
            check(Math.abs(pose.y - line) < 0.005, "rests on the line, not a tick of gravity under it");
        }
        // Out of the water: engine gravity.
        ShipHydrodynamics m = new ShipHydrodynamics();
        Pose pose = new Pose();
        pose.y = 80;
        Environment dry = new Environment();
        for (int t = 0; t < 10; t++) {
            tick(m, boat(), pose, c(), dry, 100, 8.5);
        }
        check(pose.vy < -0.1, "a hull with no water under it falls");
    }

    static void shallows() {
        System.out.println("shallows");
        Params p = boat();
        double[] speeds = new double[2];
        for (int i = 0; i < 2; i++) {
            ShipHydrodynamics m = new ShipHydrodynamics();
            m.throttle = 1;
            Pose pose = new Pose();
            pose.y = 63 - p.waterline;
            Environment env = sea(63, 30);
            env.keelClearance = i == 0 ? 5 : 0.1;
            for (int t = 0; t < 2000; t++) {
                tick(m, p, pose, c(), env, 100, 8.5);
            }
            speeds[i] = m.surge;
            System.out.printf("   clearance %.1f: %.3f b/t, shallow=%b%n", env.keelClearance, m.surge, m.shallow);
        }
        check(speeds[1] < 0.8 * speeds[0], "shallow water slows the hull");
    }

    static void submarine() {
        System.out.println("submarine");
        Params p = sub();
        ShipHydrodynamics m = new ShipHydrodynamics();
        Pose pose = new Pose();
        double line = 63 - p.waterline;
        pose.y = line;
        Environment env = sea(63, 20);
        Controls ctl = new Controls();
        ctl.dive = true;
        for (int t = 0; t < 40 * 20; t++) {
            tick(m, p, pose, ctl, env, 100, 71);
        }
        System.out.printf("   after 40 s of dive: setting %.1f, depth %.1f, pitch %.1f, submerged=%b%n",
                m.depthSetting, m.depth, pose.pitch, m.submerged);
        check(m.depthSetting > 20, "the setting went down");
        check(m.depth > m.depthSetting - 2, "the hull followed it");
        check(m.submerged, "reads as submerged");
        double deepest = env.waterDepth - p.draft - p.minKeelClearance;
        check(m.depthSetting <= deepest + 1.0e-9, "the setting is clamped above the seabed");
        ctl.dive = false;
        for (int t = 0; t < 30 * 20; t++) {
            tick(m, p, pose, ctl, env, 100, 71);
        }
        System.out.printf("   holding: depth %.2f vs setting %.2f, pitch %.2f%n", m.depth, m.depthSetting, pose.pitch);
        check(Math.abs(m.depth - m.depthSetting) < 0.05, "holds the set depth");
        check(Math.abs(pose.pitch) < 0.5, "levels out once there");
        ctl.rise = true;
        for (int t = 0; t < 60 * 20; t++) {
            tick(m, p, pose, ctl, env, 100, 71);
        }
        System.out.printf("   surfaced: depth %.2f, submerged=%b%n", m.depth, m.submerged);
        check(m.depth < 0.1 && !m.submerged, "surfaces");
        Environment deep = sea(63, -40);
        m.depthSetting = 70;
        m.step(p, pose, c(), deep, 100, 100, 71, 30, d -> d);
        pose.y = 63 - p.waterline - 65;
        m.step(p, pose, c(), deep, 100, 100, 71, 30, d -> d);
        check(m.crushed(p), "past crush depth is reported");
    }

    public static void main(String[] args) {
        acceleration();
        coasting();
        turning();
        heave();
        shallows();
        submarine();
        System.out.println(failures == 0 ? "\nAll ship checks passed." : "\n" + failures + " check(s) FAILED.");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
