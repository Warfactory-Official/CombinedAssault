package com.wf.combinedassault.item;

import com.wf.wflib.network.WFNetwork;
import com.wf.wflib.probe.ProbeActionPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import com.wf.combinedassault.ballistics.addon.AddonActions;
import com.wf.combinedassault.ballistics.addon.AddonFit;
import com.wf.combinedassault.ballistics.addon.AddonKind;
import com.wf.combinedassault.ballistics.addon.AddonMountState;
import com.wf.combinedassault.ballistics.addon.AddonTargeting;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.List;
import java.util.Locale;

public class AddonArmorItem extends VehicleItem {

    private final AddonFit fit;

    public AddonArmorItem(AddonFit fit) {
        super(new Properties().stacksTo(fit.kind == AddonKind.ERA ? 16 : 8));
        this.fit = fit;
    }

    public AddonFit fit() {
        return fit;
    }

    /**
     * Aim not re-verified here; probe packet revalidates server-side.
     * Past independent server-side aim re-derivation disagreed with client on some mounts => fell through to boarding; server now unconditionally CONSUMEs.
     */
    @Override
    public InteractionResult interactEntity(ItemStack itemStack, Player player, Entity target, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !(target instanceof AbstractVehicle vehicle)
                || vehicle.addonArmor().mounts().stream().noneMatch(mount -> mount.info.kind == fit.kind)) {
            return InteractionResult.PASS;
        }
        if (!player.level().isClientSide()) {
            return InteractionResult.CONSUME;
        }
        AddonTargeting.Target aimed = AddonTargeting.underCrosshair(player);
        if (aimed == null || aimed.vehicle() != vehicle
                || vehicle.addonArmor().mounts().get(aimed.mount()).info.kind != fit.kind) {
            return InteractionResult.CONSUME;
        }
        AddonMountState state = vehicle.addonArmor().state(aimed.mount());
        boolean refill = state.fit == fit && state.liveCells() < vehicle.addonArmor().mounts().get(aimed.mount()).cells();
        if (!state.isEmpty() && !refill) {
            return InteractionResult.CONSUME;
        }
        WFNetwork.sendToServer(new ProbeActionPacket(BlockPos.ZERO, vehicle.getId(),
                state.isEmpty() ? AddonActions.INSTALL : AddonActions.REPLACE, AddonActions.arg(aimed.mount(), fit)));
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String key = "item.combinedassault." + fit.name().toLowerCase(Locale.ROOT) + ".hint";
        tooltip.add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("addon_armor.combinedassault.install_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
