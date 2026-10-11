package com.wf.combinedassault.fpv.payload;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.api.custom.sync.SyncDataSerializers;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.fpv.FpvImpact;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;

import java.util.List;

/**
 * Mount (this unit) + payload (the load), matching the host's missile rack so a new ordnance kind is a
 * new {@link FpvPayload}, not a new drone. Declares no structure bone, like the host's plain seats: no
 * collision cubes, cannot be shot off independently.
 *
 * <p>Extends {@link SwitchableUnit}: arm/safe really is a switch, so it syncs on the stock vehicle-action
 * packet for free. Firing is destructive and gets its own checked message instead.
 */
public class PayloadUnit extends SwitchableUnit<PayloadUnitData> {

    private static final String TAG_PAYLOAD_TYPE = "PayloadType";
    private static final String TAG_PAYLOAD = "Payload";
    private static final String TAG_ARMED_TICKS = "ArmedTicks";

    @Nullable
    private FpvPayload payload;
    private int armedTicks;

    /** What was just hit; threaded through here, not {@link FpvPayload#trigger}'s signature, so others skip it. */
    @Nullable
    private Entity struckTarget;

    /** True mid-detonation: without this, the operator's forced eject looks identical to stepping off voluntarily. */
    private boolean detonating;

    /** Last tick's position + distance moved, for the swept impact test. */
    @Nullable
    private Vec3 lastPos;
    private double lastSpeed;

    public PayloadUnit(int index, AbstractVehicle vehicle, PayloadUnitData data) {
        super(index, vehicle, data);
        this.payload = data.createPayload();
        // Fuze countdown synced for ARMING-vs-ARMED HUD state; only bit SwitchableUnit doesn't already carry.
        this.getSyncData().define(SyncDataSerializers.INT, this::setArmedTicks, this::getArmedTicks, 0);
    }

