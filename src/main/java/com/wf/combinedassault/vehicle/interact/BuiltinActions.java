package com.wf.combinedassault.vehicle.interact;

import com.google.gson.JsonObject;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.AccessActions;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.weapon.kit.Hardpoints;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** {@code combinedassault:toggle} / {@code board} / {@code storage}. */
final class BuiltinActions {

    private static final String KEY = "access.combinedassault.";

    private BuiltinActions() {
    }

    /** {@code part}: door-like part id; replaces its generated door node. */
    static InteractionAction toggle(JsonObject params, InteractionTypes.Def def) {
        String part = string(params, "part", true, def);
        if (!def.partIds().contains(part)) {
            throw new IllegalArgumentException("part " + part + " not in " + def.partIds());
        }
        return new InteractionAction() {
            @Override
            public void offer(InteractionContext ctx, Rows out) {
                if (!(door(ctx.vehicle(), part) instanceof SwitchableUnit<?> door)) {
                    return;
                }
                AbstractVehicle vehicle = ctx.vehicle();
                Component label = Component.translatable(KEY + (part.equals(vehicle.towing.towable.stowedBy)
                        ? (door.isOn() ? "stow" : "deploy") : door.isOn() ? "close" : "open"));
                Component refusal = door.isDestroyed() ? Component.translatable("door.combinedassault.jammed")
                        : Hardpoints.doorRefusal(vehicle, door);
                out.add(0, label, refusal != null ? refusal : vehicle.openRefusal(door));
            }

            @Override
            public boolean apply(InteractionContext ctx, int arg) {
                PartUnit<?> door = door(ctx.vehicle(), part);
                return door != null && AccessActions.toggle((ServerPlayer) ctx.player(), ctx.vehicle(), door.getIndex());
            }

            @Override
            public boolean riders() {
                return true;
            }

            @Override
            public Set<String> claims() {
                return Set.of(part);
            }
        };
    }

    /**
     * {@code via}: entry id or ids (any open + in reach), absent => every seat {@code mayBoard}; {@code seats}: seat part
     * ids offered, absent => all. Rows: free seats with open hatch.
     */
    static InteractionAction board(JsonObject params, InteractionTypes.Def def) {
        keys(params, "via", "seats");
        List<String> via = strings(params, "via");
        List<String> seats = strings(params, "seats");
        for (String id : via) {
            if (!def.entries().contains(id)) {
                throw new IllegalArgumentException("via " + id + " not in entry " + def.entries());
            }
        }
        for (String id : seats) {
            if (!def.partIds().contains(id)) {
                throw new IllegalArgumentException("seat " + id + " not in " + def.partIds());
            }
        }
        return new InteractionAction() {
            @Override
            public void offer(InteractionContext ctx, Rows out) {
                for (int i = 0; i < ctx.vehicle().seats.size(); i++) {
                    if (boardable(ctx, i)) {
                        out.add(i, Component.translatable(KEY + "enter_seat",
                                ctx.partName().apply(ctx.vehicle().seats.get(i).partUnit)), null);
                    }
                }
            }

            @Override
            public boolean available(InteractionContext ctx) {
                for (int i = 0; i < ctx.vehicle().seats.size(); i++) {
                    if (boardable(ctx, i)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean apply(InteractionContext ctx, int arg) {
                AbstractVehicle vehicle = ctx.vehicle();
                return arg < vehicle.seats.size() && !vehicle.uav && !ctx.player().isShiftKeyDown() && boardable(ctx, arg)
                        && vehicle.board(ctx.player(), arg);
            }

            private boolean boardable(InteractionContext ctx, int index) {
                AbstractVehicle vehicle = ctx.vehicle();
                AbstractVehicle.Seat seat = vehicle.seats.get(index);
                if (seat.passengerId != -1 || seat.partUnit.isDetached() || !vehicle.hatchOpen(seat)
                        || !seats.isEmpty() && !seats.contains(seat.partUnit.getId())) {
                    return false;
                }
                if (via.isEmpty()) {
                    return vehicle.mayBoard(ctx.player(), seat);
                }
                for (String id : via) {
                    if (door(vehicle, id) instanceof SwitchableUnit<?> entry && vehicle.serves(entry, seat)
                            && vehicle.reaches(entry, ctx.player())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /** Opens the vehicle storage. */
    static InteractionAction storage(JsonObject params, InteractionTypes.Def def) {
        string(params, "", false, def);
        return new InteractionAction() {
            @Override
            public void offer(InteractionContext ctx, Rows out) {
                if (ctx.vehicle().hasContainer()) {
                    out.add(0, Component.translatable(KEY + "storage"), null);
                }
            }

            @Override
            public boolean apply(InteractionContext ctx, int arg) {
                if (!ctx.vehicle().hasContainer()) {
                    return false;
                }
                ctx.vehicle().openCustomInventoryScreen(ctx.player());
                return true;
            }
        };
    }

    @Nullable
    private static PartUnit<?> door(AbstractVehicle vehicle, String id) {
        PartUnit<?> part = vehicle.getPartUnit(id).orElse(null);
        return part == null || part.isDetached() || !AccessActions.isDoor(part) ? null : part;
    }

    private static void keys(JsonObject params, String... allowed) {
        for (String k : params.keySet()) {
            if (!List.of(allowed).contains(k)) {
                throw new IllegalArgumentException("unknown key " + k);
            }
        }
    }

    /** String or array of strings; absent => empty. */
    private static List<String> strings(JsonObject params, String key) {
        if (!params.has(key)) {
            return List.of();
        }
        if (params.get(key).isJsonPrimitive()) {
            return List.of(params.get(key).getAsString());
        }
        List<String> out = new ArrayList<>();
        params.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        return List.copyOf(out);
    }

    /** Reads {@code key} (empty: none expected); any other key => throw. */
    @Nullable
    private static String string(JsonObject params, String key, boolean required, InteractionTypes.Def def) {
        for (String k : params.keySet()) {
            if (!k.equals(key)) {
                throw new IllegalArgumentException("unknown key " + k);
            }
        }
        if (!params.has(key)) {
            if (required) {
                throw new IllegalArgumentException("needs " + key);
            }
            return null;
        }
        return params.get(key).getAsString();
    }
}
