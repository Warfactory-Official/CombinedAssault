package com.wf.combinedassault.uav;

import com.wf.combinedassault.all.AllDataComponents;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.uav.net.UavTabletAction;
import com.wf.combinedassault.uav.net.UavTabletState;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.drone.cam.CameraNet;
import com.wf.wflib.drone.cam.FeedTarget;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.pda.alerts.PdaAlert;
import com.wf.wflib.stream.HostWakeup;
import com.wf.wflib.stream.HostWakeupData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PDA UAV app, server side: linked vehicles = {@link AllDataComponents#UAV_LINKS} on the held wflib PDA; feed
 * entitlement via {@link CameraNet#entitle}.
 */
public final class UavTablet {

    public static final int CAP = 16;
    private static final int FUEL_CHECK_TICKS = 100;
    /** Warn below, re-arm above. */
    private static final float LOW_FUEL = 0.15f;
    private static final float REFUELLED = 0.25f;
    /** (player, UAV) already warned. */
    private static final Set<List<UUID>> WARNED = new HashSet<>();

    private UavTablet() {
    }

    /** Common setup. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(UavTablet::onTick);
        CameraNet.entitle((level, player, feedId) -> {
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                if (!Pda.is(stack)) {
                    continue;
                }
                for (FeedTarget target : channels(stack).targets()) {
                    if (target.resolve(level) == feedId) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    /** PDA alert when a linked, loaded UAV runs low on fuel / charge. */
    private static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % FUEL_CHECK_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (Pda.is(stack)) {
                    for (FeedTarget target : channels(stack).targets()) {
                        target.drone().ifPresent(id -> checkFuel(player, id));
                    }
                }
            }
        }
    }

    private static void checkFuel(ServerPlayer player, UUID id) {
        AbstractVehicle uav = find(player, id);
        float capacity = uav == null ? 0 : uav.energyInfo.capacityUnits();
        if (capacity <= 0) {
            return;
        }
        float fuel = uav.getEnergy() / capacity;
        List<UUID> key = List.of(player.getUUID(), id);
        if (fuel < LOW_FUEL && WARNED.add(key)) {
            PdaAlert.send(player, Component.translatable(uav.isElectric() ? "tips.uav_link.low_charge"
                    : "tips.uav_link.low_fuel", uav.getDisplayName(), Math.round(fuel * 100)), PdaAlert.Tier.WARNING);
        } else if (fuel > REFUELLED) {
            WARNED.remove(key);
        }
    }

    public static CameraChannels channels(ItemStack stack) {
        return stack.getOrDefault(AllDataComponents.UAV_LINKS.get(), CameraChannels.EMPTY);
    }

    /** PDA used on an entity (sneak + use at rest): a commandable UAV => linked; anything else => PASS. */
    public static InteractionResult interact(ItemStack stack, Player player, Entity target) {
        if (!(target instanceof AbstractVehicle vehicle) || !vehicle.uav || vehicle.isDestroyed()) {
            return InteractionResult.PASS;
        }
        if (!player.level().isClientSide) {
            if (!vehicle.mayCommand(player)) {
                player.displayClientMessage(Component.translatable("tips.uav_link.not_yours"), true);
                return InteractionResult.FAIL;
            }
            player.displayClientMessage(Component.translatable(link(stack, vehicle)), true);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    /** @return chat key of the outcome */
    public static String link(ItemStack stack, AbstractVehicle vehicle) {
        CameraChannels channels = channels(stack);
        FeedTarget target = FeedTarget.ofDrone(vehicle.getUUID());
        if (channels.has(target)) {
            stack.set(AllDataComponents.UAV_LINKS.get(), channels.select(channels.targets().indexOf(target)));
            return "tips.uav_link.already";
        }
        if (channels.full(CAP)) {
            return "tips.uav_link.full";
        }
        stack.set(AllDataComponents.UAV_LINKS.get(), channels.with(target, CAP));
        return "tips.uav_link.paired";
    }

    public static void act(ServerPlayer player, UavTabletAction.Kind kind, int index) {
        ItemStack stack = Pda.heldStack(player);
        if (stack.isEmpty()) {
            return;
        }
        CameraChannels channels = channels(stack);
        UUID id = index >= 0 && index < channels.size() ? channels.targets().get(index).drone().orElse(null) : null;
        switch (kind) {
            case SELECT -> {
                if (id != null) {
                    stack.set(AllDataComponents.UAV_LINKS.get(), channels.select(index));
                    wake(player, id);
                }
            }
            case CONNECT -> {
                if (id != null) {
                    connect(player, id);
                }
            }
            case REMOVE -> {
                if (id != null) {
                    stack.set(AllDataComponents.UAV_LINKS.get(), channels.without(index));
                }
            }
            case REFRESH -> {
            }
        }
        send(player, stack);
    }

    /** Asleep => load it; awake => feed subscribed at once (chunk stream holds it until the screen's renew). */
    private static void wake(ServerPlayer player, UUID id) {
        HostWakeup.request(player, id, new HostWakeup.WakeCallback() {

            @Override
            public void onWake(ServerPlayer requester, Entity host) {
                CameraNet.subscribe(requester, host.getId());
            }

            @Override
            public void onTimeout(ServerPlayer requester) {
                requester.displayClientMessage(Component.translatable("tips.uav_link.wake_failed"), true);
            }
        });
    }

    private static void connect(ServerPlayer player, UUID id) {
        HostWakeup.Result result = HostWakeup.request(player, id, new HostWakeup.WakeCallback() {

            @Override
            public void onWake(ServerPlayer requester, Entity host) {
                if (!(host instanceof AbstractVehicle uav) || !uav.uav || uav.isDestroyed()) {
                    requester.displayClientMessage(Component.translatable("tips.uav_link.unavailable"), true);
                } else if (!uav.mayCommand(requester)) {
                    requester.displayClientMessage(Component.translatable("tips.uav_link.not_yours"), true);
                } else {
                    requester.startRiding(uav);
                }
            }

            @Override
            public void onTimeout(ServerPlayer requester) {
                requester.displayClientMessage(Component.translatable("tips.uav_link.wake_failed"), true);
            }
        });
        switch (result) {
            case WAKING -> player.displayClientMessage(Component.translatable("tips.uav_link.reconnect"), true);
            case UNKNOWN -> player.displayClientMessage(Component.translatable("tips.uav_link.unknown"), true);
            case OTHER_DIMENSION ->
                    player.displayClientMessage(Component.translatable("tips.uav_link.other_dimension"), true);
            case LOADED -> {
            }
        }
    }

    private static void send(ServerPlayer player, ItemStack stack) {
        CameraChannels channels = channels(stack);
        List<UavTabletState.Entry> entries = new ArrayList<>(channels.size());
        for (FeedTarget target : channels.targets()) {
            target.drone().ifPresent(id -> entries.add(entry(player, id)));
        }
        PacketDistributor.sendToPlayer(player, new UavTabletState(channels.selected(), entries));
    }

    private static UavTabletState.Entry entry(ServerPlayer player, UUID id) {
        AbstractVehicle vehicle = find(player, id);
        if (vehicle == null) {
            HostWakeupData.Entry record = HostWakeup.lookup(player.server, id);
            if (record == null) {
                return offline(id, UavTabletState.Status.UNKNOWN, -1);
            }
            boolean here = record.dimension() == player.level().dimension();
            return offline(id, here ? UavTabletState.Status.ASLEEP : UavTabletState.Status.OTHER_DIMENSION,
                    here ? player.position().distanceTo(record.position()) : -1);
        }
        boolean here = vehicle.level() == player.level();
        double distance = here ? player.position().distanceTo(vehicle.position()) : -1;
        UavTabletState.Status status = vehicle.isDestroyed() ? UavTabletState.Status.DESTROYED
                : !here ? UavTabletState.Status.OTHER_DIMENSION
                : vehicle.seats.stream().allMatch(seat -> seat.passengerId != -1) ? UavTabletState.Status.OCCUPIED
                : UavTabletState.Status.ONLINE;
        List<UavTabletState.Cell> cells = new ArrayList<>();
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            cells.add(new UavTabletState.Cell(cell.label(), cell.ratio()));
        }
        float capacity = vehicle.energyInfo.capacityUnits();
        UavCamera camera = new UavCamera(vehicle);
        return new UavTabletState.Entry(id, here ? vehicle.getId() : 0, status, vehicle.getDisplayName(), cells,
                capacity <= 0 ? 0 : Math.min(1, vehicle.getEnergy() / capacity), vehicle.isElectric(), ammo(vehicle),
                distance < 0 || vehicle.seats.isEmpty() ? 0 : camera.spec().linkAt(distance), distance);
    }

    private static UavTabletState.Entry offline(UUID id, UavTabletState.Status status, double distance) {
        return new UavTabletState.Entry(id, 0, status, Component.translatable("pda.combinedassault.uav.unnamed"),
                List.of(), 0, false, List.of(), 0, distance);
    }

    private static List<UavTabletState.Ammo> ammo(AbstractVehicle vehicle) {
        Map<AbstractVehicleWeapon<?>, Boolean> seen = new IdentityHashMap<>();
        List<UavTabletState.Ammo> out = new ArrayList<>();
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (!(part instanceof WeaponUnit unit)) {
                continue;
            }
            for (List<AbstractVehicleWeapon<?>> list : List.of(unit.weapons, unit.secondaryWeapons, unit.independentWeapons)) {
                for (AbstractVehicleWeapon<?> weapon : list) {
                    if (seen.put(weapon, Boolean.TRUE) == null) {
                        out.add(new UavTabletState.Ammo(weapon.getDisplayName(), weapon.getRemainAmmo(),
                                weapon.getMaxCapacity(), weapon.getStorageAmmo()));
                    }
                }
            }
        }
        return out;
    }

    @Nullable
    private static AbstractVehicle find(ServerPlayer player, UUID id) {
        if (player.serverLevel().getEntity(id) instanceof AbstractVehicle vehicle) {
            return vehicle;
        }
        for (ServerLevel level : player.server.getAllLevels()) {
            if (level != player.level() && level.getEntity(id) instanceof AbstractVehicle vehicle) {
                return vehicle;
            }
        }
        return null;
    }
}
