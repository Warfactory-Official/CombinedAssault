package com.wf.combinedassault.tow.trial;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.tow.HitchInfo;
import com.wf.combinedassault.tow.TowLink;
import com.wf.combinedassault.tow.TowLinks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Verdict is not one number: a coupling can hold while a trailer sits buried, or buzzes against
 * its stops. Every row carries coupling + both vehicles' attitude + ground clearance; verdict is
 * the worst of them. */
public final class TowTrialRun {

    public enum Outcome {
        RUNNING,
        COMPLETED,
        /** Coupling let go. */
        PARTED,
        STUCK,
        TIMED_OUT
    }

    /** Coupling-open limit, blocks, past which a lap is a failure, not lag. */
    private static final double OPEN_LIMIT = 0.5;

    /** Hull-below-ground limit, blocks. Measured, not tidy: a solo tractor on ROUGH alone reaches
     * 1.08 blocks under surface and is airborne a third of the lap; tighter fails the host, not the tow. */
    private static final double BURIED_LIMIT = 1.25;

    /** Articulation-change limit, degrees/tick, above which the trailer is buzzing, not steering. */
    private static final double CHATTER_LIMIT = 12.0;

    /** Consecutive no-progress ticks before the run is called off. */
    private static final int STUCK_LIMIT = 200;

    /** Run timeout, ticks per ring block. */
    private static final int TICKS_PER_BLOCK = 12;

    private final ServerLevel level;
    private final AbstractVehicle tractor;
    private final AbstractVehicle trailer;
    private final TowGround.Built course;
    private final TowDriver driver;
    private final double laps;
    private final int limit;
    private final List<String> rows = new ArrayList<>();

    private Outcome outcome = Outcome.RUNNING;
    private int ticks;
    private int stuckFor;
    private double lastArticulation = Double.NaN;
    private double travelled;
    private double lastX;
    private double lastZ;

    private double worstGap;
    private double worstClearance = Double.POSITIVE_INFINITY;
    private double worstChatter;
    private double worstArticulation;
    private int airborneTicks;
    private String worstGapSurface = "";
    private String worstClearanceSurface = "";

    private final String name;

    public TowTrialRun(ServerLevel level, AbstractVehicle tractor, AbstractVehicle trailer,
                       TowGround.Built course, TowDriver driver, double laps, String name) {
        this.level = level;
        this.tractor = tractor;
        this.trailer = trailer;
        this.course = course;
        this.driver = driver;
        this.laps = laps;
        this.name = name;
        this.limit = (int) (2 * Math.PI * course.spec().radius() * laps * TICKS_PER_BLOCK);
        this.lastX = tractor.getX();
        this.lastZ = tractor.getZ();
        rows.add("tick,lap,surface,x,z,speed,bearing_error,tractor_pitch,tractor_roll,trailer_pitch,"
                + "trailer_roll,articulation,chatter,gap,strain,"
                + "tractor_clearance,trailer_clearance,trailer_airborne,coupled,tick_ms");
    }

    public Outcome outcome() {
        return outcome;
    }

    public int ticks() {
        return ticks;
    }

