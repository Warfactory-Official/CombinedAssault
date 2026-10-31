package com.wf.combinedassault;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import com.wf.wflib.core.api.WFSystems;

@Mod(value = CombinedAssault.MOD_ID, dist = Dist.CLIENT)
public class CombinedAssaultClient {

    public CombinedAssaultClient(IEventBus modEventBus) {
        WFSystems.startClient(CombinedAssault.MOD_ID, modEventBus);
    }
}
