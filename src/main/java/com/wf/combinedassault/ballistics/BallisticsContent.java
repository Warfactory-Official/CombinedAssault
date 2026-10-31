package com.wf.combinedassault.ballistics;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.addon.AddonArmorItem;
import com.wf.combinedassault.ballistics.addon.AddonFit;

public final class BallisticsContent {

    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, CombinedAssault.MOD_ID);

    public static final DeferredHolder<Item, RepairToolItem> REPAIR_TOOL = ITEMS.register("repair_tool", RepairToolItem::new);
    public static final DeferredHolder<Item, AddonArmorItem> ERA_BRICK =
            ITEMS.register("era_brick", () -> new AddonArmorItem(AddonFit.ERA_LIGHT));
    public static final DeferredHolder<Item, AddonArmorItem> HEAVY_ERA_BRICK =
            ITEMS.register("heavy_era_brick", () -> new AddonArmorItem(AddonFit.ERA_HEAVY));
    public static final DeferredHolder<Item, AddonArmorItem> SLAT_PANEL =
            ITEMS.register("slat_panel", () -> new AddonArmorItem(AddonFit.SLAT));

    private BallisticsContent() {
    }
}
