package com.wf.combinedassault.naval.trial;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.naval.ShipVehicle;
import com.wf.combinedassault.naval.ShipHydrodynamics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One passage of a course, sampled every tick: deterministic helm (reruns differ only by code under
 * test) + a CSV row per tick (a failed run names the tick/leg, not just that it failed).
 */
public final class TrialRun {

    public enum Outcome {
        RUNNING,
        /** Reached the last mark under her own power. */
        COMPLETED,
        /** Aground past the limit a hull could work off. */
        AGROUND,
        /** Out of the channel, sunk, or gone. */
        LOST,
        TIMED_OUT,
        ABANDONED
    }

    private static final int AGROUND_LIMIT = 300;
    private static final int TICKS_PER_HULL = 1200;

    private final ServerLevel level;
    private final ShipVehicle ship;
    private final TrialCourse.Built course;
    private final TrialPilot pilot;
    private final TrialCrew crew;
    private final long started;
    private final int limit;
    private final long[] stageNanos = new long[TrialProfile.stageCount()];
    private final long[] stageCalls = new long[TrialProfile.stageCount()];
    private final long[] stageTotals = new long[TrialProfile.stageCount()];
    private final long[] work = new long[TrialProfile.workCount()];
    private final long[] workTotals = new long[TrialProfile.workCount()];
    private final List<String> rows = new ArrayList<>();
    private final List<Double> shipCosts = new ArrayList<>();

    private Outcome outcome = Outcome.RUNNING;
    private int ticks;
    private int agroundRun;
    private int shallowTicks;
    private int agroundTicks;
    private double worstKeel = Double.POSITIVE_INFINITY;
    private double worstMargin = Double.POSITIVE_INFINITY;
    private String worstMarginLeg = "";
    private double peakCost;
    private String peakCostLeg = "";
    private double maxServerTickMs;
    private double paceSum;
    private int paceTicks;
    private int minAboard = Integer.MAX_VALUE;
    private double maxParkedDrift;
    private double distance;
    private double lastX;
    private double lastZ;

    public TrialRun(ServerLevel level, ShipVehicle ship, TrialCourse.Built course,
                    TrialPilot pilot, TrialCrew crew) {
        this.level = level;
        this.ship = ship;
        this.course = course;
        this.pilot = pilot;
        this.crew = crew;
        this.started = level.getGameTime();
        this.limit = (int) Math.max(600, course.length() / Math.max(1.0, course.hullLength()) * TICKS_PER_HULL);
        this.lastX = ship.getX();
        this.lastZ = ship.getZ();
        rows.add(header());
        TrialProfile.setEnabled(true);
    }

    public Outcome outcome() {
        return outcome;
    }

    public ShipVehicle ship() {
        return ship;
    }

    public int ticks() {
        return ticks;
    }

    public TrialCourse.Built course() {
        return course;
    }

    public TrialPilot pilot() {
        return pilot;
    }

    public void tick() {
        if (outcome != Outcome.RUNNING) {
            return;
        }
        if (ship.isRemoved() || !ship.isAlive()) {
            finish(Outcome.LOST);
            return;
        }
        ticks++;
        pilot.tick(ship);
        crew.tick(ship);
        sample();

        if (pilot.finished()) {
            finish(Outcome.COMPLETED);
        } else if (agroundRun >= AGROUND_LIMIT) {
            finish(Outcome.AGROUND);
        } else if (ticks >= limit) {
            finish(Outcome.TIMED_OUT);
        }
    }

    public void abandon() {
        if (outcome == Outcome.RUNNING) {
            finish(Outcome.ABANDONED);
        }
    }

    private void finish(Outcome result) {
        outcome = result;
        ship.setHelm(0, 0);
        TrialProfile.setEnabled(false);
    }

