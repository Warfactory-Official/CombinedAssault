package com.wf.combinedassault.barrel.client;

import com.wf.combinedassault.barrel.GunBarrel;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.wflib.barrel.BarrelState;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import net.minecraft.client.Minecraft;

/**
 * Weapon subject signals: {@code barrel}, {@code heat} [0,1], {@code temp} degC, {@code wear}, {@code jammed},
 * {@code clearing} / {@code changing} progress.
 */
public final class BarrelHud {
    private BarrelHud() {
    }

    public static void weapon(Subject<AbstractVehicleWeapon<?>> subject) {
        Signals.bool(subject, "barrel", w -> w.barrel() != null);
        Signals.num(subject, "heat", w -> {
            GunBarrel b = w.barrel();
            return b == null ? 0 : (b.state().temp() - BarrelState.AMBIENT) / (b.spec().max() - BarrelState.AMBIENT);
        });
        Signals.num(subject, "temp", w -> w.barrel() == null ? BarrelState.AMBIENT : w.barrel().state().temp());
        Signals.num(subject, "wear", w -> w.barrel() == null ? 0 : w.barrel().state().wear());
        Signals.bool(subject, "jammed", w -> w.barrel() != null && w.barrel().jammed());
        Signals.num(subject, "clearing", w -> w.barrel() == null ? 0 : w.barrel().clearProgress(partialTick()));
        Signals.num(subject, "changing", w -> w.barrel() == null ? 0 : w.barrel().swapProgress(partialTick()));
    }

    private static float partialTick() {
        return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
    }
}