    public void tick() {
        if (outcome != Outcome.RUNNING) {
            return;
        }
        if (tractor.isRemoved() || trailer.isRemoved()) {
            outcome = Outcome.PARTED;
            return;
        }
        driver.tick(tractor);
        ticks++;

        // solo: trailer == tractor (no coupling to lose).
        boolean solo = trailer == tractor;
        TowLink link = trailer.towLink();
        boolean coupled = solo || link != null;
        double gap = Double.NaN;
        double strain = Double.NaN;
        if (link != null) {
            HitchInfo hitch = link.hitch();
            if (hitch != null) {
                gap = TowLinks.hitchPoint(tractor, hitch).distanceTo(TowLinks.couplingPoint(trailer));
            }
            strain = link.strain();
        }

        Vec3 velocity = tractor.getDeltaMovement();
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double articulation = Mth.wrapDegrees(trailer.getYRot() - tractor.getYRot());
        double chatter = Double.isNaN(lastArticulation) ? 0
                : Math.abs(Mth.wrapDegrees((float) (articulation - lastArticulation)));
        lastArticulation = articulation;
        double tractorClearance = TowGround.clearance(level, tractor);
        double trailerClearance = TowGround.clearance(level, trailer);
        String surface = course.surfaceAt(tractor.getX(), tractor.getZ()).lower();

        travelled += Math.hypot(tractor.getX() - lastX, tractor.getZ() - lastZ);
        lastX = tractor.getX();
        lastZ = tractor.getZ();
        stuckFor = speed < 0.01 ? stuckFor + 1 : 0;

        if (!Double.isNaN(strain) && strain > worstGap) {
            // Tracks strain, not gap: a cable coupling fails by stretch past its own length.
            worstGap = strain;
            worstGapSurface = surface;
        }
        if (trailerClearance < worstClearance) {
            worstClearance = trailerClearance;
            worstClearanceSurface = surface;
        }
        worstChatter = Math.max(worstChatter, chatter);
        worstArticulation = Math.max(worstArticulation, Math.abs(articulation));
        if (!trailer.onGround()) {
            airborneTicks++;
        }

        rows.add(String.format(Locale.ROOT,
                "%d,%.3f,%s,%.2f,%.2f,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.3f,%.3f,"
                        + "%.3f,%.3f,%d,%d,%.3f",
                ticks, driver.laps(), surface, tractor.getX(), tractor.getZ(), speed,
                driver.bearingError(), tractor.getXRot(), tractor.getZRot(), trailer.getXRot(),
                trailer.getZRot(), articulation, chatter, gap, strain,
                tractorClearance, trailerClearance, trailer.onGround() ? 0 : 1, coupled ? 1 : 0,
                level.getServer().getAverageTickTimeNanos() / 1.0E6));

        if (!coupled) {
            outcome = Outcome.PARTED;
        } else if (driver.laps() >= laps) {
            outcome = Outcome.COMPLETED;
        } else if (stuckFor > STUCK_LIMIT) {
            outcome = Outcome.STUCK;
        } else if (ticks > limit) {
            outcome = Outcome.TIMED_OUT;
        }
        if (outcome != Outcome.RUNNING) {
            TowDriver.stop(tractor);
        }
    }

    public List<String> verdict() {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "%s after %d ticks, %.2f laps, %.0f blocks",
                outcome.name().toLowerCase(Locale.ROOT), ticks, driver.laps(), travelled));
        out.add(String.format(Locale.ROOT, "  coupling: worst strain %.3f on %s (limit %.2f)",
                worstGap, worstGapSurface.isEmpty() ? "none" : worstGapSurface, OPEN_LIMIT));
        out.add(String.format(Locale.ROOT,
                "  trailer:  worst clearance %.3f on %s (buried past %.2f), airborne %d ticks",
                worstClearance, worstClearanceSurface.isEmpty() ? "none" : worstClearanceSurface,
                -BURIED_LIMIT, airborneTicks));
        out.add(String.format(Locale.ROOT,
                "  steering: peak articulation %.1f degrees, worst chatter %.1f a tick (limit %.1f)",
                worstArticulation, worstChatter, CHATTER_LIMIT));
        return out;
    }

    public boolean clean() {
        return outcome == Outcome.COMPLETED
                && worstGap <= OPEN_LIMIT
                && worstClearance >= -BURIED_LIMIT
                && worstChatter <= CHATTER_LIMIT;
    }

    @Nullable
    public Path dump(String suffix) {
        Path directory = FMLPaths.GAMEDIR.get().resolve("combinedassault_trials");
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("tow-" + name + suffix + ".csv");
            Files.write(file, rows, StandardCharsets.UTF_8);
            return file;
        } catch (IOException failure) {
            return null;
        }
    }

}
