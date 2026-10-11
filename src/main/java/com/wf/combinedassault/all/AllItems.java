package com.wf.combinedassault.all;

import com.wf.combinedassault.ballistics.addon.AddonFit;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.item.*;

import java.util.LinkedHashMap;
import java.util.function.Supplier;

public class AllItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(net.minecraft.core.registries.BuiltInRegistries.ITEM, CombinedAssault.MOD_ID);
    public static final LinkedHashMap<String, DeferredHolder<Item, ? extends Item>> ITEMS_LOOKUP = new LinkedHashMap<>();

    public static final DeferredHolder<Item, VehicleSpawnItem> VEHICLE_SPAWN_ITEM = registerItem(AllTabs.Category.VEHICLE, "vehicle_spawn_item", () -> new VehicleSpawnItem(new Item.Properties().durability(1)));
    public static final DeferredHolder<Item, PlainTextureItem> PLAIN_TEXTURE_ITEM = registerItem(AllTabs.Category.NONE, "plain_texture_item", () -> new PlainTextureItem(new Item.Properties().durability(1)));
    public static final DeferredHolder<Item, Item> FUEL_TANK = registerItem(AllTabs.Category.MISC, "fuel_tank", () -> new FuelTankItem(new Item.Properties().durability(125)));
    public static final DeferredHolder<Item, Item> UAV_CONTROLLER = registerItem(AllTabs.Category.MISC, "uav_controller", () -> new UavControllerItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> FIGURE_BOX = registerItem(AllTabs.Category.MISC, "figure_box", () -> new FigureBoxItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> MACHINE_MAX_BLOCK = registerItem(AllTabs.Category.MISC, "machine_max_block", () -> new MachineMaxBlockItem(AllBlocks.MACHINE_MAX_BLOCK.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, RepairToolItem> REPAIR_TOOL = registerItem(AllTabs.Category.MISC, "repair_tool", RepairToolItem::new);
    public static final DeferredHolder<Item, ModdingToolItem> MODDING_TOOL = registerItem(AllTabs.Category.MISC, "modding_tool", () -> new ModdingToolItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, DecorationItem> DECORATION_ITEM = registerItem(AllTabs.Category.MISC, "decoration_item", DecorationItem::new);
    public static final DeferredHolder<Item, CamoKitItem> CAMO_KIT = registerItem(AllTabs.Category.MISC, "camo_kit", CamoKitItem::new);
    public static final DeferredHolder<Item, AddonArmorItem> ERA_BRICK = registerItem(AllTabs.Category.MISC, "era_brick", () -> new AddonArmorItem(AddonFit.ERA_LIGHT));
    public static final DeferredHolder<Item, AddonArmorItem> HEAVY_ERA_BRICK = registerItem(AllTabs.Category.MISC, "heavy_era_brick", () -> new AddonArmorItem(AddonFit.ERA_HEAVY));
    public static final DeferredHolder<Item, AddonArmorItem> SLAT_PANEL = registerItem(AllTabs.Category.MISC, "slat_panel", () -> new AddonArmorItem(AddonFit.SLAT));
    public static final DeferredHolder<Item, CargoParachuteItem> CARGO_PARACHUTE = registerItem(AllTabs.Category.MISC, "cargo_parachute", CargoParachuteItem::new);
    public static final DeferredHolder<Item, ParachutePackItem> PARACHUTE_PACK = registerItem(AllTabs.Category.MISC, "parachute_pack", () -> new ParachutePackItem(ArmorItem.Type.CHESTPLATE, new Item.Properties().durability(1)));
    public static final DeferredHolder<Item, Item> AMMO_CREATIVE = registerItem(AllTabs.Category.MISC, "ammo_creative", () -> new Item(new Item.Properties().stacksTo(1)));

    public static <T extends Item> DeferredHolder<Item, T> registerItem(AllTabs.Category category, String name, Supplier<T> item) {
        DeferredHolder<Item, T> registryObject = ITEMS.register(name, item);
        if (AllTabs.Category.MISC.equals(category)) {
            AllTabs.MISC_ITEMS.add(registryObject);
        }
        AllItems.ITEMS_LOOKUP.put(name, registryObject);
        return registryObject;
    }

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }

}
