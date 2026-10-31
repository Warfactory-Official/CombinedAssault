package com.wf.combinedassault.indirect.client;

import com.wf.combinedassault.hud.HudCache;
import com.wf.combinedassault.hud.HudSubjects;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.indirect.BallisticSolver;
import com.wf.combinedassault.indirect.IndirectFire;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.indirect.Trajectory;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.ui.api.Signals;
import com.wf.wflib.ui.api.Subject;
import com.wf.wflib.ui.api.UiContext;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.Nullable;

/**
 * Figures of one gun's built-in computer, refreshed by the renderer at most every {@link #REFRESH_NANOS}; signals
 * read the fields. Range / flight: level ground at the breech height, round in the breech (else the default round).
 */
public final class GunComputer implements UiContext {
    public static final Subject<GunComputer> SUBJECT = Subject.register("computer", GunComputer.class);
    private static final long REFRESH_NANOS = 40_000_000L;

    public enum State {
        STOWED, UNLOADED, LOADING, READY
    }

    final AbstractVehicle vehicle;
    final WeaponUnit unit;

    private long refreshed = Long.MIN_VALUE / 2;
    private RoundSpec rangeSpec;
    private double rangeElevation = Double.NaN;

    State state = State.STOWED;
    boolean crewed;
    double elevation;
    double bearing;
    double stopLow;
    double stopHigh;
    double hullPitch;
    double hullRoll;
    boolean spec;
    double range = Double.NaN;
    double flight = Double.NaN;
    double loadProgress;
    double loadSeconds;
    String round = "";

    public GunComputer(AbstractVehicle vehicle, WeaponUnit unit) {
        this.vehicle = vehicle;
        this.unit = unit;
    }

    public void refresh(float partialTick) {
        long now = System.nanoTime();
        if (now - refreshed < REFRESH_NANOS) {
            return;
        }
        refreshed = now;
        // TODO: WeaponUnit.worldRot allocates (Vec3/Vec2/Quaternionf) per refresh.
        Vec2 pointing = unit.worldRot(unit.getViewXRot(partialTick), unit.getViewYRot(partialTick));
        elevation = -pointing.x;
        bearing = Mth.positiveModulo(pointing.y + 180.0, 360.0);
        stopLow = -unit.getXRotMax();
        stopHigh = -unit.getXRotMin();
        hullPitch = vehicle.getXRot();
        hullRoll = vehicle.getZRot();
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        if (weapon == null) {
            state = State.STOWED;
            return;
        }
        crewed = CrewRules.canLoad(unit);
        loadProgress = weapon.reloadProgress(partialTick);
        loadSeconds = weapon.getReloadTime() / 20.0;
        round = HudCache.roundLabel(weapon.solutionRound());
        state = !unit.supported() ? State.STOWED
                : weapon.isReloading() ? State.LOADING
                : weapon.getRemainAmmo() <= 0 ? State.UNLOADED : State.READY;
        RoundSpec roundSpec = IndirectFire.specOf(unit);
        spec = roundSpec != null;
        if (roundSpec != null) {
            double clamped = Mth.clamp(elevation, stopLow, stopHigh);
            if (!roundSpec.equals(rangeSpec) || clamped != rangeElevation) {
                rangeSpec = roundSpec;
                rangeElevation = clamped;
                range = BallisticSolver.groundRange(roundSpec, clamped);
                double ticks = Trajectory.of(roundSpec, clamped).ticksToDescendTo(0);
                flight = Double.isFinite(ticks) ? ticks / 20.0 : Double.NaN;
            }
        }
    }

    @Nullable
    private AbstractVehicleWeapon<?> weapon() {
        return unit.getCurrentWeapon().orElse(null);
    }

    @Override
    public Object subject(Subject<?> subject) {
        if (subject == SUBJECT) {
            return this;
        }
        if (subject == HudSubjects.VEHICLE) {
            return vehicle;
        }
        if (subject == HudSubjects.STATION) {
            return unit;
        }
        if (subject == HudSubjects.PRIMARY) {
            return weapon();
        }
        return null;
    }

    public static void register() {
        Subject<GunComputer> s = SUBJECT;
        Signals.choice(s, "state", State.class, c -> c.state);
        Signals.bool(s, "crewed", c -> c.crewed);
        Signals.num(s, "elevation", c -> c.elevation);
        Signals.num(s, "bearing", c -> c.bearing);
        Signals.num(s, "stop_low", c -> c.stopLow);
        Signals.num(s, "stop_high", c -> c.stopHigh);
        Signals.num(s, "hull_pitch", c -> c.hullPitch);
        Signals.num(s, "hull_roll", c -> c.hullRoll);
        Signals.bool(s, "has_round", c -> c.spec);
        Signals.num(s, "range", c -> c.range);
        Signals.num(s, "flight", c -> c.flight);
        Signals.num(s, "load_progress", c -> c.loadProgress);
        Signals.num(s, "load_seconds", c -> c.loadSeconds);
        Signals.text(s, "round", 48, c -> c.round);
    }
}
