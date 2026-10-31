package com.wf.combinedassault.indirect;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.indirect.client.CallForFireMapTool;
import com.wf.combinedassault.indirect.client.FireMissionMapTool;
import com.wf.combinedassault.indirect.client.IndirectFireInput;
import com.wf.combinedassault.indirect.client.IndirectFireKeys;
import com.wf.combinedassault.indirect.client.ShotClock;
import com.wf.wflib.map.api.client.WFMaps;

/** Server-unsafe half of {@link IndirectSystem}. */
final class IndirectClient {

    private IndirectClient() {
    }

    /** Layouts naming indirect keys and signals: loaded only while the system is on. */
    private static void addUiPack(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        Path root = ModList.get().getModFileById(CombinedAssault.MOD_ID).getFile().findResource("resource_packs", "indirect");
        Component title = Component.literal("CombinedAssault indirect fire UI");
        event.addRepositorySource(out -> out.accept(new Pack(
                new PackLocationInfo(CombinedAssault.MOD_ID + "/indirect", title, PackSource.BUILT_IN, Optional.empty()),
                new PathPackResources.PathResourcesSupplier(root),
                new Pack.Metadata(title, PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), false),
                new PackSelectionConfig(true, Pack.Position.TOP, false))));
    }

    static void start(IEventBus modBus) {
        modBus.addListener(AddPackFindersEvent.class, IndirectClient::addUiPack);
        modBus.register(IndirectFireKeys.class);
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(() -> {
            WFMaps.TOOLS.register(CombinedAssault.modLocation("fire_mission"), 0, new FireMissionMapTool());
            WFMaps.TOOLS.register(CombinedAssault.modLocation("call_for_fire"), 0, new CallForFireMapTool());
        }));
        NeoForge.EVENT_BUS.register(IndirectFireInput.class);
        NeoForge.EVENT_BUS.register(ShotClock.class);
    }
}
