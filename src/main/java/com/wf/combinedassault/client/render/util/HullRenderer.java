package com.wf.combinedassault.client.render.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.hit.HitMesh;
import com.wf.combinedassault.vehicle.hit.HitModel;
import com.wf.combinedassault.vehicle.hit.TriangleBvh;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import org.joml.Vector3f;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.vehicle.structure.ConvexHull;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.EnumMap;
import java.util.Map;

/**
 * Convex hull wireframe, the way {@link OBBRenderer} draws a box.
 * Load-time checks can't see a hull facing the wrong way or mirrored; this can.
 * Drawn in the pose collision code would use, not a reconstruction of it.
 */
@OnlyIn(Dist.CLIENT)
public final class HullRenderer {

    /** Told-apart palette, not a decorative one: running gear warm, weapons cool, fuel/ammo brightest (crew-killers). */
    private static final Map<ComponentKind, float[]> COLOURS = new EnumMap<>(ComponentKind.class);

    static {
        COLOURS.put(ComponentKind.TRACK, new float[]{1.00F, 0.55F, 0.10F});
        COLOURS.put(ComponentKind.WHEEL, new float[]{1.00F, 0.72F, 0.32F});
        COLOURS.put(ComponentKind.ENGINE, new float[]{1.00F, 0.25F, 0.15F});
        COLOURS.put(ComponentKind.TURRET, new float[]{0.20F, 0.80F, 1.00F});
        COLOURS.put(ComponentKind.BARREL, new float[]{0.35F, 0.50F, 1.00F});
        COLOURS.put(ComponentKind.FUEL, new float[]{1.00F, 0.90F, 0.20F});
        COLOURS.put(ComponentKind.AMMO, new float[]{1.00F, 0.25F, 0.80F});
        COLOURS.put(ComponentKind.OPTICS, new float[]{0.50F, 1.00F, 0.60F});
        COLOURS.put(ComponentKind.CREW, new float[]{1.00F, 0.78F, 0.78F});
        COLOURS.put(ComponentKind.ROTOR, new float[]{0.70F, 0.50F, 1.00F});
        COLOURS.put(ComponentKind.TAIL_ROTOR, new float[]{0.90F, 0.55F, 1.00F});
        COLOURS.put(ComponentKind.WING, new float[]{0.30F, 0.90F, 0.80F});
        COLOURS.put(ComponentKind.GENERIC, new float[]{0.65F, 0.90F, 0.65F});
    }

    /** No kind => plain structure colour. */
    private static final float[] UNTAGGED = {0.20F, 1.00F, 0.20F};

    /** Collision pieces; hit geometry draws in system colours. */
    private static final float[] COLLISION = {0.95F, 0.95F, 0.95F};

    /** Non-colliding piece draws half-lit: reads as a ghost. */
    private static final float SEE_THROUGH = 0.45F;

    private HullRenderer() {}

    public static float[] colourOf(@Nullable ComponentKind kind) {
        float[] colour = kind == null ? null : COLOURS.get(kind);
        return colour == null ? UNTAGGED : colour;
    }

    /** Parts always turret-colour, whatever the node's own kind. */
    private static final float[] PART = {0.20F, 0.80F, 1.00F};
    /** Faint: duplicates the model the player already sees. */
    private static final float SURFACE = 0.35F;
    private static final int DISC_SIDES = 24;

