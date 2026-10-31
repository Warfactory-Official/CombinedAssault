package com.wf.combinedassault.radar;

import com.wf.wflib.recon.api.ContactClass;
import com.wf.wflib.recon.api.ReconNet;
import com.wf.wflib.recon.api.ReconOwners;
import com.wf.wflib.recon.api.ReconTargets;
import com.wf.wflib.recon.api.SensorSpec;
import com.wf.wflib.recon.api.SignatureProviders;
import com.wf.wflib.recon.api.FireControl;
import com.wf.wflib.recon.api.HubIndex;
import com.wf.wflib.recon.api.ReconTerrain;
import com.wf.wflib.recon.api.Track;
import com.wf.wflib.recon.api.TrackPicture;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.ballistics.message.ServerVehicleWarn;
import com.wf.combinedassault.vehicle.part.RotatableUnit;
import com.wf.combinedassault.vehicle.pojo.ReconRole;
import com.wf.combinedassault.vehicle.pojo.WarnType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Vehicle side of WFLib sensor net: vehicles as targets, vehicle radars as sensor/receiver/both.
 * {@link #register} registers source + sensor together; either alone leaves the bridge half-blind.
 * World-thread only. Standalone {@code RadarUnit.tickScan} offloads its box query to a private executor;
 * the net instead collects on the tick thread and propagates on a shared pool.
 */
public final class ReconBridge {

    /** Registration move interval; matches {@code RadarUnit}'s own scan tick; sweep-phase anchor. */
    private static final int SWEEP_TICKS = 20;
    /**
     * Snap grid for sensor pos = net terrain resolution; finer wastes (new terrain field per distinct block
     * pos), and cross-range error >> cell at any useful range. See {@link #tickSensor}.
     */
    private static final int CELL = ReconTerrain.CELL;
    /** Track-error ceiling for entity attribution; past this, no warning rather than a guess. */
    private static final double WARN_MAX_ERROR = 32.0;
    /** Min closure rate (blocks/tick) for an inbound warning; missile cruise ~1.2, filters coasting-track noise. */
    private static final double INBOUND_MIN_CLOSURE = 0.3;
    private static final String SONAR_TYPE = "sonar";

    /** Weak: GC'd vehicle drops its entry. Value holds only primitives, never keeps the key alive. */
    private static final Map<RadarUnit, Registration> SENSORS = new WeakHashMap<>();

    private ReconBridge() {
    }

    static void register() {
        ReconTargets.SOURCES.register(CombinedAssault.modLocation("vehicles"), 0, new VehicleTargetSource());
        SignatureProviders.PROVIDERS.register(CombinedAssault.modLocation("vehicles"), 0, new VehicleTargetSource.BuiltIn());
        // Maps scoreboard team -> network UUID; without it, affiliation can't select a network.
        ReconOwners.SOURCES.register(CombinedAssault.modLocation("teams"), -100, new VehicleOwners());
    }

    /**
     * Net keyed by block pos; terrain field rebuilt at a 1-per-dimension-per-tick budget => moving sensor
     * snaps to {@link #CELL}, moves at most once/sweep, old registration dropped explicitly (no idle timeout
     * here). Outrunning its terrain field degrades a sensor to an empty one (reads as flat unmasked ground).
     *
     * @return true: on net, caller skips private scan (true even for a receiver, which registers nothing and
     * has no scan to skip).
     */
    public static boolean tickSensor(RadarUnit unit) {
        if (!RadarConfig.get().recon()) {
            return false;
        }
        AbstractVehicle vehicle = unit.getVehicle();
        if (!(vehicle.level() instanceof ServerLevel level)) {
            return false;
        }
        Registration held = SENSORS.get(unit);
        if (!unit.isOn() || !vehicle.hasPower() || vehicle.isDestroyed()) {
            if (held != null) {
                ReconNet.unregisterSensor(level, held.pos, held.netId);
                SENSORS.remove(unit);
            }
            return false;
        }

        ReconRole role = unit.getReconRole();
        long gameTime = level.getGameTime();
        long netId = netFor(level, vehicle, unit);

        // Sonar: listening-only still registers as sensor (unlike radar, where RECEIVER registers nothing).
        boolean sonar = isSonar(unit);
        boolean sensing = (role.emits() || sonar) && (!sonar || inWater(level, unit.worldRadarPosition()));
        if (sensing) {
            // Re-key on gameTime % SWEEP_TICKS == 1, not == 0: listener order vs the net's own tick is
            // unfixed, so re-keying exactly on the sweep boundary can replace the handle right after its due
            // sweep, stranding it a full interval. One tick of lead avoids the race.
            BlockPos pos = held == null || gameTime % SWEEP_TICKS == 1
                    ? snap(unit.worldRadarPosition())
                    : held.pos;
            if (held != null && (!held.pos.equals(pos) || held.netId != netId)) {
                ReconNet.unregisterSensor(level, held.pos, held.netId);
                held = null;
            }
            if (held == null) {
                SENSORS.put(unit, new Registration(pos, netId));
            }
            // Called every tick, idempotent; this alone prevents idle-timeout drop.
            ReconNet.registerSensor(level, pos, spec(unit, netId, sonar, role));
        } else if (held != null) {
            // Must unregister on sense->no-sense transition or it sweeps unowned. Hit every time a hull sonar breaches.
            ReconNet.unregisterSensor(level, held.pos, held.netId);
            SENSORS.remove(unit);
        }

        if (gameTime % SWEEP_TICKS == 0) {
            TrackPicture picture = ReconNet.picture(level, netId);
            if (!picture.tracks().isEmpty()) {
                if (role.emits()) {
                    warnPainted(level, unit, vehicle, picture);
                }
                if (role.receives()) {
                    warnInbound(level, vehicle, picture);
                }
            }
        }
        return true;
    }

    /** Side check first: radar's tick runs both sides; the registration map is touched server-side only. */
    public static void dropSensor(RadarUnit unit) {
        if (!(unit.getVehicle().level() instanceof ServerLevel level)) {
            return;
        }
        Registration held = SENSORS.remove(unit);
        if (held != null) {
            ReconNet.unregisterSensor(level, held.pos, held.netId);
        }
    }

    /**
     * Affiliation first (vehicle's own net wherever it is); else the nearest hub's net. Deliberate:
     * gating on proximity would break orbital relays (no ground presence by design). Range still bites via
     * empty pictures / loss of ground-station contact, not here.
     */
    private static long netFor(ServerLevel level, AbstractVehicle vehicle, RadarUnit unit) {
        long owner = ReconOwners.netOf(vehicle);
        if (owner != ReconNet.UNAFFILIATED) {
            return owner;
        }
        return HubIndex.netFor(level, BlockPos.containing(unit.worldRadarPosition()),
                RadarConfig.get().receiverRange(), ReconNet.UNAFFILIATED);
    }

    /**
     * Emitting sets only (receivers light nothing). Track -> entity via {@code FireControl}, the same
     * bounded world-thread resolve WFLib' own turrets use. An unresolved track warns nobody (countermeasure
     * working, not a gap).
     */
    private static void warnPainted(ServerLevel level, RadarUnit unit, AbstractVehicle vehicle,
                                    TrackPicture picture) {
        List<Track> tracks = picture.tracks();
        Vec3 origin = unit.worldRadarPosition();
        double range = unit.getMaxScanDistance();
        double rangeSq = range * range;
        String radarType = unit.getRadarType();
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            // Picture is net-wide; filtered to this radar's own aperture (range + sector) before warning.
            if (track.distanceSqTo(origin.x, origin.y, origin.z) > rangeSq || !inSector(unit, track)) {
                continue;
            }
            // UNKNOWN/VEHICLE only: a MISSILE-classified track isn't a vehicle, would resolve to whatever's
            // parked nearby.
            if (track.guess() != ContactClass.UNKNOWN && track.guess() != ContactClass.VEHICLE) {
                continue;
            }
            AbstractVehicle painted = FireControl.resolve(level, AbstractVehicle.class, track, WARN_MAX_ERROR,
                    v -> v != vehicle && !v.isRemoved() && !v.isDestroyed());
            if (painted != null) {
                send(painted, vehicle.getId(), painted.getId(), WarnType.RADAR_SEARCH, radarType);
            }
        }
    }

    /**
     * Receiver's payoff: track may come from another radar or a relay, held while this vehicle itself emits
     * nothing. Resolved to a real entity (warning display needs a bearing); unresolvable localization => no alarm.
     */
    private static void warnInbound(ServerLevel level, AbstractVehicle vehicle, TrackPicture picture) {
        List<Track> tracks = picture.tracks();
        double x = vehicle.getX();
        double y = vehicle.getY();
        double z = vehicle.getZ();
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            if (track.guess() != ContactClass.MISSILE || track.closingOn(x, y, z) < INBOUND_MIN_CLOSURE) {
                continue;
            }
            Entity threat = FireControl.resolve(level, Entity.class, track, WARN_MAX_ERROR,
                    e -> !e.isRemoved() && e != vehicle);
            if (threat != null) {
                send(vehicle, threat.getId(), vehicle.getId(), WarnType.MISSILE_LAUNCH, "NET");
            }
        }
    }

    private static void send(AbstractVehicle to, int fromEntityId, int toEntityId, WarnType type, String info) {
        ServerVehicleWarn message = new ServerVehicleWarn(fromEntityId, toEntityId, type, info);
        for (Entity passenger : to.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, message);
            }
        }
    }

    private static boolean inSector(RadarUnit unit, Track track) {
        Vec2 aim = unit.aimRot(new Vec3(track.x(), track.y(), track.z()));
        return RotatableUnit.inYawArc(aim.y, unit.getYRotMin(), unit.getYRotMax());
    }

    /** @param sonar emits=pinging, receive-only=listening (not idle), vs radar role semantics. */
    private static SensorSpec spec(RadarUnit unit, long netId, boolean sonar, ReconRole role) {
        double range = Math.min(unit.getMaxScanDistance(), RadarConfig.get().radarRange());
        if (sonar) {
            SensorSpec listening = SensorSpec.sonarArray(netId, range).withSweep(SWEEP_TICKS);
            return role.emits() ? listening.withRadiating(true) : listening;
        }
        // Fire-control (tracking), not search: beam/ranging quoted for lock-handoff. Swept at vehicle rate,
        // not the preset's, for net phase-alignment.
        return SensorSpec.fireControlRadar(netId, range)
                .withSweep(SWEEP_TICKS)
                // Position already includes mast height.
                .withMast(0.0);
    }

    /** @return hull-sonar flag from free-text {@code radarType}; no dedicated part type/field needed. */
    static boolean isSonar(RadarUnit unit) {
        String type = unit.getRadarType();
        return type != null && type.toLowerCase(Locale.ROOT).contains(SONAR_TYPE);
    }

    /** A hull sonar hears nothing with its transducer in the air, however powered the hull is. */
    private static boolean inWater(ServerLevel level, Vec3 at) {
        return level.getFluidState(BlockPos.containing(at)).is(FluidTags.WATER);
    }

    /**
     * Horizontal: snap to {@link #CELL}. Vertical: exact; height error is qualitative (underground vs
     * in-beam lateral slop).
     */
    private static BlockPos snap(Vec3 at) {
        return new BlockPos(
                Math.floorDiv((int) Math.floor(at.x), CELL) * CELL,
                (int) Math.floor(at.y),
                Math.floorDiv((int) Math.floor(at.z), CELL) * CELL);
    }

    /** One radar's place on the net. Primitives only, so holding it cannot keep its owner alive. */
    private record Registration(BlockPos pos, long netId) {
    }

}
