package com.wf.combinedassault.autonomy;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.fpv.payload.FpvTrigger;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.vehicle.pojo.AutonomyInfo;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.api.WFTelemetryService;
import com.wf.wflib.chunk.MissileChunkLoader;
import com.wf.wflib.drone.ControllableDrone;
import com.wf.wflib.drone.DroneFlight;
import com.wf.wflib.drone.DroneOrders;
import com.wf.wflib.drone.DroneRoute;
import com.wf.wflib.drone.DroneSquadRole;
import com.wf.wflib.drone.DroneState;
import com.wf.wflib.drone.DroneTask;
import com.wf.wflib.drone.PowerProfile;
import com.wf.wflib.drone.ai.DroneAction;
import com.wf.wflib.drone.ai.DronePlan;
import com.wf.wflib.drone.ai.DroneSnapshot;
import com.wf.wflib.drone.ai.TerrainSampler;
import com.wf.wflib.drone.flight.FlightAttitude;
import com.wf.wflib.telemetry.Telemetry;
import com.wf.wflib.telemetry.TelemetryLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A UAV vehicle as a wflib autonomy body. Plans fly only while nobody is aboard: a remote pilot suspends them and
 * the mission resumes from wherever they let go.
 */
public final class VehicleDrone implements ControllableDrone {

    private final AbstractVehicle vehicle;
    private final Set<DroneTask.Kind> tasks = EnumSet.noneOf(DroneTask.Kind.class);
    private final VehiclePilot pilot;
    private final DroneFlight flight = new DroneFlight(this);
    private final DroneRoute route = new DroneRoute(this);
    private final DroneOrders orders = new DroneOrders(this);
    private final DroneSquadRole squad = new DroneSquadRole(this);
    /** Own tickets along the route; a remote pilot's session holds its own (DetachedBodyStream). */
    private final MissileChunkLoader chunkLoader = new MissileChunkLoader();
    @Nullable
    private String callsign;
    @Nullable
    private UUID operatorId;
    /** Kamikaze terminal dive: flown straight in, plans ignored, impact fuze does the rest. */
    private static final double DIVE_SPEED = 1.0;

    @Nullable
    private Vec3 dive;
    private Vec3 desired = Vec3.ZERO;
    private Vec3 feedForward = Vec3.ZERO;
    private float yaw;

    public VehicleDrone(AbstractVehicle vehicle, AutonomyInfo info) {
        this.vehicle = vehicle;
        for (String id : info.tasks) {
            this.tasks.add(DroneTask.Kind.byId(id));
        }
        this.pilot = VehiclePilot.of(vehicle, info);
        if (info.cruiseSpeed != null) {
            this.flight.setCruiseSpeed(info.cruiseSpeed);
        }
        this.flight.setReleaseSpeed(info.releaseSpeed != null ? info.releaseSpeed : this.flight.getCruiseSpeed());
        if (this.pilot.grounded()) {
            this.flight.setCruiseAltitude(0.0);
        } else if (info.cruiseAltitude != null) {
            this.flight.setCruiseAltitude(info.cruiseAltitude);
        }
        this.yaw = vehicle.getYRot();
    }

    /** Server, every tick before the physics step. */
    public void tick() {
        this.flight.tick();
        this.vehicle.autopilotControl = false;
        ServerLevel level = (ServerLevel) this.vehicle.level();
        if (!this.flight.getDroneState().airborne()) {
            this.chunkLoader.releaseAll(this.vehicle, level);
            if (this.vehicle.linkLost() && !this.vehicle.onGround()) {
                this.vehicle.autopilotControl = true;
                this.pilot.fly(this.vehicle, Vec3.ZERO, Vec3.ZERO, this.vehicle.getYRot());
            }
            return;
        }
        this.chunkLoader.update(this.vehicle, level, this.vehicle.position(), this.vehicle.getDeltaMovement(), true);
        if (piloted()) {
            return;
        }
        if (!this.vehicle.isEngineOn()) {
            this.vehicle.toggleEngine(true);
        }
        this.vehicle.autopilotControl = true;
        if (this.dive != null) {
            Vec3 run = this.dive.subtract(this.vehicle.position());
            this.pilot.fly(this.vehicle, run.normalize().scale(DIVE_SPEED), Vec3.ZERO,
                    (float) Math.toDegrees(Math.atan2(-run.x, run.z)));
            return;
        }
        this.pilot.fly(this.vehicle, this.desired, this.feedForward, this.yaw);
    }

