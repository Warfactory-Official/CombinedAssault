package com.wf.combinedassault.indirect.client;

import com.wf.combinedassault.indirect.net.CallForFirePacket;
import com.wf.wflib.client.map.MapMenu;
import com.wf.wflib.client.map.MapTool;
import com.wf.wflib.client.map.MapView;
import com.wf.wflib.pda.client.PdaClient;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** PDA carrier as observer: map menu => fire request to same-side map-station operators ({@code CallForFire}). */
public final class CallForFireMapTool implements MapTool {

    @Override
    public boolean available() {
        return PdaClient.carried();
    }

    @Override
    public void menu(MapMenu menu, MapView view) {
        menu.item("PDA: call for fire here", pos -> PacketDistributor.sendToServer(new CallForFirePacket(
                new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5))));
    }
}
