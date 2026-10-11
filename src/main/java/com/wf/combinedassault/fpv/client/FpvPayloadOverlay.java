package com.wf.combinedassault.fpv.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.wf.combinedassault.client.render.util.Color;
import com.wf.combinedassault.fpv.payload.FpvPayload;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;

import java.util.Locale;

/** Host overlay covers crosshair/altitude/speed/fuel already; this adds only the drone's fuze state. */
@OnlyIn(Dist.CLIENT)
public class FpvPayloadOverlay implements LayeredDraw.Layer {

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        PayloadUnit hardpoint = PayloadUnit.on(LocalVehiclePlayer.instance.vehicle);
        if (hardpoint == null || !hardpoint.isLoaded()) {
            return;
        }
        FpvPayload payload = hardpoint.getPayload();
        int x = guiGraphics.guiWidth() / 2 - 120;
        int y = guiGraphics.guiHeight() / 2 + 27;
        var font = minecraft.font;
        guiGraphics.drawString(font, payload.capacity() <= 1
                        ? payload.displayName()
                        : Component.translatable("ui.vehicle_payload.rounds", payload.remaining(), payload.capacity()),
                x, y, Color.GREEN);

        Component state;
        int colour;
        if (!hardpoint.isArmed()) {
            state = Component.translatable("ui.vehicle_payload.safe");
            colour = Color.GRAY;
        } else if (!hardpoint.isLive()) {
            // Counting down in seconds; ticks would be a number nobody can act on.
            state = Component.translatable("ui.vehicle_payload.arming",
                    String.format(Locale.ROOT, "%.1f", hardpoint.armingRemaining() / 20.0F));
            colour = Color.AMMO_WARNING;
        } else {
            state = Component.translatable("ui.vehicle_payload.armed");
            colour = Color.RED;
        }
        guiGraphics.drawString(font, state, x, y + 12, colour);
    }

}
