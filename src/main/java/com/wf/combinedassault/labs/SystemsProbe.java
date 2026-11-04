package com.wf.combinedassault.labs;

import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.VehicleMobility;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullWear;

import java.util.List;

/** Reads and restores a vehicle's systems around real damage calls; kill predicates are CA's own. */
final class SystemsProbe {

    static final ComponentKind[] KINDS = {ComponentKind.HULL, ComponentKind.ENGINE, ComponentKind.TRACK, ComponentKind.WHEEL,
            ComponentKind.TURRET, ComponentKind.BARREL, ComponentKind.FUEL, ComponentKind.AMMO, ComponentKind.OPTICS,
            ComponentKind.ROTOR, ComponentKind.TAIL_ROTOR, ComponentKind.WING};

    private SystemsProbe() {
    }

    /** Per {@link #KINDS}: {health, max}. */
    static double[][] totals(AbstractVehicle vehicle) {
        double[][] out = new double[KINDS.length][2];
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            for (int k = 0; k < KINDS.length; k++) {
                if (KINDS[k] == cell.kind()) {
                    for (VehicleSystems.Member m : cell.members()) {
                        out[k][0] += m.health();
                        out[k][1] += m.maxHealth();
                    }
                }
            }
        }
        return out;
    }

    /** Fraction of each kind's health lost; NaN where the vehicle has none of it. */
    static double[] lost(double[][] before, double[][] after) {
        double[] out = new double[KINDS.length];
        for (int k = 0; k < KINDS.length; k++) {
            out[k] = before[k][1] <= 0 ? Double.NaN : Math.max(0, (before[k][0] - after[k][0]) / before[k][1]);
        }
        return out;
    }


    static void reset(AbstractVehicle vehicle) {
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            for (VehicleSystems.Member m : cell.members()) {
                m.setHealth(m.maxHealth());
            }
        }
        vehicle.setHealth(vehicle.getMaxHealth());
        for (HullWear.Face f : HullWear.Face.VALUES) {
            vehicle.hullWear().repair(f, 1);
        }
    }

    static boolean mobilityKilled(AbstractVehicle vehicle) {
        return !VehicleSystems.engineWorks(vehicle) || VehicleMobility.of(vehicle).speedScale() <= 1.0e-3f;
    }

    /** All gun systems failed; false when the vehicle has none. */
    static boolean firepowerKilled(AbstractVehicle vehicle) {
        boolean any = false;
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            if (cell.kind() == ComponentKind.BARREL) {
                any = true;
                if (!cell.failed()) {
                    return false;
                }
            }
        }
        return any;
    }

    static boolean catastrophic(AbstractVehicle vehicle) {
        return VehicleSystems.allFailed(vehicle);
    }

    /** Cell label that lost the most health since {@code before}, "" if none. */
    static String worstCell(AbstractVehicle vehicle, java.util.Map<String, Double> beforeByLabel) {
        String best = "";
        double most = 0;
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            double now = 0;
            for (VehicleSystems.Member m : cell.members()) {
                now += m.health();
            }
            double lost = beforeByLabel.getOrDefault(cell.label(), 0.0) - now;
            if (lost > most) {
                most = lost;
                best = cell.label();
            }
        }
        return best;
    }

    static java.util.Map<String, Double> byLabel(AbstractVehicle vehicle) {
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            double h = 0;
            for (VehicleSystems.Member m : cell.members()) {
                h += m.health();
            }
            out.put(cell.label(), h);
        }
        return out;
    }
}
