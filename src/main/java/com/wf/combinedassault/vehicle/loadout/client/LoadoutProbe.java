package com.wf.combinedassault.vehicle.loadout.client;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.fpv.payload.FpvPayload;
import com.wf.combinedassault.fpv.payload.FpvPayloadType;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.vehicle.loadout.Loadouts;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.pojo.LoadoutInfo;
import com.wf.wflib.probe.ProbeAction;
import com.wf.wflib.probe.ProbeContext;
import com.wf.wflib.probe.ProbeElement;
import com.wf.wflib.probe.ProbeInfo;
import com.wf.wflib.probe.ProbeInventory;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Node panel: current payload loadout's hardpoint (strap / strip). */
public final class LoadoutProbe {

    private static final String KEY = "loadout.combinedassault.";

    private LoadoutProbe() {
    }

    public static void fill(ProbeInfo info, ProbeContext ctx, AbstractVehicle vehicle, LoadoutInfo loadout,
                            PartUnit<?> part) {
        Player player = ctx.player();
        info.title(Component.translatable(KEY + loadout.id));
        payload(info, player, (PayloadUnit) part);
    }

    private static void payload(ProbeInfo info, Player player, PayloadUnit hardpoint) {
        FpvPayloadType type = hardpoint.payloadType();
        if (type == null) {
            return;
        }
        FpvPayload probe = type.empty().create(type);
        List<ItemStack> contents = hardpoint.contents();
        info.entry(Component.translatable(KEY + "slots"), Component.literal(contents.size() + " / " + probe.capacity())
                .withStyle(contents.isEmpty() ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
        for (ItemStack stack : contents) {
            info.add(new ProbeElement.Icon(stack, 12, false));
        }
        if (contents.size() < probe.capacity()) {
            ItemStack found = ProbeInventory.find(player, probe::canLoad);
            ProbeAction strap = ProbeAction.of(Loadouts.INSTALL, 0, found.isEmpty()
                    ? Component.translatable(KEY + "action.strap_any") : Component.translatable(KEY + "action.strap",
                    found.getHoverName()));
            if (found.isEmpty()) {
                strap = strap.unavailable(Component.translatable(KEY + "need_payload"));
            } else {
                strap = strap.withIcon(new ProbeElement.Icon(found, 10, false));
            }
            info.action(strap);
        }
        if (!contents.isEmpty()) {
            info.action(ProbeAction.of(Loadouts.REMOVE, 0, Component.translatable(KEY + "action.remove")));
        }
    }
}
