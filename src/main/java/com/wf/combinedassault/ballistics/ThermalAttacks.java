package com.wf.combinedassault.ballistics;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllDamageTypes;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.VehicleBlast;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;
import com.wf.wflib.api.ThermalAttackEvent;
import com.wf.wflib.util.OBB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Vector3f;

/**
 * Incendiary heat on a hull: {@code vehicle.hurt(thermite)} at the hull point nearest the charge => armour region wear
 * ({@link ArmorRegions.Kind#THERMAL}), hull, struck system. Charge in the running-gear band under a track => that
 * track also takes {@link #TRACK_SHARE}.
 */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID)
public final class ThermalAttacks {

    /** Heat => vehicle damage. */
    static final float HEAT_DAMAGE = 1.0f;
    static final float TRACK_SHARE = 1.5f;
    /** Running gear: lowest third of the main box. */
    static final double RUNNING_GEAR_BAND = 1.0 / 3.0;

    private ThermalAttacks() {
    }

    @SubscribeEvent
    public static void onThermal(ThermalAttackEvent event) {
        if (!(event.target() instanceof AbstractVehicle vehicle) || vehicle.isDestroyed()
                || vehicle.level().isClientSide()) {
            return;
        }
        Vec3 at = VehicleBlast.nearestHullPoint(vehicle, event.at());
        float damage = event.heat() * HEAT_DAMAGE;
        vehicle.hurt(AllDamageTypes.Sources.thermite(vehicle.level().registryAccess(), event.charge(),
                event.attacker(), at), damage);
        VehicleSystems.Side side = underTrack(vehicle, event.at());
        if (side != null) {
            for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
                if (cell.kind() == ComponentKind.TRACK && cell.side() == side) {
                    for (VehicleSystems.Member m : cell.members()) {
                        m.setHealth(Math.max(0, m.health() - damage * TRACK_SHARE));
                    }
                }
            }
        }
    }

    /** Side whose track the point is under or beside, inside the running-gear band; null = not. */
    static VehicleSystems.Side underTrack(AbstractVehicle vehicle, Vec3 world) {
        VehicleCubeOBB main = vehicle.getMainCubeOBB();
        if (main == null) {
            return null;
        }
        OBB box = main.obb();
        Vector3f[] axes = box.getAxes();
        double rx = world.x - box.centerX();
        double ry = world.y - box.centerY();
        double rz = world.z - box.centerZ();
        double x = rx * axes[0].x + ry * axes[0].y + rz * axes[0].z;
        double y = rx * axes[1].x + ry * axes[1].y + rz * axes[1].z;
        double z = rx * axes[2].x + ry * axes[2].y + rz * axes[2].z;
        Vector3f ext = box.extents();
        boolean low = y < -ext.y + 2 * ext.y * RUNNING_GEAR_BAND;
        // Outer half of the width: under a track, not under the belly between them.
        boolean outboard = Math.abs(x) > ext.x * 0.5;
        if (!low || !outboard || Math.abs(z) > ext.z + 0.5) {
            return null;
        }
        // +X = port = left
        return x >= 0 ? VehicleSystems.Side.LEFT : VehicleSystems.Side.RIGHT;
    }
}
