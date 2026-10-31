package com.wf.combinedassault.vehicle;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import com.wf.combinedassault.weapon.kit.HardwareItem;
import com.wf.combinedassault.weapon.kit.HardwareKits;
import net.minecraft.world.level.ItemLike;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.props.PropsContent;
import com.wf.wflib.core.api.WFSystems;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.CommonAssetsManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

public class VehicleTabs {

    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CombinedAssault.MOD_ID);
    private static final List<Supplier<? extends ItemLike>> MISC_ITEMS = new ArrayList<>();

    /** Figure box when props is on. */
    private static ItemStack icon() {
        return new ItemStack(WFSystems.enabled(CombinedAssault.MOD_ID + ":props")
                ? PropsContent.FIGURE_BOX.get() : VehicleContent.MODDING_TOOL.get());
    }

    public static void addMisc(Supplier<? extends ItemLike> item) {
        MISC_ITEMS.add(item);
    }

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB_MISC = TABS.register("tab_misc", () ->
            CreativeModeTab
                    .builder()
                    .title(Component.translatable("tab.misc"))
                    .icon(VehicleTabs::icon)
                    .displayItems((displayParams, output) -> {
                        MISC_ITEMS.forEach(itemLike -> output.accept(itemLike.get()));
                        CommonAssetsManager.ensureLoaded();
                        HardwareKits.all().forEach(kit -> output.accept(HardwareItem.of(kit.id())));
                        com.wf.wflib.missile.api.MissileContent.missileItems().stream().map(DeferredHolder::get)
                                .filter(i -> i.preset().id().getNamespace().equals(CombinedAssault.MOD_ID))
                                .forEach(output::accept);
                        com.wf.wflib.round.api.RoundContent.shellItems().stream().map(DeferredHolder::get)
                                .filter(i -> i.preset().id().getNamespace().equals(CombinedAssault.MOD_ID))
                                .forEach(output::accept);
                    })
                    .build());

    /** One tab per pack; icon = its first vehicle. */
    public static void addVehicleTab(String name, Component title, String pack) {
        TABS.register(name, () ->
                CreativeModeTab
                        .builder()
                        .title(title)
                        .icon(() -> CommonAssetsManager.vehicleDataManager().getVehicleData().keySet().stream()
                                .filter(id -> pack.equals(CommonAssetsManager.packOf(id))).findFirst()
                                .map(id -> VehicleContent.VEHICLE_SPAWN_ITEM.get().createInstance(id))
                                .orElseGet(VehicleTabs::icon))
                        .displayItems((displayParams, output) ->
                                CommonAssetsManager.vehicleDataManager().getVehicleData().values().stream()
                                        .filter(vehicleData -> pack.equals(CommonAssetsManager.packOf(vehicleData.getVehicleId())))
                                        .sorted(Comparator.comparingInt(vehicleData ->
                                                ClientAssetsManager.INSTANCE
                                                        .getVehicleDisplay(vehicleData.getVehicleId())
                                                        .map(baseDisplay -> baseDisplay.getTabIndex() + (vehicleData.isExperimental() ? 1_000_000 : 0))
                                                        .orElse(0)))
                                        .forEach(vehicleData -> output.accept(VehicleContent.VEHICLE_SPAWN_ITEM.get().createInstance(vehicleData.getVehicleId()))))
                        .build());
    }

    public static void register(IEventBus eventBus) {
        CombinedAssault.packs().packs().forEach(pack -> addVehicleTab("tab_" + pack.id().replaceAll("[^a-z0-9_]", "_"),
                Component.literal(pack.description()), pack.id()));
        TABS.register(eventBus);
    }

    public enum Category {
        VEHICLE,
        MISC,
        NONE
    }

}
