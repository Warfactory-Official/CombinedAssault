package com.wf.combinedassault.vehicle.audio;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;

public class VehicleSoundEvents {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(net.minecraft.core.registries.Registries.SOUND_EVENT, CombinedAssault.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> VEHICLE_HURT = registerSoundEvent("vehicle_hurt", CombinedAssault.modLocation("vehicle_hurt"));
    public static final DeferredHolder<SoundEvent, SoundEvent> VEHICLE_HIT_SMALL = registerSoundEvent("vehicle_hit_small", CombinedAssault.modLocation("vehicle_hit_small"));
    public static final DeferredHolder<SoundEvent, SoundEvent> VEHICLE_HIT_MED = registerSoundEvent("vehicle_hit_med", CombinedAssault.modLocation("vehicle_hit_med"));
    public static final DeferredHolder<SoundEvent, SoundEvent> VEHICLE_HIT_BIG = registerSoundEvent("vehicle_hit_big", CombinedAssault.modLocation("vehicle_hit_big"));
    public static final DeferredHolder<SoundEvent, SoundEvent> IR_TRACK_ALARM = registerSoundEvent("ir_track_alarm", CombinedAssault.modLocation("ir_track_alarm"));
    public static final DeferredHolder<SoundEvent, SoundEvent> RADAR_SEARCH_WARN = registerSoundEvent("radar_search_warn", CombinedAssault.modLocation("radar_search_warn"));
    public static final DeferredHolder<SoundEvent, SoundEvent> RADAR_LOCK_WARN = registerSoundEvent("radar_lock_warn", CombinedAssault.modLocation("radar_lock_warn"));
    public static final DeferredHolder<SoundEvent, SoundEvent> MISSILE_LAUNCH_WARN = registerSoundEvent("missile_launch_warn", CombinedAssault.modLocation("missile_launch_warn"));
    public static final DeferredHolder<SoundEvent, SoundEvent> GUN_7_62MM_SHOT = registerSoundEvent("gun_7.62mm_shot", CombinedAssault.modLocation("gun_7.62mm_shot"));
    public static final DeferredHolder<SoundEvent, SoundEvent> GUN_12_7MM_SHOT = registerSoundEvent("gun_12.7mm_shot", CombinedAssault.modLocation("gun_12.7mm_shot"));
    public static final DeferredHolder<SoundEvent, SoundEvent> GUN_14_5MM_SHOT = registerSoundEvent("gun_14.5mm_shot", CombinedAssault.modLocation("gun_14.5mm_shot"));
    public static final DeferredHolder<SoundEvent, SoundEvent> AUTO_CANNON_SHOT = registerSoundEvent("auto_cannon_shot", CombinedAssault.modLocation("auto_cannon_shot"));
    public static final DeferredHolder<SoundEvent, SoundEvent> CANNON_125_MM_SHOT = registerSoundEvent("cannon_125mm_shot", CombinedAssault.modLocation("cannon_125mm_shot"));
    public static final DeferredHolder<SoundEvent, SoundEvent> ROCKET_LAUNCH = registerSoundEvent("rocket_launch", CombinedAssault.modLocation("rocket_launch"));
    public static final DeferredHolder<SoundEvent, SoundEvent> MISSILE_LAUNCH = registerSoundEvent("missile_launch", CombinedAssault.modLocation("missile_launch"));
    public static final DeferredHolder<SoundEvent, SoundEvent> ROCKET_FLYING = registerSoundEvent("rocket_flying", CombinedAssault.modLocation("rocket_flying"));
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_DROP = registerSoundEvent("bomb_drop", CombinedAssault.modLocation("bomb_drop"));
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_WHISTLE = registerSoundEvent("bomb_whistle", CombinedAssault.modLocation("bomb_whistle"));
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMBS_INCOMING = registerSoundEvent("bombs_incoming", CombinedAssault.modLocation("bombs_incoming"));
    public static final DeferredHolder<SoundEvent, SoundEvent> CANNON_SHELL_DROP = registerSoundEvent("cannon_shell_drop", CombinedAssault.modLocation("cannon_shell_drop"));
    public static final DeferredHolder<SoundEvent, SoundEvent> SMOKE_GRENADE_LAUNCH = registerSoundEvent("smoke_grenade_launch", CombinedAssault.modLocation("smoke_grenade_launch"));
    public static final DeferredHolder<SoundEvent, SoundEvent> DECOY_FLARE_LAUNCH = registerSoundEvent("decoy_flare_launch", CombinedAssault.modLocation("decoy_flare_launch"));
    public static final DeferredHolder<SoundEvent, SoundEvent> TIRE_SQUEAL = registerSoundEvent("tire_squeal", CombinedAssault.modLocation("tire_squeal"));
    public static final DeferredHolder<SoundEvent, SoundEvent> LANDING = registerSoundEvent("landing", CombinedAssault.modLocation("landing"));
    public static final DeferredHolder<SoundEvent, SoundEvent> AEROBATICS_SMOKE_START = registerSoundEvent("aerobatics_smoke_start", CombinedAssault.modLocation("aerobatics_smoke_start"));
    public static final DeferredHolder<SoundEvent, SoundEvent> AEROBATICS_SMOKE_LOOP = registerSoundEvent("aerobatics_smoke_loop", CombinedAssault.modLocation("aerobatics_smoke_loop"));
    public static final DeferredHolder<SoundEvent, SoundEvent> TURRET_TURN_SERVO_V = registerSoundEvent("turret_turn_servo_v", CombinedAssault.modLocation("turret_turn_servo_v"));
    public static final DeferredHolder<SoundEvent, SoundEvent> TURRET_TURN_SERVO_H = registerSoundEvent("turret_turn_servo_h", CombinedAssault.modLocation("turret_turn_servo_h"));
    public static final DeferredHolder<SoundEvent, SoundEvent> SELECT_WEAPON = registerSoundEvent("select_weapon", CombinedAssault.modLocation("select_weapon"));
    public static final DeferredHolder<SoundEvent, SoundEvent> PARACHUTE_OPEN = registerSoundEvent("parachute_open", CombinedAssault.modLocation("parachute_open"));
    public static final DeferredHolder<SoundEvent, SoundEvent> PARACHUTE_DOWN = registerSoundEvent("parachute_down", CombinedAssault.modLocation("parachute_down"));

    public static final DeferredHolder<SoundEvent, SoundEvent> LAV150_ENGINE_START = registerSoundEvent("lav150_engine_start", CombinedAssault.modLocation("lav150_engine_start"));
    public static final DeferredHolder<SoundEvent, SoundEvent> LAV150_ENGINE_IDLE = registerSoundEvent("lav150_engine_idle", CombinedAssault.modLocation("lav150_engine_idle"));
    public static final DeferredHolder<SoundEvent, SoundEvent> LAV150_ENGINE_RUN = registerSoundEvent("lav150_engine_run", CombinedAssault.modLocation("lav150_engine_run"));

    private static DeferredHolder<SoundEvent, SoundEvent> registerSoundEvent(String name, ResourceLocation soundResourceLocation) {
        DeferredHolder<SoundEvent, SoundEvent> soundEventRegistryObject = SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(soundResourceLocation));
        return soundEventRegistryObject;
    }

    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }

}
