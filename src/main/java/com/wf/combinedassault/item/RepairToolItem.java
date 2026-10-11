package com.wf.combinedassault.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * {@code #combinedassault:all_tool}: satisfies every {@code <system>_tool} tag.
 * Used by the probe's repair rows ({@code SystemRepair}).
 */
public class RepairToolItem extends VehicleItem {

    public RepairToolItem() {
        super(new Properties().durability(100));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.combinedassault.repair_tool.tip").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult interactEntity(ItemStack itemStack, Player player, Entity target, InteractionHand pHand) {
        return InteractionResult.PASS;
    }

}
