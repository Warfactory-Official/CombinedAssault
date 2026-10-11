package com.wf.combinedassault.client.render.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.client.resource.ClientAssetsManager;
import com.wf.combinedassault.item.DecorationItem;

import javax.annotation.Nonnull;

public class DecorationItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static final ResourceLocation DECORATION_ITEM_TEXTURE = CombinedAssault.modLocation("textures/item/decoration_item.png");

    public DecorationItemRenderer(BlockEntityRenderDispatcher pBlockEntityRenderDispatcher, EntityModelSet pEntityModelSet) {
        super(pBlockEntityRenderDispatcher, pEntityModelSet);
    }

    @Override
    public void renderByItem(@Nonnull ItemStack itemStack, @Nonnull ItemDisplayContext transformType, @Nonnull PoseStack poseStack, @Nonnull MultiBufferSource pBuffer, int pPackedLight, int pPackedOverlay) {
        if (itemStack.getItem() instanceof DecorationItem) {
            CompoundTag tag = itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            ResourceLocation decorationDisplayId = CombinedAssault.resourceLocation(tag.getString(DecorationItem.TAG_DECORATION_DISPLAY_ID));
            var decorationDisplay = ClientAssetsManager.INSTANCE.getDecorationDisplay(decorationDisplayId).orElse(null);
            poseStack.pushPose();
            {
                poseStack.translate(0.5, 0.5, 0.5);
                if (decorationDisplay != null && decorationDisplay.getSlotTexture() != null) {
                    if (transformType != ItemDisplayContext.GUI) {
                        poseStack.mulPose(Axis.YN.rotationDegrees(-45f));
                    }
                    VertexConsumer buffer = pBuffer.getBuffer(RenderType.entityTranslucent(decorationDisplay.getSlotTexture()));
                    SlotQuad.render(poseStack, buffer, pPackedLight, pPackedOverlay);
                    poseStack.popPose();
                    return;
                }
                VertexConsumer buffer = pBuffer.getBuffer(RenderType.entityTranslucent(DECORATION_ITEM_TEXTURE));
                SlotQuad.render(poseStack, buffer, pPackedLight, pPackedOverlay);
            }
            poseStack.popPose();
        }
    }

}
