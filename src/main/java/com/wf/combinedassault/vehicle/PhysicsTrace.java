package com.wf.combinedassault.vehicle;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.collision.SweptHull;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Per-tick vertical-movement ledger by source. Closes against actual movement; MOVE = unclaimed remainder. */
public final class PhysicsTrace {

    /** Long enough to see a period, short enough to hold. */
    public static final int MAX_TICKS = 2400;

    /** Up to 16 substeps/tick => a few thousand ticks' worth. */
    public static final int MAX_SWEEPS = 8000;

    /** Blocks; overlap beyond this = penetration, not slop. */
    private static final double PENETRATION_THRESHOLD = 0.05;

    /** Blocks; above this, a substep is "trying to move". */
    private static final double STALL_REQUEST = 0.005;

    /** toi fraction below this ~= allowed nothing. */
    private static final double STALL_TOI = 0.02;

    /** Blocks/tick; launch vs settling noise. Gravity ~0.0245/tick, so this is under one tick of fall. */
    private static final double LAUNCH_THRESHOLD = 0.02;

    /** In-contact + |yDelta| under this = settled. */
    private static final double SETTLED_THRESHOLD = 0.001;

    public enum Source {

        /** Contact velocity cancellation + bounce. */
        IMPACT(false),
        /** Lift for a hull judged buried in geometry. */
        SUPPORT_LIFT(false),
        /** Flat 0.1 kick: hull centre inside a solid block. */
        CENTRE_KICK(false),
        GRAVITY(false),
        /** Downward velocity clamped: centre of gravity supported. */
        SUPPORT_CLAMP(false),
        /** Scales the whole velocity vector; tilts the vertical part too. */
        FRICTION(false),
        /** Includes fluid damping, not just buoyant accel. */
        BUOYANCY(false),
        /** Engine thrust, aiStep drag, mod compat: unclaimed velocity residual. */
        EXTERNAL(false),
        /** climb()'s step-up teleport, tick-level. */
        CLIMB(true),
        /** stepUp()'s retry-lift, substep-level. */
        STEP_UP(true),
        /** Position from rotating about a support edge. */
        ROTATION(true),
        MOVE(true);

        /** True: source writes position, not velocity. */
        public final boolean position;

        Source(boolean position) {
            this.position = position;
        }

    }

    /**
     * @param travel   horizontal dist this tick; lift without it = levitation, not climb
     * @param speed    horizontal speed, tick end; slope-cost metric
     * @param bySource vertical contribution per Source, indexed by ordinal
     */
    public record Sample(long gameTime, double x, double y, double z,
                         double vy, double yDelta, double vyDelta,
                         double travel, double speed,
                         boolean onGround, boolean supported, float xRot, float zRot, float rotV,
                         int contacts, int bottomContacts, int blockingContacts,
                         double[] bySource) {

        double of(Source source) {
            return bySource[source.ordinal()];
        }

        /**
         * Rise/block driven. Distinguishes driving up a hill from being lifted onto one; the live
         * path bounds this via climbGradient. Infinite if height gained with no travel.
         */
        public double climbRate() {
            if (travel <= 1.0e-6) {
                return yDelta > 1.0e-6 ? Double.POSITIVE_INFINITY : 0;
            }
            return yDelta / travel;
        }

    }

    /**
     * One substep + swept-hull backstop outcome. Per-substep because mid-substep tunnelling can
     * pass undetected if sampled only at tick end.
     *
     * @param mx          requested step, not allowed; multiply by toi for actual. Recording the
     *                    allowed step alone hid toi=0 deadlocks (looked motionless)
     * @param toi         fraction of substep taken
     * @param penetration deepest overlap after substep, blocks
     */
    public record Sweep(long gameTime, int substep, int substeps,
                        double x, double y, double z,
                        double mx, double my, double mz,
                        double toi, SweptHull.Outcome outcome, int boxes,
                        double penetration, double axisX, double axisY, double axisZ,
                        double blockerX, double blockerY, double blockerZ,
                        float xRot, float yRot, float zRot, float rotV) {

        public boolean penetrating() {
            return penetration >= PENETRATION_THRESHOLD;
        }

        /** Requested motion but allowed ~none: wedged signature. */
        public boolean stalled() {
            return Math.sqrt(mx * mx + my * my + mz * mz) > STALL_REQUEST && toi < STALL_TOI;
        }

    }

    private static final Source[] SOURCES = Source.values();

