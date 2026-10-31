package com.wf.combinedassault.pack.sync.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.sync.SyncDataSerializer;
import com.wf.combinedassault.pack.sync.SyncDataSerializers;
import com.wf.combinedassault.pack.sync.SyncDataManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Unregistered serializer fails only on encode to a real client; headless runs never encode. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class SyncSerializersGameTest {

    @GameTest(template = "empty")
    public static void everySerializerIsRegistered(GameTestHelper helper) throws IllegalAccessException {
        for (Field field : SyncDataSerializers.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.get(null) instanceof SyncDataSerializer<?> s) {
                try {
                    SyncDataManager.get().getId(s);
                } catch (IllegalArgumentException e) {
                    helper.fail("SyncDataSerializers." + field.getName() + " never registered");
                    return;
                }
            }
        }
        helper.succeed();
    }
}
