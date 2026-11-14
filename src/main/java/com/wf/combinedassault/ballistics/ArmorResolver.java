package com.wf.combinedassault.ballistics;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullResolver;
import com.wf.wflib.armor.api.hull.HullResult;
import com.wf.wflib.damage.api.Threat;
import net.minecraft.world.phys.Vec3;
import com.wf.wflib.armor.api.hull.PlateWalk;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class ArmorResolver implements BallisticResolver {

    @Override
    @Nullable
    public HullResult resolve(AbstractVehicle vehicle, Vec3 start, Vec3 direction, Vec3 hitPos, Threat threat,
                              float incomingDamage, @Nullable Consumer<PlateWalk> lead) {
        return HullResolver.resolve(vehicle, start, direction, hitPos, threat, incomingDamage, lead);
    }
}