    private static final SweptHull.Outcome[] OUTCOMES = SweptHull.Outcome.values();

    /** Sources that can launch a vehicle off something it is already touching. */
    private static final Source[] LAUNCH_SOURCES =
            {Source.CLIMB, Source.STEP_UP, Source.SUPPORT_LIFT, Source.CENTRE_KICK, Source.ROTATION};

    private final int vehicleId;
    private final String vehicleName;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Deque<Sweep> sweeps = new ArrayDeque<>();

    /** Reused per substep; avoids per-substep allocation. */
    private final SweptHull.Probe probe = new SweptHull.Probe();

    private boolean recording = true;
    private int remainingTicks;

    private final double[] bySource = new double[SOURCES.length];
    private double attributedSinceMark;
    private double startX;
    private double startY;
    private double startZ;
    private double startVy;
    private boolean supported;
    private int contacts;
    private int bottomContacts;
    private int blockingContacts;

    public PhysicsTrace(AbstractVehicle vehicle, int ticks) {
        this.vehicleId = vehicle.getId();
        this.vehicleName = vehicle.getType().toShortString();
        this.remainingTicks = ticks;
    }

    public boolean isRecording() {
        return recording;
    }

    public void stop() {
        recording = false;
    }

    public int size() {
        return samples.size();
    }

    public void beginTick(AbstractVehicle vehicle) {
        if (!recording) {
            return;
        }
        Arrays.fill(bySource, 0);
        attributedSinceMark = 0;
        startX = vehicle.getX();
        startY = vehicle.getY();
        startZ = vehicle.getZ();
        startVy = vehicle.getDeltaMovement().y;
        supported = false;
        contacts = 0;
        bottomContacts = 0;
        blockingContacts = 0;
    }

    public void endTick(AbstractVehicle vehicle) {
        if (!recording) {
            return;
        }
        double y = vehicle.getY();
        double vy = vehicle.getDeltaMovement().y;
        double claimedPosition = 0;
        double claimedVelocity = 0;
        for (Source source : SOURCES) {
            if (source.position) {
                claimedPosition += bySource[source.ordinal()];
            } else {
                claimedVelocity += bySource[source.ordinal()];
            }
        }
        // Residuals: position close tracks previous tick's velocity; velocity close tracks engine thrust.
        bySource[Source.MOVE.ordinal()] += (y - startY) - claimedPosition;
        bySource[Source.EXTERNAL.ordinal()] += (vy - startVy) - claimedVelocity;

        double x = vehicle.getX();
        double z = vehicle.getZ();
        double travelX = x - startX;
        double travelZ = z - startZ;
        Vec3 movement = vehicle.getDeltaMovement();
        samples.addLast(new Sample(vehicle.level().getGameTime(), x, y, z,
                vy, y - startY, vy - startVy,
                Math.sqrt(travelX * travelX + travelZ * travelZ),
                Math.sqrt(movement.x * movement.x + movement.z * movement.z),
                vehicle.onGround(), supported, vehicle.getXRot(), vehicle.getZRot(),
                vehicle.physicsEngine.angularVelocity.length(), contacts, bottomContacts, blockingContacts,
                bySource.clone()));
        while (samples.size() > MAX_TICKS) {
            samples.removeFirst();
        }
        if (remainingTicks > 0 && --remainingTicks == 0) {
            recording = false;
        }
    }

    /** Resets the running-attributed total, so remainder() can close a stage. */
    public void mark() {
        attributedSinceMark = 0;
    }

    public void add(Source source, double amount) {
        bySource[source.ordinal()] += amount;
        attributedSinceMark += amount;
    }

    /** Credits source with total minus whatever is already claimed since the last mark(). */
    public void remainder(Source source, double total) {
        add(source, total - attributedSinceMark);
    }

    public void supported() {
        supported = true;
    }

    public void contacts(int total, int bottom, int blocking) {
        contacts = total;
        bottomContacts = bottom;
        blockingContacts = blocking;
    }

    public SweptHull.Probe probe() {
        return probe;
    }

