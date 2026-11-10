package com.wf.combinedassault.aircraft;

import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.hit.HitMesh;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Server. Spinning rotor disc cutting a block collision shape => its pool to 0. A rotor pool reaching 0, by any
 * cause, throws its nodes off as {@link RotorDebris}; blades hidden while the pool is 0 ({@code VehicleRender}).
 */
public final class RotorStrike {

    /** Power % below which a strike leaves the blades whole. */
    private static final float MIN_POWER = 20;
    /** Half-thickness of the struck slab about the disc mid-plane. Disc's own is padded for shots (Mavic resting
     * nose-down on grass reaches it). */
    private static final double BLADE_HALF = 0.05;

    @Nullable
    private HullComponents pools;
    /** Per pool: 0 at the last tick. Seeded from the first tick seen => a wreck loaded from save throws nothing. */
    private boolean[] shed;

    public static boolean rotor(@Nullable ComponentKind kind) {
        return kind == ComponentKind.ROTOR || kind == ComponentKind.TAIL_ROTOR;
    }

    public void tick(RotaryWingVehicle vehicle) {
        VehicleHits hits = vehicle.hits();
        HullComponents current = vehicle.hullComponents();
        if (hits == null || current.isEmpty()) {
            return;
        }
        if (current != pools) {
            pools = current;
            shed = new boolean[current.size()];
            for (int i = 0; i < shed.length; i++) {
                shed[i] = rotor(current.kind(i)) && current.health(i) <= 0;
            }
        }
        if (vehicle.getPower() >= MIN_POWER) {
            strike(vehicle, hits, current);
        }
        for (int i = 0; i < shed.length; i++) {
            if (!rotor(current.kind(i))) {
                continue;
            }
            boolean gone = current.health(i) <= 0;
            if (gone && !shed[i]) {
                throwOff(vehicle, hits, current.id(i));
            }
            shed[i] = gone;
        }
    }

    private static void strike(RotaryWingVehicle vehicle, VehicleHits hits, HullComponents pools) {
        List<HitModel.Node> nodes = hits.model().nodes();
        Vec3 at = vehicle.position();
        Matrix4f pose = new Matrix4f();
        for (int i = 0; i < nodes.size(); i++) {
            HitModel.Node node = nodes.get(i);
            HitMesh.Disc disc = node.mesh().disc();
            if (disc == null || !rotor(node.kind()) || !hits.live(i)) {
                continue;
            }
            int pool = pools.indexOf(node.systemId());
            if (pool < 0 || pools.health(pool) <= 0) {
                continue;
            }
            hits.pose(i, at, pose);
            BlockPos struck = struck((ServerLevel) vehicle.level(), at, pose, disc);
            if (struck != null) {
                pools.setHealth(pool, 0);
                shatter((ServerLevel) vehicle.level(), struck);
            }
        }
    }

