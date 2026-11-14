package com.wf.combinedassault.vehicle.interact;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.lock.VehicleLock;
import com.wf.wflib.probe.api.ProbeActions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Probe action {@code combinedassault:interact}; arg = interaction << 24 | action << 16 | row arg. */
public final class InteractionActions {

    public static final ResourceLocation INTERACT = CombinedAssault.modLocation("interact");

    private InteractionActions() {
    }

    public static void register() {
        ProbeActions.registerEntity(INTERACT, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && perform(player, vehicle, arg));
    }

    public static int arg(int interaction, int action, int row) {
        return interaction << 24 | action << 16 | row & 0xFFFF;
    }

    /** Server path of {@link #INTERACT}: riders of this vehicle only {@link InteractionAction#riders} actions; first node in sight from the eye, then the action. */
    public static boolean perform(ServerPlayer player, AbstractVehicle vehicle, int arg) {
        int interaction = arg >>> 24;
        int action = arg >>> 16 & 0xFF;
        VehicleHits hits = vehicle.hits();
        if (vehicle.isDestroyed() || hits == null || interaction >= vehicle.interactions().size()
                || player.isPassenger() && player.getVehicle() != vehicle || !VehicleLock.check(vehicle, player)) {
            return false;
        }
        Interaction entry = vehicle.interactions().get(interaction);
        if (action >= entry.actions().size() || player.isPassenger() && !entry.actions().get(action).riders()) {
            return false;
        }
        for (int node : entry.nodes()) {
            Vec3 at = hits.origin(node);
            if (hits.probeReaches(player.getEyePosition(), at, VehicleHits.NONE)) {
                return entry.actions().get(action).apply(new InteractionContext(player, vehicle, entry, at,
                        part -> Component.literal(part.getId())), arg & 0xFFFF);
            }
        }
        return false;
    }
}
