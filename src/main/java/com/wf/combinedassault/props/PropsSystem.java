package com.wf.combinedassault.props;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.wf.combinedassault.vehicle.VehicleTabs;
import com.wf.combinedassault.props.client.FigureBoxBlockRenderer;
import com.wf.combinedassault.props.message.ClientDecorationAction;
import com.wf.combinedassault.props.message.ClientFigureBoxUpdate;
import com.wf.combinedassault.props.message.ClientMachineMaxAction;
import com.wf.combinedassault.props.message.ServerDecorationAction;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class PropsSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "props";
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        PropsContent.BLOCKS.register(modBus);
        PropsContent.ITEMS.register(modBus);
        PropsContent.BLOCK_ENTITIES.register(modBus);
        VehicleTabs.addMisc(PropsContent.FIGURE_BOX);
        VehicleTabs.addMisc(PropsContent.MACHINE_MAX_BLOCK_ITEM);
        VehicleTabs.addMisc(PropsContent.DECORATION_ITEM);
        container.registerConfig(ModConfig.Type.COMMON, PropsConfig.SPEC, "combinedassault-props.toml");
        modBus.addListener(ModConfigEvent.Loading.class, PropsConfig::onLoad);
        modBus.addListener(ModConfigEvent.Reloading.class, PropsConfig::onLoad);
        PropsConfig.loadBlacklist();
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("props-1");
            registrar.playToServer(ClientMachineMaxAction.TYPE, ClientMachineMaxAction.STREAM_CODEC, ClientMachineMaxAction::handle);
            registrar.playToServer(ClientFigureBoxUpdate.TYPE, ClientFigureBoxUpdate.STREAM_CODEC, ClientFigureBoxUpdate::handle);
            registrar.playToServer(ClientDecorationAction.TYPE, ClientDecorationAction.STREAM_CODEC, ClientDecorationAction::handle);
            registrar.playToClient(ServerDecorationAction.TYPE, ServerDecorationAction.STREAM_CODEC, ServerDecorationAction::handle);
        });
    }

    @Override
    public void registerClient(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, event -> {
            ItemBlockRenderTypes.setRenderLayer(PropsContent.FIGURE_BOX_BLOCK.get(), RenderType.cutout());
            BlockEntityRenderers.register(PropsContent.FIGURE_BOX_BLOCK_ENTITY.get(), FigureBoxBlockRenderer::new);
        });
    }
}
