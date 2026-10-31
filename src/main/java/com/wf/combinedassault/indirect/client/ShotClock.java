package com.wf.combinedassault.indirect.client;

import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import com.wf.combinedassault.vehicle.event.VehicleFireEvent;
import com.wf.combinedassault.indirect.GunLaying;
import com.wf.combinedassault.indirect.IndirectFire;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.indirect.Trajectory;
import com.wf.combinedassault.indirect.FireSolution;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.weapon.part.WeaponUnit;

/**
 * ETA of fired rounds landing, captured from the laying at the moment of firing, not read live later:
 * re-laying the gun behind a shot must not change its ETA. Game ticks, not millis: pauses with the world.
 */
public final class ShotClock {

    /** Ticks the board keeps showing an impact after it's due. */
    static final int LINGER = 30;

    private static final int MAX_TRACKED = 16;

    private static final double[] DUE = new double[MAX_TRACKED];
    private static int count;

    private ShotClock() {
    }

    @SubscribeEvent
    public static void onFire(VehicleFireEvent.Post event) {
        if (!event.isClientSide()) {
            return;
        }
        WeaponUnit unit = event.getWeapon().getWeaponUnit();
        LocalVehiclePlayer local = LocalVehiclePlayer.instance();
        if (unit == null || local == null || unit.getOwner() != local.getPlayer()) {
            return;
        }
        GunLaying laying = unit.indirectFire();
        if (!laying.isIndirect()) {
            return;
        }
        double ticks = flightTicks(unit, laying);
        if (!(ticks > 0) || !Double.isFinite(ticks)) {
            return;
        }
        Level level = event.getVehicle().level();
        record(level.getGameTime() + ticks);
    }

    /** Solved flight time if laid on a point, else time back down to muzzle height. */
    private static double flightTicks(WeaponUnit unit, GunLaying laying) {
        FireSolution solution = laying.solution();
        if (solution != null) {
            return solution.flightTicks();
        }
        RoundSpec spec = IndirectFire.specOf(unit);
        return spec == null ? 0 : Trajectory.of(spec, laying.elevation()).ticksToDescendTo(0);
    }

    static void record(double dueTick) {
        if (count < MAX_TRACKED) {
            DUE[count++] = dueTick;
        }
    }

    /** Drop what has already landed. Returns how many rounds are still on the board. */
    static int prune(double now) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (DUE[i] + LINGER > now) {
                DUE[kept++] = DUE[i];
            }
        }
        count = kept;
        return count;
    }

    /** The next round due, in ticks from now, or NaN if the board is empty. Negative means it has landed. */
    static double nextDue(double now) {
        double soonest = Double.NaN;
        for (int i = 0; i < count; i++) {
            double left = DUE[i] - now;
            if (Double.isNaN(soonest) || left < soonest) {
                soonest = left;
            }
        }
        return soonest;
    }

    static void clear() {
        count = 0;
    }

}