    /**
     * Records one movement substep. Call after the working hull is advanced, while probe() still
     * holds the sweep result. Pose comes from the rig, not the entity; the pipeline flushes the
     * shadow pose after.
     */
    public void sweep(AbstractVehicle vehicle, PhysicsRig rig, int substep, int substeps, Vec3 requested) {
        if (!recording) {
            return;
        }
        AABB blocker = probe.blocker != null ? probe.blocker : probe.penetrator;
        Vec3 at = blocker != null ? blocker.getCenter() : Vec3.ZERO;
        sweeps.addLast(new Sweep(vehicle.level().getGameTime(), substep, substeps,
                rig.x, rig.y, rig.z,
                requested.x, requested.y, requested.z,
                probe.toi, probe.outcome, probe.boxes,
                probe.penetration, probe.penetrationAxis.x, probe.penetrationAxis.y,
                probe.penetrationAxis.z, at.x, at.y, at.z,
                rig.getXRot(), rig.getYRot(), rig.getZRot(),
                vehicle.physicsEngine.angularVelocity.length()));
        while (sweeps.size() > MAX_SWEEPS) {
            sweeps.removeFirst();
        }
    }

    private record Stat(int ticks, double total, double max) {}

    private Stat stat(Source source) {
        int ticks = 0;
        double total = 0;
        double max = 0;
        for (Sample sample : samples) {
            double value = sample.of(source);
            if (value == 0) {
                continue;
            }
            ticks++;
            total += value;
            if (Math.abs(value) > Math.abs(max)) {
                max = value;
            }
        }
        return new Stat(ticks, total, max);
    }

    /** Upward lift this tick while already in contact. */
    private static double launch(Sample sample) {
        if (sample.contacts() == 0) {
            return 0;
        }
        double up = 0;
        for (Source source : LAUNCH_SOURCES) {
            double value = sample.of(source);
            if (value > 0) {
                up += value;
            }
        }
        return up;
    }

    /** Is the vehicle settling, and if not, what's lifting it? */
    public List<String> report() {
        List<String> lines = new ArrayList<>();
        if (samples.isEmpty()) {
            lines.add("physics trace: " + label() + " — no ticks recorded yet");
            return lines;
        }
        int total = samples.size();
        lines.add(String.format(Locale.ROOT, "physics trace: %s — %d ticks (%.1fs)%s",
                label(), total, total / 20.0, recording ? "" : ", stopped"));

        int airborne = 0;
        int settled = 0;
        int launches = 0;
        double launchTotal = 0;
        double launchMax = 0;
        long firstLaunch = -1;
        long lastLaunch = -1;
        List<Integer> airborneRuns = new ArrayList<>();
        int run = 0;
        for (Sample sample : samples) {
            if (sample.contacts() == 0) {
                airborne++;
                run++;
            } else {
                if (run > 0) {
                    airborneRuns.add(run);
                }
                run = 0;
                if (Math.abs(sample.yDelta()) < SETTLED_THRESHOLD) {
                    settled++;
                }
            }
            double up = launch(sample);
            if (up >= LAUNCH_THRESHOLD) {
                launches++;
                launchTotal += up;
                launchMax = Math.max(launchMax, up);
                if (firstLaunch < 0) {
                    firstLaunch = sample.gameTime();
                }
                lastLaunch = sample.gameTime();
            }
        }
        if (run > 0) {
            airborneRuns.add(run);
        }

        lines.add(String.format(Locale.ROOT, "  airborne %d ticks (%d%%), settled %d ticks (%d%%)",
                airborne, airborne * 100 / total, settled, settled * 100 / total));
        if (!airborneRuns.isEmpty()) {
            int sum = 0;
            int longest = 0;
            for (int length : airborneRuns) {
                sum += length;
                longest = Math.max(longest, length);
            }
            // Short hops and one long flight read very differently.
            lines.add(String.format(Locale.ROOT, "  airborne runs: %d, mean %.1f ticks, longest %d",
                    airborneRuns.size(), (double) sum / airborneRuns.size(), longest));
        }

        if (launches == 0) {
            lines.add("  no launches: nothing threw it upward while it was in contact");
        } else {
            String interval = launches > 1 && lastLaunch > firstLaunch
                    ? String.format(Locale.ROOT, ", every ~%.0f ticks",
                            (double) (lastLaunch - firstLaunch) / (launches - 1))
                    : "";
            lines.add(String.format(Locale.ROOT,
                    "  launches while in contact: %d%s, mean +%.4f, max +%.4f",
                    launches, interval, launchTotal / launches, launchMax));
            for (Source source : LAUNCH_SOURCES) {
                int ticks = 0;
                double sum = 0;
                for (Sample sample : samples) {
                    if (launch(sample) >= LAUNCH_THRESHOLD && sample.of(source) > 0) {
                        ticks++;
                        sum += sample.of(source);
                    }
                }
                if (ticks > 0) {
                    lines.add(String.format(Locale.ROOT, "    %-13s %3d/%d launches, total +%.4f",
                            source, ticks, launches, sum));
                }
            }
        }

        appendLedger(lines, "  vertical velocity, by source", false);
        appendLedger(lines, "  vertical position, by source", true);
        appendSweeps(lines);
        return lines;
    }

