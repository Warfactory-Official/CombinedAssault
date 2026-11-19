package com.wf.combinedassault.render.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.camo.client.CamoMesh;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.wflib.armor.api.hull.PlateSpec;
import com.wf.wflib.armor.api.hull.PlateWear;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/** Star decals on worn brittle plates ({@link PlateWear}); one quad per chip, none on an unworn vehicle. */
@OnlyIn(Dist.CLIENT)
public final class GlassChips {

    private static final ResourceLocation TEXTURE = CombinedAssault.modLocation("textures/fx/glass_chips.png");
    private static final float MIN_HALF = 0.025f, MAX_HALF = 0.11f;
    /** Wear at which every star spreads. */
    private static final float CRAZED = 0.66f;
    private static final float CRAZED_SCALE = 1.6f;
    private static final float LIFT = 0.004f;

    private GlassChips() {
    }

    /** {@code poseStack}: the vehicle's own frame, as for the addon panels. */
    public static void render(AbstractVehicle vehicle, PoseStack poseStack, MultiBufferSource buffers, int light,
                              float partialTick) {
        PlateWear wear = vehicle.plateWear();
        HullProfile profile = vehicle.hullProfile();
        VehicleHits hits = vehicle.hits();
        if (!wear.worn() || profile == null || hits == null) {
            return;
        }
        VertexConsumer buffer = null;
        Vector3f p = new Vector3f(), n = new Vector3f(), t = new Vector3f(), b = new Vector3f();
        for (String id : wear.plates()) {
            PlateSpec spec = profile.plates.get(id);
            PlateWear.State state = wear.state(id);
            if (spec == null || state == null) {
                continue;
            }
            float spread = state.wear() >= CRAZED ? CRAZED_SCALE : 1.0f;
            for (int i = 0; i < state.count(); i++) {
                int node = state.node(i);
                state.point(i, p);
                Vector3f rest = spec.restNormal(p.x);
                if (rest == null) {
                    continue;
                }
                hits.toGroupDirection(node, n.set(rest)).normalize();
                hits.toGroupPoint(node, p);
                if (Math.abs(n.y) > 0.95f) {
                    t.set(1, 0, 0);
                } else {
                    n.cross(0, 1, 0, t).normalize();
                }
                n.cross(t, b);
                float half = (MIN_HALF + state.size(i) / 15.0f * (MAX_HALF - MIN_HALF)) * spread;
                float u0 = (state.variant(i) & 1) * 0.5f, v0 = (state.variant(i) >> 1 & 1) * 0.5f;
                if (buffer == null) {
                    buffer = buffers.getBuffer(RenderType.entityTranslucent(TEXTURE));
                }
                poseStack.pushPose();
                CamoMesh.applyAttachment(poseStack, hits.groupOf(node), partialTick);
                quad(buffer, poseStack.last(), p.x + n.x * LIFT, p.y + n.y * LIFT, p.z + n.z * LIFT, t, b, n, half,
                        u0, v0, light);
                poseStack.popPose();
            }
        }
    }

    private static void quad(VertexConsumer buffer, PoseStack.Pose pose, float cx, float cy, float cz, Vector3f t,
                             Vector3f b, Vector3f n, float half, float u0, float v0, int light) {
        for (int side = 0; side < 2; side++) {
            float s = side == 0 ? 1 : -1;
            for (int k = 0; k < 4; k++) {
                int corner = side == 0 ? k : 3 - k;
                float a = (corner == 0 || corner == 3) ? -1 : 1, c = corner < 2 ? -1 : 1;
                buffer.addVertex(pose.pose(), cx + (t.x * a + b.x * c) * half, cy + (t.y * a + b.y * c) * half,
                                cz + (t.z * a + b.z * c) * half)
                        .setColor(255, 255, 255, 255)
                        .setUv(u0 + (a > 0 ? 0.5f : 0), v0 + (c > 0 ? 0.5f : 0))
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, n.x * s, n.y * s, n.z * s);
            }
        }
    }
}
