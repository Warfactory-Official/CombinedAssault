package com.wf.combinedassault.vehicle.audio;

import com.wf.combinedassault.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.audio.api.AudioCategory;
import com.wf.wflib.audio.api.SoundSpec;
import com.wf.wflib.audio.api.client.Emitter;
import com.wf.wflib.audio.api.client.SoundScheduler;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/** Client: weapon def {@code sounds} at the station pivot, + travel time. */
@OnlyIn(Dist.CLIENT)
public final class WeaponSounds {
    private WeaponSounds() {
    }

    /** {@code fire} now; {@code shell} half a cycle later (single-round reload: half the reload, <= 1 s). */
    public static void fire(AbstractVehicleWeapon<?> weapon) {
        BaseVehicleWeaponData data = weapon.getData();
        atPivot(weapon, data.sound("fire"), 0);
        SoundSpec shell = data.sound("shell");
        if (shell != null) {
            double cycle = data.getMaxCapacity() == 1 && data.getReload() != null
                    ? Math.min(2, data.getReload().getTime() / 20.0) : data.getShootInterval() / 1000.0;
            atPivot(weapon, shell, cycle / 2);
        }
    }

    /** {@code after}: s from now, before the spec's own delay. */
    public static void atPivot(AbstractVehicleWeapon<?> weapon, @Nullable SoundSpec spec, double after) {
        if (spec == null) {
            return;
        }
        Vec3 at = weapon.getWeaponUnit().worldPivotPosition();
        SoundScheduler.at(Emitter.at(spec, AudioCategory.WEAPONS, at.x, at.y, at.z,
                weapon.getVehicle().getRandom().nextLong()), after + spec.delay());
    }
}