    /**
     * Tunnelling-backstop outcome + penetration depth. ALREADY_INSIDE: sweep declined to clip
     * (pre-overlap). Ramps keep the hull in permanent light overlap, switching the backstop off
     * exactly where the vehicle is moving fastest.
     */
    private void appendSweeps(List<String> lines) {
        if (sweeps.isEmpty()) {
            lines.add("  sweeps: none recorded (vehicle never moved, or collision is off)");
            return;
        }
        int[] byOutcome = new int[OUTCOMES.length];
        int penetrating = 0;
        int stalled = 0;
        int longestStall = 0;
        int stallRun = 0;
        Sweep stallAt = null;
        Sweep deepest = null;
        double stepTotal = 0;
        int multiStep = 0;
        for (Sweep sweep : sweeps) {
            byOutcome[sweep.outcome().ordinal()]++;
            if (sweep.stalled()) {
                stalled++;
                if (++stallRun > longestStall) {
                    longestStall = stallRun;
                    stallAt = sweep;
                }
            } else {
                stallRun = 0;
            }
            if (sweep.penetrating()) {
                penetrating++;
            }
            if (deepest == null || sweep.penetration() > deepest.penetration()) {
                deepest = sweep;
            }
            stepTotal += Math.sqrt(sweep.mx() * sweep.mx() + sweep.my() * sweep.my()
                    + sweep.mz() * sweep.mz());
            if (sweep.substeps() > 1) {
                multiStep++;
            }
        }
        int total = sweeps.size();
        lines.add(String.format(Locale.ROOT,
                "  sweeps: %d substeps, mean step %.3f blocks, %d%% of them subdivided",
                total, stepTotal / total, multiStep * 100 / total));
        for (SweptHull.Outcome outcome : OUTCOMES) {
            int count = byOutcome[outcome.ordinal()];
            if (count == 0) {
                continue;
            }
            String note = switch (outcome) {
                case ALREADY_INSIDE -> "  <-- backstop disabled, no tunnelling guarantee";
                case NO_BOXES -> "  (nothing near the path)";
                case NO_MOTION -> "  (substep carried no movement, nothing to cast)";
                default -> "";
            };
            lines.add(String.format(Locale.ROOT, "    %-14s %5d (%2d%%)%s",
                    outcome, count, count * 100 / total, note));
        }
        // Wedged vs parked look identical elsewhere; a long STALLED run is a vehicle that cannot
        // move at all, not a slow one.
        if (stalled > 0) {
            lines.add(String.format(Locale.ROOT,
                    "  STALLED substeps (asked to move, allowed none): %d (%d%%), longest run %d",
                    stalled, stalled * 100 / total, longestStall));
            if (stallAt != null) {
                lines.add(String.format(Locale.ROOT,
                        "    worst at tick %d, hull (%.1f %.1f %.1f), pitch %.1f roll %.1f,"
                                + " wanted (%.3f %.3f %.3f), blocked by (%.1f %.1f %.1f)",
                        stallAt.gameTime(), stallAt.x(), stallAt.y(), stallAt.z(),
                        stallAt.xRot(), stallAt.zRot(), stallAt.mx(), stallAt.my(), stallAt.mz(),
                        stallAt.blockerX(), stallAt.blockerY(), stallAt.blockerZ()));
            }
        }
        lines.add(String.format(Locale.ROOT,
                "  penetrating substeps (>= %.2f deep): %d (%d%%)",
                PENETRATION_THRESHOLD, penetrating, penetrating * 100 / total));
        if (deepest != null && deepest.penetration() > 0) {
            lines.add(String.format(Locale.ROOT,
                    "  deepest %.3f at tick %d, hull (%.1f %.1f %.1f), block (%.1f %.1f %.1f)",
                    deepest.penetration(), deepest.gameTime(), deepest.x(), deepest.y(),
                    deepest.z(), deepest.blockerX(), deepest.blockerY(), deepest.blockerZ()));
            lines.add(String.format(Locale.ROOT,
                    "    pitch %.1f roll %.1f rotV %.3f, step (%.3f %.3f %.3f) x%d, toi %.2f, %s",
                    deepest.xRot(), deepest.zRot(), deepest.rotV(), deepest.mx(), deepest.my(),
                    deepest.mz(), deepest.substeps(), deepest.toi(), deepest.outcome()));
        }
    }

