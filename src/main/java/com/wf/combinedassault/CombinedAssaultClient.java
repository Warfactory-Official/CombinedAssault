package com.wf.combinedassault;

import com.wf.wflib.core.api.WFSystems;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = CombinedAssault.MOD_ID, dist = Dist.CLIENT)
public class CombinedAssaultClient {

    public CombinedAssaultClient(IEventBus modEventBus) {
        WFSystems.startClient(CombinedAssault.MOD_ID, modEventBus);
    }
}
