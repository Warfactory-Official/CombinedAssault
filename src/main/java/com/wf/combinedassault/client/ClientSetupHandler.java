package com.wf.combinedassault.client;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import com.wf.combinedassault.compat.gemrender.GemRenderSetup;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllBlockEntities;
import com.wf.combinedassault.all.AllBlocks;
import com.wf.combinedassault.all.AllEntities;
import com.wf.combinedassault.all.AllMenus;
import com.wf.combinedassault.client.screen.VehicleStorageScreen;
import com.wf.combinedassault.all.AllParticleTypes;
import com.wf.combinedassault.client.particle.BulletHoleParticle;
import com.wf.combinedassault.client.particle.DustSmokeParticle;
import com.wf.combinedassault.client.particle.DustStoneParticle;
import com.wf.combinedassault.client.particle.SmokeCloudParticle;
import com.wf.combinedassault.client.particle.TrackDustParticle;
import com.wf.combinedassault.client.render.entity.block.FigureBoxBlockRenderer;
import com.wf.combinedassault.client.render.entity.misc.RadarMarkerRenderer;
import com.wf.combinedassault.client.render.entity.vehicle.VehiclePartRender;
import com.wf.combinedassault.client.render.entity.vehicle.VehicleRender;
import com.wf.combinedassault.client.resource.ClientAssetsManager;
import com.wf.combinedassault.particle.TrackParticle;

@EventBusSubscriber(value = Dist.CLIENT)
public class ClientSetupHandler {

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(AllMenus.VEHICLE_STORAGE.get(), VehicleStorageScreen::new);
    }

    @SuppressWarnings("removal")
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        com.wf.combinedassault.client.fx.HitFx.register();
        com.wf.combinedassault.client.fx.WakeFx.register();
        com.wf.wflib.audio.client.AudioMixer.addCabin(com.wf.combinedassault.audio.CabinAcoustics::muffle);
        // Must precede renderer registration: reload racing ahead of this => built-in loader for the session.
        event.enqueueWork(GemRenderSetup::init);
        event.enqueueWork(com.wf.combinedassault.client.hud.CaHudSource::register);
        event.enqueueWork(com.wf.combinedassault.vehicle.pose.client.SeatPoseAnim::register);
        event.enqueueWork(com.wf.combinedassault.vehicle.part.client.VehicleNodes::register);
        event.enqueueWork(() -> com.wf.wflib.client.map.WFMaps.addTool(
                new com.wf.combinedassault.indirect.client.FireMissionMapTool()));
        event.enqueueWork(() -> com.wf.wflib.client.map.WFMaps.addTool(
                new com.wf.combinedassault.indirect.client.CallForFireMapTool()));
        event.enqueueWork(com.wf.combinedassault.uav.client.UavPdaApp::register);
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.NONE_VEHICLE.get(), VehicleRender::new));
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.VEHICLE_PART.get(), VehiclePartRender::new));
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.WHEELED_VEHICLE.get(), VehicleRender::new));
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.TRACKED_VEHICLE.get(), VehicleRender::new));
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.VESSEL_VEHICLE.get(), VehicleRender::new));
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.ROTARY_WING_VEHICLE.get(), VehicleRender::new));
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.FIXED_WING_VEHICLE.get(), VehicleRender::new));
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.SHIP.get(), VehicleRender::new));

        event.enqueueWork(() -> EntityRenderers.register(AllEntities.QUADCOPTER.get(), VehicleRender::new));
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.M1A2.get(), VehicleRender::new));
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.AH64D.get(), VehicleRender::new));


        event.enqueueWork(() -> EntityRenderers.register(AllEntities.RADAR_MARKER.get(), RadarMarkerRenderer::new));
        event.enqueueWork(() -> EntityRenderers.register(AllEntities.PARAGLIDER_CANOPY.get(), NoopRenderer::new));

        ItemBlockRenderTypes.setRenderLayer(AllBlocks.FIGURE_BOX_BLOCK.get(), RenderType.cutout());
        BlockEntityRenderers.register(AllBlockEntities.FIGURE_BOX_BLOCK_ENTITY.get(), FigureBoxBlockRenderer::new);
    }

    @SubscribeEvent
    public static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(AllParticleTypes.DUST_SMOKE.get(), DustSmokeParticle::provider);
        event.registerSpriteSet(AllParticleTypes.DUST_STONE.get(), DustStoneParticle::provider);
        event.registerSpriteSet(AllParticleTypes.TRACK.get(), TrackParticle.Factory::new);
        event.registerSpriteSet(AllParticleTypes.TRACK_DUST.get(), TrackDustParticle.Provider::new);
        event.registerSpriteSet(AllParticleTypes.CHANGING_CLOUD.get(), SmokeCloudParticle::provider);
        event.registerSpriteSet(AllParticleTypes.FIXED_CLOUD.get(), SmokeCloudParticle::provider);
        event.registerSpecial(AllParticleTypes.BULLET_HOLE.get(), new BulletHoleParticle.Provider());
    }

    @SubscribeEvent
    public static void onRegisterResourceListener(RegisterClientReloadListenersEvent event) {
        ClientAssetsManager.INSTANCE.registerListeners(event::registerReloadListener);
        GemRenderSetup.registerListeners(event::registerReloadListener);
    }

}
