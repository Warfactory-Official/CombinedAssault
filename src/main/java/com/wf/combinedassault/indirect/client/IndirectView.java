package com.wf.combinedassault.indirect.client;

import com.wf.combinedassault.hud.HudCache;
import com.wf.combinedassault.indirect.BallisticSolver;
import com.wf.combinedassault.indirect.FireArc;
import com.wf.combinedassault.indirect.FireMission;
import com.wf.combinedassault.indirect.FireSolution;
import com.wf.combinedassault.indirect.GunLaying;
import com.wf.combinedassault.indirect.IndirectFire;
import com.wf.combinedassault.indirect.LayingMode;
import com.wf.combinedassault.indirect.ReachBand;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.ui.api.Signals;
import com.wf.wflib.ui.api.Subject;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Gun-laying figures of the local station while it is off the sight, derived once per frame for the
 * {@code indirect} board. Solver results are cached until spec, stops, arc or elevation change.
 */
public final class IndirectView {
    public static final Subject<IndirectView> SUBJECT = Subject.register("indirect", IndirectView.class);
    public static final Subject<StepCell> STEP = Subject.register("indirect_step", StepCell.class);

    /** Legend stands this long after off-sight, fading over the last {@link #LEGEND_FADE}. */
    private static final long LEGEND_MILLIS = 9000;
    private static final long LEGEND_FADE = 2000;
    /** Shorter stand when only announcing a just-made change. */
    private static final long LEGEND_AGAIN = 3500;

    private static final IndirectView VIEW = new IndirectView();

    public enum State {
        LAYING, LAID, STOP
    }

    public enum RangeKey {
        ELEVATE, CORRECT
    }

    public enum LineKey {
        TRAVERSE, SHIFT
    }

    public static final class StepCell {
        final int index;
        final String label;
        final boolean inBlocks;

        StepCell(int index, GunLaying.Step step) {
            this.index = index;
            this.inBlocks = step.inBlocks();
            String size = step.size() >= 1 ? Long.toString((long) step.size()) : Double.toString(step.size()).substring(1);
            this.label = size + (step.inBlocks() ? "m" : "°");
        }
    }

    private final List<StepCell> steps;

    private WeaponUnit unit;
    private boolean standing;
    private long legendSince;
    private boolean correcting;

    double gunElevation;
    double gunBearing;
    double laidElevation;
    double laidBearing;
    double stopLow;
    double stopHigh;
    boolean withinStops;
    double error;
    @Nullable
    RoundSpec spec;
    @Nullable
    ReachBand band;
    double laidRange;
    @Nullable
    FireSolution solution;
    @Nullable
    Vec3 target;
    int inFlight;
    double splashTicks;
    @Nullable
    FireMission ordered;
    private String orderedRound = "";

    @Nullable
    private RoundSpec bandSpec;
    private double bandLow;
    private double bandHigh;
    @Nullable
    private FireArc bandArc;
    @Nullable
    private RoundSpec rangeSpec;
    private double rangeElevation = Double.NaN;
    @Nullable
    private FireMission orderedFor;

