package com.wf.combinedassault.screen;

import com.wf.wflib.ui.api.UiContext;
import com.wf.wflib.ui.api.UiLayout;
import com.wf.wflib.ui.api.UiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/** CA Ore screen: world stays visible (no blur / dim), never pauses; {@link Port}s take drag + wheel before wfUI. */
public abstract class OreScreen extends UiScreen {
    private final List<Port> ports = new ArrayList<>();
    private Port dragging;
    private int dragButton = -1;

    protected OreScreen(UiLayout layout, Component title) {
        super(layout, UiContext.EMPTY, title, new Options(false, false, null, null));
    }

    public static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    protected final Port port(Port.Handler handler) {
        Port port = new Port(handler);
        ports.add(port);
        return port;
    }

    private Port portAt(double x, double y) {
        for (Port port : ports) {
            if (port.contains(x, y)) {
                return port;
            }
        }
        return null;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        for (Port port : ports) {
            port.shown = false;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Port port = portAt(mouseX, mouseY);
        if (port != null && port.handler.accepts(button)) {
            dragging = port;
            dragButton = button;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging != null && button == dragButton) {
            dragging.handler.drag(button, dragX, dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != null && button == dragButton) {
            dragging = null;
            dragButton = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Port port = portAt(mouseX, mouseY);
        if (port != null) {
            port.handler.scroll(mouseX, mouseY, scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