    /** Remote pilot aboard with a working link; lost link => the program flies. */
    private boolean piloted() {
        return this.vehicle.getDriver() != null && !this.vehicle.linkLost();
    }

    /** Server, leaving the level. */
    public void release() {
        this.chunkLoader.releaseAll(this.vehicle, (ServerLevel) this.vehicle.level());
    }

    @Override
    public Entity asEntity() {
        return this.vehicle;
    }

    @Override
    public DroneFlight flight() {
        return this.flight;
    }

    @Override
    public DroneRoute route() {
        return this.route;
    }

    @Override
    public DroneOrders orders() {
        return this.orders;
    }

    @Override
    public DroneSquadRole squad() {
        return this.squad;
    }

    @Override
    public String callsign() {
        return this.callsign != null ? this.callsign : this.vehicle.getVehicleId().getPath();
    }

    @Override
    public void setCallsign(@Nullable String callsign) {
        this.callsign = callsign;
    }

    @Override
    @Nullable
    public UUID getTeamId() {
        return this.vehicle.uavSide();
    }

    /** Mission side stamps the claim only when the vehicle has none. */
    @Override
    public void setTeamId(@Nullable UUID teamId) {
        if (teamId != null && this.vehicle.getSide() == null) {
            this.vehicle.setSide(teamId);
        }
    }

    @Override
    @Nullable
    public UUID getOperatorId() {
        return this.operatorId;
    }

    @Override
    public void setOperatorId(@Nullable UUID operatorId) {
        this.operatorId = operatorId;
    }

    @Override
    public void recordEvent(WFEventType type, String detail) {
        if (this.orders.isClassified()) {
            return;
        }
        WFTelemetryService.record(this.vehicle.getUUID(), type, this.vehicle.level().getGameTime(),
                this.vehicle.position(), false, detail);
        TelemetryLog.post(this.vehicle, type, detail);
    }

    @Override
    public double chargeFraction() {
        float capacity = this.vehicle.energyInfo.capacityUnits();
        return capacity <= 0 ? 0.0 : this.vehicle.getEnergy() / capacity;
    }

    /** STRIKE only with something strapped on. */
    @Override
    public boolean supports(DroneTask.Kind kind) {
        return this.tasks.contains(kind) && (kind != DroneTask.Kind.STRIKE || this.loaded());
    }

    private boolean loaded() {
        PayloadUnit hardpoint = PayloadUnit.on(this.vehicle);
        return hardpoint != null && hardpoint.isLoaded();
    }

    /** Leaving the ground starts the engine; touching down idle stops it. */
    @Override
    public void onStateChanged(DroneState state) {
        if (piloted()) {
            return;
        }
        if (state.airborne()) {
            this.vehicle.toggleEngine(true);
        } else if (state == DroneState.IDLE) {
            this.vehicle.toggleEngine(false);
        }
        PayloadUnit hardpoint = PayloadUnit.on(this.vehicle);
        if (state == DroneState.PAYLOAD_RUN && hardpoint != null && !hardpoint.isArmed()) {
            hardpoint.setOn(true);
        }
    }

    @Override
    public void onAttitude(FlightAttitude attitude) {
    }

    @Override
    @Nullable
    public Telemetry datalink() {
        return this.vehicle.datalink();
    }

    /** Charge in wflib units: ticks of full-power burn x stock hover drain, so PowerPolicy reserves read the same. */
    private double charge(double energy) {
        return energy / Math.max(1.0E-9, this.vehicle.burnPerTick()) * PowerProfile.DEFAULT.hoverDrain();
    }

