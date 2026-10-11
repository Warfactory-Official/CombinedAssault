package com.wf.combinedassault.client;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.item.CamoKitItem;
import com.wf.combinedassault.network.message.ClientCamoAction;
import com.wf.combinedassault.vehicle.camo.CamoTargeting;
import com.wf.combinedassault.vehicle.pojo.CamoAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Camo kit aims -> server; nothing applied locally, stroke appears on server echo.
 * Server traces rays against hull collision cubes, reads material/size off kit: client can't place
 * a piece it couldn't have pointed at.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class CamoBrush {

    /** Flush threshold: bounds packet size vs one packet per sample. */
    private static final int SEGMENT = 48;
    /** Filters a stationary hold; server decides actual ray spacing (cross-player consistency needs that, not this). */
    private static final double AIM_EPSILON = 0.02;

    private static final List<ClientCamoAction.Ray> pending = new ArrayList<>();
    private static int strokeVehicleId = -1;
    private static ClientCamoAction.Ray lastAim;
    private static boolean wasUsing;

    private CamoBrush() {
    }

    public static void dab(AbstractVehicle vehicle, ItemStack itemStack) {
        flush();
        sample(vehicle, true);
        flush();
    }

    public static void erase(AbstractVehicle vehicle) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientCamoAction.Ray ray = aim();
        if (ray == null) {
            return;
        }
        flush();
        ClientCamoAction message = new ClientCamoAction();
        message.action = CamoAction.Action.ERASE;
        message.vehicleId = vehicle.getId();
        message.rays = List.of(ray);
        PacketDistributor.sendToServer(message);
        minecraft.player.playSound(SoundEvents.WOOL_BREAK, 0.5f, 1.2f);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            reset();
            return;
        }
        ItemStack itemStack = player.getItemInHand(InteractionHand.MAIN_HAND);
        boolean using = minecraft.options.keyUse.isDown()
                && minecraft.screen == null
                && itemStack.getItem() instanceof CamoKitItem
                && !player.isShiftKeyDown();
        if (!using) {
            if (wasUsing) {
                flush();
            }
            wasUsing = false;
            return;
        }
        wasUsing = true;
        AbstractVehicle vehicle = lookedAtVehicle(minecraft);
        if (vehicle == null) {
            return;
        }
        sample(vehicle, false);
        if (pending.size() >= SEGMENT) {
            flush();
        }
    }

    /**
     * Not {@code minecraft.hitResult}: vanilla limits to interaction range, kit sweeps further. Server
     * runs the same query on arrival.
     */
    private static AbstractVehicle lookedAtVehicle(Minecraft minecraft) {
        return CamoTargeting.underCrosshair(minecraft.player);
    }

    /** @param force bypass {@link #AIM_EPSILON}; a deliberate click always counts */
    private static void sample(AbstractVehicle vehicle, boolean force) {
        ClientCamoAction.Ray ray = aim();
        if (ray == null) {
            return;
        }
        if (!force && lastAim != null
                && lastAim.from().distanceToSqr(ray.from()) < AIM_EPSILON * AIM_EPSILON
                && lastAim.to().distanceToSqr(ray.to()) < AIM_EPSILON * AIM_EPSILON) {
            return;
        }
        // One vehicle per stroke: switching target flushes and starts a new stroke.
        if (strokeVehicleId != vehicle.getId()) {
            flush();
            strokeVehicleId = vehicle.getId();
        }
        lastAim = ray;
        pending.add(ray);
    }

    /** Player's eye ray, not the camera's: third-person camera sits blocks back, server would reject that origin. */
    private static ClientCamoAction.Ray aim() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        return new ClientCamoAction.Ray(CamoTargeting.rayStart(player), CamoTargeting.rayEnd(player));
    }

    private static void flush() {
        if (pending.isEmpty() || strokeVehicleId < 0) {
            reset();
            return;
        }
        ClientCamoAction message = new ClientCamoAction();
        message.action = CamoAction.Action.PAINT;
        message.vehicleId = strokeVehicleId;
        message.rays = new ArrayList<>(pending);
        PacketDistributor.sendToServer(message);
        reset();
    }

    private static void reset() {
        pending.clear();
        strokeVehicleId = -1;
        lastAim = null;
    }
}
