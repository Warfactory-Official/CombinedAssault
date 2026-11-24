package com.wf.combinedassault.dynamics;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.compat.dynamictrees.DynamicTrees;
import com.wf.wflib.geom.api.OBB;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic Trees branches in the hull's path this tick that its momentum can break are felled before the step:
 * the solver then never sees them, so the hull keeps its speed instead of stopping and breaking them a tick late.
 * Same budget as {@link PhysicsEngine} ram breaking: {@code mass * speed / ramBreakMomentum >= hardness}.
 */
public final class TreeRam {

    /** Ticks of travel swept ahead of the hull. */
    private static final double LOOKAHEAD = 2.0;
    /** Below this (blocks/tick) the solver's own ram breaking handles it. */
    private static final double MIN_SPEED = 0.05;
    private static final float REACH = 0.25f;

    private TreeRam() {
    }

    /** Tick thread, server, before the step. */
    public static void clear(AbstractVehicle vehicle) {
        if (!DynamicTrees.loaded() || !(vehicle.level() instanceof ServerLevel level) || !vehicle.physicsEngine.breaking()) {
            return;
        }
        Vec3 motion = vehicle.getDeltaMovement();
        double speed = motion.length();
        if (speed < MIN_SPEED) {
            return;
        }
        double perHardness = VehicleSettings.current().ramBreakMomentum();
        double budget = perHardness <= 0 ? Double.MAX_VALUE : vehicle.physicsEngine.mass * speed / perHardness;
        vehicle.updateOBBs();
        List<OBB> boxes = new ArrayList<>(vehicle.getOBBs());
        boxes.add(vehicle.getMainCubeOBB().obb());
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList cells = new LongArrayList();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Quaternionf inverse = new Quaternionf();
        Vector3f local = new Vector3f();
        for (double ahead = 0; ahead <= LOOKAHEAD; ahead += LOOKAHEAD / 2) {
            double sx = motion.x * ahead, sy = motion.y * ahead, sz = motion.z * ahead;
            for (OBB box : boxes) {
                AABB bounds = box.bounds().move(sx, sy, sz).inflate(REACH);
                Vector3f extents = box.extents();
                box.rotation().conjugate(inverse);
                double cx = box.centerX() + sx, cy = box.centerY() + sy, cz = box.centerZ() + sz;
                for (int x = Mth.floor(bounds.minX); x <= Mth.floor(bounds.maxX); x++) {
                    for (int y = Mth.floor(bounds.minY); y <= Mth.floor(bounds.maxY); y++) {
                        for (int z = Mth.floor(bounds.minZ); z <= Mth.floor(bounds.maxZ); z++) {
                            inverse.transform(local.set((float) (x + 0.5 - cx), (float) (y + 0.5 - cy), (float) (z + 0.5 - cz)));
                            if (Math.abs(local.x) > extents.x + REACH || Math.abs(local.y) > extents.y + REACH
                                    || Math.abs(local.z) > extents.z + REACH || !seen.add(BlockPos.asLong(x, y, z))
                                    || !level.isLoaded(cursor.set(x, y, z))) {
                                continue;
                            }
                            BlockState state = level.getBlockState(cursor);
                            if (DynamicTrees.isBranch(state) && state.getDestroySpeed(level, cursor) <= budget) {
                                cells.add(cursor.asLong());
                            }
                        }
                    }
                }
            }
        }
        if (cells.isEmpty()) {
            return;
        }
        AABB around = vehicle.getBoundingBox().inflate(16);
        for (int i = 0; i < cells.size(); i++) {
            cursor.set(cells.getLong(i));
            if (DynamicTrees.isBranch(level.getBlockState(cursor))) {
                level.destroyBlock(cursor.immutable(), false, vehicle);
            }
        }
        DynamicTrees.shoveFresh(level, around, motion.scale(1.0 / speed));
    }
}