    private void sample() {
        TrialProfile.drain(stageNanos, stageCalls);
        TrialProfile.drainWork(work);
        for (int i = 0; i < stageNanos.length; i++) {
            stageTotals[i] += stageNanos[i];
        }
        for (int i = 0; i < work.length; i++) {
            workTotals[i] += work[i];
        }
        ShipHydrodynamics model = ship.model();
        ShipHydrodynamics.Environment env = ship.environment();
        double speed = Math.hypot(ship.getX() - lastX, ship.getZ() - lastZ);
        distance += speed;
        lastX = ship.getX();
        lastZ = ship.getZ();

        boolean aground = ship.aground();
        agroundRun = aground ? agroundRun + 1 : 0;
        if (aground) {
            agroundTicks++;
        }
        if (model.shallow) {
            shallowTicks++;
        }
        if (!Double.isNaN(env.keelClearance)) {
            worstKeel = Math.min(worstKeel, env.keelClearance);
        }
        if (pilot.margin() < worstMargin) {
            worstMargin = pilot.margin();
            worstMarginLeg = pilot.legName();
        }
        double costUs = ship.lastTickNanos() / 1000.0;
        shipCosts.add(costUs);
        if (costUs > peakCost) {
            peakCost = costUs;
            peakCostLeg = pilot.legName();
        }
        double serverMs = level.getServer().getAverageTickTimeNanos() / 1.0E6;
        maxServerTickMs = Math.max(maxServerTickMs, serverMs);
        if (crew.crewCount() > 0) {
            paceSum += crew.pace;
            paceTicks++;
            minAboard = Math.min(minAboard, crew.aboard);
        }
        maxParkedDrift = Math.max(maxParkedDrift, crew.parkedDrift);

        StringBuilder row = new StringBuilder(220);
        row.append(level.getGameTime() - started).append(',')
                .append(pilot.leg()).append(',').append('"').append(pilot.legName()).append('"').append(',');
        append(row, ship.getX());
        append(row, ship.getZ());
        append(row, ship.getYRot());
        append(row, ship.getXRot());
        append(row, ship.getZRot());
        append(row, speed);
        append(row, model.surge);
        append(row, model.throttle);
        append(row, model.rudder);
        append(row, env.keelClearance);
        append(row, env.waterDepth);
        row.append(model.shallow ? 1 : 0).append(',').append(aground ? 1 : 0).append(',')
                .append(env.afloat ? 1 : 0).append(',')
                .append(ship.blocked() ? 1 : 0).append(',').append(model.turnBlocked ? 1 : 0).append(',');
        append(row, pilot.crossTrack());
        append(row, pilot.margin());
        append(row, pilot.bearingError());
        append(row, costUs);
        for (int i = 0; i < stageNanos.length; i++) {
            append(row, stageNanos[i] / 1000.0);
        }
        for (int i = 0; i < stageCalls.length; i++) {
            row.append(stageCalls[i]).append(',');
        }
        for (int i = 0; i < work.length; i++) {
            row.append(work[i]).append(',');
        }
        row.append(ship.deckRiders().size()).append(',')
                .append(ship.harnessedVehicles().size()).append(',')
                .append(crew.aboard).append(',');
        append(row, crew.pace);
        append(row, crew.parkedDrift);
        append(row, serverMs);
        row.setLength(row.length() - 1);
        rows.add(row.toString());
    }

    private static void append(StringBuilder row, double value) {
        if (Double.isNaN(value)) {
            row.append(",");
            return;
        }
        row.append(String.format(Locale.ROOT, "%.4f", value)).append(',');
    }

    private static String header() {
        StringBuilder head = new StringBuilder(
                "tick,leg,leg_name,x,z,yaw,pitch,roll,speed,surge,throttle,rudder,"
                        + "keel_clearance,water_depth,shallow,aground,afloat,blocked,turn_blocked,"
                        + "cross_track,margin,bearing_error,ship_tick_us");
        for (TrialProfile.Stage stage : TrialProfile.Stage.values()) {
            head.append(",us_").append(stage.name().toLowerCase(Locale.ROOT));
        }
        for (TrialProfile.Stage stage : TrialProfile.Stage.values()) {
            head.append(",calls_").append(stage.name().toLowerCase(Locale.ROOT));
        }
        for (TrialProfile.Work item : TrialProfile.Work.values()) {
            head.append(",n_").append(item.name().toLowerCase(Locale.ROOT));
        }
        head.append(",riders,harnessed,crew_aboard,crew_pace,parked_drift,server_tick_ms");
        return head.toString();
    }

