package com.wf.combinedassault.item;

import com.wf.combinedassault.all.AllDataComponents;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.client.tooltip.VehicleTooltips;
import com.wf.combinedassault.compat.gemrender.HardwareItemClient;
import com.wf.combinedassault.vehicle.hardware.HardwareKit;
import com.wf.combinedassault.vehicle.hardware.HardwareKits;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** A {@link HardwareKit} carried: kit id in {@code hardware_kit}; fitted at a hardpoint probe node. */
public class HardwareItem extends Item {

    public HardwareItem() {
        super(new Item.Properties().stacksTo(1));
    }

    public static ItemStack of(ResourceLocation kit) {
        ItemStack stack = new ItemStack(AllItems.HARDWARE.get());
        stack.set(AllDataComponents.HARDWARE_KIT.get(), kit);
        return stack;
    }

    @Nullable
    public static HardwareKit kit(ItemStack stack) {
        return stack.is(AllItems.HARDWARE.get()) ? HardwareKits.get(stack.get(AllDataComponents.HARDWARE_KIT.get())) : null;
    }

    @Override
    public Component getName(ItemStack stack) {
        HardwareKit kit = kit(stack);
        return kit == null ? super.getName(stack) : Component.translatable(kit.nameKey());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        HardwareKit kit = kit(stack);
        if (kit != null) {
            VehicleTooltips.kit(kit, tooltip);
        }
    }

    @Override
    @SuppressWarnings("removal")
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return HardwareItemClient.renderer();
            }
        });
    }
}
