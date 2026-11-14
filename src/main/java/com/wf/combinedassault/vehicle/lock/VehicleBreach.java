package com.wf.combinedassault.vehicle.lock;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.VehicleConfig;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Main-hand {@link #TOOLS} item, started by use on a locked vehicle; held {@link #ticks} within {@link #REACH} => lock removed. */
@EventBusSubscriber
public final class VehicleBreach {

    public static final TagKey<Item> TOOLS = TagKey.create(Registries.ITEM, CombinedAssault.modLocation("breach_tools"));
    public static int ticks(AbstractVehicle vehicle) {
        return vehicle.breachSeconds != null ? vehicle.breachSeconds * 20 : VehicleConfig.breachTicks();
    }

    private static final double REACH = 4.5;

    private record Job(int vehicleId, int progress) {
    }

    private static final Map<UUID, Job> JOBS = new HashMap<>();

    private VehicleBreach() {
    }

    public static boolean tool(ItemStack stack) {
        return stack.is(TOOLS);
    }

    /** @return true if a breach is now running (tool in hand, vehicle locked against the player). */
    public static boolean start(ServerPlayer player, AbstractVehicle vehicle) {
        if (!tool(player.getItemInHand(InteractionHand.MAIN_HAND)) || VehicleLock.permits(vehicle, player)) {
            return false;
        }
        Job old = JOBS.get(player.getUUID());
        if (old == null || old.vehicleId != vehicle.getId()) {
            JOBS.put(player.getUUID(), new Job(vehicle.getId(), 0));
        }
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Map.Entry<UUID, Job>> it = JOBS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            Job job = entry.getValue();
            if (player == null || !(player.serverLevel().getEntity(job.vehicleId) instanceof AbstractVehicle vehicle)
                    || vehicle.isDestroyed() || VehicleLock.permits(vehicle, player) || player.isPassenger()
                    || !tool(player.getMainHandItem())
                    || vehicle.getBoundingBox().distanceToSqr(player.getEyePosition()) > REACH * REACH) {
                if (player != null && JOBS.get(entry.getKey()) == job && job.progress > 0) {
                    player.displayClientMessage(Component.translatable("lock.combinedassault.breach_stopped")
                            .withStyle(ChatFormatting.RED), true);
                }
                it.remove();
                continue;
            }
            int progress = job.progress + 1;
            int total = ticks(vehicle);
            if (progress >= total) {
                VehicleLock.open(vehicle);
                player.displayClientMessage(Component.translatable("lock.combinedassault.breached"), true);
                it.remove();
                continue;
            }
            if (progress % 10 == 0) {
                player.displayClientMessage(Component.translatable("lock.combinedassault.breaching",
                        (total - progress + 19) / 20), true);
            }
            entry.setValue(new Job(job.vehicleId, progress));
        }
    }
}
