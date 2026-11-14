package com.wf.combinedassault.vehicle.interact;

import com.google.gson.JsonObject;
import com.wf.combinedassault.energy.FuelTransfer;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import net.minecraft.network.chat.Component;

import java.util.List;

/** {@code combinedassault:refuel}: fuel cap node; rows = in-range vehicles with a cargo tank, or stop. No params. */
final class RefuelAction {

    private static final int STOP = 0xFFFF;

    private RefuelAction() {
    }

    static InteractionAction create(JsonObject params, InteractionTypes.Def def) {
        if (!params.keySet().isEmpty()) {
            throw new IllegalArgumentException("unknown key " + params.keySet().iterator().next());
        }
        return new InteractionAction() {
            @Override
            public void offer(InteractionContext ctx, Rows out) {
                AbstractVehicle vehicle = ctx.vehicle();
                if (vehicle.refuel != null) {
                    out.add(STOP, Component.translatable("access.combinedassault.refuel_stop"), null);
                    return;
                }
                List<AbstractVehicle> sources = FuelTransfer.providers(vehicle);
                for (int i = 0; i < sources.size(); i++) {
                    out.add(i, Component.translatable("access.combinedassault.refuel", sources.get(i).getDisplayName()), null);
                }
            }

            @Override
            public boolean available(InteractionContext ctx) {
                return !ctx.vehicle().isElectric();
            }

            @Override
            public boolean apply(InteractionContext ctx, int arg) {
                AbstractVehicle vehicle = ctx.vehicle();
                if (arg == STOP) {
                    boolean was = vehicle.refuel != null;
                    FuelTransfer.stop(vehicle);
                    return was;
                }
                List<AbstractVehicle> sources = FuelTransfer.providers(vehicle);
                return arg < sources.size() && FuelTransfer.start(vehicle, ctx.player(), sources.get(arg));
            }
        };
    }
}