    private IndirectView() {
        StepCell[] cells = new StepCell[GunLaying.STEPS.length];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new StepCell(i, GunLaying.STEPS[i]);
        }
        steps = List.of(cells);
    }

    /** Once per frame with the local station (null => none); the view while it is off the sight, else null. */
    @Nullable
    public static IndirectView refresh(@Nullable WeaponUnit station, float partialTick) {
        IndirectView v = VIEW;
        if (station == null || !station.indirectFire().isIndirect()) {
            if (v.standing) {
                v.standing = false;
                ShotClock.clear();
            }
            v.unit = null;
            return null;
        }
        GunLaying laying = station.indirectFire();
        if (!v.standing) {
            v.standing = true;
            v.correcting = laying.step().inBlocks();
            v.legendSince = Util.getMillis();
        }
        if (v.correcting != laying.step().inBlocks()) {
            v.correcting = laying.step().inBlocks();
            v.legendSince = Util.getMillis() - (LEGEND_MILLIS - LEGEND_AGAIN);
        }
        v.unit = station;
        v.update(station, laying, partialTick);
        return v;
    }

    private void update(WeaponUnit unit, GunLaying laying, float partialTick) {
        // TODO: WeaponUnit.worldRot allocates (Vec3/Vec2/Quaternionf) once per frame here.
        Vec2 pointing = unit.worldRot(unit.getViewXRot(partialTick), unit.getViewYRot(partialTick));
        gunElevation = -pointing.x;
        gunBearing = compass(pointing.y);
        laidElevation = laying.elevation();
        laidBearing = compass(laying.azimuth());
        stopLow = -unit.getXRotMax();
        stopHigh = -unit.getXRotMin();
        withinStops = laidElevation >= stopLow - 1.0E-4 && laidElevation <= stopHigh + 1.0E-4;
        error = laying.error(gunElevation, pointing.y);
        spec = IndirectFire.specOf(unit);
        if (spec != null) {
            band(spec, stopLow, stopHigh, laying.arc());
            laidRange(spec, Mth.clamp(laidElevation, stopLow, stopHigh));
        }
        solution = laying.solution();
        target = laying.target();
        Minecraft minecraft = Minecraft.getInstance();
        double now = minecraft.level == null ? 0 : minecraft.level.getGameTime() + partialTick;
        inFlight = ShotClock.prune(now);
        splashTicks = inFlight > 0 ? ShotClock.nextDue(now) : Double.NaN;
        ordered = unit.fireMission();
        if (ordered != orderedFor) {
            orderedFor = ordered;
            orderedRound = ordered == null || ordered.round() == Items.AIR ? ""
                    : HudCache.translate(ordered.round().getDescriptionId());
        }
    }

    private void band(RoundSpec spec, double lowest, double highest, FireArc arc) {
        if (band == null || !spec.equals(bandSpec) || lowest != bandLow || highest != bandHigh || arc != bandArc) {
            bandSpec = spec;
            bandLow = lowest;
            bandHigh = highest;
            bandArc = arc;
            band = ReachBand.of(spec, lowest, highest, arc);
        }
    }

    private void laidRange(RoundSpec spec, double elevation) {
        if (!spec.equals(rangeSpec) || elevation != rangeElevation) {
            rangeSpec = spec;
            rangeElevation = elevation;
            laidRange = BallisticSolver.groundRange(spec, elevation);
        }
    }

    private static double compass(double azimuth) {
        return Mth.positiveModulo(azimuth + 180.0, 360.0);
    }

    private double legend() {
        long shown = Util.getMillis() - legendSince;
        if (shown > LEGEND_MILLIS) {
            return 0;
        }
        return shown < LEGEND_MILLIS - LEGEND_FADE ? 1 : (LEGEND_MILLIS - shown) / (double) LEGEND_FADE;
    }

    private State state() {
        return !withinStops ? State.STOP : error <= IndirectFire.LAID_TOLERANCE ? State.LAID : State.LAYING;
    }

    private boolean mission() {
        return solution != null && target != null;
    }

    public static void register() {
        Subject<IndirectView> s = SUBJECT;
        Signals.num(s, "gun_elevation", v -> v.gunElevation);
        Signals.num(s, "gun_bearing", v -> v.gunBearing);
        Signals.num(s, "laid_elevation", v -> v.laidElevation);
        Signals.num(s, "laid_bearing", v -> v.laidBearing);
        Signals.num(s, "stop_low", v -> v.stopLow);
        Signals.num(s, "stop_high", v -> v.stopHigh);
        Signals.bool(s, "within_stops", v -> v.withinStops);
        Signals.choice(s, "state", State.class, IndirectView::state);
        Signals.choice(s, "mode", LayingMode.class, v -> v.unit.indirectFire().mode());
        Signals.choice(s, "arc", FireArc.class, v -> v.unit.indirectFire().arc());
        Signals.bool(s, "self_guided", v -> IndirectFire.selfGuided(v.unit));
        Signals.bool(s, "in_blocks", v -> v.correcting);
        Signals.choice(s, "range_key", RangeKey.class, v -> v.correcting ? RangeKey.CORRECT : RangeKey.ELEVATE);
        Signals.choice(s, "line_key", LineKey.class, v -> v.correcting ? LineKey.SHIFT : LineKey.TRAVERSE);
        Signals.num(s, "legend", IndirectView::legend);
        Signals.items(s, "steps", v -> v.steps, STEP);
        Signals.text(STEP, "label", 8, c -> c.label);
        Signals.bool(STEP, "live", c -> c.index == VIEW.unit.indirectFire().stepIndex());
        Signals.bool(STEP, "usable", c -> VIEW.spec != null || !c.inBlocks);
        Signals.bool(s, "has_round", v -> v.spec != null);
        Signals.bool(s, "covered", v -> v.band != null && v.band.covered());
        Signals.num(s, "reach_near", v -> v.band == null ? Double.NaN : v.band.nearest());
        Signals.num(s, "reach_far", v -> v.band == null ? Double.NaN : v.band.farthest());
        Signals.num(s, "reach_max", v -> v.band == null ? Double.NaN : v.band.maxRange());
        Signals.num(s, "reach_laid", v -> v.spec == null ? Double.NaN : v.laidRange);
        Signals.bool(s, "mission", IndirectView::mission);
        Signals.num(s, "target_x", v -> v.target == null ? Double.NaN : v.target.x);
        Signals.num(s, "target_y", v -> v.target == null ? Double.NaN : v.target.y);
        Signals.num(s, "target_z", v -> v.target == null ? Double.NaN : v.target.z);
        Signals.num(s, "flight", v -> v.solution == null ? Double.NaN : v.solution.flightSeconds());
        Signals.num(s, "apex", v -> v.solution == null ? Double.NaN : v.solution.apexHeight());
        Signals.num(s, "in_flight", v -> v.inFlight);
        Signals.num(s, "splash_in", v -> v.splashTicks / 20.0);
        Signals.bool(s, "ordered", v -> v.ordered != null);
        Signals.text(s, "ordered_round", 48, v -> v.orderedRound);
        Signals.num(s, "ordered_remaining", v -> v.ordered == null ? Double.NaN : v.ordered.remaining());
    }
}
