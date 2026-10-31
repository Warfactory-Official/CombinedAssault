package com.wf.combinedassault.hud;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Screen position of the operator's aim point, sampled per tick and lerped per frame. */
public final class AimPoint {
    static double xO;
    static double yO;
    static double x;
    static double y;
    static boolean shown = true;

    private AimPoint() {
    }

    public static boolean shown() {
        return shown;
    }

    public static double x(float partialTick) {
        return Mth.lerp(partialTick, xO, x);
    }

    public static double y(float partialTick) {
        return Mth.lerp(partialTick, yO, y);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Pre event) {
        Player player = Minecraft.getInstance().player;
        LocalVehiclePlayer local = LocalVehiclePlayer.instance();
        if (player == null || !player.isAlive() || !(player.getVehicle() instanceof AbstractVehicle)
                || local.sightUnit() == null) {
            return;
        }
        Vec3 screen = VectorUtil.worldToScreen(local.viewType == LocalVehiclePlayer.ViewType.SCOPE
                ? local.scopeAimPos() : local.freeAimPos());
        if (screen.z >= 0) {
            xO = x;
            yO = y;
            x = screen.x;
            y = screen.y;
            shown = true;
        } else {
            shown = false;
        }
    }
}
