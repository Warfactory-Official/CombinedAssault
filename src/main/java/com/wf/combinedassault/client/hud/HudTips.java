package com.wf.combinedassault.client.hud;

import net.minecraft.network.chat.Component;

/** One transient centre-screen notice (e.g. out of launch limits), shown 3 s. */
public final class HudTips {
    private static final long SHOWN_MILLIS = 3000;

    private static String text = "";
    private static long since = Long.MIN_VALUE / 2;

    private HudTips() {
    }

    public static void show(Component message) {
        text = message.getString();
        since = System.currentTimeMillis();
    }

    public static void clear() {
        since = Long.MIN_VALUE / 2;
    }

    static boolean shown() {
        return System.currentTimeMillis() - since < SHOWN_MILLIS;
    }

    static String text() {
        return text;
    }
}
