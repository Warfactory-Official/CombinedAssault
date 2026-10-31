package com.wf.combinedassault.weapon.client;

import com.wf.combinedassault.weapon.kit.HardwareItem;
import com.wf.combinedassault.weapon.kit.HardwareKit;
import com.wf.combinedassault.weapon.kit.Hardpoints;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.vehicle.part.client.PartNames;
import com.wf.wflib.probe.api.ProbeAction;
import com.wf.wflib.probe.api.ProbeContext;
import com.wf.wflib.probe.api.ProbeElement;
import com.wf.wflib.probe.api.ProbeInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Hardpoint node panel: fitted kit + timed take off; else one timed mount row per accepted kit. */
public final class HardpointProbe {

    private static final String KEY = "hardpoint.combinedassault.";

    private HardpointProbe() {
    }

    public static void fill(ProbeInfo info, ProbeContext ctx, WeaponUnit unit) {
        Player player = ctx.player();
        info.title(PartNames.name(unit));
        List<HardwareKit> kits = unit.hardpoint().kits();
        if (unit.kit() != null) {
            HardwareKit kit = kits.get(unit.kitIndex());
            ItemStack item = HardwareItem.of(kit.id());
            info.entry(Component.translatable(KEY + "hardware"), item.getHoverName().copy().withStyle(ChatFormatting.GREEN));
            ProbeAction off = ProbeAction.of(Hardpoints.TAKE_OFF, Hardpoints.arg(unit, unit.kitIndex()),
                    Component.translatable(KEY + "action.take_off")).timed(kit.installTicks())
                    .withIcon(new ProbeElement.Icon(item, 10, false));
            Component refusal = Hardpoints.takeOffRefusal(player, unit);
            info.action(refusal == null ? off : off.unavailable(refusal));
            return;
        }
        info.entry(Component.translatable(KEY + "hardware"), Component.translatable(KEY + "unfitted")
                .withStyle(ChatFormatting.YELLOW));
        for (int i = 0; i < kits.size(); i++) {
            ItemStack item = HardwareItem.of(kits.get(i).id());
            ProbeAction mount = ProbeAction.of(Hardpoints.FIT, Hardpoints.arg(unit, i),
                    Component.translatable(KEY + "action.mount", item.getHoverName())).timed(kits.get(i).installTicks())
                    .withIcon(new ProbeElement.Icon(item, 10, false));
            Component refusal = Hardpoints.fitRefusal(player, unit, i);
            info.action(refusal == null ? mount : mount.unavailable(refusal));
        }
    }
}
