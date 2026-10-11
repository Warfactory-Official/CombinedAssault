package com.wf.combinedassault.vehicle.crew;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;

/** {@code crew <vehicle>}: seats, roles, driver, per-station ammo/reload. {@code crew arm <vehicle>}: creative ammo. */
public final class CrewCommand {

    private CrewCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("crew")
                .then(Commands.literal("arm").then(Commands.argument("vehicle", EntityArgument.entity())
                        .executes(CrewCommand::arm)))
                .then(Commands.argument("vehicle", EntityArgument.entity()).executes(CrewCommand::status));
    }

    private static AbstractVehicle vehicle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "vehicle");
        if (entity instanceof AbstractVehicle vehicle) {
            return vehicle;
        }
        throw EntityArgument.NO_ENTITIES_FOUND.create();
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        AbstractVehicle vehicle = vehicle(ctx);
        StringBuilder out = new StringBuilder("crew required=" + CrewRules.enabled()
                + " driver=" + name(vehicle.controlUnit.getOperator()) + " loader=" + CrewRules.loaderAboard(vehicle));
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            out.append(" | seat ").append(seat.seatIndex).append(' ').append(seat.partUnit.getId())
                    .append(' ').append(CrewRules.roles(seat)).append(' ')
                    .append(seat.passengerId == -1 ? "-" : name(vehicle.level().getEntity(seat.passengerId)));
        }
        for (PartUnit<?> unit : vehicle.getPartUnits()) {
            if (unit instanceof WeaponUnit station) {
                for (AbstractVehicleWeapon<?> weapon : station.indexedWeapons) {
                    out.append(" | ").append(station.getId()).append(' ').append(CrewRules.loading(station))
                            .append(' ').append(weapon.getSerializeId())
                            .append(" ammo=").append(weapon.getRemainAmmo())
                            .append(" reload=").append(weapon.getReloadTime());
                }
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
        return 1;
    }

    private static int arm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        AbstractVehicle vehicle = vehicle(ctx);
        for (int i = 0; i < vehicle.getItemStacks().size(); i++) {
            if (vehicle.getItemStacks().get(i).isEmpty()) {
                vehicle.getItemStacks().set(i, new ItemStack(AllItems.AMMO_CREATIVE.get()));
                ctx.getSource().sendSuccess(() -> Component.literal("armed"), false);
                return 1;
            }
        }
        ctx.getSource().sendFailure(Component.literal("hold full"));
        return 0;
    }

    private static String name(Entity entity) {
        return entity == null ? "-" : entity.getScoreboardName();
    }
}
