package com.wf.combinedassault.uav;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.wf.wflib.pda.api.Pda;

public final class UavEvents {

    private UavEvents() {
    }

    /** Before the vehicle's own interact handler: a tablet use wins. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ItemStack itemStack = event.getItemStack();
        if (Pda.is(itemStack)) {
            InteractionResult result = UavTablet.interact(itemStack, event.getEntity(), event.getTarget());
            if (result != InteractionResult.PASS) {
                event.setCancellationResult(result);
                event.setCanceled(true);
            }
        }
    }
}
