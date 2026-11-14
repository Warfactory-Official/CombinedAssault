package com.wf.combinedassault.energy;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.VehicleSprayPod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Vehicle-to-vehicle refuelling: a player starts it at the receiver's interaction node, the receiver's tick runs
 * it. Source = another vehicle's spray-pod tank (cargo tank); its own fuel tank is never drawn. Hull centres must
 * see each other through the blocks, at offer and every tick.
 */
public final class FuelTransfer {

    /** Hull-box gap, source to receiver; farther => session ends. */
    public static final double RANGE = 12;
    public static final double PLAYER_RANGE = 10;
    public static final int CONNECT_TICKS = 40;
    public static final int RATE_MB_PER_TICK = 25;

    private FuelTransfer() {
    }

    public static final class Session {
        final UUID provider;
        final Player player;
        int ticks;

        public int ticks() {
            return ticks;
        }

        Session(UUID provider, Player player) {
            this.provider = provider;
            this.player = player;
        }
    }

    /** Sources in range holding something the receiver burns, nearest first. */
    public static List<AbstractVehicle> providers(AbstractVehicle receiver) {
        if (receiver.isElectric()) {
            return List.of();
        }
        return receiver.level().getEntitiesOfClass(AbstractVehicle.class, receiver.getBoundingBox().inflate(RANGE),
                        v -> v != receiver && !v.isRemoved() && offers(v, receiver) && hoseClear(receiver, v)).stream()
                .sorted(Comparator.comparingDouble(v -> v.distanceToSqr(receiver)))
                .toList();
    }

    /** No block between the two hull centres. */
    public static boolean hoseClear(AbstractVehicle a, AbstractVehicle b) {
        return a.level().clip(new ClipContext(a.getBoundingBox().getCenter(), b.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, a)).getType() == HitResult.Type.MISS;
    }

    private static boolean offers(AbstractVehicle source, AbstractVehicle receiver) {
        var fluids = source.fluids();
        if (fluids == null) {
            return false;
        }
        for (VehicleSprayPod pod : fluids.pods()) {
            var held = pod.getFluidInTank(0);
            if (!held.isEmpty() && receiver.fuelTank().canAccept(held.getFluid())) {
                return true;
            }
        }
        return false;
    }

    public static boolean active(AbstractVehicle receiver) {
        return receiver.refuel != null;
    }

    /** Server. @return whether a session started */
    public static boolean start(AbstractVehicle receiver, Player player, AbstractVehicle provider) {
        if (receiver.refuel != null || !providers(receiver).contains(provider)) {
            return false;
        }
        receiver.refuel = new Session(provider.getUUID(), player);
        return true;
    }

    public static void stop(AbstractVehicle receiver) {
        receiver.refuel = null;
    }

    /** Server, every vehicle tick. */
    public static void tick(AbstractVehicle receiver) {
        Session session = receiver.refuel;
        if (session == null) {
            return;
        }
        Level level = receiver.level();
        Player player = session.player;
        AbstractVehicle provider = level.getEntitiesOfClass(AbstractVehicle.class,
                receiver.getBoundingBox().inflate(RANGE), v -> v.getUUID().equals(session.provider))
                .stream().findFirst().orElse(null);
        if (player.isRemoved() || player.level() != level || provider == null || provider.isRemoved()
                || player.distanceTo(receiver) > PLAYER_RANGE || !hoseClear(receiver, provider)) {
            end(receiver, player, "refuel.lost");
            return;
        }
        if (++session.ticks < CONNECT_TICKS) {
            tell(player, Component.translatable("message.combinedassault.refuel.connecting",
                    session.ticks * 100 / CONNECT_TICKS));
            return;
        }
        int moved = 0;
        for (VehicleSprayPod pod : provider.fluids() == null ? List.<VehicleSprayPod>of() : provider.fluids().pods()) {
            moved = VehicleResupply.pullFuel(receiver, pod, RATE_MB_PER_TICK);
            if (moved > 0) {
                break;
            }
        }
        if (moved > 0) {
            tell(player, Component.translatable("message.combinedassault.refuel.flowing", receiver.fuelTank().storedMb(),
                    receiver.fuelTank().getTankCapacity(0)));
        } else {
            end(receiver, player, receiver.fuelTank().storedMb() >= receiver.fuelTank().getTankCapacity(0)
                    ? "refuel.done" : "refuel.empty");
        }
    }

    private static void end(AbstractVehicle receiver, Player player, String key) {
        receiver.refuel = null;
        tell(player, Component.translatable("message.combinedassault." + key));
    }

    private static void tell(Player player, Component message) {
        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(message, true);
        }
    }
}