    private void appendLedger(List<String> lines, String heading, boolean position) {
        lines.add(heading);
        int listed = 0;
        for (Source source : SOURCES) {
            if (source.position != position) {
                continue;
            }
            Stat stat = stat(source);
            if (stat.ticks() == 0) {
                continue;
            }
            listed++;
            lines.add(String.format(Locale.ROOT, "    %-13s %4d ticks  total %+.4f  largest %+.4f",
                    source, stat.ticks(), stat.total(), stat.max()));
        }
        if (listed == 0) {
            // listed==0 != no data: every source was zero the whole window; for the position
            // ledger specifically, the vehicle never moved at all.
            lines.add("    (nothing moved it)");
        }
    }

    /** One CSV row/tick, all columns. Per-source columns expose cadence: periodic launch = feedback loop, single = landing. */
    public Path dump(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(String.format(Locale.ROOT, "physics-%s-%d-%d.csv",
                vehicleName, vehicleId, samples.isEmpty() ? 0 : samples.getLast().gameTime()));
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("tick,x,y,z,vy,dy,dvy,travel,distance,speed,climbRate,"
                    + "onGround,supported,xRot,zRot,rotV,"
                    + "contacts,bottomContacts,blockingContacts");
            for (Source source : SOURCES) {
                writer.write("," + source.name().toLowerCase(Locale.ROOT));
            }
            writer.write('\n');
            double distance = 0;
            for (Sample sample : samples) {
                distance += sample.travel();
                // distance = running sum of travel; y vs distance is the climb profile (hill vs lift).
                writer.write(String.format(Locale.ROOT,
                        "%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.4f,%b,%b,%.2f,%.2f,%.4f,%d,%d,%d",
                        sample.gameTime(), sample.x(), sample.y(), sample.z(), sample.vy(),
                        sample.yDelta(), sample.vyDelta(), sample.travel(), distance,
                        sample.speed(), sample.climbRate(),
                        sample.onGround(), sample.supported(), sample.xRot(), sample.zRot(),
                        sample.rotV(), sample.contacts(), sample.bottomContacts(),
                        sample.blockingContacts()));
                for (Source source : SOURCES) {
                    writer.write(String.format(Locale.ROOT, ",%.5f", sample.of(source)));
                }
                writer.write('\n');
            }
        }
        return file;
    }

    /** One CSV row/substep: pose, requested step, sweep allowance, penetration. Sort by penetration desc for frames that matter. */
    public Path dumpSweeps(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(String.format(Locale.ROOT, "sweeps-%s-%d-%d.csv",
                vehicleName, vehicleId, sweeps.isEmpty() ? 0 : sweeps.getLast().gameTime()));
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("tick,substep,substeps,x,y,z,mx,my,mz,toi,outcome,boxes,"
                    + "penetration,axisX,axisY,axisZ,blockX,blockY,blockZ,xRot,yRot,zRot,rotV\n");
            for (Sweep sweep : sweeps) {
                writer.write(String.format(Locale.ROOT,
                        "%d,%d,%d,%.4f,%.4f,%.4f,%.5f,%.5f,%.5f,%.4f,%s,%d,"
                                + "%.5f,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f\n",
                        sweep.gameTime(), sweep.substep(), sweep.substeps(),
                        sweep.x(), sweep.y(), sweep.z(), sweep.mx(), sweep.my(), sweep.mz(),
                        sweep.toi(), sweep.outcome(), sweep.boxes(),
                        sweep.penetration(), sweep.axisX(), sweep.axisY(), sweep.axisZ(),
                        sweep.blockerX(), sweep.blockerY(), sweep.blockerZ(),
                        sweep.xRot(), sweep.yRot(), sweep.zRot(), sweep.rotV()));
            }
        }
        return file;
    }

    public int sweepCount() {
        return sweeps.size();
    }

    private String label() {
        return vehicleName + " #" + vehicleId;
    }

    /** Null: not a vehicle, or not being traced. */
    public static PhysicsTrace of(Entity entity) {
        return entity instanceof AbstractVehicle vehicle ? vehicle.physicsTrace() : null;
    }

}
