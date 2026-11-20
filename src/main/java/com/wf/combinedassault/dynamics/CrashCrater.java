package com.wf.combinedassault.dynamics;

import com.wf.combinedassault.dynamics.message.ServerCrashImpact;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.core.api.ViewRange;
import com.wf.wflib.explosion.api.BlockProcessorStandard;
import com.wf.wflib.explosion.api.ExplosionAEF;
import com.wf.wflib.geom.api.OBB;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ground a hard impact excavates: the active collision boxes swept along the velocity the contacts cancelled, layer
 * by layer from the hull outward, nearest the impact first, until the impact's energy is spent. Steep => hull-shaped
 * pit; shallow => gouge along the slide.
 */
public final class CrashCrater {

    /** Sweep stride, blocks. */
    private static final double STRIDE = 0.5;
    private static final double MAX_DEPTH = 12;
    /** Cell centre inside a box grown by this counts as struck. */
    private static final float REACH = 0.25f;
    /** Hardness charged for a cell softer than this (plants, snow). */
    private static final float MIN_COST = 0.1f;
    /** Hardness a slide banks before it ploughs. */
    private static final float FURROW = 1;
    /** Downward share of a slide's sweep direction: a hull on flat ground sweeps over it otherwise. TODO estimate. */
    private static final float PLOUGH = 0.5f;

    private record Cell(long pos, float cost, double distance) {
    }

    private CrashCrater() {
    }

    /** @param lost velocity the contacts cancelled, b/t */
    public static boolean digs(Vector3f lost) {
        return lost.length() * PhysicsHelper.TICKS_PER_SECOND >= VehicleSettings.current().crashCraterSpeed();
    }

    /**
     * Tick thread, server.
     *
     * @param lost velocity the contacts cancelled, b/t, world
     * @param at   impact centroid
     * @return share of {@code lost} the hull keeps: what stood in the sweep broke before the energy ran out
     */
    public static float dig(AbstractVehicle vehicle, Vector3f lost, Vec3 at) {
        VehicleSettings settings = VehicleSettings.current();
        double speed = lost.length() * PhysicsHelper.TICKS_PER_SECOND;
        if (!digs(lost)) {
            return 0;
        }
        return (float) Math.sqrt(dig(vehicle, new Vector3f(lost).normalize(),
                (float) (0.5 * vehicle.physicsEngine.mass * speed * speed / settings.crashEnergyPerHardness()), at));
    }

    /**
     * Hull sliding on the ground at {@code crashCraterSpeed}+: contact losses bank until {@link #FURROW} hardness is
     * paid, then plough ahead of and under the contact. Tick thread, server.
     *
     * @param free  velocity this step without contacts, b/t
     * @param after velocity after them
     * @return energy still banked, J
     */
    public static double scrape(AbstractVehicle vehicle, double banked, Vector3f free, Vec3 after, Vec3 at) {
        VehicleSettings settings = VehicleSettings.current();
        if (after.length() * PhysicsHelper.TICKS_PER_SECOND < settings.crashCraterSpeed()) {
            return 0;
        }
        banked += Math.max(0, 0.5 * vehicle.physicsEngine.mass * (free.lengthSquared() - after.lengthSqr())
                * PhysicsHelper.TICKS_PER_SECOND_SQUARED);
        if (banked < FURROW * settings.crashEnergyPerHardness()) {
            return banked;
        }
        Vector3f into = new Vector3f((float) after.x, (float) after.y, (float) after.z).normalize().add(0, -PLOUGH, 0).normalize();
        dig(vehicle, into, (float) (banked / settings.crashEnergyPerHardness()), at);
        return 0;
    }

    /**
     * @param into   unit sweep direction
     * @param budget block hardness to excavate
     * @return share of the budget left with the sweep cleared; 0: spent, unbreakable floor, claim kept a cell, nothing struck
     */
    private static float dig(AbstractVehicle vehicle, Vector3f into, float budget, Vec3 at) {
        VehicleSettings settings = VehicleSettings.current();
        if (!settings.crashCraters() || !settings.canDestroyBlock() || !(vehicle.level() instanceof ServerLevel level)) {
            return 0;
        }
        float full = budget;
        boolean floored = false;
        vehicle.updateOBBs();
        List<OBB> boxes = new ArrayList<>(vehicle.getOBBs());
        boxes.add(vehicle.getMainCubeOBB().obb());
        LongOpenHashSet seen = new LongOpenHashSet();
        Set<BlockPos> cells = new HashSet<>();
        List<Cell> layer = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Quaternionf inverse = new Quaternionf();
        Vector3f local = new Vector3f();
        for (double depth = 0; depth <= MAX_DEPTH && budget > 0; depth += STRIDE) {
            layer.clear();
            int unbreakable = 0;
            double sx = into.x * depth, sy = into.y * depth, sz = into.z * depth;
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
                                    || Math.abs(local.z) > extents.z + REACH || !seen.add(BlockPos.asLong(x, y, z))) {
                                continue;
                            }
                            BlockState state = level.getBlockState(cursor.set(x, y, z));
                            if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, cursor).isEmpty()) {
                                continue;
                            }
                            float hardness = state.getDestroySpeed(level, cursor);
                            if (hardness < 0 || hardness >= PhysicsEngine.UNBREAKABLE_HARDNESS) {
                                unbreakable++;
                                continue;
                            }
                            layer.add(new Cell(cursor.asLong(), Math.max(hardness, MIN_COST),
                                    at.distanceToSqr(x + 0.5, y + 0.5, z + 0.5)));
                        }
                    }
                }
            }
            // Bedrock / obsidian floor: nothing behind it is reached.
            if (unbreakable > layer.size()) {
                floored = true;
                break;
            }
            layer.sort(Comparator.comparingDouble(Cell::distance));
            for (int i = 0; i < layer.size() && budget > 0; i++) {
                budget -= layer.get(i).cost;
                cells.add(BlockPos.of(layer.get(i).pos));
            }
        }
        if (cells.isEmpty()) {
            return 0;
        }
        int count = cells.size();
        ServerCrashImpact packet = new ServerCrashImpact(at.x, at.y, at.z, into.x, into.y, into.z, count);
        double range = ViewRange.blocks();
        for (ServerPlayer player : level.getPlayers(p -> p.distanceToSqr(at) < range * range)) {
            PacketDistributor.sendToPlayer(player, packet);
        }
        // Claims, block entities, observers: wflib's block stage. Claim filter may empty the set.
        new ExplosionAEF(level, at.x, at.y, at.z, (float) Math.cbrt(count), vehicle)
                .setBlockAllocator((explosion, world, x, y, z, size) -> cells)
                .setBlockProcessor(new BlockProcessorStandard().setNoDrop())
                .explode();
        if (floored || budget <= 0) {
            return 0;
        }
        for (BlockPos cell : cells) {
            if (!level.getBlockState(cell).isAir()) {
                return 0;
            }
        }
        return budget / full;
    }
}
