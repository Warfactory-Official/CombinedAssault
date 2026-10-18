package com.wf.combinedassault.compat.gemrender;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.direct.GemRenderItemRenderer;
import com.wf.gemrender.direct.ItemAppearance;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import com.wf.combinedassault.item.HardwareItem;
import com.wf.combinedassault.vehicle.hardware.HardwareKit;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** {@link HardwareItem}: its kit's mount glTF, bounding sphere centred, diameter {@link #FILL} of the cell. */
@OnlyIn(Dist.CLIENT)
public final class HardwareItemClient {

    private static final float FILL = 1.0f;
    /** Long thin guns: diagonal sphere chord fits the cell corners. */
    private static final float GUI_FILL = 1.3f;
    private static final float HAND_FILL = 0.6f;
    private static final Map<ResourceLocation, ModelCache.Handle<GemRenderGltfModel>> HANDLES = new HashMap<>();
    private static final Map<GemRenderGltfModel, Vector4f> SPHERES = new WeakHashMap<>();
    @Nullable
    private static GemRenderItemRenderer renderer;

    private HardwareItemClient() {
    }

    public static GemRenderItemRenderer renderer() {
        if (renderer == null) {
            renderer = new GemRenderItemRenderer(new Appearance());
        }
        return renderer;
    }

    @Nullable
    private static GemRenderGltfModel model(ItemStack stack) {
        HardwareKit kit = HardwareItem.kit(stack);
        return kit == null ? null : HANDLES.computeIfAbsent(kit.model(), GemRenderModels::handle).get();
    }

    private static Vector4f sphere(GemRenderGltfModel gltf) {
        return SPHERES.computeIfAbsent(gltf, g -> {
            Vector4f s = new Vector4f();
            g.bounds().evaluate(RestPalettes.of(g), s);
            return s;
        });
    }

    private static final class Appearance implements ItemAppearance {
        @Nullable
        @Override
        public GemRenderGltfModel model(ItemStack stack, ItemDisplayContext context) {
            return HardwareItemClient.model(stack);
        }

        /** Bore -Z. GUI: left side, muzzle up-left diagonal. First person: carried bore forward. Else: left side. */
        @Override
        public void transform(ItemStack stack, ItemDisplayContext context, PoseStack pose) {
            GemRenderGltfModel gltf = HardwareItemClient.model(stack);
            if (gltf == null) {
                return;
            }
            Vector4f s = sphere(gltf);
            float fill = FILL;
            if (context == ItemDisplayContext.GUI) {
                pose.mulPose(Axis.ZP.rotationDegrees(-35));
                fill = GUI_FILL;
            }
            if (context.firstPerson()) {
                fill = HAND_FILL;
            } else {
                pose.mulPose(Axis.YP.rotationDegrees(90));
            }
            float k = fill / (2 * Math.max(s.w, 1e-3f));
            pose.scale(k, k, k);
            pose.translate(-s.x, -s.y, -s.z);
        }
    }
}
