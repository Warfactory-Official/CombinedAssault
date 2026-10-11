package com.wf.combinedassault.mixin.common;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

/** {@code -PtestFilter=a,b}: run only tests whose name contains a or b (case-insensitive). */
@Mixin(GameTestServer.class)
public abstract class GameTestServerMixin {

    @ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true)
    private static Collection<TestFunction> combinedassault$filter(Collection<TestFunction> tests) {
        String filter = System.getProperty("combinedassault.test.filter", "");
        if (filter.isEmpty()) {
            return tests;
        }
        String[] parts = filter.toLowerCase(Locale.ROOT).split(",");
        return tests.stream().filter(t -> Arrays.stream(parts).anyMatch(t.testName().toLowerCase(Locale.ROOT)::contains)).toList();
    }
}
