package com.wf.combinedassault.naval;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.ShipVehicle;

import java.util.Locale;
import java.util.Optional;

/** {@code /combinedassault naval ...}: spawn, read, drive any vehicle without a player; what RCON needs, nothing else. */
public final class NavalCommand {

    private static final double SEARCH_RADIUS = 256.0;

    private NavalCommand() {
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
                                                        FloatArgumentType.getFloat(context, "yaw")))))))
                .then(Commands.literal("status").executes(NavalCommand::status)
                        .then(Commands.argument("id", IntegerArgumentType.integer(0)).executes(NavalCommand::status)))
                .then(Commands.literal("helm")
                        .then(Commands.argument("throttle", DoubleArgumentType.doubleArg(-1, 1))
                                .then(Commands.argument("rudder", DoubleArgumentType.doubleArg(-1, 1))
                                        .executes(NavalCommand::helm)
                                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                                .executes(NavalCommand::helm)))))
                .then(Commands.literal("depth")
                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0))
                                .executes(NavalCommand::depth)
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                        .executes(NavalCommand::depth))))
                .then(Commands.literal("fire")
                        .then(Commands.argument("preset", ResourceLocationArgument.id())
                                .then(Commands.argument("from", Vec3Argument.vec3())
                                        .then(Commands.argument("target", Vec3Argument.vec3())
                                                .executes(NavalCommand::fire)))))
                .then(Commands.literal("fuel").executes(NavalCommand::fuel)
                        .then(Commands.argument("id", IntegerArgumentType.integer(0)).executes(NavalCommand::fuel)))
                .then(com.wf.combinedassault.naval.trial.TrialCommand.get())
                .then(Commands.literal("engine")
                        .then(Commands.literal("on").executes(context -> engine(context, true))
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                        .executes(context -> engine(context, true))))
                        .then(Commands.literal("off").executes(context -> engine(context, false))
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                        .executes(context -> engine(context, false)))));
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

    private static int status(CommandContext<CommandSourceStack> context) {
        ShipVehicle ship = target(context);
        if (ship == null) {
            return 0;
        }
        ShipHydrodynamics model = ship.model();
        ShipHydrodynamics.Environment env = ship.environment();
        Vec3 v = ship.getDeltaMovement();
        String line = String.format(Locale.ROOT,
                "ship id=%d pos=%.2f %.2f %.2f yaw=%.2f pitch=%.2f roll=%.2f "
                        + "throttle=%.2f rudder=%.2f surge=%.4f sway=%.4f yawRate=%.4f speed=%.4f "
                        + "afloat=%b surface=%.2f keel=%.2f depth=%.2f set=%.2f shallow=%b aground=%b "
                        + "engine=%b power=%.0f health=%.0f/%.0f tickCost=%.1fus",
                ship.getId(), ship.getX(), ship.getY(), ship.getZ(), ship.getYRot(), ship.getXRot(), ship.getZRot(),
                model.throttle, model.rudder, model.surge, model.sway, model.yawRate, v.length(),
                env.afloat, env.surfaceY, env.keelClearance, model.depth, model.depthSetting, model.shallow,
                ship.aground(), ship.isEngineOn(), ship.getPower(), ship.getHealth(), ship.getMaxHealth(),
                ship.tickCostNanos() / 1000.0);
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int helm(CommandContext<CommandSourceStack> context) {
        ShipVehicle ship = target(context);
        if (ship == null) {
            return 0;
        }
        double throttle = DoubleArgumentType.getDouble(context, "throttle");
        double rudder = DoubleArgumentType.getDouble(context, "rudder");
        ship.setHelm(throttle, rudder);
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "helm throttle=%.2f rudder=%.2f", throttle, rudder)), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int depth(CommandContext<CommandSourceStack> context) {
        ShipVehicle ship = target(context);
        if (ship == null) {
            return 0;
        }
        double blocks = DoubleArgumentType.getDouble(context, "blocks");
        ship.setDepthSetting(blocks);
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "depth set %.1f", blocks)), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int fire(CommandContext<CommandSourceStack> context) {
        ResourceLocation preset = context.getArgument("preset", ResourceLocation.class);
        Vec3 from = Vec3Argument.getVec3(context, "from");
        Vec3 target = Vec3Argument.getVec3(context, "target");
        if (!com.wf.combinedassault.compat.wflib.WfLibCompat.launchMissile(
                context.getSource().getLevel(), preset, from, target)) {
            context.getSource().sendFailure(Component.literal("could not launch " + preset + " (WFLib absent, or no such preset)"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("launched " + preset), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int fuel(CommandContext<CommandSourceStack> context) {
        ShipVehicle ship = target(context);
        if (ship == null) {
            return 0;
        }
        ship.setEnergy(ship.energyInfo.capacityUnits());
        context.getSource().sendSuccess(() -> Component.literal("tank filled"), true);
        return Command.SINGLE_SUCCESS;
    }

    /** Any vehicle by {@code id}; else the ship {@link #target} finds. */
    private static int engine(CommandContext<CommandSourceStack> context, boolean on) {
        AbstractVehicle vehicle = null;
        try {
            if (context.getSource().getLevel().getEntity(IntegerArgumentType.getInteger(context, "id")) instanceof AbstractVehicle found) {
                vehicle = found;
            }
        } catch (IllegalArgumentException absent) {
            // No id given.
        }
        if (vehicle == null) {
            vehicle = target(context);
        }
        if (vehicle == null) {
            return 0;
        }
        vehicle.toggleEngine(on);
        context.getSource().sendSuccess(() -> Component.literal("engine " + (on ? "on" : "off")), true);
        return Command.SINGLE_SUCCESS;
    }

    /** The ship named by an {@code id} argument, else the ridden one, else the nearest to the source within range. */
    @Nullable
    private static ShipVehicle target(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Integer id = null;
        try {
            id = IntegerArgumentType.getInteger(context, "id");
        } catch (IllegalArgumentException absent) {
            // No id given.
        }
        if (id != null) {
            if (source.getLevel().getEntity(id) instanceof ShipVehicle ship) {
                return ship;
            }
            source.sendFailure(Component.literal("no ship with id " + id));
            return null;
        }
        Entity entity = source.getEntity();
        if (entity != null && entity.getVehicle() instanceof ShipVehicle ship) {
            return ship;
        }
        Vec3 at = source.getPosition();
        AABB box = AABB.ofSize(at, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2);
        ShipVehicle best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : source.getLevel().getEntities((Entity) null, box, e -> e instanceof ShipVehicle)) {
            double distance = candidate.distanceToSqr(at);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (ShipVehicle) candidate;
            }
        }
        if (best == null) {
            source.sendFailure(Component.literal("no ship within " + (int) SEARCH_RADIUS + " blocks"));
        }
        return best;
    }

}