    /** @return file path, or null on IO failure */
    @Nullable
    public Path dump(String name) {
        Path directory = FMLPaths.GAMEDIR.get().resolve("combinedassault_trials");
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(name + "-" + course.name() + "-" + started + ".csv");
            Files.write(file, rows, StandardCharsets.UTF_8);
            return file;
        } catch (IOException failed) {
            return null;
        }
    }

    public List<String> report() {
        List<String> lines = new ArrayList<>();
        double[] costs = shipCosts.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(costs);
        double mean = 0;
        for (double cost : costs) {
            mean += cost;
        }
        mean = costs.length == 0 ? 0 : mean / costs.length;
        lines.add(String.format(Locale.ROOT, "%s: %s after %d ticks, %.0f blocks made good of %.0f",
                course.name(), outcome, ticks, distance, course.length()));
        lines.add(String.format(Locale.ROOT,
                "  water: worst keel clearance %.2f, %d ticks dragging, %d aground",
                worstKeel, shallowTicks, agroundTicks));
        lines.add(String.format(Locale.ROOT,
                "  channel: closest the hull came to a bank %.1f blocks, on %s",
                worstMargin, worstMarginLeg));
        lines.add(String.format(Locale.ROOT,
                "  ship tick: mean %.0f us, median %.0f, p95 %.0f, peak %.0f on %s",
                mean, percentile(costs, 0.5), percentile(costs, 0.95), peakCost, peakCostLeg));
        StringBuilder stages = new StringBuilder("  of that tick, us per tick:");
        for (TrialProfile.Stage stage : TrialProfile.Stage.values()) {
            stages.append(String.format(Locale.ROOT, " %s %.0f",
                    stage.name().toLowerCase(Locale.ROOT),
                    ticks == 0 ? 0 : stageTotals[stage.ordinal()] / 1000.0 / ticks));
        }
        // SEA (nested in MOVE) excluded from the sum. Remaining
        // stages partition a vehicle tick, but counters are level-wide: parked vehicles' own ticks
        // land here too, so only an empty-deck run compares straight to the hull's own tick.
        double probed = 0;
        for (TrialProfile.Stage stage : TrialProfile.Stage.values()) {
            if (stage == TrialProfile.Stage.SEA) {
                continue;
            }
            probed += ticks == 0 ? 0 : stageTotals[stage.ordinal()] / 1000.0 / ticks;
        }
        stages.append(String.format(Locale.ROOT, " (%.0f us over every vehicle, against %.0f for the hull)",
                probed, mean));
        lines.add(stages.toString());
        StringBuilder over = new StringBuilder("  what the solve worked over, per tick:");
        for (TrialProfile.Work item : TrialProfile.Work.values()) {
            over.append(String.format(Locale.ROOT, " %s %.0f",
                    item.name().toLowerCase(Locale.ROOT),
                    ticks == 0 ? 0 : (double) workTotals[item.ordinal()] / ticks));
        }
        lines.add(over.toString());
        if (crew.crewCount() > 0) {
            lines.add(String.format(Locale.ROOT,
                    "  crew: %d aboard at muster, fewest %d, pace %.4f blocks a tick in the deck's frame",
                    crew.crewCount(), minAboard == Integer.MAX_VALUE ? 0 : minAboard,
                    paceTicks == 0 ? 0 : paceSum / paceTicks));
        }
        if (crew.parkedCount() > 0) {
            lines.add(String.format(Locale.ROOT, "  parked: %d on deck, worst drift %.3f blocks",
                    crew.parkedCount(), maxParkedDrift));
        }
        lines.add(String.format(Locale.ROOT, "  server: worst average tick %.2f ms", maxServerTickMs));
        return lines;
    }

    private static double percentile(double[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.min(sorted.length - 1, Math.round(fraction * (sorted.length - 1)));
        return sorted[index];
    }

    /** null, "", or "none" => no vehicle parked */
    @Nullable
    public static ResourceLocation parkedVehicle(String id) {
        return id == null || id.isEmpty() || "none".equals(id) ? null : ResourceLocation.parse(id);
    }

}
