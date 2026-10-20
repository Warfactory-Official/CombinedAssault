package com.wf.combinedassault.barrel;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.wflib.barrel.BarrelState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.function.Consumer;

/**
 * {@code barrel <vehicle>}: every barrelled weapon's temp / wear / jam. {@code barrel <vehicle> <temp> [jammed]}: force
 * temp (wear kept), optionally the jam.
 */
public final class BarrelCommand {

    private BarrelCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("barrel").then(Commands.argument("vehicle", EntityArgument.entity())
                .executes(c -> each(c, b -> {
                }))
                .then(Commands.argument("temp", FloatArgumentType.floatArg(BarrelState.AMBIENT))
                        .executes(c -> each(c, b -> heat(b, FloatArgumentType.getFloat(c, "temp"))))
                        .then(Commands.argument("jammed", BoolArgumentType.bool())
                                .executes(c -> each(c, b -> {
                                    heat(b, FloatArgumentType.getFloat(c, "temp"));
                                    b.setJammed(BoolArgumentType.getBool(c, "jammed"));
                                })))));
    }

    private static void heat(GunBarrel b, float temp) {
        BarrelState s = b.state();
        b.setState(new BarrelState(Math.min(b.spec().max(), temp), s.at(), s.wear()));
    }

    private static int each(CommandContext<CommandSourceStack> ctx, Consumer<GunBarrel> action)
            throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "vehicle");
        if (!(entity instanceof AbstractVehicle vehicle)) {
            throw EntityArgument.NO_ENTITIES_FOUND.create();
        }
        StringBuilder out = new StringBuilder("barrels");
        int n = 0;
        for (PartUnit<?> unit : vehicle.getPartUnits()) {
            if (unit instanceof WeaponUnit station) {
                for (AbstractVehicleWeapon<?> weapon : station.indexedWeapons) {
                    GunBarrel b = weapon.barrel();
                    if (b == null) {
                        continue;
                    }
                    action.accept(b);
                    BarrelState s = b.state();
                    out.append(" | ").append(station.getId()).append(' ').append(weapon.getSerializeId())
                            .append(" temp=").append(Math.round(s.temp())).append(" wear=")
                            .append(String.format("%.3f", s.wear())).append(" jammed=").append(b.jammed());
                    n++;
                }
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
        return n;
    }
}