    /** First block whose collision boxes cut the disc; null if none or the disc reaches unloaded chunks. */
    @Nullable
    static BlockPos struck(ServerLevel level, Vec3 at, Matrix4f pose, HitMesh.Disc disc) {
        Vector3f axis = pose.transformDirection(new Vector3f().setComponent(disc.axis(), 1)).normalize();
        Vector3f mid = pose.transformPosition(new Vector3f().setComponent(disc.axis(), (disc.lo() + disc.hi()) / 2));
        double cx = at.x + mid.x, cy = at.y + mid.y, cz = at.z + mid.z;
        double half = Math.min((disc.hi() - disc.lo()) / 2, BLADE_HALF);
        double r = disc.radius();
        double ex = r * Math.sqrt(Math.max(0, 1 - axis.x * axis.x)) + half * Math.abs(axis.x);
        double ey = r * Math.sqrt(Math.max(0, 1 - axis.y * axis.y)) + half * Math.abs(axis.y);
        double ez = r * Math.sqrt(Math.max(0, 1 - axis.z * axis.z)) + half * Math.abs(axis.z);
        int x0 = Mth.floor(cx - ex), x1 = Mth.floor(cx + ex);
        int y0 = Mth.floor(cy - ey), y1 = Mth.floor(cy + ey);
        int z0 = Mth.floor(cz - ez), z1 = Mth.floor(cz + ez);
        if (!level.hasChunksAt(x0, z0, x1, z1)) {
            return null;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    BlockState state = level.getBlockState(p.set(x, y, z));
                    if (state.isAir()) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(level, p);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    for (AABB box : shape.toAabbs()) {
                        if (cuts(box.move(p), cx, cy, cz, axis, half, r)) {
                            return p.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Box within the disc slab along {@code axis}, and its point nearest the centre within {@code r} of the axis. */
    static boolean cuts(AABB box, double cx, double cy, double cz, Vector3f axis, double half, double r) {
        double hx = box.getXsize() / 2, hy = box.getYsize() / 2, hz = box.getZsize() / 2;
        double along = (box.minX + hx - cx) * axis.x + (box.minY + hy - cy) * axis.y + (box.minZ + hz - cz) * axis.z;
        double reach = hx * Math.abs(axis.x) + hy * Math.abs(axis.y) + hz * Math.abs(axis.z);
        if (Math.abs(along) > half + reach) {
            return false;
        }
        double vx = Mth.clamp(cx, box.minX, box.maxX) - cx;
        double vy = Mth.clamp(cy, box.minY, box.maxY) - cy;
        double vz = Mth.clamp(cz, box.minZ, box.maxZ) - cz;
        double axial = vx * axis.x + vy * axis.y + vz * axis.z;
        return vx * vx + vy * vy + vz * vz - axial * axial <= r * r;
    }

    private static void shatter(ServerLevel level, BlockPos struck) {
        BlockState state = level.getBlockState(struck);
        double x = struck.getX() + 0.5, y = struck.getY() + 0.5, z = struck.getZ() + 0.5;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 40, 0.6, 0.4, 0.6, 0.4);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 12, 0.8, 0.4, 0.8, 0.05);
        level.sendParticles(ParticleTypes.CRIT, x, y, z, 30, 0.8, 0.4, 0.8, 0.8);
        level.playSound(null, x, y, z, SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 3.0f, 0.5f);
        level.playSound(null, x, y, z, SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 3.0f, 0.6f);
    }

    private static void throwOff(RotaryWingVehicle vehicle, VehicleHits hits, String pool) {
        ServerLevel level = (ServerLevel) vehicle.level();
        RandomSource random = level.getRandom();
        List<HitModel.Node> nodes = hits.model().nodes();
        Vec3 at = vehicle.position();
        Matrix4f pose = new Matrix4f();
        for (int i = 0; i < nodes.size(); i++) {
            HitModel.Node node = nodes.get(i);
            if (node.mesh().disc() == null || !rotor(node.kind()) || !pool.equals(node.systemId()) || !hits.live(i)) {
                continue;
            }
            hits.pose(i, at, pose);
            HitMesh.Disc disc = node.mesh().disc();
            // Model -> world with the node at its bind pose: pose * rest^-1. Pivot = disc centre (node origin can sit
            // metres off the blades).
            Quaternionf model = pose.getNormalizedRotation(new Quaternionf())
                    .mul(new Quaternionf(node.mesh().rotation()).conjugate());
            Vector3f centre = new Vector3f().setComponent(disc.axis(), (disc.lo() + disc.hi()) / 2);
            Vector3f hub = pose.transformPosition(new Vector3f(centre));
            Vector3f pivot = node.mesh().rotation().transform(new Vector3f(centre)).add(node.mesh().origin());
            Vector3f axis = pose.transformDirection(new Vector3f().setComponent(disc.axis(), 1)).normalize();
            Vector3f discAxis = node.mesh().rotation().transform(new Vector3f().setComponent(disc.axis(), 1));
            Vec3 fling = new Vec3(random.nextGaussian(), 0, random.nextGaussian()).normalize()
                    .scale(0.2 + random.nextDouble() * 0.3);
            Vec3 velocity = vehicle.getDeltaMovement().add(fling).add(0, 0.25 + random.nextDouble() * 0.2, 0);
            Vector3f spin = new Vector3f(axis).mul(0.6f * (random.nextBoolean() ? 1 : -1))
                    .add((float) random.nextGaussian() * 0.12f, (float) random.nextGaussian() * 0.12f,
                            (float) random.nextGaussian() * 0.12f);
            level.addFreshEntity(new RotorDebris(level, at.add(hub.x, hub.y, hub.z), vehicle.getDisplayId(),
                    node.mesh().name(), pivot, discAxis, model, velocity, spin));
        }
    }
}
