package com.wf.combinedassault.naval.trial;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.naval.ShipVehicle;

import java.util.List;
import java.util.Optional;

/** One command: course, ship, crew, run - in order. */
public final class TrialCommand {

    private TrialCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("trial")
                .then(Commands.literal("run")
                        .then(Commands.argument("vehicle_id", ResourceLocationArgument.id())
                                .then(Commands.argument("course", StringArgumentType.word())
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .then(Commands.argument("heading", FloatArgumentType.floatArg())
                                                        .executes(context -> run(context, 0, null, 0))
                                                        .then(Commands.argument("crew", IntegerArgumentType.integer(0, 64))
                                                                .executes(context -> run(context,
                                                                        IntegerArgumentType.getInteger(context, "crew"), null, 0))
                                                                .then(Commands.argument("parked_id", ResourceLocationArgument.id())
                                                                        .then(Commands.argument("parked", IntegerArgumentType.integer(0, 16))
                                                                                .executes(context -> run(context,
                                                                                        IntegerArgumentType.getInteger(context, "crew"),
                                                                                        context.getArgument("parked_id", ResourceLocation.class),
                                                                                        IntegerArgumentType.getInteger(context, "parked")))))))))))
                .then(Commands.literal("status").executes(TrialCommand::status))
                .then(Commands.literal("report").executes(TrialCommand::report))
                .then(Commands.literal("stop").executes(TrialCommand::stop));
    }

    private static int run(CommandContext<CommandSourceStack> context, int crew,
                           ResourceLocation parkedId, int parked) {
        CommandSourceStack source = context.getSource();
        if (Trials.busy()) {
            source.sendFailure(Component.literal("a trial is already under way; stop it first"));
            return 0;
        }
        ResourceLocation id = context.getArgument("vehicle_id", ResourceLocation.class);
        String course = StringArgumentType.getString(context, "course");
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        float heading = FloatArgumentType.getFloat(context, "heading");
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            source.sendFailure(Component.literal("no vehicle " + id));
            return 0;
        }
        ServerLevel level = source.getLevel();
        AbstractVehicle vehicle = data.get().construct(level, pos, 0, heading);
        vehicle.setDisplayId(id);
        if (!(vehicle instanceof ShipVehicle ship)) {
            source.sendFailure(Component.literal(id + " is not a ship"));
            return 0;
        }
        if (!level.addFreshEntity(ship)) {
            source.sendFailure(Component.literal("could not add " + id));
            return 0;
        }
        ship.setEnergy(ship.energyInfo.capacityUnits());
        Trials.begin(level, ship, course, pos, heading, crew, parkedId, parked);
        source.sendSuccess(() -> Component.literal(
                "trial " + course + ": " + id + " id=" + ship.getId() + " spawned, cutting the course"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(Trials.status()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int report(CommandContext<CommandSourceStack> context) {
        return print(context, Trials.finished());
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        return print(context, Trials.stop());
    }

    private static int print(CommandContext<CommandSourceStack> context, List<String> lines) {
        for (String line : lines) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

}
