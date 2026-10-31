package com.wf.combinedassault.vehicle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.wf.combinedassault.vehicle.command.RootCommand;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.item.VehicleItem;
import com.wf.combinedassault.vehicle.part.DoorUnit;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.util.VectorUtil;

public final class VehicleEvents {

    private VehicleEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        RootCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity().isPassenger() && event.getEntity().getVehicle() instanceof AbstractVehicle) {
            event.getEntity().stopRiding();
        }
    }

    @SubscribeEvent
    public static void onPlayerTeleport(EntityTeleportEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (player.isPassenger() && player.getVehicle() instanceof AbstractVehicle) {
                player.stopRiding();
            }
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ItemStack itemStack = event.getItemStack();
        Player player = event.getEntity();
        if (itemStack.getItem() instanceof VehicleItem vehicleItem) {
            InteractionResult result = vehicleItem.interactEntity(itemStack, player, event.getTarget(), event.getHand());
            if (result != null && result != InteractionResult.PASS) {
                event.setCancellationResult(result);
                event.setCanceled(true);
                return;
            }
        }
        if (event.getTarget() instanceof AbstractVehicle vehicle) {
            PartUnit<?> partUnit = VectorUtil.pickPartUnit(player, vehicle);
            if (partUnit != null) {
                if (!partUnit.onInteract(player, event.getHand())) {
                    event.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityMount(EntityMountEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (event.getEntityBeingMounted() instanceof AbstractVehicle vehicle && event.getEntityMounting() instanceof LivingEntity livingEntity) {
            if (event.isMounting()) {
                if (livingEntity instanceof ServerPlayer serverPlayer) {
                    if (VehicleConfig.common().checkTeamOnEnterVehicle() && serverPlayer.getTeam() != null) {
                        if (vehicle.getPassengers().stream()
                                .anyMatch(entity -> entity instanceof ServerPlayer passenger
                                        && passenger.getTeam() != null
                                        && !passenger.getTeam().isAlliedTo(serverPlayer.getTeam()))) {
                            event.setCanceled(true);
                        }
                    }
                }
            } else {
                if (!event.getLevel().isClientSide()) {
                    DoorUnit doorUnit = vehicle.getNearestDoorUnit(livingEntity);
                    if (doorUnit != null && !doorUnit.isOn()) {
                        doorUnit.setOn(true);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingDamageEvent.Pre event) {
        if (event.getEntity().getVehicle() instanceof AbstractVehicle vehicle) {
            if (vehicle.getDetachedBodyAnchor(event.getEntity()) != null) {
                event.getEntity().stopRiding();
                return;
            }
            PartUnit<?> seat = vehicle.getOwnOperatorUnit(event.getEntity());
            Boolean protect = seat == null ? null : seat.getData().getProtectPassenger();
            if ((protect == null ? vehicle.protectPassenger : protect)
                    && !event.getSource().is(VehicleDamageTypes.BREECH_FIRE)) {
                event.setNewDamage(0);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        LeadTickets.tick(event.getServer());
    }
}
