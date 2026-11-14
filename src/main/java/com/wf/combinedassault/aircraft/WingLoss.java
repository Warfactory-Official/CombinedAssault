package com.wf.combinedassault.aircraft;

import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.HullComponents;
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
 * Per-side wing pools ({@code left wing}, {@code right wing}). A pool reaching 0 (fire, or a live wing cutting a block
 * collision box at {@link #STRIKE_SPEED}+) throws its topmost drawn wing nodes off as {@link RotorDebris}; the subtree
 * is hidden while the pool is 0 ({@code VehicleRender}) and that side gives no lift ({@link #intact}).
 */
public final class WingLoss {

    /** b/t: slower contact is taxiing past, not a strike. */
    private static final double STRIKE_SPEED = 0.4;
    /** Sample spacing over a wing node's triangles, blocks. */
    private static final float SAMPLE = 0.3f;

    @Nullable
    private HullComponents pools;
    /** Per pool: 0 at the last tick. Seeded from the first tick seen => a wreck loaded from save throws nothing. */
    private boolean[] shed;

    public void tick(FixedWingVehicle vehicle) {
        VehicleHits hits = vehicle.hits();
        HullComponents current = vehicle.hullComponents();
        if (hits == null || current.isEmpty()) {
            return;
        }
        if (current != pools) {
            pools = current;
            shed = new boolean[current.size()];
            for (int i = 0; i < shed.length; i++) {
                shed[i] = current.kind(i) == ComponentKind.WING && current.health(i) <= 0;
            }
        }
        if (vehicle.getDeltaMovement().length() >= STRIKE_SPEED) {
            strike(vehicle);
        }
        for (int i = 0; i < shed.length; i++) {
            if (current.kind(i) != ComponentKind.WING) {
                continue;
            }
            boolean gone = current.health(i) <= 0;
            if (gone && !shed[i]) {
                throwOff(vehicle, hits, current.id(i));
            }
            shed[i] = gone;
        }
    }

    /**
     * {left, right} lift share, 1 or 0. Sided wing pools only; an unsided {@code wing} pool (fittings, caps) counts
     * only on an airframe with no sided ones. No wing pools => {1, 1}.
     */
    public static float[] intact(HullComponents pools) {
        float left = 1, right = 1, both = 1;
        boolean sided = false;
        for (int i = 0; i < pools.size(); i++) {
            if (pools.kind(i) != ComponentKind.WING) {
                continue;
            }
            float up = pools.health(i) > 0 ? 1 : 0;
            String id = pools.id(i);
            if (id.startsWith("left ")) {
                left = Math.min(left, up);
                sided = true;
            } else if (id.startsWith("right ")) {
                right = Math.min(right, up);
                sided = true;
            } else {
                both = Math.min(both, up);
            }
        }
        return sided ? new float[]{left, right} : new float[]{both, both};
    }

    /** Drawn wing node whose pool is spent; its subtree goes with it. */
    public static boolean spent(HitModel.Node node, HullComponents pools) {
        if (node.kind() != ComponentKind.WING || node.mesh().mesh() == null) {
            return false;
        }
        int pool = pools.indexOf(node.systemId());
        return pool >= 0 && pools.health(pool) <= 0;
    }

    /** First live wing node whose triangles cut a block collision box => its pool to 0. True if one did. */
    public static boolean strike(FixedWingVehicle vehicle) {
        VehicleHits hits = vehicle.hits();
        HullComponents pools = vehicle.hullComponents();
        ServerLevel level = (ServerLevel) vehicle.level();
        List<HitModel.Node> nodes = hits.model().nodes();
        Vec3 at = vehicle.position();
        Matrix4f pose = new Matrix4f();
        Vector3f p = new Vector3f();
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int i = 0; i < nodes.size(); i++) {
            HitModel.Node node = nodes.get(i);
            if (node.kind() != ComponentKind.WING || node.mesh().mesh() == null || !hits.live(i)) {
                continue;
            }
            int pool = pools.indexOf(node.systemId());
            if (pool < 0 || pools.health(pool) <= 0) {
                continue;
            }
            hits.pose(i, at, pose);
            // Box broad phase: a swept / dihedral wing's box spans air the surface never reaches.
            if (!level.getBlockCollisions(null, worldBox(pose, node.bounds(), at)).iterator().hasNext()) {
                continue;
            }
            float[] t = node.mesh().mesh().triangles();
            for (int k = 0; k < t.length; k += 9) {
                if (cuts(level, cell, pose, p, at, t, k)) {
                    pools.setHealth(pool, 0);
                    shatter(level, cell);
                    return true;
                }
            }
        }
        return false;
    }

    private static AABB worldBox(Matrix4f pose, float[] b, Vec3 at) {
        Vector3f c = new Vector3f();
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (int k = 0; k < 8; k++) {
            pose.transformPosition(c.set(b[(k & 1) * 3], b[1 + (k >> 1 & 1) * 3], b[2 + (k >> 2) * 3]));
            x0 = Math.min(x0, c.x);
            y0 = Math.min(y0, c.y);
            z0 = Math.min(z0, c.z);
            x1 = Math.max(x1, c.x);
            y1 = Math.max(y1, c.y);
            z1 = Math.max(z1, c.z);
        }
        return new AABB(x0, y0, z0, x1, y1, z1).move(at);
    }

    /** Barycentric samples <= {@link #SAMPLE} apart over triangle {@code k}; first one inside a block => true. */
    private static boolean cuts(ServerLevel level, BlockPos.MutableBlockPos cell, Matrix4f pose, Vector3f p, Vec3 at,
                                float[] t, int k) {
        float e = Math.max(dist(t, k, k + 3), Math.max(dist(t, k + 3, k + 6), dist(t, k + 6, k)));
        int n = steps(e);
        for (int a = 0; a <= n; a++) {
            for (int b = 0; b <= n - a; b++) {
                float u = (float) a / n, v = (float) b / n, w = 1 - u - v;
                pose.transformPosition(p.set(u * t[k] + v * t[k + 3] + w * t[k + 6],
                        u * t[k + 1] + v * t[k + 4] + w * t[k + 7], u * t[k + 2] + v * t[k + 5] + w * t[k + 8]));
                if (solid(level, cell, at.x + p.x, at.y + p.y, at.z + p.z)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float dist(float[] t, int a, int b) {
        float dx = t[a] - t[b], dy = t[a + 1] - t[b + 1], dz = t[a + 2] - t[b + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int steps(float extent) {
        return Math.max(1, Mth.ceil(extent / SAMPLE));
    }

    /** Point inside a block collision box; unloaded => false. */
    private static boolean solid(ServerLevel level, BlockPos.MutableBlockPos cell, double x, double y, double z) {
        cell.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        if (!level.isLoaded(cell)) {
            return false;
        }
        BlockState state = level.getBlockState(cell);
        if (state.isAir()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, cell);
        if (shape.isEmpty()) {
            return false;
        }
        double lx = x - cell.getX(), ly = y - cell.getY(), lz = z - cell.getZ();
        for (AABB box : shape.toAabbs()) {
            if (box.contains(lx, ly, lz)) {
                return true;
            }
        }
        return false;
    }

    private static void shatter(ServerLevel level, BlockPos struck) {
        BlockState state = level.getBlockState(struck);
        double x = struck.getX() + 0.5, y = struck.getY() + 0.5, z = struck.getZ() + 0.5;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 40, 0.6, 0.4, 0.6, 0.4);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 12, 0.8, 0.4, 0.8, 0.05);
        level.playSound(null, x, y, z, SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 3.0f, 0.5f);
        level.playSound(null, x, y, z, SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 3.0f, 0.6f);
    }

    private static void throwOff(FixedWingVehicle vehicle, VehicleHits hits, String pool) {
        ServerLevel level = (ServerLevel) vehicle.level();
        RandomSource random = level.getRandom();
        List<HitModel.Node> nodes = hits.model().nodes();
        Vec3 at = vehicle.position();
        Matrix4f pose = new Matrix4f();
        for (int i = 0; i < nodes.size(); i++) {
            HitModel.Node node = nodes.get(i);
            if (node.kind() != ComponentKind.WING || node.mesh().mesh() == null || !pool.equals(node.systemId())
                    || !hits.live(i) || underShed(nodes, i, pool)) {
                continue;
            }
            hits.pose(i, at, pose);
            float[] b = node.bounds();
            Vector3f centre = new Vector3f((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
            Quaternionf model = pose.getNormalizedRotation(new Quaternionf())
                    .mul(new Quaternionf(node.mesh().rotation()).conjugate());
            Vector3f mid = pose.transformPosition(new Vector3f(centre));
            Vector3f pivot = node.mesh().rotation().transform(new Vector3f(centre)).add(node.mesh().origin());
            Vector3f up = node.mesh().rotation().transform(new Vector3f(0, 1, 0));
            // Outboard: away from the model centreline at the wing's own height.
            Vector3f out = pose.transformDirection(new Vector3f(pivot.x, 0, 0)).normalize(0.15f);
            Vec3 velocity = vehicle.getDeltaMovement().scale(0.9)
                    .add(out.x, out.y + 0.1 + random.nextDouble() * 0.1, out.z);
            Vector3f spin = pose.transformDirection(new Vector3f(0, 0, 1)).normalize()
                    .mul(0.35f * (pivot.x > 0 ? 1 : -1))
                    .add((float) random.nextGaussian() * 0.1f, (float) random.nextGaussian() * 0.1f,
                            (float) random.nextGaussian() * 0.1f);
            level.addFreshEntity(new RotorDebris(level, at.add(mid.x, mid.y, mid.z), vehicle.getDisplayId(),
                    node.mesh().name(), pivot, up, model, velocity, spin));
        }
    }

    /** An ancestor is a drawn node of the same pool: it carries this one. */
    private static boolean underShed(List<HitModel.Node> nodes, int index, String pool) {
        for (int at = nodes.get(index).mesh().parent(); at >= 0; at = nodes.get(at).mesh().parent()) {
            HitModel.Node up = nodes.get(at);
            if (up.mesh().mesh() != null && pool.equals(up.systemId())) {
                return true;
            }
        }
        return false;
    }
}
