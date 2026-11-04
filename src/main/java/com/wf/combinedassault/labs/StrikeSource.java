package com.wf.combinedassault.labs;

import com.wf.wflib.damage.api.StrikeContext;
import com.wf.wflib.damage.api.Threat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** A round strike as armour reads it: what {@code DamageSystem} receives from a live round. */
final class StrikeSource extends DamageSource implements StrikeContext {

    private final Threat threat;
    private final Vec3 position;
    private final Vec3 velocity;

    StrikeSource(ServerLevel level, Threat threat, Vec3 position, Vec3 velocity) {
        super(level.damageSources().generic().typeHolder(), null, null, position);
        this.threat = threat;
        this.position = position;
        this.velocity = velocity;
    }

    @Override
    public Threat threat() {
        return threat;
    }

    @Override
    public Vec3 position() {
        return position;
    }

    @Override
    public Vec3 velocity() {
        return velocity;
    }

    @Override
    public Object key() {
        return this;
    }

    @Nullable
    @Override
    public Entity shooter() {
        return null;
    }
}
