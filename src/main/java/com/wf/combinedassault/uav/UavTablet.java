package com.wf.combinedassault.uav;

import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.item.UavControllerItem;
import com.wf.combinedassault.uav.net.UavTabletAction;
import com.wf.combinedassault.uav.net.UavTabletState;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.drone.cam.CameraNet;
import com.wf.wflib.drone.cam.FeedTarget;
import com.wf.wflib.item.ModDataComponents;
import com.wf.wflib.stream.HostWakeup;
import com.wf.wflib.stream.HostWakeupData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** UAV tablet, server side: linked vehicles = wflib {@link CameraChannels} on the stack (feed entitlement). */
public final class UavTablet {

    public static final int CAP = 16;

    private UavTablet() {
    }

    public static ItemStack held(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof UavControllerItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static CameraChannels channels(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.CHANNELS.get(), CameraChannels.EMPTY);
    }

    /** @return chat key of the outcome */
    public static String link(ItemStack stack, AbstractVehicle vehicle) {
        CameraChannels channels = channels(stack);
        FeedTarget target = FeedTarget.ofDrone(vehicle.getUUID());
        if (channels.has(target)) {
            stack.set(ModDataComponents.CHANNELS.get(), channels.select(channels.targets().indexOf(target)));
            return "tips.uav_controller.already";
        }
        if (channels.full(CAP)) {
            return "tips.uav_controller.full";
        }
        stack.set(ModDataComponents.CHANNELS.get(), channels.with(target, CAP));
        return "tips.uav_controller.paired";
    }

    public static void open(ServerPlayer player) {
        send(player, held(player), true);
    }

    public static void act(ServerPlayer player, UavTabletAction.Kind kind, int index) {
        ItemStack stack = held(player);
        if (stack.isEmpty()) {
            return;
        }
        CameraChannels channels = channels(stack);
        UUID id = index >= 0 && index < channels.size() ? channels.targets().get(index).drone().orElse(null) : null;
        switch (kind) {
            case SELECT -> {
                if (id != null) {
                    stack.set(ModDataComponents.CHANNELS.get(), channels.select(index));
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
                    stack.set(ModDataComponents.CHANNELS.get(), channels.without(index));
                }
            }
            case REFRESH -> {
            }
        }
        send(player, stack, false);
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
                requester.displayClientMessage(Component.translatable("tips.uav_controller.wake_failed"), true);
            }
        });
    }

    private static void connect(ServerPlayer player, UUID id) {
        HostWakeup.Result result = HostWakeup.request(player, id, new HostWakeup.WakeCallback() {

            @Override
            public void onWake(ServerPlayer requester, Entity host) {
                if (!(host instanceof AbstractVehicle uav) || !uav.uav || uav.isDestroyed()) {
                    requester.displayClientMessage(Component.translatable("tips.uav_controller.unavailable"), true);
                } else if (!uav.mayCommand(requester)) {
                    requester.displayClientMessage(Component.translatable("tips.uav_controller.not_yours"), true);
                } else {
                    requester.startRiding(uav);
                }
            }

            @Override
            public void onTimeout(ServerPlayer requester) {
                requester.displayClientMessage(Component.translatable("tips.uav_controller.wake_failed"), true);
            }
        });
        switch (result) {
            case WAKING -> player.displayClientMessage(Component.translatable("tips.uav_controller.reconnect"), true);
            case UNKNOWN -> player.displayClientMessage(Component.translatable("tips.uav_controller.unknown"), true);
            case OTHER_DIMENSION ->
                    player.displayClientMessage(Component.translatable("tips.uav_controller.other_dimension"), true);
            case LOADED -> {
            }
        }
    }

    private static void send(ServerPlayer player, ItemStack stack, boolean open) {
        CameraChannels channels = channels(stack);
        List<UavTabletState.Entry> entries = new ArrayList<>(channels.size());
        for (FeedTarget target : channels.targets()) {
            target.drone().ifPresent(id -> entries.add(entry(player, id)));
        }
        PacketDistributor.sendToPlayer(player, new UavTabletState(open, channels.selected(), entries));
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
        return new UavTabletState.Entry(id, 0, status, Component.translatable("tablet.combinedassault.unnamed"),
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
