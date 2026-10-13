package com.wf.combinedassault;

import com.wf.combinedassault.ballistics.addon.AddonActions;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wf.combinedassault.all.*;
import com.wf.combinedassault.compat.CreateCompat;
import com.wf.combinedassault.compat.sable.SableCompat;
import com.wf.combinedassault.compat.gtceu.GregTechCompat;
import com.wf.combinedassault.compat.ultimateweight.UltimateWeightCompat;
import com.wf.combinedassault.compat.wflib.WfLibCompat;
import com.wf.combinedassault.network.Channel;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.pack.CombinedPacks;
import com.wf.combinedassault.pack.PackHandshake;
import com.wf.combinedassault.uav.UavCamera;
import com.wf.combinedassault.uav.UavTablet;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;

@Mod(CombinedAssault.MOD_ID)
public class CombinedAssault {

    public static final String MOD_ID = "combinedassault";
    public static final String PROTOCOL = "1.0";
    public static final Logger LOGGER = LogManager.getLogger(CombinedAssault.class);

    public CombinedAssault(IEventBus modEventBus) {
        CombinedPacks.discover();
        modEventBus.addListener(PackHandshake::onRegisterPayloads);
        modEventBus.addListener(PackHandshake::onRegisterTasks);
        modEventBus.addListener(AddPackFindersEvent.class, CombinedPacks::onAddPackFinders);
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(CommonAssetsManager::ensureLoaded));
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(UavCamera::register));
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(UavTablet::register));
        AllConfigs.register(ModLoadingContext.get(), modEventBus);
        AllBlocks.register(modEventBus);
        AllBlockEntities.register(modEventBus);
        AllItems.register(modEventBus);
        AllEntities.register(modEventBus);
        AllFluids.register(modEventBus);
        AllSounds.register(modEventBus);
        AllPartUnitType.register(modEventBus);
        AllVehicleWeaponTypes.register(modEventBus);
        AllVehicleDataTypes.register(modEventBus);
        AllParticleTypes.register(modEventBus);
        AllDisplayTypes.register(modEventBus);
        AllTabs.register(modEventBus);
        AllRecipe.register(modEventBus);
        AllMenus.register(modEventBus);
        AllDataComponents.register(modEventBus);
        initCompat();
        AddonActions.register();
        com.wf.combinedassault.vehicle.part.AccessActions.register();
        com.wf.combinedassault.vehicle.part.WeaponActions.register();
        com.wf.combinedassault.airdefense.AirDefenseActions.register();
        com.wf.combinedassault.vehicle.pose.SeatPoses.register();
        com.wf.combinedassault.ballistics.component.SystemRepair.register();
        com.wf.combinedassault.ballistics.component.ArmorRepair.register();
        com.wf.combinedassault.vehicle.part.BarrelCharge.register();
        modEventBus.register(Channel.class);
    }

    public void initCompat() {
        SableCompat.init();
        CreateCompat.init();
        WfLibCompat.init();
        UltimateWeightCompat.init();
        GregTechCompat.init();
        com.wf.combinedassault.compat.rail.RailCompat.init();
    }

    public static ResourceLocation modLocation(String name) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, name);
    }

    public static ResourceLocation resourceLocation(String location) {
        return ResourceLocation.parse(location);
    }

}
