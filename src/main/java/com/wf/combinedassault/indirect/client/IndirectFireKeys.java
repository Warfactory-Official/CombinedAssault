package com.wf.combinedassault.indirect.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Bindings a tank gunner's controls don't need: laying mode, elevate/traverse, step size, arc; rack round. */
@EventBusSubscriber(value = Dist.CLIENT)
public final class IndirectFireKeys {

    public static final KeyMapping TOGGLE_INDIRECT = key("indirect_mode", GLFW.GLFW_KEY_Y);
    public static final KeyMapping ELEVATION_UP = key("indirect_elevation_up", GLFW.GLFW_KEY_PAGE_UP);
    public static final KeyMapping ELEVATION_DOWN = key("indirect_elevation_down", GLFW.GLFW_KEY_PAGE_DOWN);
    public static final KeyMapping TRAVERSE_LEFT = key("indirect_traverse_left", GLFW.GLFW_KEY_COMMA);
    public static final KeyMapping TRAVERSE_RIGHT = key("indirect_traverse_right", GLFW.GLFW_KEY_PERIOD);
    public static final KeyMapping CYCLE_STEP = key("indirect_step", GLFW.GLFW_KEY_SEMICOLON);
    public static final KeyMapping TOGGLE_ARC = key("indirect_arc", GLFW.GLFW_KEY_APOSTROPHE);

    private IndirectFireKeys() {
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.combinedassault." + name + ".desc",
                KeyConflictContext.IN_GAME, KeyModifier.NONE, InputConstants.Type.KEYSYM, code,
                "key.category.combinedassault");
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_INDIRECT);
        event.register(ELEVATION_UP);
        event.register(ELEVATION_DOWN);
        event.register(TRAVERSE_LEFT);
        event.register(TRAVERSE_RIGHT);
        event.register(CYCLE_STEP);
        event.register(TOGGLE_ARC);
    }

}
