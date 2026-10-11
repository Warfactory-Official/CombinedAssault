package com.wf.combinedassault.fpv.payload;

import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.all.AllDamageTypes;
import com.wf.combinedassault.util.VehicleExplosion;

/**
 * Impact-fuzed; delivery is flying the drone into the target, so the drone doesn't survive using it.
 * All numbers from JSON: a heavier/lighter warhead is a data change.
 */
public class WarheadPayload implements FpvPayload {

    private final FpvPayloadType type;
    private final float damage;
    private final float hitDamage;
    private final float radius;
    private final boolean destroyBlocks;
    private final int armingTicks;
    private final double fuzeSpeed;
    private boolean armed;

    public WarheadPayload(FpvPayloadType type, JsonObject json) {
        this.type = type;
        this.damage = GsonHelper.getAsFloat(json, "damage", 60F);
        // On top of blast: landed on whatever the drone struck, so a direct hit beats a near miss.
        this.hitDamage = GsonHelper.getAsFloat(json, "hit_damage", 30F);
        this.radius = GsonHelper.getAsFloat(json, "radius", 5F);
        this.destroyBlocks = GsonHelper.getAsBoolean(json, "destroy_block", true);
        this.armingTicks = GsonHelper.getAsInt(json, "arming_ticks", 40);
        // Blocks/tick not per second: a settling landing stays under this, a dive doesn't.
        this.fuzeSpeed = GsonHelper.getAsDouble(json, "fuze_speed", 0.12D);
    }

    @Override
    public FpvPayloadType type() {
        return type;
    }

    @Override
    public Component displayName() {
        return Component.translatable("fpv.combinedassault.payload.warhead");
    }

    @Override
    public int remaining() {
        return 1;
    }

    @Override
    public int capacity() {
        return 1;
    }

    @Override
    public boolean isArmed() {
        return armed;
    }

    @Override
    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    @Override
    public int armingTicks() {
        return armingTicks;
    }

    @Override
    public boolean accepts(FpvTrigger trigger, double closingSpeed) {
        return switch (trigger) {
            case IMPACT -> closingSpeed >= fuzeSpeed;
            // Cook-off on carrier death: makes a drone unsafe to shoot down rather than a free kill.
            case OPERATOR, DRONE_DESTROYED -> true;
            // Losing signal strands the drone rather than bombing whatever happens to be under it.
            case LINK_LOST -> false;
        };
    }

    /** Always true: that's what makes it a kamikaze warhead. */
    @Override
    public boolean consumesDrone(FpvTrigger trigger) {
        return true;
    }

    @Override
    public FpvPayloadResult trigger(PayloadUnit hardpoint, FpvTrigger trigger, Vec3 at, @Nullable LivingEntity operator) {
        Level level = hardpoint.getVehicle().level();
        if (level.isClientSide()) {
            return FpvPayloadResult.NOTHING;
        }
        // Drone already discarded: not the direct entity, not caught in its own blast. Attribution
        // credits the operator (kill credit, claim-mod visibility).
        Entity struck = hardpoint.takeStruckTarget();
        if (struck != null && hitDamage > 0) {
            struck.hurt(AllDamageTypes.Sources.explosion(level.registryAccess(), null, operator, at), hitDamage);
            // Same-tick blast; vanilla invulnerability frames would otherwise swallow it.
            struck.invulnerableTime = 0;
        }
        new VehicleExplosion(level, operator, null, at, radius, damage, destroyBlocks).explode();
        return FpvPayloadResult.DETONATED;
    }

    @Override
    public void save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putBoolean("Armed", armed);
    }

    @Override
    public void load(CompoundTag tag, HolderLookup.Provider provider) {
        this.armed = tag.getBoolean("Armed");
    }

    public float getDamage() {
        return damage;
    }

    public float getRadius() {
        return radius;
    }

}
