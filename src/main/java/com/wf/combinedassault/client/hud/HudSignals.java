package com.wf.combinedassault.client.hud;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.NoneVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.entity.vehicle.ShipVehicle;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.WarnType;
import com.wf.combinedassault.vehicle.passenger.WarningReceiver;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.VehicleMissile;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.wf.combinedassault.client.hud.HudSubjects.*;

/** Every value CA's HUD layouts can read. Units: km/h, metres, percent, degrees, seconds. */
public final class HudSignals {
    private static final double KMH_PER_BLOCK_PER_TICK = 72.0;
    private static final double KNOTS_PER_BLOCK_PER_TICK = 20.0 * 1.9438;

    private static final String[] AHEAD = {
            "ui.combinedassault.helm.ahead.dead_slow", "ui.combinedassault.helm.ahead.slow",
            "ui.combinedassault.helm.ahead.half", "ui.combinedassault.helm.ahead.full"};
    private static final String[] ASTERN = {
            "ui.combinedassault.helm.astern.dead_slow", "ui.combinedassault.helm.astern.slow",
            "ui.combinedassault.helm.astern.half", "ui.combinedassault.helm.astern.full"};

    private static boolean registered;

    private HudSignals() {
    }

    static float partialTick() {
        return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        vehicle();
        local();
        station();
        weapon(PRIMARY);
        weapon(SECONDARY);
        weapon(INDEPENDENT);
        weapon(WEAPON);
        Signals.text(SYSTEM, "label", 8, HudCache.SystemCell::label);
        Signals.num(SYSTEM, "state", HudCache.SystemCell::state);
        Signals.num(SYSTEM, "ratio", HudCache.SystemCell::ratio);
        Signals.text(SEAT, "name", 32, HudCache::seatName);
        Signals.bool(SEAT, "occupied", seat -> seat.passengerId != null && seat.passengerId >= 0);
        flight();
        ship();
        payload();
    }

    private static void vehicle() {
        Signals.text(VEHICLE, "name", 48, HudCache::vehicleName);
        Signals.num(VEHICLE, "name_color", HudCache::nameColour);
        Signals.num(VEHICLE, "hull", v -> v.getMaxHealth() <= 0 ? 1 : Math.max(0, v.getHealth() / v.getMaxHealth()));
        Signals.num(VEHICLE, "hull_state", v -> HudCache.hullState(v).ordinal());
        Signals.num(VEHICLE, "speed", v -> v.getDeltaMovement().length() * KMH_PER_BLOCK_PER_TICK);
        Signals.num(VEHICLE, "ground_speed", v -> v.getDeltaMovement().horizontalDistance() * KMH_PER_BLOCK_PER_TICK);
        Signals.num(VEHICLE, "vspeed", v -> v.getDeltaMovement().y * 20);
        Signals.num(VEHICLE, "altitude", AbstractVehicle::getY);
        Signals.num(VEHICLE, "fuel", v -> {
            float capacity = v.energyInfo.capacityUnits();
            return capacity > 0 ? v.getEnergy() / capacity * 100 : 0;
        });
        Signals.num(VEHICLE, "power", AbstractVehicle::getPower);
        Signals.bool(VEHICLE, "radio", v -> v.radioVoice() != null);
        Signals.text(VEHICLE, "radio_station", 48, v -> com.wf.wflib.radio.client.RadioCatalog.name(v.radioStation())
                .getString().toUpperCase(java.util.Locale.ROOT));
        Signals.text(VEHICLE, "radio_key", 16, v -> HudCache.keyName(com.wf.combinedassault.fpv.client.FpvKeys.RADIO_STATION));
        Signals.text(VEHICLE, "fuel_label", 24, HudCache::fuelLabel);
        Signals.bool(VEHICLE, "overloaded", v -> v.overload() > 1);
        Signals.num(VEHICLE, "payload_pct", v -> v.overload() * 100);
        Signals.num(VEHICLE, "fuel_seconds", v -> v.burnPerTick() <= 0 ? Double.NaN : v.getEnergy() / v.burnPerTick() / 20);
        Signals.num(VEHICLE, "pitch", v -> v.getViewXRot(partialTick()));
        Signals.num(VEHICLE, "roll", v -> v.getViewZRot(partialTick()));
        Signals.num(VEHICLE, "yaw", v -> v.getViewYRot(partialTick()));
        Signals.bool(VEHICLE, "none", v -> v instanceof NoneVehicle);
        Signals.bool(VEHICLE, "ship", v -> v instanceof ShipVehicle);
        Signals.items(VEHICLE, "systems", HudCache::systems, SYSTEM);
        Signals.num(VEHICLE, "system_count", v -> HudCache.systems(v).size());
        Signals.bool(VEHICLE, "rotary", v -> v instanceof RotaryWingVehicle);
        Signals.items(VEHICLE, "seats", v -> v.seats, SEAT);
        Signals.bool(VEHICLE, "rwr", v -> v.warningReceiver != null);
        Signals.bool(VEHICLE, "missile_incoming", v -> warned(v.warningReceiver, WarnType.MISSILE_LAUNCH));
        Signals.bool(VEHICLE, "being_locked", v -> warned(v.warningReceiver, WarnType.RADAR_LOCK));
    }

