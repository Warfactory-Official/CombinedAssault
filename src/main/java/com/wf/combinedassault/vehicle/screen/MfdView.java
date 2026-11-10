package com.wf.combinedassault.vehicle.screen;

import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.hud.HudSubjects;
import com.wf.combinedassault.radar.RadarUnit;
import com.wf.combinedassault.radar.WarningReceiver;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.pojo.WarnType;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.ui.api.Signals;
import com.wf.wflib.ui.api.Subject;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Aircraft display ({@code mfd} subject): the vehicle's HUD subjects, its sight station (the rider's own when the
 * local player rides it) and a tactical picture (radar contacts, allied vehicles, RWR emitters) refreshed at most
 * every {@link #REFRESH_NANOS}.
 */
public final class MfdView implements ScreenView {
    public static final Subject<MfdView> SUBJECT = Subject.register("mfd", MfdView.class);
    private static final long REFRESH_NANOS = 100_000_000L;
    /** Allied vehicles within this many blocks join the picture (data link). */
    private static final double LINK_RANGE = 8000;
    private static final double SEA_LEVEL = 63;
    private static final double KMH_PER_BLOCK_PER_TICK = 72.0;

    public enum Kind { HOSTILE, FRIEND, UNKNOWN, MISSILE }

    /** Picture entry; world position, refreshed with the picture. */
    public static final class Track {
        public double x;
        public double z;
        public double vx;
        public double vz;
        public Kind kind;
        public boolean locked;
    }

    /** RWR emitter; relative bearing in degrees (0 = nose, clockwise). */
    public static final class Emitter {
        public float bearing;
        public WarnType type;
        public String label = "";
        public long received;
    }

    final AbstractVehicle vehicle;
    @Nullable
    WeaponUnit station;
    @Nullable
    AbstractVehicleWeapon<?> primary;
    @Nullable
    AbstractVehicleWeapon<?> independent;
    private long refreshed = Long.MIN_VALUE / 2;
    private final List<Track> trackPool = new ArrayList<>();
    private final List<Emitter> emitterPool = new ArrayList<>();
    public int tracks;
    public int emitters;
    boolean launch;
    boolean lock;
    double farthest;
    /** Level clock {@code HH:MM}. */
    final StringBuilder time = new StringBuilder(5);
    private final Vector3f forward = new Vector3f();
    private final Vector3f up = new Vector3f();

    public MfdView(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
    }

    public AbstractVehicle vehicle() {
        return vehicle;
    }

    public Track track(int i) {
        return trackPool.get(i);
    }

    public Emitter emitter(int i) {
        return emitterPool.get(i);
    }

    /** Compass heading, degrees (0 = north, 90 = east). */
    public double heading(float partialTick) {
        return Mth.positiveModulo(vehicle.getViewYRot(partialTick) + 180, 360);
    }

    /** Level clock, hours [0, 24). */
    public double clock() {
        return (vehicle.level().getDayTime() + 6000) % 24000 / 1000.0;
    }

    /** Height above the surface below (radar altimeter), blocks. */
    public double agl() {
        return vehicle.getY() - vehicle.level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(vehicle.getX()), Mth.floor(vehicle.getZ()));
    }

    /** ISA troposphere: 1 - h / 44330.8 m, h above sea level (y 63), clamped to [0, 11 km]. */
    private double isaTheta() {
        return 1 - Mth.clamp(vehicle.getY() - SEA_LEVEL, 0, 11000) * 2.25577e-5;
    }

    /** Indicated airspeed, km/h: true speed * sqrt(density ratio). */
    public double ias() {
        return vehicle.getDeltaMovement().length() * KMH_PER_BLOCK_PER_TICK * Math.sqrt(Math.pow(isaTheta(), 4.2559));
    }

    /** True speed / local speed of sound. */
    public double mach() {
        return vehicle.getDeltaMovement().length() * KMH_PER_BLOCK_PER_TICK / (1225.06 * Math.sqrt(isaTheta()));
    }

    /** Angle of attack, degrees: velocity below the nose in the body frame, 0 below 1 block/s. */
    public double aoa() {
        Vec3 v = vehicle.getDeltaMovement();
        if (v.lengthSqr() < 0.0025) {
            return 0;
        }
        Quaternionf q = vehicle.rotYXZShared();
        q.transform(0, 0, 1, forward);
        q.transform(0, 1, 0, up);
        return Math.toDegrees(Math.atan2(-(v.x * up.x + v.y * up.y + v.z * up.z), v.x * forward.x + v.y * forward.y + v.z * forward.z));
    }

    @Override
    public void refresh(float partialTick) {
        long now = System.nanoTime();
        if (now - refreshed < REFRESH_NANOS) {
            return;
        }
        refreshed = now;
        long day = vehicle.level().getDayTime() + 6000;
        int hh = (int) (day / 1000 % 24);
        int mm = (int) (day % 1000 * 60 / 1000);
        time.setLength(0);
        time.append((char) ('0' + hh / 10)).append((char) ('0' + hh % 10)).append(':')
                .append((char) ('0' + mm / 10)).append((char) ('0' + mm % 10));
        LocalVehiclePlayer local = LocalVehiclePlayer.instance();
        boolean ridden = local.vehicle == vehicle && local.onVehicle();
        station = ridden ? local.sightUnit() : null;
        if (station == null) {
            for (PartUnit<?> unit : vehicle.getPartUnits()) {
                if (unit instanceof WeaponUnit weapon && !weapon.weapons.isEmpty()) {
                    station = weapon;
                    break;
                }
            }
        }
        primary = station == null ? null : station.currentWeapon() != null ? station.currentWeapon() : station.currentSecondaryWeapon();
        WeaponUnit root = station == null ? null : station.getRootParentWeaponUnit();
        independent = root == null || root.independentWeapons.isEmpty() ? null : root.independentWeapons.get(0);
        tracks = 0;
        farthest = 0;
        Entity locked = null;
        for (PartUnit<?> unit : vehicle.getPartUnits()) {
            if (!(unit instanceof WeaponUnit weapon)) {
                continue;
            }
            for (RadarUnit radar : weapon.getRadarUnits()) {
                if (!radar.isOn()) {
                    continue;
                }
                if (radar.getLockedEntity() != null) {
                    locked = radar.getLockedEntity();
                }
                for (RadarUnit.DetectedObject object : radar.getDetectedEntities().values()) {
                    Entity entity = object.entity;
                    Track t = add(object.detectedPosition.x, object.detectedPosition.z, entity);
                    t.kind = entity instanceof com.wf.wflib.missile.api.MissileEntity ? Kind.MISSILE
                            : allied(entity) ? Kind.FRIEND : entity.getTeam() == null ? Kind.UNKNOWN : Kind.HOSTILE;
                }
            }
        }
        var level = Minecraft.getInstance().level;
        if (level != null && vehicle.getTeam() != null) {
            for (Entity entity : level.entitiesForRendering()) {
                if (entity != vehicle && entity instanceof AbstractVehicle other && allied(other)
                        && other.distanceToSqr(vehicle) < LINK_RANGE * LINK_RANGE) {
                    add(other.getX(), other.getZ(), other).kind = Kind.FRIEND;
                }
            }
        }
        for (int i = 0; i < tracks; i++) {
            trackPool.get(i).locked = false;
        }
        if (locked != null) {
            Track t = add(locked.getX(), locked.getZ(), locked);
            t.kind = allied(locked) ? Kind.FRIEND : Kind.HOSTILE;
            t.locked = true;
        }
        emitters = 0;
        launch = false;
        lock = false;
        WarningReceiver receiver = vehicle.warningReceiver;
        if (ridden && receiver != null) {
            double heading = heading(partialTick);
            for (var entry : receiver.targets.entrySet()) {
                Entity source = level == null ? null : level.getEntity(entry.getKey());
                if (source == null) {
                    LocalVehiclePlayer.ServerEntity remote = local.serverEntities.get(entry.getKey());
                    source = remote == null ? null : remote.entity;
                }
                if (source == null) {
                    continue;
                }
                if (emitters == emitterPool.size()) {
                    emitterPool.add(new Emitter());
                }
                Emitter e = emitterPool.get(emitters++);
                WarningReceiver.WarnTarget target = entry.getValue();
                double bearing = Math.toDegrees(Math.atan2(source.getX() - vehicle.getX(), -(source.getZ() - vehicle.getZ())));
                e.bearing = (float) Mth.wrapDegrees(bearing - heading);
                e.type = target.warnType();
                e.label = target.info() == null || target.info().isEmpty() ? "U" : target.info();
                e.received = target.receivedTime();
                launch |= e.type == WarnType.MISSILE_LAUNCH;
                lock |= e.type == WarnType.RADAR_LOCK;
            }
        }
    }

    private Track add(double x, double z, Entity entity) {
        if (tracks == trackPool.size()) {
            trackPool.add(new Track());
        }
        Track t = trackPool.get(tracks++);
        t.x = x;
        t.z = z;
        t.vx = entity.getDeltaMovement().x;
        t.vz = entity.getDeltaMovement().z;
        t.locked = false;
        farthest = Math.max(farthest, Math.hypot(x - vehicle.getX(), z - vehicle.getZ()));
        return t;
    }

    private boolean allied(Entity entity) {
        return vehicle.getTeam() != null && entity.getTeam() != null && vehicle.getTeam().isAlliedTo(entity.getTeam());
    }

    @Override
    public Object subject(Subject<?> subject) {
        if (subject == SUBJECT) {
            return this;
        }
        if (subject == HudSubjects.VEHICLE) {
            return vehicle;
        }
        if (subject == HudSubjects.FIXED) {
            return vehicle instanceof FixedWingVehicle fixed ? fixed : null;
        }
        if (subject == HudSubjects.ROTARY) {
            return vehicle instanceof RotaryWingVehicle rotary ? rotary : null;
        }
        if (subject == HudSubjects.STATION) {
            return station;
        }
        if (subject == HudSubjects.PRIMARY) {
            return primary;
        }
        if (subject == HudSubjects.INDEPENDENT) {
            return independent;
        }
        return null;
    }

    public static void register() {
        Subject<MfdView> s = SUBJECT;
        Signals.num(s, "heading", m -> m.heading(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)));
        Signals.num(s, "tracks", m -> m.tracks);
        Signals.num(s, "emitters", m -> m.emitters);
        Signals.bool(s, "launch", m -> m.launch);
        Signals.bool(s, "lock", m -> m.lock);
        Signals.text(s, "time", 5, m -> m.time);
        Signals.num(s, "clock", MfdView::clock);
        Signals.num(s, "agl", MfdView::agl);
        Signals.num(s, "ias", MfdView::ias);
        Signals.num(s, "mach", MfdView::mach);
        Signals.num(s, "aoa", MfdView::aoa);
    }
}
