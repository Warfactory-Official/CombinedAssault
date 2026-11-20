package com.wf.combinedassault.ballistics.event;

import com.wf.combinedassault.ballistics.message.HitDetail;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;

import java.util.UUID;

public class HitVehicleEvent extends Event {

    public final UUID shooterUuid;
    public final int entityId;
    public final Vec3 hitPosition;
    public final Vec3 hitVector;
    public float caliber;
    public final float damage;
    public final Component message;
    @Nullable
    public final HitDetail detail;

    public HitVehicleEvent(UUID shooterUuid, int entityId, Vec3 hitPosition, Vec3 hitVector, float caliber, float damage, Component message) {
        this(shooterUuid, entityId, hitPosition, hitVector, caliber, damage, message, null);
    }

    public HitVehicleEvent(UUID shooterUuid, int entityId, Vec3 hitPosition, Vec3 hitVector, float caliber, float damage, Component message,
                           @Nullable HitDetail detail) {
        this.shooterUuid = shooterUuid;
        this.entityId = entityId;
        this.hitPosition = hitPosition;
        this.hitVector = hitVector;
        this.caliber = caliber;
        this.damage = damage;
        this.message = message;
        this.detail = detail;
    }

}
