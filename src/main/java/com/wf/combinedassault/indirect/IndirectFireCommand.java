package com.wf.combinedassault.indirect;

import com.wf.wflib.round.api.Rounds;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.dynamics.PhysicsEngine;

import java.util.Locale;

/** Solver inspectable without a gun: separates a bad solution from a mount that won't elevate that far. */
public final class IndirectFireCommand {

    private IndirectFireCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("indirect")
                .then(Commands.literal("solve")
                        .then(Commands.argument("muzzle_speed", DoubleArgumentType.doubleArg(0.001))
                                .then(Commands.argument("drag", DoubleArgumentType.doubleArg(0, 1))
                                        .then(Commands.argument("target", Vec3Argument.vec3())
                                                .executes(context -> solve(context.getSource(),
                                                        DoubleArgumentType.getDouble(context, "muzzle_speed"),
                                                        DoubleArgumentType.getDouble(context, "drag"),
                                                        Vec3Argument.getVec3(context, "target")))))))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())));
    }

    private static int solve(CommandSourceStack source, double muzzleSpeed, double drag, Vec3 target) {
        RoundSpec spec = new RoundSpec(muzzleSpeed, drag, PhysicsEngine.G, 20 * 60 * 10);
        Vec3 muzzle = source.getPosition();
        double reach = BallisticSolver.maxRange(spec);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "muzzle %.1f b/t  drag %.4f  max range %s",
                muzzleSpeed, drag, Double.isInfinite(reach) ? "unbounded" : String.format(Locale.ROOT, "%.0f", reach)))
                .withStyle(ChatFormatting.GRAY), false);
        boolean any = false;
        for (FireArc arc : FireArc.values()) {
            FireSolution solution = BallisticSolver.solve(spec, muzzle, target, arc);
            if (solution == null) {
                source.sendSuccess(() -> Component.literal(arc.id() + ": unreachable").withStyle(ChatFormatting.RED), false);
                continue;
            }
            any = true;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "%s: elevation %.3f  azimuth %.3f  range %.1f  flight %.1fs  apex %.0f  impact %.2f b/t",
                    arc.id(), solution.elevationDegrees(), solution.azimuthDegrees(), solution.range(),
                    solution.flightSeconds(), solution.apexHeight(), solution.impactSpeed()))
                    .withStyle(ChatFormatting.GREEN), false);
        }
        return any ? 1 : 0;
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int count = Rounds.count(level);
        source.sendSuccess(() -> Component.literal(count + " round(s) in flight in " + level.dimension().location())
                .withStyle(ChatFormatting.GRAY), false);
        return count;
    }

}