    /**
     * Hit geometry at its current pose: surface triangles faint (part / system colour), {@code hit_*} modules solid,
     * rotor discs as rings. {@code poseStack} at the entity.
     */
    public static void renderHits(AbstractVehicle vehicle, PoseStack poseStack, VertexConsumer buffer) {
        VehicleHits hits = vehicle.hits();
        if (hits == null) {
            return;
        }
        Matrix4f pose = new Matrix4f();
        for (int i = 0; i < hits.model().nodes().size(); i++) {
            HitModel.Node node = hits.model().nodes().get(i);
            if (!hits.live(i)) {
                continue;
            }
            float[] colour = node.part() != null && !node.mesh().module() ? PART : colourOf(node.kind());
            float alpha = node.mesh().module() ? 1.0F : SURFACE;
            poseStack.pushPose();
            poseStack.last().pose().mul(hits.pose(i, vehicle.position(), pose));
            PoseStack.Pose last = poseStack.last();
            TriangleBvh mesh = node.mesh().mesh();
            if (mesh != null) {
                float[] t = mesh.triangles();
                for (int k = 0; k < t.length; k += 9) {
                    line(last, buffer, t, k / 3, k / 3 + 1, colour[0], colour[1], colour[2], alpha);
                    line(last, buffer, t, k / 3 + 1, k / 3 + 2, colour[0], colour[1], colour[2], alpha);
                    line(last, buffer, t, k / 3 + 2, k / 3, colour[0], colour[1], colour[2], alpha);
                }
            }
            HitMesh.Disc disc = node.mesh().disc();
            if (disc != null) {
                float[] ring = new float[(DISC_SIDES * 2) * 3];
                int u = (disc.axis() + 1) % 3;
                int w = (disc.axis() + 2) % 3;
                for (int k = 0; k < DISC_SIDES; k++) {
                    double angle = 2 * Math.PI * k / DISC_SIDES;
                    for (int level = 0; level < 2; level++) {
                        int at = (level * DISC_SIDES + k) * 3;
                        ring[at + disc.axis()] = level == 0 ? disc.lo() : disc.hi();
                        ring[at + u] = (float) (disc.radius() * Math.cos(angle));
                        ring[at + w] = (float) (disc.radius() * Math.sin(angle));
                    }
                }
                for (int k = 0; k < DISC_SIDES; k++) {
                    int next = (k + 1) % DISC_SIDES;
                    line(last, buffer, ring, k, next, colour[0], colour[1], colour[2], 1.0F);
                    line(last, buffer, ring, DISC_SIDES + k, DISC_SIDES + next, colour[0], colour[1], colour[2], 1.0F);
                    if (k % (DISC_SIDES / 4) == 0) {
                        line(last, buffer, ring, k, DISC_SIDES + k, colour[0], colour[1], colour[2], 1.0F);
                    }
                }
            }
            poseStack.popPose();
        }
    }

    public static void render(Vec3 viewOrigin, VehicleCubeOBB cube, PoseStack poseStack,
                              VertexConsumer buffer) {
        render(viewOrigin, cube, true, poseStack, buffer);
    }

    /**
     * As above; collides=false draws see-through (shot/bound still apply, collision doesn't).
     * Covers two causes that look the same in a pack: tag not solid, or dropped (inside hull's own shape).
     */
    public static void render(Vec3 viewOrigin, VehicleCubeOBB cube, boolean collides,
                              PoseStack poseStack, VertexConsumer buffer) {
        render(viewOrigin, cube, poseStack, buffer, COLLISION[0], COLLISION[1], COLLISION[2],
                cube.tag().solid() && collides ? 1.0F : SEE_THROUGH);
    }

    /** No-op if the cube has no hull. */
    public static void render(Vec3 viewOrigin, VehicleCubeOBB cube, PoseStack poseStack,
                              VertexConsumer buffer, float red, float green, float blue, float alpha) {
        ConvexHull hull = cube.hull();
        if (hull == null) {
            return;
        }
        Vector3f centre = cube.obb().center();
        render(hull, centre.x - (float) viewOrigin.x, centre.y - (float) viewOrigin.y,
                centre.z - (float) viewOrigin.z, cube.obb().rotation(),
                poseStack, buffer, red, green, blue, alpha);
    }

    /**
     * Each hull edge is walked twice (once per adjoining face, opposite directions);
     * keeping only the ascending half draws every edge exactly once, no dedup set needed.
     */
    public static void render(ConvexHull hull, float x, float y, float z, Quaternionf rotation,
                              PoseStack poseStack, VertexConsumer buffer,
                              float red, float green, float blue, float alpha) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(rotation);
        PoseStack.Pose pose = poseStack.last();

        float[] points = hull.points();
        int[] vertices = hull.faceVertices();
        for (int face = 0; face < hull.faceCount(); face++) {
            int from = hull.faceStart(face);
            int to = hull.faceEnd(face);
            for (int i = from; i < to; i++) {
                int a = vertices[i];
                int b = vertices[i + 1 == to ? from : i + 1];
                if (a >= b) {
                    continue;
                }
                line(pose, buffer, points, a, b, red, green, blue, alpha);
            }
        }
        poseStack.popPose();
    }

    private static void line(PoseStack.Pose pose, VertexConsumer buffer, float[] points, int a, int b,
                             float red, float green, float blue, float alpha) {
        float ax = points[a * 3], ay = points[a * 3 + 1], az = points[a * 3 + 2];
        float bx = points[b * 3], by = points[b * 3 + 1], bz = points[b * 3 + 2];
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-6f) {
            return;
        }
        dx /= length;
        dy /= length;
        dz /= length;
        buffer.addVertex(pose, ax, ay, az).setColor(red, green, blue, alpha).setNormal(pose, dx, dy, dz);
        buffer.addVertex(pose, bx, by, bz).setColor(red, green, blue, alpha).setNormal(pose, dx, dy, dz);
    }

}
