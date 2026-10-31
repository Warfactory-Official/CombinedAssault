package com.wf.combinedassault.ballistics;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullResolver;
import com.wf.wflib.armor.api.hull.HullResult;
import com.wf.wflib.damage.api.ThreatKind;

public class ArmorResolver implements BallisticResolver {

    @Override
    @Nullable
    public HullResult resolve(AbstractVehicle vehicle, Vec3 start, Vec3 direction, Vec3 hitPos, float penetration,
                              float incomingDamage, ThreatKind kind) {
        return HullResolver.resolve(vehicle, start, direction, hitPos, penetration, incomingDamage, kind);
    }
}
