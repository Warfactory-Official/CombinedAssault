package com.wf.combinedassault.uav;

import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.drone.cam.FeedTarget;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.pda.alerts.PdaAlert;
import com.wf.wflib.pda.drone.PdaDrones;
import com.wf.wflib.pda.drone.RemoteBody;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** UAVs in the wflib PDA Drones app: link (sneak + use), {@link RemoteBody} (status, systems, ammo, CONNECT), low-fuel alert. */
public final class UavTablet {

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
        RemoteBody.register(e -> e instanceof AbstractVehicle vehicle && vehicle.uav ? new Uav(vehicle) : null);
    }

    public static CameraChannels channels(ItemStack stack) {
        return PdaDrones.channels(stack);
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
        if (!(player.serverLevel().getEntity(id) instanceof AbstractVehicle uav) || !uav.uav) {
            return;
        }
        float capacity = uav.energyInfo.capacityUnits();
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
        return "tips.uav_link." + PdaDrones.link(stack, vehicle.getUUID()).name().toLowerCase(Locale.ROOT);
    }

    /** Remote ride = manual control; side's allies only; one pilot. */
    private record Uav(AbstractVehicle vehicle) implements RemoteBody {

        @Override
        public String name() {
            return this.vehicle.getDisplayName().getString();
        }

        @Override
        public String status() {
            return this.vehicle.isDestroyed() ? "DESTROYED" : this.vehicle.getDriver() != null ? "IN USE" : "ONLINE";
        }

        @Override
        public float fuel() {
            float capacity = this.vehicle.energyInfo.capacityUnits();
            return capacity <= 0 ? 0 : Math.min(1, this.vehicle.getEnergy() / capacity);
        }

        @Override
        public boolean electric() {
            return this.vehicle.isElectric();
        }

        @Override
        public List<Cell> cells() {
            List<Cell> out = new ArrayList<>();
            for (VehicleSystems.Cell cell : VehicleSystems.cells(this.vehicle)) {
                out.add(new Cell(cell.label(), cell.ratio()));
            }
            return out;
        }

        @Override
        public List<String> ammo() {
            Map<AbstractVehicleWeapon<?>, Boolean> seen = new IdentityHashMap<>();
            List<String> out = new ArrayList<>();
            for (PartUnit<?> part : this.vehicle.getPartUnits()) {
                if (!(part instanceof WeaponUnit unit)) {
                    continue;
                }
                for (List<AbstractVehicleWeapon<?>> list : List.of(unit.weapons, unit.secondaryWeapons,
                        unit.independentWeapons)) {
                    for (AbstractVehicleWeapon<?> weapon : list) {
                        if (seen.put(weapon, Boolean.TRUE) == null) {
                            out.add(weapon.getDisplayName().getString() + " " + weapon.getRemainAmmo() + "/"
                                    + weapon.getMaxCapacity() + (weapon.getStorageAmmo() > 0
                                    ? " +" + weapon.getStorageAmmo() : ""));
                        }
                    }
                }
            }
            return out;
        }

        @Override
        @Nullable
        public String connectRefusal(ServerPlayer player) {
            if (this.vehicle.isDestroyed()) {
                return "destroyed";
            }
            if (!this.vehicle.mayCommand(player)) {
                return "not your side's";
            }
            if (this.vehicle.seats.stream().allMatch(seat -> seat.passengerId != -1)) {
                return "in use";
            }
            return null;
        }

        @Override
        public void connect(ServerPlayer player) {
            player.startRiding(this.vehicle);
        }
    }
}
