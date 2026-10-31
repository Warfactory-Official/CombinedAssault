package com.wf.combinedassault.ballistics;

import net.minecraft.world.phys.Vec3;
import com.wf.wflib.damage.api.ThreatKind;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullResult;

/** Impact -> damage. Null => caller falls back to stock model; lets a resolver cover partial cases. */
public interface BallisticResolver {

    @Nullable
    HullResult resolve(AbstractVehicle vehicle, Vec3 start, Vec3 direction, Vec3 hitPos, float penetration,
                       float incomingDamage, ThreatKind kind);

}
