package com.wf.combinedassault.labs;

import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.wflib.damage.api.DamageClass;
import com.wf.wflib.damage.api.WFDamageSources;
import com.wf.wflib.labs.api.LabShelter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An occupant seated in a CA vehicle. CA shields nothing but damage events ({@code protect_passenger} zeroes
 * {@code LivingDamageEvent.Pre}): damage hazards run through {@code LivingEntity.hurt} with the vehicle boarded;
 * radiation and gas are not damage events, CA has no code for them, so the full amount arrives.
 */
final class VehicleShelter implements LabShelter {

    private final ResourceLocation id;
    private final VehicleRig rig;

    private VehicleShelter(ResourceLocation id, VehicleRig rig) {
        this.id = id;
        this.rig = rig;
    }

    static List<LabShelter> all(ServerLevel level) {
        List<LabShelter> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, BaseVehicleData> e : new TreeMap<>(
                CommonAssetsManager.vehicleDataManager().getVehicleData()).entrySet()) {
            VehicleRig rig = VehicleRig.spawnLive(level, e.getKey(), e.getValue(), false);
            if (rig.vehicle.armorProfile != null && rig.vehicle.armorProfile.isDefined()) {
                out.add(new VehicleShelter(e.getKey(), rig));
            } else {
                rig.vehicle.discard();
            }
        }
        return out;
    }

    @Override
    public String id() {
        return id.toString();
    }

    @Override
    public double shield(LivingEntity occupant, String kind, double amount) {
        if (kind.equals("radiation") || kind.equals("gas") || !(occupant.level() instanceof ServerLevel level)) {
            return amount;
        }
        try {
            if (!occupant.startRiding(rig.vehicle, true)) {
                return amount;
            }
            float before = occupant.getHealth();
            DamageClass clazz = kind.equals("fire") ? DamageClass.FIRE : kind.equals("laser") ? DamageClass.LASER
                    : kind.equals("electric") ? DamageClass.ELECTRIC : kind.equals("explosive") ? DamageClass.EXPLOSIVE : DamageClass.PHYSICAL;
            occupant.hurt(WFDamageSources.create(level, clazz, null, rig.vehicle), (float) amount);
            double got = before - occupant.getHealth();
            occupant.setHealth(before);
            return Math.max(0, got);
        } finally {
            occupant.stopRiding();
        }
    }
}