    /** The first hardpoint on a vehicle, or null if it carries nothing. */
    @Nullable
    public static PayloadUnit on(@Nullable AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof PayloadUnit hardpoint) {
                return hardpoint;
            }
        }
        return null;
    }

    @Nullable
    public FpvPayload getPayload() {
        return payload;
    }

    public boolean isArmed() {
        return payload != null && isOn();
    }

    /** Flipping resets the fuze delay; toggling can't be used to skip the wait. */
    @Override
    public void setOn(boolean on) {
        if (isOn() != on) {
            this.armedTicks = 0;
        }
        super.setOn(on);
        if (payload != null) {
            payload.setArmed(on);
        }
    }

    /** Armed + past fuze delay; until then it's a brick, so arming at the operator's own feet is survivable. */
    public boolean isLive() {
        return isArmed() && armedTicks >= payload.armingTicks();
    }

    /** True if the hardpoint carries anything. */
    public boolean isLoaded() {
        return payload != null;
    }

    /** Ticks still to run before {@link #isLive()}. */
    public int armingRemaining() {
        return payload == null ? 0 : Math.max(0, payload.armingTicks() - armedTicks);
    }

    public int getArmedTicks() {
        return armedTicks;
    }

    private void setArmedTicks(int armedTicks) {
        this.armedTicks = armedTicks;
    }

    /** Fuze ticks configured on the payload, or zero when the hardpoint is empty. */
    public int armingTicks() {
        return payload == null ? 0 : payload.armingTicks();
    }

    /**
     * Fuze, impact test and cook-off, all here: no session/link manager/world scan needed, since part
     * units already tick and an empty hardpoint costs one null check. An armed drone behaves the same
     * flown, hovering unpiloted, or falling with the operator already gone.
     */
    @Override
    public void tick() {
        super.tick();
        if (isArmed() && armedTicks < payload.armingTicks()) {
            armedTicks++;
        }
        if (vehicle.level().isClientSide() || payload == null) {
            return;
        }
        Vec3 now = vehicle.position();
        Vec3 previous = lastPos;
        double travelled = previous == null ? 0 : now.distanceTo(previous);

        if (isLive()) {
            // Live charge + destroyed drone => fire before the wreck settles; otherwise a free kill.
            if (vehicle.isDestroyed()) {
                fire(FpvTrigger.DRONE_DESTROYED, operator(), 0);
            } else if (previous != null) {
                FpvImpact.Contact contact = FpvImpact.detect(vehicle, previous, lastSpeed);
                if (contact != null) {
                    setStruckTarget(contact.entity());
                    if (!fire(FpvTrigger.IMPACT, operator(), contact.closingSpeed())) {
                        setStruckTarget(null);
                    }
                }
            }
        }
        this.lastSpeed = travelled;
        this.lastPos = now;
    }

    /** Whoever is flying, for damage attribution. Null for a drone nobody is aboard. */
    @Nullable
    private LivingEntity operator() {
        return vehicle.controlUnit.getOperator();
    }

    /** True only between the operator being ejected and the effect landing. */
    public boolean isDetonating() {
        return detonating;
    }

    /** Where the payload sits; also where its effect lands. */
    public Vec3 payloadPosition() {
        return worldPosition(data.getAttachOffset());
    }

    public void setStruckTarget(@Nullable Entity struckTarget) {
        this.struckTarget = struckTarget;
    }

    @Nullable
    public Entity takeStruckTarget() {
        Entity target = struckTarget;
        this.struckTarget = null;
        return target;
    }

    /**
     * Gates on armed + {@link FpvPayload#accepts}, then if {@link FpvPayload#consumesDrone} clears the
     * operator and removes the drone before the effect exists. Host ejects a damaged remote pilot
     * instantly, so firing with them still aboard would dismount them into their own blast.
     *
     * @return true if the payload acted
     */
    public boolean fire(FpvTrigger trigger, @Nullable LivingEntity operator, double closingSpeed) {
        return fire(trigger, operator, closingSpeed, null);
    }

    /**
     * @param where effect location, or null for the hardpoint's current position. Matters when trigger and
     *              fire land a tick apart: a drone falls far in a tick, and the wreck's position isn't
     *              where the shooter was standing.
     */
    public boolean fire(FpvTrigger trigger, @Nullable LivingEntity operator, double closingSpeed, @Nullable Vec3 where) {
        if (payload == null || vehicle.level().isClientSide()) {
            return false;
        }
        if (!isLive() || !payload.accepts(trigger, closingSpeed)) {
            return false;
        }
        Vec3 at = where != null ? where : payloadPosition();
        if (payload.consumesDrone(trigger)) {
            this.detonating = true;
            for (Entity rider : List.copyOf(vehicle.getPassengers())) {
                rider.stopRiding();
            }
            vehicle.discard();
        }
        FpvPayloadResult result = payload.trigger(this, trigger, at, operator);
        if (result == FpvPayloadResult.NOTHING) {
            this.detonating = false;
            return false;
        }
        if (payload.remaining() <= 0) {
            this.payload = null;
        }
        this.struckTarget = null;
        this.detonating = false;
        return true;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = super.serializeNBT(provider);
        if (payload != null) {
            tag.putString(TAG_PAYLOAD_TYPE, payload.type().id().toString());
            CompoundTag payloadTag = new CompoundTag();
            payload.save(payloadTag, provider);
            tag.put(TAG_PAYLOAD, payloadTag);
            tag.putInt(TAG_ARMED_TICKS, armedTicks);
        }
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        if (!nbt.contains(TAG_PAYLOAD_TYPE)) {
            // Empty hardpoint saved as empty; don't re-arm from JSON on load.
            this.payload = null;
            this.armedTicks = 0;
            return;
        }
        FpvPayloadType type = FpvPayloadTypes.get(ResourceLocation.tryParse(nbt.getString(TAG_PAYLOAD_TYPE)));
        if (type == null) {
            this.payload = null;
            this.armedTicks = 0;
            return;
        }
        FpvPayload restored = type.empty().create(type);
        restored.load(nbt.getCompound(TAG_PAYLOAD), provider);
        this.payload = restored;
        this.armedTicks = nbt.getInt(TAG_ARMED_TICKS);
        // Switch is authority on armed state; payload's own flag follows, or HUD reads safe while fuze still counts.
        this.payload.setArmed(isOn());
    }

    /** Registered type id for the part-unit registry. */
    public static ResourceLocation typeId() {
        return CombinedAssault.modLocation("payload");
    }

}
