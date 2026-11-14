package com.wf.combinedassault.radar.client;

import com.wf.combinedassault.vehicle.client.VehicleKeys;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.radar.message.ClientRadarScope;
import com.wf.wflib.recon.api.ScopeFrame;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Expanded radar scope state; client only. */
@OnlyIn(Dist.CLIENT)
public final class RadarExpanded {
    public static final int[] RANGES = {250, 500, 1000, 2000, 4000, 8000, 16000};
    private static boolean open;
    private static int index = 2;
    /** Net frames arrive every 10 ticks; older than this => link lost. */
    private static final long STALE_MS = 1500;
    private static ScopeFrame frame;
    private static long received;

    private RadarExpanded() {
    }

    public static boolean shown(AbstractVehicle vehicle) {
        return open && capable(vehicle);
    }

    public static void accept(ScopeFrame next) {
        frame = next;
        received = System.currentTimeMillis();
    }

    /** Latest network picture while the link is live, else null. */
    @Nullable
    public static ScopeFrame frame() {
        return frame != null && System.currentTimeMillis() - received < STALE_MS ? frame : null;
    }

    public static int range() {
        return RANGES[index];
    }

    /** Any radar unit, powered or not, or a warning receiver. */
    public static boolean capable(AbstractVehicle vehicle) {
        if (vehicle.warningReceiver != null) {
            return true;
        }
        List<PartUnit<?>> parts = vehicle.getPartUnits();
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) instanceof WeaponUnit weapon && !weapon.getRadarUnits().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static void tick(AbstractVehicle vehicle) {
        while (VehicleKeys.RADAR_SCOPE.consumeClick()) {
            open = !open && capable(vehicle);
            if (open) {
                index = nearest(farthestRange(vehicle));
            }
            announce();
        }
        while (VehicleKeys.RADAR_RANGE_UP.consumeClick()) {
            index = Math.min(RANGES.length - 1, index + 1);
            announce();
        }
        while (VehicleKeys.RADAR_RANGE_DOWN.consumeClick()) {
            index = Math.max(0, index - 1);
            announce();
        }
    }

    private static void announce() {
        frame = null;
        PacketDistributor.sendToServer(new ClientRadarScope(open ? range() : 0));
    }

    /** Longest scan distance of the vehicle's radars; 0 without any. */
    private static double farthestRange(AbstractVehicle vehicle) {
        double range = 0;
        List<PartUnit<?>> parts = vehicle.getPartUnits();
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) instanceof WeaponUnit weapon) {
                var radars = weapon.getRadarUnits();
                for (int r = 0; r < radars.size(); r++) {
                    range = Math.max(range, radars.get(r).getMaxScanDistance());
                }
            }
        }
        return range;
    }

    private static int nearest(double range) {
        int best = 2;
        if (range > 0) {
            best = 0;
            for (int i = 0; i < RANGES.length; i++) {
                if (RANGES[i] <= range) {
                    best = i;
                }
            }
        }
        return best;
    }
}
