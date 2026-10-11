package com.wf.combinedassault.airdefense;

import com.wf.combinedassault.compat.wflib.WfLibCompat;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.part.MissileHolderUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.VehicleMissile;
import com.wf.wflib.entity.InterceptTarget;
import com.wf.wflib.intercept.InterceptBattery;
import com.wf.wflib.item.MissileItem;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A station's {@code air_defense}: probe settings, and the WFLib battery that runs them while nobody operates it. */
public final class AirDefense implements InterceptBattery.Mount {

    public enum Mode {
        /** Operator (UAV controller) locks and fires. */
        MANUAL,
        /** Unmanned: engages on its own. */
        AUTO
    }

    public enum Source {
        /** Side's recon net picture only. */
        NETWORK,
        /** Own surveillance radar ({@code range}). */
        ORGANIC
    }

    public static final int MAX_SALVO = 2;
    private static final double LAID = Math.toRadians(2.0);
    /** Manual lock pick, radians off the sight line. */
    private static final double LOCK_CONE = Math.toRadians(3.0);
    private static final double LAY_DISTANCE = 64.0;

    private final WeaponUnit unit;
    private final AirDefenseSpec spec;
    private Mode mode = Mode.MANUAL;
    private Source source = Source.NETWORK;
    private int salvo = 1;
    @Nullable
    private InterceptBattery battery;
    private boolean running;
    private InterceptBattery.State state = InterceptBattery.State.IDLE;

    public AirDefense(WeaponUnit unit, AirDefenseSpec spec) {
        this.unit = unit;
        this.spec = spec;
    }

    /** Server tick. */
    public void tick() {
        AbstractVehicle vehicle = this.unit.getVehicle();
        if (!(vehicle.level() instanceof ServerLevel sl)) {
            return;
        }
        if (this.mode != Mode.AUTO || this.unit.getOwner() != null || vehicle.isDestroyed()
                || this.unit.isDestroyed()) {
            if (this.running) {
                this.battery().release(sl);
                this.running = false;
            }
            this.state = InterceptBattery.State.IDLE;
            return;
        }
        this.running = true;
        this.state = this.battery().tick(sl, new InterceptBattery.Settings(this.spec.range(),
                this.source == Source.ORGANIC, this.spec.mast(), this.salvo, this.spec.salvoSpacing(),
                this.spec.cooldown()));
    }

    private InterceptBattery battery() {
        if (this.battery == null) {
            UUID id = this.unit.getVehicle().getUUID();
            this.battery = new InterceptBattery(this, id.getMostSignificantBits() ^ id.getLeastSignificantBits()
                    ^ this.unit.getIndex(), id);
        }
        return this.battery;
    }

    /** Client: engageable target nearest the operator's sight line toward {@code lookAt}; never this vehicle's. */
    @Nullable
    public Entity lockCandidate(Vec3 lookAt) {
        Vec3 eye = this.unit.worldOpticalSightPosition(1.0f);
        UUID self = this.unit.getVehicle().getUUID();
        return InterceptBattery.lockCandidate(this.unit.getVehicle().level(), eye, lookAt.subtract(eye),
                this.spec.range(), LOCK_CONE, e -> !self.equals(((InterceptTarget) e).interceptControlId()));
    }

    // -- Mount --------------------------------------------------------------------------------------

    @Override
    public Vec3 muzzle() {
        return this.unit.aimPivot();
    }

    @Override
    public UUID controlId() {
        return this.unit.getVehicle().getUUID();
    }

    @Nullable
    @Override
    public UUID team() {
        return ReconOwners.owningEntity(this.unit.getVehicle());
    }

    /** Unowned => a net of its own: the shared unaffiliated net reads every unaffiliated missile as friendly. */
    @Override
    public long netId() {
        UUID team = this.team();
        return ReconNet.netId(team != null ? team : this.unit.getVehicle().getUUID());
    }

    @Override
    public double interceptorSpeed() {
        VehicleMissile launcher = this.launcher();
        MissileHolderUnit holder = launcher == null ? null : launcher.missileHolder();
        int slot = holder == null ? -1 : holder.nextLoaded(WfLibCompat::isInterceptor);
        return slot < 0 ? Double.NaN : ((MissileItem) holder.round(slot).getItem()).preset().cruiseSpeed();
    }

    /** Bearing of the lead point; elevation clamped to the spec window. */
    @Override
    public boolean lay(Vec3 aimPoint) {
        Vec3 d = aimPoint.subtract(this.muzzle());
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        Vec3 bearing = horizontal > 1.0e-3 ? new Vec3(d.x / horizontal, 0.0, d.z / horizontal)
                : new Vec3(this.unit.worldVec().x, 0.0, this.unit.worldVec().z).normalize();
        double elevation = Math.toRadians(Math.max(this.spec.minElevation(), Math.min(this.spec.maxElevation(),
                Math.toDegrees(Math.atan2(d.y, horizontal)))));
        Vec3 dir = bearing.scale(Math.cos(elevation)).add(0.0, Math.sin(elevation), 0.0);
        this.unit.aimAlong(dir, LAY_DISTANCE);
        return VectorUtil.angleBetween(this.unit.worldVec(), dir) < LAID;
    }

    @Override
    public boolean fire(InterceptTarget target) {
        VehicleMissile launcher = this.launcher();
        return launcher != null && launcher.fireRound(WfLibCompat::isInterceptor, target.interceptEntity());
    }

    @Override
    public boolean valid() {
        return this.unit.getVehicle().isAlive();
    }

    @Nullable
    private VehicleMissile launcher() {
        return this.unit.getCurrentWeapon().orElse(null) instanceof VehicleMissile missile ? missile : null;
    }

    // -- settings -----------------------------------------------------------------------------------

    public Mode mode() {
        return this.mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Source source() {
        return this.source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public int salvo() {
        return this.salvo;
    }

    public void setSalvo(int salvo) {
        this.salvo = Math.max(1, Math.min(MAX_SALVO, salvo));
    }

    public InterceptBattery.State state() {
        return this.state;
    }

    public int interceptors() {
        VehicleMissile launcher = this.launcher();
        MissileHolderUnit holder = launcher == null ? null : launcher.missileHolder();
        if (holder == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < holder.getCapacity(); i++) {
            ItemStack round = holder.round(i);
            if (!round.isEmpty() && WfLibCompat.isInterceptor(round)) {
                n++;
            }
        }
        return n;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", this.mode.name());
        tag.putString("Source", this.source.name());
        tag.putInt("Salvo", this.salvo);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.mode = Mode.valueOf(tag.getString("Mode"));
        this.source = Source.valueOf(tag.getString("Source"));
        this.setSalvo(tag.getInt("Salvo"));
    }
}
