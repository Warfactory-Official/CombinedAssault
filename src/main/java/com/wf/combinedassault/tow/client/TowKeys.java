package com.wf.combinedassault.tow.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Toggle (driver can't name hitches one-handed); server works out the rest. M not the more natural P: vanilla binds P to social interactions, so P here would look dead. */
public final class TowKeys {

    public static final KeyMapping TOGGLE_COUPLING = new KeyMapping(
            "key.combinedassault.tow_couple.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.category.combinedassault");

    private TowKeys() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_COUPLING);
    }

}
