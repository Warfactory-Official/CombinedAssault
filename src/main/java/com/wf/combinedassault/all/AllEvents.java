package com.wf.combinedassault.all;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import com.wf.wflib.aef.ExplosionAEF;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wf.combinedassault.api.entity.RemoteTickEntity;
import com.wf.wflib.api.SeekerDecoy;
import com.wf.combinedassault.api.event.HitVehicleEvent;
import com.wf.combinedassault.api.event.VehicleFireEvent;
import com.wf.combinedassault.command.RootCommand;
import com.wf.combinedassault.entity.misc.RadarMarkerEntity;
import com.wf.combinedassault.ballistics.addon.AddonStrike;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.VehicleBlast;
import com.wf.wflib.MissileEntity;
import com.wf.combinedassault.item.FuelTankItem;
import com.wf.combinedassault.item.VehicleItem;
import com.wf.combinedassault.mixin.common.ExplosionAccessor;
import com.wf.combinedassault.network.message.ServerBroadcastEntities;
import com.wf.combinedassault.network.message.ServerHitVehicleEvent;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.util.VehicleExplosion;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.DoorUnit;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.parenting.DeckRejoin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class AllEvents {

    @EventBusSubscriber
    public static class AllModEvents {

        @SubscribeEvent
        public static void onRegisterCaps(RegisterCapabilitiesEvent event) {
            event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> {
                        if (stack.getItem() instanceof FuelTankItem) {
                            return new FuelTankItem.FuelTankFluidHandler(stack, stack.getMaxDamage());
                        }
                        return null;
                    },
                    AllItems.FUEL_TANK.get()
            );
            for (EntityType<? extends AbstractVehicle> type : AllEntities.vehicleTypes()) {
                registerVehicleEnergy(event, type);
            }
        }

        /** Tank/battery as capabilities: pump/charger sees a vehicle as a machine; only the one it runs on answers. */
        private static <T extends AbstractVehicle> void registerVehicleEnergy(RegisterCapabilitiesEvent event, EntityType<T> type) {
            event.registerEntity(Capabilities.FluidHandler.ENTITY, type,
                    (vehicle, context) -> vehicle.isElectric() ? null : vehicle.fuelTank());
            event.registerEntity(Capabilities.EnergyStorage.ENTITY, type,
                    (vehicle, context) -> vehicle.isElectric() ? vehicle.energyBuffer() : null);
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            RootCommand.register(event.getDispatcher());
        }

        @OnlyIn(Dist.CLIENT)
        @SubscribeEvent
        public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
            if (event.getEntity() instanceof LocalPlayer) {
                LocalVehiclePlayer.instance.clear();
            }
        }

        @SubscribeEvent
        public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                DeckRejoin.onLoggedIn(player);
            }
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

        @OnlyIn(Dist.CLIENT)
        @SubscribeEvent
        public static void onInteractionKeyMappingTriggered(InputEvent.InteractionKeyMappingTriggered event) {
            AbstractVehicle.Seat seat = LocalVehiclePlayer.instance.seat;
            if (seat != null && !seat.partUnit.getData().passengerCanUseItem()) {
                event.setCanceled(true);
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
                        if (AllConfigs.common.checkTeamOnEnterVehicle.get() && serverPlayer.getTeam() != null) {
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
                if (protect == null ? vehicle.protectPassenger : protect) {
                    event.setNewDamage(0);
                }
            }
        }

        @SubscribeEvent
        public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
            Iterator<Entity> iterator = event.getAffectedEntities().iterator();
            while (iterator.hasNext()) {
                Entity entity = iterator.next();
                if (!(entity instanceof AbstractVehicle vehicle)) {
                    continue;
                }
                iterator.remove();
                Explosion explosion = event.getExplosion();
                Vec3 explosionPos = explosion.center();
                float explosionRadius = ((ExplosionAccessor) explosion).getRadius() * 2.0F;
                if (entity.ignoreExplosion(explosion)) {
                    continue;
                }
                ExplosionAEF aef = ExplosionAEF.running(explosion);
                float exposure = AddonStrike.blast(vehicle, explosionPos, ((ExplosionAccessor) explosion).getRadius(), aef);
                // Hull distance, not origin: a carrier's plate can be 30 blocks from its own spawn point.
                Vec3 hullPoint = VehicleBlast.nearestHullPoint(vehicle, explosionPos);
                if (aef != null && !aef.inBlastShape(hullPoint) && !aef.inBlastShape(vehicle.getBoundingBox().getCenter())) {
                    continue;
                }
                double distance = hullPoint.distanceTo(explosionPos);
                double distanceRatio = distance / explosionRadius;
                if (distanceRatio > 1.0D) {
                    continue;
                }
                double visibilityFactor = VehicleBlast.seenFraction(entity.level(), vehicle, explosionPos, hullPoint);
                double impactStrength = (1.0D - distanceRatio) * visibilityFactor * exposure;
                float damage = (float) ((int) ((impactStrength * impactStrength + impactStrength) / 2.0D * 7.0D * explosionRadius + 1.0D));
                Entity direct = explosion.getDirectSourceEntity();
                Entity causing = explosion.getIndirectSourceEntity();
                entity.hurt(AllDamageTypes.Sources.explosion(entity.level().registryAccess(), direct, causing, hullPoint), damage);
            }
        }

        @SubscribeEvent
        public static void onVehicleFire(VehicleFireEvent.Post event) {
            if (event.isClientSide()) {
                event.getWeapon().onClientFire();
                event.getWeapon().getWeaponUnit().onClientFire();
            }
        }

        @SubscribeEvent
        public static void onHitVehicle(HitVehicleEvent event) {
            ServerPlayer serverPlayer = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(event.shooterUuid);
            if (serverPlayer == null) {
                return;
            }
            Level level = serverPlayer.level();
            if (level.getEntity(event.entityId) instanceof AbstractVehicle vehicle) {
                List<Entity> players = level.getEntities(vehicle,
                        AABB.ofSize(vehicle.position(), 256, 256, 256),
                        entity -> entity instanceof ServerPlayer && entity != serverPlayer);
                players.add(serverPlayer);
                players.forEach(player -> PacketDistributor.sendToPlayer((ServerPlayer) player, new ServerHitVehicleEvent(event)));
            }
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            MinecraftServer server = event.getServer();
            for (ServerLevel level : server.getAllLevels()) {
                VehicleExplosion.tick(level);
            }
            if (server.getTickCount() % AllConfigs.server.serverBroadcastEntitiesInterval.get() == 0) {
                for (ServerLevel level : server.getAllLevels()) {
                    // Currently used only by radar.
                    List<Entity> entities = new ArrayList<>();
                    for (Entity target : level.getEntities().getAll()) {
                        if (!target.isAlive()) {
                            continue;
                        }
                        if ((target instanceof AbstractVehicle vehicle && !vehicle.isDestroyed())
                                || target instanceof MissileEntity
                                || target instanceof SeekerDecoy
                                || target instanceof RadarMarkerEntity
                                || AllConfigs.serverBroadcastEntityWhitelist.stream()
                                .anyMatch(entityType -> BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString().matches(entityType))) {
                            entities.add(target);
                        }
                    }
                    if (entities.isEmpty()) {
                        continue;
                    }
                    ServerBroadcastEntities serverBroadcastEntities = new ServerBroadcastEntities();
                    serverBroadcastEntities.entities = new ArrayList<>();
                    for (Entity target : entities) {
                        CompoundTag data = new CompoundTag();
                        if (target instanceof RemoteTickEntity targetRemoteTickEntity) {
                            targetRemoteTickEntity.writeData(data);
                        }
                        serverBroadcastEntities.entities.add(new ServerBroadcastEntities.BroadcastEntity(target.getId(),
                                EntityType.getKey(target.getType()),
                                target.position(),
                                target.getDeltaMovement(),
                                data));
                    }
                    for (ServerPlayer player : level.players()) {
                        Entity vehicle = player.getVehicle();
                        if (vehicle instanceof AbstractVehicle) {
                            PacketDistributor.sendToPlayer(player, serverBroadcastEntities);
                        }
                    }
                }
            }
        }

    }

}
