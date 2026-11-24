package com.wf.combinedassault;

import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.wflib.cam.api.client.CameraEffects;
import com.wf.wflib.core.api.WFSystems;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = CombinedAssault.MOD_ID, dist = Dist.CLIENT)
public class CombinedAssaultClient {

    public CombinedAssaultClient(IEventBus modEventBus) {
        WFSystems.startClient(CombinedAssault.MOD_ID, modEventBus);
        CameraEffects.register(pt -> LocalVehiclePlayer.instance().vehicle != null ? 0.0f : 1.0f);
    }
}
