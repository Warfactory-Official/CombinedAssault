package com.wf.combinedassault.all;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.storage.VehicleStorageMenu;

public class AllMenus {

    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CombinedAssault.MOD_ID);

    /** Extra data: slot count is data-driven; client needs it before the first content sync. */
    public static final DeferredHolder<MenuType<?>, MenuType<VehicleStorageMenu>> VEHICLE_STORAGE =
            MENUS.register("vehicle_storage", () -> IMenuTypeExtension.create(VehicleStorageMenu::new));

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }

}
