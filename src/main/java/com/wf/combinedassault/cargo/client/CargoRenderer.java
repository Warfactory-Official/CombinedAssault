package com.wf.combinedassault.cargo.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.render.gem.GemModels;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

/** Stowed cargo (rest pose, authored paint) and deployed cargo parachutes; vehicle model frame. */
@OnlyIn(Dist.CLIENT)
public final class CargoRenderer {

    private static final ResourceLocation CANOPY = CombinedAssault.modLocation("models/effect/cargo_parachute.gltf");
    /** Canopy rig: lines meet the load on a square this wide, blocks. */
    private static final float RIG_SPAN = 7.9f;
    /** Kg per canopy in a cluster. */
    private static final float CANOPY_LOAD = 8000f;
    private static final int MAX_CANOPIES = 6;

    private CargoRenderer() {
    }

    public static void render(AbstractVehicle vehicle, PoseStack pose, int light) {
        CompoundTag view = vehicle.cargoView();
        if (!view.isEmpty()) {
            ListTag stowed = view.getList("v", Tag.TAG_COMPOUND);
            for (int i = 0; i < stowed.size(); i++) {
                CompoundTag entry = stowed.getCompound(i);
                ResourceLocation model = ResourceLocation.tryParse(entry.getString("m"));
                if (model == null) {
                    continue;
                }
                ListTag p = entry.getList("p", Tag.TAG_FLOAT);
                ListTag q = entry.getList("q", Tag.TAG_FLOAT);
                pose.pushPose();
                pose.translate(p.getFloat(0), p.getFloat(1), p.getFloat(2));
                pose.mulPose(new Quaternionf(q.getFloat(0), q.getFloat(1), q.getFloat(2), q.getFloat(3)));
                GemModels.draw(model, pose, light);
                pose.popPose();
            }
        }
        if (vehicle.parachuteDeployed()) {
            canopies(vehicle, pose, light);
        }
    }

    private static void canopies(AbstractVehicle vehicle, PoseStack pose, int light) {
        AABB box = vehicle.getBoundingBox();
        float span = (float) Math.max(box.getXsize(), box.getZsize());
        float top = (float) (box.maxY - vehicle.getY());
        int count = Mth.clamp(Mth.ceil(vehicle.physicsEngine.mass / CANOPY_LOAD), 1, MAX_CANOPIES);
        float scale = Mth.clamp(span / RIG_SPAN, 0.6f, 1.6f);
        float ring = count == 1 ? 0 : RIG_SPAN * scale * 0.55f;
        for (int i = 0; i < count; i++) {
            float angle = Mth.TWO_PI * i / count;
            pose.pushPose();
            pose.translate(0, top, 0);
            pose.mulPose(new Quaternionf().rotateY(angle));
            if (count > 1) {
                pose.mulPose(new Quaternionf().rotateX(0.35f));
            }
            pose.translate(0, 0, ring);
            pose.scale(scale, scale, scale);
            GemModels.draw(CANOPY, pose, light);
            pose.popPose();
        }
    }

}