    @Override
    public DroneSnapshot snapshot(ServerLevel level, long gameTime) {
        Vec3 pos = this.vehicle.position();
        Vec3 destination = this.route.getDestination();
        double groundY = TerrainSampler.groundY(level, pos.x, pos.z, pos.y - this.flight.getCruiseAltitude());
        double destGroundY = destination == null ? groundY
                : TerrainSampler.groundY(level, destination.x, destination.z, destination.y);
        this.flight.noteGround(groundY);
        return new DroneSnapshot(this.vehicle.getUUID(), false, pos, this.vehicle.getDeltaMovement(),
                (float) -Math.toRadians(this.vehicle.getYRot()), this.pilot.attitude(this.vehicle),
                this.pilot.envelope(this.vehicle), this.route.sampleNav(level, pos, groundY, gameTime),
                this.flight.getDroneState(), this.flight.stateTicks(), destination, this.route.nextLeg(),
                this.orders.getProgram(), this.route.getExfil(), false, this.loaded(), false,
                this.orders.dropZoneClear(level), this.orders.sweepForContacts(level),
                this.charge(this.vehicle.getEnergy()), this.charge(this.vehicle.energyInfo.capacityUnits()),
                PowerProfile.DEFAULT, this.flight.getCruiseSpeed(), this.flight.getCruiseAltitude(),
                this.flight.getClimbRate(), this.flight.getReleaseSpeed(), groundY, destGroundY,
                this.squad.squadId(), this.squad.leader(), this.squad.squadSize(), null, gameTime,
                this.vehicle.getUUID().getLeastSignificantBits());
    }

    /** Remote pilot aboard: plan dropped, state machine keeps reading the real position. */
    @Override
    public void apply(ServerLevel level, DronePlan plan) {
        if (piloted()) {
            return;
        }
        for (DroneAction action : plan.actions()) {
            this.perform(action);
        }
        if (plan.nextState() != null) {
            this.flight.setState(plan.nextState());
        }
        this.desired = plan.desired();
        this.feedForward = plan.feedForward();
        this.yaw = (float) -Math.toDegrees(plan.yaw());
    }

    @Override
    public void coast(ServerLevel level) {
    }

    private void perform(DroneAction action) {
        switch (action) {
            case DroneAction.AdvanceLeg ignored -> this.route.advanceLeg();
            case DroneAction.AdvanceTask ignored -> this.orders.advanceTask();
            case DroneAction.AbortProgram abort -> this.orders.abandonProgram(abort.reason());
            case DroneAction.CompleteMission complete -> {
                this.route.setDestination(null);
                this.recordEvent(WFEventType.MISSION_COMPLETE, complete.reason());
            }
            case DroneAction.Retarget retarget -> this.route.setDestination(retarget.destination());
            case DroneAction.Log log -> this.recordEvent(log.type(), log.detail());
            case DroneAction.DropPayload drop -> this.release(drop.aim());
            default -> throw new IllegalStateException(this.vehicle.getVehicleId() + " cannot " + action
                    + "; autonomy.tasks lists more than it flies");
        }
    }

    /** Drone-consuming payload => dive on {@code aim}; else drop one. */
    private void release(Vec3 aim) {
        PayloadUnit hardpoint = PayloadUnit.on(this.vehicle);
        if (hardpoint.getPayload().consumesDrone(FpvTrigger.OPERATOR)) {
            this.dive = aim;
        } else {
            hardpoint.fire(FpvTrigger.OPERATOR, null, 0);
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Flight", this.flight.save());
        tag.put("Route", this.route.save());
        tag.put("Orders", this.orders.save());
        tag.put("Squad", this.squad.save());
        if (this.callsign != null) {
            tag.putString("Callsign", this.callsign);
        }
        if (this.operatorId != null) {
            tag.putUUID("Operator", this.operatorId);
        }
        if (this.dive != null) {
            tag.putDouble("DiveX", this.dive.x);
            tag.putDouble("DiveY", this.dive.y);
            tag.putDouble("DiveZ", this.dive.z);
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        this.flight.load(tag.getCompound("Flight"));
        this.route.load(tag.getCompound("Route"));
        this.orders.load(tag.getCompound("Orders"));
        this.squad.load(tag.getCompound("Squad"));
        this.callsign = tag.contains("Callsign") ? tag.getString("Callsign") : null;
        this.operatorId = tag.hasUUID("Operator") ? tag.getUUID("Operator") : null;
        this.dive = tag.contains("DiveX")
                ? new Vec3(tag.getDouble("DiveX"), tag.getDouble("DiveY"), tag.getDouble("DiveZ")) : null;
    }
}
