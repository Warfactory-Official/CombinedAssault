package com.wf.combinedassault.vehicle.parenting;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.List;
import java.util.UUID;

/**
 * Something a vehicle can be harnessed to. Carried thing always {@link AbstractVehicle}; carrier need not be
 * (hull, flatcar, lift: anything stating a pivot + rotation each tick via {@link #deckSnapshot()}).
 * Parking (rest via deck geometry/contact) stays on {@link AbstractVehicle}; a non-vehicle carrier holds
 * only by explicit mount.
 */
public interface DeckCarrier {

    DeckSnapshot deckSnapshot();

    /** Mutated in place by attach/detach, not replaced. */
    List<AbstractVehicle> harnessedVehicles();

    /** Origin to snapshot pivot; mount offsets are stated from the origin. */
    Vec3 carrierCenterOffset();

    Level level();

    boolean isRemoved();

    /** Null while present. Unloaded => its mounts wait rather than drop. */
    @Nullable
    Entity.RemovalReason getRemovalReason();

    /** Chunk of this position is where a waiting mount looks for the carrier. */
    Vec3 position();

    int getId();

    /** Survives reload; a mount records and resolves the carrier by it. */
    UUID getUUID();

    // Server-reported pose, not interpolated: a strapped vehicle's offset is derived from these on
    // both entities, since interpolated poses bake in different lags on the two.

    double lerpTargetX();

    double lerpTargetY();

    double lerpTargetZ();

    float lerpTargetXRot();

    float lerpTargetYRot();

    float lerpTargetZRot();
}
