package com.wf.combinedassault.uav.fpv.payload;

import com.google.gson.JsonObject;
import com.wf.combinedassault.vehicle.VehicleDamageTypes;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.drone.api.StrappedExplosive;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Kamikaze: one strapped {@code #wflib:drone_explosive}, its own wflib warhead along the flight path, or dropped. */
public class WarheadPayload extends StrappedPayload {

    /** On top of the blast, to whatever the drone struck. */
    private float hitDamage;
    /** Blocks/tick: a settling landing stays under this, a dive doesn't. */
    private double fuzeSpeed;

    public WarheadPayload(FpvPayloadType type, JsonObject json) {
        super(type, json, 1, 40);
        this.hitDamage = GsonHelper.getAsFloat(json, "hit_damage", 30F);
        this.fuzeSpeed = GsonHelper.getAsDouble(json, "fuze_speed", 0.12D);
    }

    @Override
    protected float massOf(ItemStack stack) {
        return StrappedExplosive.of(stack).massKg();
    }

    @Override
    public boolean canLoad(ItemStack stack) {
        return this.items.size() < this.capacity && StrappedExplosive.of(stack) != null;
    }

    @Override
    public Component displayName() {
        return this.items.isEmpty() ? Component.translatable("fpv.combinedassault.payload.warhead")
                : this.items.getFirst().getHoverName();
    }

    @Override
    public boolean accepts(FpvTrigger trigger, double closingSpeed) {
        return !this.items.isEmpty() && switch (trigger) {
            case IMPACT -> closingSpeed >= this.fuzeSpeed;
            // Cook-off on carrier death: makes a drone unsafe to shoot down rather than a free kill.
            case OPERATOR, RELEASE, DRONE_DESTROYED -> true;
            // Losing signal strands the drone rather than bombing whatever happens to be under it.
            case LINK_LOST -> false;
        };
    }

    @Override
    public boolean consumesDrone(FpvTrigger trigger) {
        return trigger != FpvTrigger.RELEASE;
    }

    @Override
    public FpvPayloadResult trigger(PayloadUnit hardpoint, FpvTrigger trigger, Vec3 at, @Nullable LivingEntity operator) {
        if (!(hardpoint.getVehicle().level() instanceof ServerLevel level)) {
            return FpvPayloadResult.NOTHING;
        }
        AbstractVehicle drone = hardpoint.getVehicle();
        if (trigger == FpvTrigger.RELEASE) {
            // Operator rides the drone => same root vehicle => round never strikes its own carrier.
            StrappedExplosive.of(this.items.removeFirst()).release(level, at, drone.getDeltaMovement(),
                    operator != null ? operator : drone, drone.uavSide());
            return FpvPayloadResult.SPENT;
        }
        // Drone already discarded: not the direct entity, not caught in its own blast.
        Entity struck = hardpoint.takeStruckTarget();
        if (struck != null && this.hitDamage > 0) {
            struck.hurt(VehicleDamageTypes.Sources.explosion(level.registryAccess(), null, operator, at), this.hitDamage);
            // Same-tick blast; vanilla invulnerability frames would otherwise swallow it.
            struck.invulnerableTime = 0;
        }
        StrappedExplosive.of(this.items.removeFirst()).detonate(level, at, drone.getLookAngle(), operator,
                drone.uavSide());
        return FpvPayloadResult.DETONATED;
    }

    @Override
    public void save(CompoundTag tag, HolderLookup.Provider provider) {
        super.save(tag, provider);
        tag.putFloat("HitDamage", this.hitDamage);
        tag.putDouble("FuzeSpeed", this.fuzeSpeed);
    }

    @Override
    public void load(CompoundTag tag, HolderLookup.Provider provider) {
        super.load(tag, provider);
        this.hitDamage = tag.getFloat("HitDamage");
        this.fuzeSpeed = tag.getDouble("FuzeSpeed");
    }
}
