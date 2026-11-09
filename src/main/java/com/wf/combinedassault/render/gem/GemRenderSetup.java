package com.wf.combinedassault.render.gem;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.client.vehicle.BaseDisplay;
import com.wf.gemrender.asset.GemRenderModels;
import dev.engine_room.flywheel.api.event.EndClientResourceReloadEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Optional;
import java.util.function.Consumer;

@OnlyIn(Dist.CLIENT)
public final class GemRenderSetup {

    private GemRenderSetup() {
    }

    public static void init() {
        VehicleGemVisual.register();
        com.wf.combinedassault.vehicle.light.client.VehicleLights.register();
    }

    public static void registerListeners(Consumer<PreparableReloadListener> consumer) {
        consumer.accept(VehiclePaints.INSTANCE);
    }

    /** Vehicle reload command: paints, slot icons, every GemRender model + texture re-read from the packs; no MC reload. */
    public static void reloadTextures(ResourceManager resources) {
        RenderSystem.assertOnRenderThread();
        VehiclePaints.INSTANCE.apply(VehiclePaints.INSTANCE.prepare(resources, null), resources, null);
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        ClientAssetsManager assets = ClientAssetsManager.INSTANCE;
        for (BaseDisplay display : assets.getVehicleDisplays().values()) {
            textures.release(display.getSlotTexture());
        }
        for (BaseDisplay display : assets.getWeaponDisplays()) {
            textures.release(display.getSlotTexture());
        }
        for (BaseDisplay display : assets.getDecorationDisplays()) {
            textures.release(display.getSlotTexture());
        }
        // TODO: GemRender exposes no reload entry but its end-of-reload handler (MODELS + BUILT variants + morph buffer).
        GemRenderModels.onEndClientResourceReload(
                new EndClientResourceReloadEvent(Minecraft.getInstance(), resources, false, Optional.empty()));
    }
}
