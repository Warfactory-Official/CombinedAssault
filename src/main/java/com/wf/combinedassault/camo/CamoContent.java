package com.wf.combinedassault.camo;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;

public final class CamoContent {

    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, CombinedAssault.MOD_ID);

    public static final DeferredHolder<Item, CamoKitItem> CAMO_KIT = ITEMS.register("camo_kit", CamoKitItem::new);

    private CamoContent() {
    }
}
