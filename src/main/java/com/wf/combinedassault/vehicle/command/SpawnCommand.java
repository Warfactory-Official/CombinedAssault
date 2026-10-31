package com.wf.combinedassault.vehicle.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import java.util.Optional;
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

/** {@code /combinedassault naval spawn}: any vehicle, no player. Literal shared with the naval subcommand (Brigadier merges); harness scripts use this path. */
public final class SpawnCommand {

    private SpawnCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("naval")
                .requires(stack -> stack.hasPermission(2))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("vehicle_id", ResourceLocationArgument.id())
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(context -> spawn(context, 0))
                                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                                                .executes(context -> spawn(context,
                                                        FloatArgumentType.getFloat(context, "yaw")))))));
    }

    private static int spawn(CommandContext<CommandSourceStack> context, float yaw) {
        ResourceLocation id = context.getArgument("vehicle_id", ResourceLocation.class);
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            context.getSource().sendFailure(Component.literal("no vehicle " + id));
            return 0;
        }
        ServerLevel level = context.getSource().getLevel();
        AbstractVehicle vehicle = data.get().construct(level, pos, 0, yaw);
        vehicle.setDisplayId(id);
        if (!level.addFreshEntity(vehicle)) {
            context.getSource().sendFailure(Component.literal("could not add " + id));
            return 0;
        }
        // Test spawn arrives fuelled. Pack energy_info lands in onAddedToLevel; before it => default 80.
        vehicle.setEnergy(vehicle.energyInfo.capacityUnits());
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "spawned %s id=%d at %.1f %.1f %.1f yaw %.1f", id, vehicle.getId(), pos.x, pos.y, pos.z, yaw)), true);
        return Command.SINGLE_SUCCESS;
    }
}
