package com.wf.combinedassault.cargo;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;

public final class CargoContent {

    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, CombinedAssault.MOD_ID);
    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, CombinedAssault.MOD_ID);
    static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CombinedAssault.MOD_ID);

    public static final DeferredHolder<Item, CargoParachuteItem> CARGO_PARACHUTE = ITEMS.register("cargo_parachute", CargoParachuteItem::new);
    public static final DeferredHolder<Item, ParachutePackItem> PARACHUTE_PACK = ITEMS.register("parachute_pack",
            () -> new ParachutePackItem(ArmorItem.Type.CHESTPLATE, new Item.Properties().durability(1)));

    public static final DeferredHolder<EntityType<?>, EntityType<ParagliderCanopy>> PARAGLIDER_CANOPY = ENTITIES.register("paraglider_canopy",
            () -> EntityType.Builder.of(ParagliderCanopy::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .sized(1F, 1F)
                    .clientTrackingRange(32)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(true)
                    .build("paraglider_canopy"));

    /** Extra data: slot count is data-driven; client needs it before the first content sync. */
    public static final DeferredHolder<MenuType<?>, MenuType<VehicleStorageMenu>> VEHICLE_STORAGE =
            MENUS.register("vehicle_storage", () -> IMenuTypeExtension.create(VehicleStorageMenu::new));

    private CargoContent() {
    }
}
