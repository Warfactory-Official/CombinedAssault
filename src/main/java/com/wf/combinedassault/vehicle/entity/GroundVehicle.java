package com.wf.combinedassault.vehicle.entity;

import com.wf.combinedassault.vehicle.VehicleConfig;
import com.wf.combinedassault.vehicle.drive.Transmission;
import com.wf.combinedassault.vehicle.control.ControlUnit;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Wheeled / tracked: optional {@link Transmission}. {@link #drivetrain} turns W/S into a drive command in the
 * subclass's own units: {@link #push} (-1, 0, +1) x {@link #pushScale} under {@link #pushCap}, else {@link #braking} /
 * {@link #engineBraking} toward rest. Automatic: W/S = throttle/brake by travel; stopped, the other pedal selects
 * R / 1. Manual: W throttle (R: backward), S brake.
 */
public abstract class GroundVehicle extends AbstractVehicle {
    public static final EntityDataAccessor<Byte> GEAR = SynchedEntityData.defineId(GroundVehicle.class, EntityDataSerializers.BYTE);
    /** {@link #THROTTLE} | {@link #BRAKE} | {@link #HANDBRAKE}, as applied this tick. */
    public static final EntityDataAccessor<Byte> PEDALS = SynchedEntityData.defineId(GroundVehicle.class, EntityDataSerializers.BYTE);
    public static final int THROTTLE = 1;
    public static final int BRAKE = 2;
    public static final int HANDBRAKE = 4;
    /** Blocks/tick: below => stopped (automatic picks R / 1; manual may enter R). */
    public static final double STOPPED = 0.02;
    /** Throttle off in gear: drag as a fraction of the full brake. */
    public static final float ENGINE_BRAKE = 0.05F;
    /** Free-revving rpm change per tick, fraction of the idle..max span. */
    private static final float REV_RATE = 0.1F;

    @Nullable
    public Transmission transmission;
    /** Clutch open while > 0. */
    private int clutchTicks;
    /** Driver client: manual selection ({@link #selectGear}). */
    public byte selectedGear = ControlUnit.NO_GEAR;
    protected int push;
    /** Fraction of the pack's forward force / acceleration; reverse = 1. */
    protected float pushScale;
    /** Blocks/tick the push stops at. */
    protected float pushCap;
    protected boolean braking;
    protected boolean engineBraking;

    protected GroundVehicle(EntityType<? extends AbstractVehicle> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(GEAR, (byte) Transmission.NEUTRAL);
        builder.define(PEDALS, (byte) 0);
    }

    /**
     * Gear choice (automatic), drive command, rpm into engine speed, {@link #PEDALS}.
     *
     * @param along forward speed, blocks/tick (negative = rolling back)
     */
    protected void drivetrain(Transmission t, double along, float maxSpeedForward, float maxSpeedBackward, boolean handbrake) {
        boolean auto = automatic();
        int gear = getGear();
        boolean stopped = Math.abs(along) < STOPPED;
        int request = controlUnit.gearRequest;
        if (!auto && request != ControlUnit.NO_GEAR && request != gear && request >= Transmission.REVERSE
                && request <= t.forwardGears() && (request != Transmission.REVERSE || stopped)) {
            setGear(request);
            gear = request;
        }
        if (auto) {
            int to = gear;
            if (stopped && controlUnit.backward && !controlUnit.forward) {
                to = Transmission.REVERSE;
            } else if (stopped && controlUnit.forward && !controlUnit.backward
                    || gear == Transmission.NEUTRAL && controlUnit.forward) {
                to = 1;
            } else if (gear >= 1) {
                to = t.automatic(gear, (float) along, maxSpeedForward);
            }
            if (to != gear) {
                setGear(to);
                gear = to;
            }
        }
        boolean reverse = gear == Transmission.REVERSE;
        boolean throttle = reverse && auto ? controlUnit.backward : controlUnit.forward;
        boolean brake = reverse && auto ? controlUnit.forward : controlUnit.backward;
        if (clutchTicks > 0) {
            clutchTicks--;
        }
        boolean engaged = gear != Transmission.NEUTRAL && clutchTicks == 0;
        float top = reverse ? maxSpeedBackward : gear >= 1 ? t.topSpeed(gear, maxSpeedForward) : maxSpeedForward;
        braking = brake && !stopped;
        push = !braking && throttle && engaged ? reverse ? -1 : 1 : 0;
        pushScale = reverse || gear < 1 ? 1 : t.forceScale(gear);
        pushCap = top;
        engineBraking = !braking && push == 0 && engaged && !stopped;
        float rpm;
        float held = getEngineSpeed() / 100 * t.maxRpm;
        if (!hasPower()) {
            rpm = Math.max(0, held - t.maxRpm * REV_RATE);
        } else if (engaged) {
            rpm = t.rpm((float) Math.abs(along), top);
        } else {
            float target = throttle ? t.maxRpm : t.idleRpm;
            float step = (t.maxRpm - t.idleRpm) * REV_RATE;
            rpm = held < target ? Math.min(target, held + step) : Math.max(target, held - step);
        }
        setEngineSpeed(rpm / t.maxRpm * 100);
        setPedals((throttle ? THROTTLE : 0) | (brake ? BRAKE : 0) | (handbrake ? HANDBRAKE : 0));
    }

    protected void setPedals(int pedals) {
        entityData.set(PEDALS, (byte) pedals);
    }

    public int getPedals() {
        return entityData.get(PEDALS);
    }

    /** Manual box shifts itself for a non-player driver (autopilot, NPC): nothing else presses shift. */
    public boolean automatic() {
        return transmission != null && (transmission.shift == Transmission.Shift.AUTOMATIC || VehicleConfig.automaticOnly()
                || !(getDriver() instanceof Player));
    }

    public int getGear() {
        return entityData.get(GEAR);
    }

    private void setGear(int gear) {
        entityData.set(GEAR, (byte) gear);
        clutchTicks = transmission.shiftTicks;
    }

    /** Signed forward speed, blocks/tick. */
    public abstract float getForwardSpeed();

    /**
     * Driver client, manual box: selection one position up (+1) or down (-1), sent as {@link ControlUnit#gearRequest}
     * with every input so the server shifts on the same input tick. False: R refused while moving.
     */
    public boolean selectGear(int step) {
        int from = selectedGear == ControlUnit.NO_GEAR ? getGear() : selectedGear;
        int to = Mth.clamp(from + Integer.signum(step), Transmission.REVERSE, transmission.forwardGears());
        if (to == Transmission.REVERSE && Math.abs(getForwardSpeed()) >= STOPPED) {
            return false;
        }
        selectedGear = (byte) to;
        return true;
    }

    /** rpm; NaN without a transmission. */
    public float rpm() {
        return transmission == null ? Float.NaN : getEngineSpeed() / 100 * transmission.maxRpm;
    }

    @Override
    protected void writePrediction(FriendlyByteBuf buf) {
        super.writePrediction(buf);
        buf.writeByte(getGear());
        buf.writeVarInt(clutchTicks);
        buf.writeByte(getPedals());
        // no transmission => engine speed server-only (tickEngineSpeed); restoring it rolls the synced value back
        if (transmission != null) {
            buf.writeFloat(getEngineSpeed());
        }
    }

    @Override
    protected void readPrediction(FriendlyByteBuf buf) {
        super.readPrediction(buf);
        entityData.set(GEAR, buf.readByte());
        clutchTicks = buf.readVarInt();
        entityData.set(PEDALS, buf.readByte());
        if (transmission != null) {
            setEngineSpeed(buf.readFloat());
        }
    }
}
