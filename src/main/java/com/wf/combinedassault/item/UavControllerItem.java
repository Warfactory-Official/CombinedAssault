package com.wf.combinedassault.item;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.uav.UavTablet;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** UAV tablet: use => vehicle list + feed screen; use on a UAV => linked. */
public class UavControllerItem extends VehicleItem {

    public UavControllerItem(Properties pProperties) {
        super(pProperties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand pHand) {
        if (player instanceof ServerPlayer serverPlayer) {
            UavTablet.open(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(pHand), level.isClientSide());
    }

    @Override
    public InteractionResult interactEntity(ItemStack stack, Player player, Entity target, InteractionHand pHand) {
        if (!player.level().isClientSide && target instanceof AbstractVehicle vehicle && vehicle.uav
                && !vehicle.isDestroyed()) {
            if (!vehicle.mayCommand(player)) {
                player.displayClientMessage(Component.translatable("tips.uav_controller.not_yours"), true);
                return InteractionResult.FAIL;
            }
            player.displayClientMessage(Component.translatable(UavTablet.link(stack, vehicle)), true);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack pStack, Item.TooltipContext pContext, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        pTooltipComponents.add(Component.translatable("tips.uav_controller.linked", UavTablet.channels(pStack).size(),
                UavTablet.CAP).withStyle(ChatFormatting.GRAY));
        super.appendHoverText(pStack, pContext, pTooltipComponents, pIsAdvanced);
    }

}