    private static boolean warned(WarningReceiver receiver, WarnType type) {
        if (receiver == null || receiver.targets.isEmpty()) {
            return false;
        }
        for (WarningReceiver.WarnTarget target : receiver.targets.values()) {
            if (target.warnType() == type) {
                return true;
            }
        }
        return false;
    }

    private static void local() {
        Signals.bool(LOCAL, "third_person", l -> l.viewType == LocalVehiclePlayer.ViewType.THIRD_PERSON);
        Signals.bool(LOCAL, "operator", l -> l.viewType == LocalVehiclePlayer.ViewType.OPERATOR);
        Signals.bool(LOCAL, "scope", LocalVehiclePlayer::sighted);
        Signals.bool(LOCAL, "driver", l -> l.vehicle != null && l.getPlayer() != null
                && l.getPlayer().equals(l.vehicle.getDriver()));
        Signals.num(LOCAL, "g", l -> l.currentG);
        Signals.bool(LOCAL, "lost_control", l -> l.lostControl);
        Signals.bool(LOCAL, "overload", l -> l.endureTick > 5 && (l.currentG >= 2 || l.currentG <= -1));
        Signals.bool(LOCAL, "homing", l -> !l.missiles.isEmpty());
        Signals.num(LOCAL, "aim_distance", l -> l.aimLocationDistance);
        Signals.bool(LOCAL, "out_of_range", l -> l.outOfRangeFinding);
        Signals.text(LOCAL, "tip", 64, l -> HudTips.text());
        Signals.bool(LOCAL, "tip_shown", l -> HudTips.shown());
    }

    private static void station() {
        Signals.num(STATION, "zoom", WeaponUnit::getZoom);
        Signals.bool(STATION, "stabilizer", WeaponUnit::withStabilizer);
        Signals.bool(STATION, "focus_locked", u -> u.withFocusLocker() && u.getFocusLockPos() != null);
        Signals.bool(STATION, "crt", u -> u.getOpticalSightType() == WeaponUnitData.OpticalSightType.CRT);
        Signals.bool(STATION, "optical_scope",
                u -> u.getOpticalSightType() == WeaponUnitData.OpticalSightType.OPTICAL_SCOPE);
        Signals.bool(STATION, "indirect", u -> u.indirectFire().isIndirect());
        Signals.bool(STATION, "radar", u -> !u.getRadarUnits().isEmpty());
        Signals.items(STATION, "weapons", HudCache::weapons, WEAPON);
        Signals.num(STATION, "weapon_count", u -> HudCache.weapons(u).size());
        Signals.num(STATION, "active_index", u -> {
            var list = HudCache.weapons(u);
            AbstractVehicleWeapon<?> main = u.currentWeapon();
            AbstractVehicleWeapon<?> secondary = u.currentSecondaryWeapon();
            int index = main == null ? -1 : list.indexOf(main);
            if (index < 0 && secondary != null) {
                index = list.indexOf(secondary);
            }
            return index < 0 && !list.isEmpty() ? 0 : index;
        });
    }

    private static void weapon(Subject<AbstractVehicleWeapon<?>> subject) {
        Signals.text(subject, "name", 48, HudCache::weaponName);
        Signals.num(subject, "ammo", AbstractVehicleWeapon::getRemainAmmo);
        Signals.num(subject, "capacity", AbstractVehicleWeapon::getMaxCapacity);
        Signals.num(subject, "reload", AbstractVehicleWeapon::getReloadTime);
        Signals.num(subject, "reload_max", w -> w.getData().getReload() == null ? 0 : w.getData().getReload().getTime());
        Signals.bool(subject, "reloading", w -> w.getReloadTime() > 0);
        Signals.text(subject, "round", 24, w -> HudCache.roundLabel(w instanceof VehicleMissile missile
                ? missile.currentRound() : w.getLoadedRound().isEmpty() ? w.getSelectedRound() : w.getLoadedRound()));
    }

    private static void flight() {
        Signals.num(ROTARY, "rpm", AbstractVehicle::getPower);
        Signals.num(ROTARY, "collective", r -> r.getCollectivePitch());
        Signals.num(ROTARY, "flow_angle", r -> {
            flow(r);
            return Math.toDegrees(FLOW_ANGLE[0]);
        });
        Signals.num(ROTARY, "flow_length", r -> {
            flow(r);
            return FLOW_ANGLE[1];
        });
        Signals.num(FIXED, "throttle", f -> f.getThrottleLevel());
        Signals.bool(FIXED, "brake", f -> (int) f.getThrottleLevel() == 0 && f.controlUnit.backward && f.onGround());
    }

    private static final Quaternionf FLOW_ROTATION = new Quaternionf();
    private static final Vector3f FLOW_VELOCITY = new Vector3f();
    private static final double[] FLOW_ANGLE = new double[2];

