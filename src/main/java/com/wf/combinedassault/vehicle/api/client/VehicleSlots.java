package com.wf.combinedassault.vehicle.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.render.item.SlotQuad;

/** Inventory-slot art of a vehicle, for items other mods make that stand for one. */
public final class VehicleSlots {

    private VehicleSlots() {
    }

    public static Optional<ResourceLocation> texture(ResourceLocation vehicleId) {
        return ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicleId).map(display -> display.getSlotTexture());
    }

    /** One unit quad at the pose origin, as CA's own vehicle spawn item draws it. */
    public static void renderQuad(PoseStack poseStack, VertexConsumer buffer, int light, int overlay) {
        SlotQuad.render(poseStack, buffer, light, overlay);
    }
}
