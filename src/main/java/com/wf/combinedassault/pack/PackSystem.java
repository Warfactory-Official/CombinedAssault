package com.wf.combinedassault.pack;

import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.client.DisplayTypes;
import com.wf.combinedassault.vehicle.VehicleTabs;
import com.wf.combinedassault.vehicle.command.RootCommand;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class PackSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "pack";
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:packs", "wflib:barrel", "wflib:magazine");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        BuiltinPartUnits.registerTypes();
        VehicleWeaponTypes.registerTypes();
        VehicleDataTypes.registerTypes();
        DisplayTypes.registerTypes();
        VehicleTabs.register(modBus);
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("reload"), 0, ReloadCommand::get);
        modBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(CommonAssetsManager::ensureLoaded));
    }
}
