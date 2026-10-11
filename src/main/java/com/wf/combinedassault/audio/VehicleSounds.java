package com.wf.combinedassault.audio;

import com.wf.combinedassault.all.AllSounds;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.audio.WfAudio;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.List;

/** Built-in vehicle sounds; server one-shots through {@link WfAudio}. */
public final class VehicleSounds {
    public static final SoundSpec HIT_SMALL = spec(AllSounds.VEHICLE_HIT_SMALL, 96);
    public static final SoundSpec HIT_MED = spec(AllSounds.VEHICLE_HIT_MED, 128);
    public static final SoundSpec HIT_BIG = spec(AllSounds.VEHICLE_HIT_BIG, 160);
    public static final SoundSpec SELECT_WEAPON = spec(AllSounds.SELECT_WEAPON, 16);
    public static final SoundSpec IR_TRACK_ALARM = spec(AllSounds.IR_TRACK_ALARM, 16);
    public static final SoundSpec RADAR_SEARCH_WARN = spec(AllSounds.RADAR_SEARCH_WARN, 16);
    public static final SoundSpec RADAR_LOCK_WARN = spec(AllSounds.RADAR_LOCK_WARN, 16);
    public static final SoundSpec MISSILE_LAUNCH_WARN = spec(AllSounds.MISSILE_LAUNCH_WARN, 16);
    public static final SoundSpec TIRE_SQUEAL = spec(AllSounds.TIRE_SQUEAL, 64);
    public static final SoundSpec LANDING = spec(AllSounds.LANDING, 96);
    public static final SoundSpec SMOKE_START = spec(AllSounds.AEROBATICS_SMOKE_START, 64);
    public static final SoundSpec SMOKE_LOOP = spec(AllSounds.AEROBATICS_SMOKE_LOOP, 96);
    public static final SoundSpec SERVO_H = spec(AllSounds.TURRET_TURN_SERVO_H, 24);
    public static final SoundSpec SERVO_V = spec(AllSounds.TURRET_TURN_SERVO_V, 24);

    private VehicleSounds() {
    }

    private static SoundSpec spec(DeferredHolder<SoundEvent, SoundEvent> event, float range) {
        return SoundSpec.of(event.getId(), 1, range);
    }

    /** Server: {@code spec} at world {@code at} for everyone in range. */
    public static void at(AbstractVehicle vehicle, Vec3 at, SoundSpec spec, AudioCategory category) {
        WfAudio.at((ServerLevel) vehicle.level(), at, category, List.of(spec));
    }
}
