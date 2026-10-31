package com.wf.combinedassault.cargo;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import com.wf.combinedassault.CombinedAssault;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Optional Ultimate Weight integration: configured item kg -> {@link ItemMass}. Reflection not jar dep: UW ships a
 * version-specific class per MC version; compile dep would pin + need a hand-kept {@code libs/} jar.
 * Absent mod or moved method => 0 => built-in mass.
 */
public final class UltimateWeightCompat {

    private static final String MOD_ID = "wfweight";
    private static final String VIEWS_CLASS = "com.warfactory.ultimateweight.v1211.WeightViews1211";
    private static final String WEIGHT_METHOD = "weightOf";

    private static MethodHandle weightOf;

    private UltimateWeightCompat() {
    }

    public static void init() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        try {
            Class<?> views = Class.forName(VIEWS_CLASS);
            weightOf = MethodHandles.lookup().findStatic(views, WEIGHT_METHOD,
                    MethodType.methodType(double.class, ItemStack.class));
        } catch (ReflectiveOperationException error) {
            // Present but unrecognized: warn once here, not per stack later.
            CombinedAssault.LOGGER.warn("Ultimate Weight is loaded but {}#{} did not resolve; cargo uses built-in masses",
                    VIEWS_CLASS, WEIGHT_METHOD, error);
            weightOf = null;
        }
    }

    /** Whole-stack weight (count included), kg. 0 when UW absent: callers never branch. */
    public static double kilograms(ItemStack stack) {
        if (weightOf == null || stack.isEmpty()) {
            return 0;
        }
        try {
            return (double) weightOf.invokeExact(stack);
        } catch (Throwable error) {
            // Lookup failure must never crash a vehicle tick.
            return 0;
        }
    }

}
