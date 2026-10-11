package com.wf.combinedassault.ballistics;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;

/** Impact -> damage. Null => caller falls back to stock model; lets a resolver cover partial cases. */
public interface BallisticResolver {

    @Nullable
    BallisticResult resolve(AbstractVehicle vehicle, Vec3 start, Vec3 direction, Vec3 hitPos,
                            @Nullable PartUnit<?> hitPart, float penetration, float incomingDamage);

}
