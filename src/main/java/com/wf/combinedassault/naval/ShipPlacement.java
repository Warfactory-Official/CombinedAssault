package com.wf.combinedassault.naval;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.custom.vehicle.ShipVehicleData;

/**
 * Hull placement: on water the player looks at, clear of the player, floating at its waterline.
 * Crosshair ignores fluids (hits seabed or nothing over water) => hull uses a fluid-stopping ray instead.
 */
public final class ShipPlacement {

    /** Shortest placement ray; reaches water from a bank/low hover despite a shallow look angle. */
    private static final double MIN_REACH = 24.0;
    /** Longest, so a carrier is not placed out past the chunks that are loaded to hold it. */
    private static final double MAX_REACH = 128.0;
    /** Blocks between the player and the near end of the hull. */
    private static final double CLEARANCE = 2.0;
    /** Fractions of the hull's length the water is sampled at, from the centre. */
    private static final double[] ALONG = {-0.45, 0.0, 0.45};

    private ShipPlacement() {
    }

    /**
     * A place for a hull: where its origin goes, the water it would sit in, and the water it wants.
     *
     * @param pos    origin of the hull, on the surface at its waterline
     * @param depth  shallowest water under the hull's length, in blocks
     * @param needed draft plus the clearance the hull asks for under its keel
     */
    public record Spot(Vec3 pos, double depth, double needed) {

        public boolean shallow() {
            return depth < needed;
        }

    }

    /**
     * Water the player looks at, or {@code null}. Hull pushed along look dir until its near end clears
     * the player, so a carrier is not placed around the placer.
     */
    public static Spot aim(Level level, Player player, ShipVehicleData ship, float yaw) {
        double length = Math.max(ship.getStructureLength(), 1.0);
        double reach = Mth.clamp(length, MIN_REACH, MAX_REACH);
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        HitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(reach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK
                || level.getFluidState(block.getBlockPos()).isEmpty()) {
            return null;
        }
        Vec3 target = hit.getLocation();
        double clear = length * 0.5 + CLEARANCE;
        double have = Math.hypot(target.x - player.getX(), target.z - player.getZ());
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (have < clear && flat.lengthSqr() > 1.0E-6) {
            flat = flat.normalize();
            target = new Vec3(player.getX() + flat.x * clear, target.y, player.getZ() + flat.z * clear);
        }
        return at(level, ship, target, yaw);
    }

    /** The same, for a point already known to be over water. */
    public static Spot at(Level level, ShipVehicleData ship, Vec3 target, float yaw) {
        WaterColumn column = new WaterColumn();
        if (!column.sample(level, target.x, target.z, target.y)) {
            return null;
        }
        double surface = column.surfaceY;
        double depth = surface - column.bedY;
        double length = Math.max(ship.getStructureLength(), 1.0);
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        for (double along : ALONG) {
            if (along == 0.0) {
                continue;
            }
            double x = target.x + forwardX * along * length;
            double z = target.z + forwardZ * along * length;
            // End over no water = half on the beach = shallowest reading.
            depth = column.sample(level, x, z, surface) ? Math.min(depth, column.surfaceY - column.bedY)
                    : Math.min(depth, 0.0);
        }
        double needed = ship.params.draft + ship.params.minKeelClearance;
        return new Spot(new Vec3(target.x, surface - ship.params.waterline, target.z), depth, needed);
    }

}
