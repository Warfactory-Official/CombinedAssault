package com.wf.combinedassault.render;

import com.wf.combinedassault.CombinedAssault;
import com.wf.wflib.core.api.WFSystems;
import dev.engine_room.flywheel.api.event.EndClientResourceReloadEvent;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.radar.client.RadarMarkerRenderer;
import com.wf.combinedassault.render.entity.vehicle.VehiclePartRender;
import com.wf.combinedassault.render.entity.vehicle.VehicleRender;
import com.wf.combinedassault.render.fx.HitFx;
import com.wf.combinedassault.render.fx.WakeFx;
import com.wf.combinedassault.render.gem.GemRenderSetup;
import com.wf.combinedassault.render.handler.RenderLivingHandler;
import com.wf.combinedassault.render.particle.BulletHoleParticle;
import com.wf.combinedassault.render.particle.DustSmokeParticle;
import com.wf.combinedassault.render.particle.DustStoneParticle;
import com.wf.combinedassault.render.particle.SmokeCloudParticle;
import com.wf.combinedassault.render.particle.TrackDustParticle;
import com.wf.combinedassault.render.particle.TrackParticle;
import com.wf.combinedassault.render.shader.ModShaders;
import com.wf.combinedassault.render.shader.OverloadHandler;
import com.wf.combinedassault.render.util.CollisionMeshRenderer;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.cargo.CargoContent;
import com.wf.combinedassault.vehicle.VehicleContent;
import com.wf.combinedassault.radar.RadarContent;

/** Server-unsafe half of {@link RenderSystem}. */
final class RenderClient {

    private RenderClient() {
    }

    static void start(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, RenderClient::onClientSetup);
        modBus.addListener(RegisterParticleProvidersEvent.class, RenderClient::registerParticles);
        modBus.addListener(RegisterClientReloadListenersEvent.class, event -> {
            ClientAssetsManager.INSTANCE.registerListeners(event::registerReloadListener);
            GemRenderSetup.registerListeners(event::registerReloadListener);
        });
        modBus.addListener(EndClientResourceReloadEvent.class, HitFx::onReload);
        modBus.register(ModShaders.class);
        NeoForge.EVENT_BUS.register(RenderLivingHandler.class);
        NeoForge.EVENT_BUS.register(OverloadHandler.class);
        NeoForge.EVENT_BUS.register(CollisionMeshRenderer.class);
        NeoForge.EVENT_BUS.register(VehicleRender.class);
        NeoForge.EVENT_BUS.register(VectorUtil.class);
    }

    @SuppressWarnings("removal")
    private static void onClientSetup(FMLClientSetupEvent event) {
        HitFx.register();
        WakeFx.register();
        // Must precede renderer registration: reload racing ahead of this => built-in loader for the session.
        event.enqueueWork(GemRenderSetup::init);
        event.enqueueWork(() -> VehicleContent.vehicleTypes().forEach(type -> EntityRenderers.register(type, VehicleRender::new)));
        event.enqueueWork(() -> EntityRenderers.register(VehicleContent.VEHICLE_PART.get(), VehiclePartRender::new));
        if (WFSystems.enabled(CombinedAssault.MOD_ID + ":radar")) {
            event.enqueueWork(() -> EntityRenderers.register(RadarContent.RADAR_MARKER.get(), RadarMarkerRenderer::new));
        }
        if (WFSystems.enabled(CombinedAssault.MOD_ID + ":cargo")) {
            event.enqueueWork(() -> EntityRenderers.register(CargoContent.PARAGLIDER_CANOPY.get(), NoopRenderer::new));
        }
    }

    private static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(RenderParticles.DUST_SMOKE.get(), DustSmokeParticle::provider);
        event.registerSpriteSet(RenderParticles.DUST_STONE.get(), DustStoneParticle::provider);
        event.registerSpriteSet(RenderParticles.TRACK.get(), TrackParticle.Factory::new);
        event.registerSpriteSet(RenderParticles.TRACK_DUST.get(), TrackDustParticle.Provider::new);
        event.registerSpriteSet(RenderParticles.CHANGING_CLOUD.get(), SmokeCloudParticle::provider);
        event.registerSpriteSet(RenderParticles.FIXED_CLOUD.get(), SmokeCloudParticle::provider);
        event.registerSpecial(RenderParticles.BULLET_HOLE.get(), new BulletHoleParticle.Provider());
    }
}