    /** Airflow arrow: velocity in the airframe, angle atan2(-x, z), length 15 x speed / max airspeed. */
    private static void flow(RotaryWingVehicle r) {
        float pt = partialTick();
        FLOW_ROTATION.identity()
                .rotateY((float) Math.toRadians(-Mth.lerp(pt, r.yRotO, r.getYRot())))
                .rotateX((float) Math.toRadians(Mth.lerp(pt, r.xRotO, r.getXRot())))
                .rotateZ((float) Math.toRadians(Mth.lerp(pt, r.zRotO, r.getZRot())))
                .conjugate();
        var before = r.deltaMovementO;
        var now = r.getDeltaMovement();
        FLOW_VELOCITY.set(Mth.lerp(pt, before.x, now.x), Mth.lerp(pt, before.y, now.y), Mth.lerp(pt, before.z, now.z));
        float speed = FLOW_VELOCITY.length();
        FLOW_ROTATION.transform(FLOW_VELOCITY);
        FLOW_ANGLE[0] = Math.atan2(-FLOW_VELOCITY.x, FLOW_VELOCITY.z);
        FLOW_ANGLE[1] = 15.0 * speed / r.maxAirSpeed;
    }

    private static void ship() {
        Signals.num(SHIP, "throttle", ShipVehicle::throttle);
        Signals.num(SHIP, "rudder", ShipVehicle::rudder);
        Signals.num(SHIP, "knots", s -> Math.abs(s.surge()) * KNOTS_PER_BLOCK_PER_TICK);
        Signals.num(SHIP, "max_knots", s -> topSpeed(s) * KNOTS_PER_BLOCK_PER_TICK);
        Signals.num(SHIP, "ordered_knots", s -> Math.abs(s.throttle())
                * (s.throttle() < 0 ? s.params().maxSpeedBackward : topSpeed(s)) * KNOTS_PER_BLOCK_PER_TICK);
        Signals.num(SHIP, "course", s -> Mth.positiveModulo(Mth.rotLerp(partialTick(), s.yRotO, s.getYRot()) + 180.0f, 360.0f));
        Signals.num(SHIP, "keel", ShipVehicle::keelClearance);
        Signals.num(SHIP, "depth", ShipVehicle::depth);
        Signals.num(SHIP, "depth_setting", ShipVehicle::depthSetting);
        Signals.bool(SHIP, "submarine", ShipVehicle::isSubmarine);
        Signals.bool(SHIP, "afloat", ShipVehicle::afloat);
        Signals.bool(SHIP, "aground", ShipVehicle::aground);
        Signals.bool(SHIP, "shallow", ShipVehicle::shallow);
        Signals.bool(SHIP, "crush", s -> s.isSubmarine() && s.depth() > s.params().crushDepth);
        Signals.bool(SHIP, "power", AbstractVehicle::hasPower);
        Signals.text(SHIP, "order", 32, s -> HudCache.translate(order(s.throttle())));
    }

    private static double topSpeed(ShipVehicle ship) {
        boolean submerged = ship.isSubmarine() && ship.depth() > 0.5f;
        return submerged ? ship.params().maxSpeedSubmerged : ship.params().maxSpeedForward;
    }

    private static String order(float throttle) {
        float magnitude = Math.abs(throttle);
        int step = magnitude > 0.875f ? 3 : magnitude > 0.625f ? 2 : magnitude > 0.375f ? 1 : magnitude > 0.05f ? 0 : -1;
        if (step < 0) {
            return "ui.combinedassault.helm.stop";
        }
        return throttle < 0 ? ASTERN[step] : AHEAD[step];
    }

    private static void payload() {
        Signals.bool(PAYLOAD, "loaded", p -> p.isLoaded());
        Signals.bool(PAYLOAD, "single", p -> p.isLoaded() && p.capacity() <= 1);
        Signals.text(PAYLOAD, "name", 48, p -> p.isLoaded() ? HudCache.payloadName(p.firstSynced()) : "");
        Signals.num(PAYLOAD, "remaining", p -> p.remaining());
        Signals.num(PAYLOAD, "capacity", p -> p.isLoaded() ? p.capacity() : 0);
        Signals.bool(PAYLOAD, "armed", p -> p.isArmed());
        Signals.bool(PAYLOAD, "live", p -> p.isLive());
        Signals.num(PAYLOAD, "arming", p -> p.armingRemaining() / 20.0);
        Signals.text(PAYLOAD, "arm_key", 16, p -> HudCache.keyName(com.wf.combinedassault.fpv.client.FpvKeys.ARM_PAYLOAD));
        Signals.text(PAYLOAD, "fire_key", 16, p -> HudCache.keyName(com.wf.combinedassault.all.AllKeys.MAIN_WEAPON_SHOOT));
        Signals.bool(PAYLOAD, "armable", p -> p.payloadType() != com.wf.combinedassault.fpv.payload.FpvPayloadTypes.SPEAKER);
        Signals.bool(PAYLOAD, "releasable", p -> p.payloadType() == com.wf.combinedassault.fpv.payload.FpvPayloadTypes.WARHEAD);
        Signals.text(PAYLOAD, "release_key", 16, p -> HudCache.keyName(com.wf.combinedassault.all.AllKeys.SECONDARY_WEAPON_SHOOT));
    }
}
