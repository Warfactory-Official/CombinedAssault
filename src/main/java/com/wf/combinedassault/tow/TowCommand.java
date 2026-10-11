package com.wf.combinedassault.tow;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.List;
import java.util.Locale;

/** Console coupling for testing: exact hitch + mode (the dashboard key can't), plus a per-coupling strain report when a trial run ends wrong. */
public final class TowCommand {

    private TowCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("tow")
                .then(Commands.literal("couple")
                        .then(Commands.argument("tractor", EntityArgument.entity())
                                .then(Commands.argument("towed", EntityArgument.entity())
                                        .executes(context -> couple(context.getSource(),
                                                vehicle(context.getSource(), EntityArgument.getEntity(context, "tractor")),
                                                vehicle(context.getSource(), EntityArgument.getEntity(context, "towed")),
                                                null, null))
                                        .then(Commands.argument("hitch", StringArgumentType.word())
                                                .executes(context -> couple(context.getSource(),
                                                        vehicle(context.getSource(), EntityArgument.getEntity(context, "tractor")),
                                                        vehicle(context.getSource(), EntityArgument.getEntity(context, "towed")),
                                                        StringArgumentType.getString(context, "hitch"), null))
                                                .then(Commands.argument("mode", StringArgumentType.word())
                                                        .executes(context -> couple(context.getSource(),
                                                                vehicle(context.getSource(), EntityArgument.getEntity(context, "tractor")),
                                                                vehicle(context.getSource(), EntityArgument.getEntity(context, "towed")),
                                                                StringArgumentType.getString(context, "hitch"),
                                                                StringArgumentType.getString(context, "mode"))))))))
                .then(Commands.literal("uncouple")
                        .then(Commands.argument("towed", EntityArgument.entity())
                                .executes(context -> uncouple(context.getSource(),
                                        vehicle(context.getSource(), EntityArgument.getEntity(context, "towed"))))))
                .then(Commands.literal("trial")
                        .then(Commands.literal("report")
                                .executes(context -> report(context.getSource())))
                        .then(Commands.literal("stop")
                                .executes(context -> {
                                    com.wf.combinedassault.tow.trial.TowTrials.abandon();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("trial abandoned"), true);
                                    return 1;
                                }))
                        .then(Commands.argument("course", StringArgumentType.word())
                                .executes(context -> trial(context.getSource(),
                                        StringArgumentType.getString(context, "course"), 44, 1.0, 0.35, "hitch", null))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(20, 200))
                                        .then(Commands.argument("laps", DoubleArgumentType.doubleArg(0.1, 20))
                                                .executes(context -> trial(context.getSource(),
                                                        StringArgumentType.getString(context, "course"),
                                                        IntegerArgumentType.getInteger(context, "radius"),
                                                        DoubleArgumentType.getDouble(context, "laps"),
                                                        0.35, "hitch", null))
                                                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.05, 2))
                                                        .executes(context -> trial(context.getSource(),
                                                                StringArgumentType.getString(context, "course"),
                                                                IntegerArgumentType.getInteger(context, "radius"),
                                                                DoubleArgumentType.getDouble(context, "laps"),
                                                                DoubleArgumentType.getDouble(context, "speed"), "hitch", null))
                                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                                .executes(context -> trial(context.getSource(),
                                                                        StringArgumentType.getString(context, "course"),
                                                                        IntegerArgumentType.getInteger(context, "radius"),
                                                                        DoubleArgumentType.getDouble(context, "laps"),
                                                                        DoubleArgumentType.getDouble(context, "speed"),
                                                                        StringArgumentType.getString(context, "mode"), null))
                                                                .then(Commands.argument("vehicle", ResourceLocationArgument.id())
                                                                        .executes(context -> trial(context.getSource(),
                                                                                StringArgumentType.getString(context, "course"),
                                                                                IntegerArgumentType.getInteger(context, "radius"),
                                                                                DoubleArgumentType.getDouble(context, "laps"),
                                                                                DoubleArgumentType.getDouble(context, "speed"),
                                                                                StringArgumentType.getString(context, "mode"),
                                                                                ResourceLocationArgument.getId(context, "vehicle"))))))))))
                .then(Commands.literal("status")
                        .then(Commands.argument("vehicle", EntityArgument.entity())
                                .executes(context -> status(context.getSource(),
                                        vehicle(context.getSource(), EntityArgument.getEntity(context, "vehicle"))))));
    }

    private static AbstractVehicle vehicle(CommandSourceStack source, Entity entity)
            throws CommandSyntaxException {
        if (entity instanceof AbstractVehicle vehicle) {
            return vehicle;
        }
        throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                Component.literal(entity.getName().getString() + " is not a vehicle")).create();
    }

    private static int couple(CommandSourceStack source, AbstractVehicle tractor, AbstractVehicle towed,
                              @Nullable String hitchId, @Nullable String modeName) {
        // First hitch x mode both ends support; none => first named pair, so check() names the refusal.
        HitchInfo hitch = null;
        TowMode mode = null;
        for (HitchInfo candidate : tractor.towing.hitches) {
            if (hitchId != null && !candidate.id.equals(hitchId)) {
                continue;
            }
            for (TowMode candidateMode : TowMode.values()) {
                if (modeName != null && !candidateMode.lowerName().equals(modeName)
                        || modeName == null && !candidate.supports(candidateMode)) {
                    continue;
                }
                if (hitch == null || !fits(hitch, mode, towed) && fits(candidate, candidateMode, towed)) {
                    hitch = candidate;
                    mode = candidateMode;
                }
            }
        }
        if (hitch == null) {
            source.sendFailure(Component.literal("no such hitch/mode: " + hitchId + " " + modeName));
            return 0;
        }
        TowMode coupled = mode;
        HitchInfo used = hitch;
        TowLinks.Refusal refusal = TowLinks.check(tractor, towed, hitch, mode, false);
        if (refusal != TowLinks.Refusal.NONE) {
            source.sendFailure(Component.literal("refused: " + refusal.name().toLowerCase(Locale.ROOT)));
            return 0;
        }
        if (!TowLinks.couple(tractor, towed, hitch, mode)) {
            source.sendFailure(Component.literal("coupling failed"));
            return 0;
        }
        double gap = TowLinks.hitchPoint(tractor, used).distanceTo(TowLinks.attachPoint(towed, coupled));
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "coupled on %s by %s, %.2f blocks to close", used.id, coupled.lowerName(), gap))
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    private static boolean fits(HitchInfo hitch, TowMode mode, AbstractVehicle towed) {
        return hitch.supports(mode) && towed.towing.towable.supports(mode);
    }

    private static int uncouple(CommandSourceStack source, AbstractVehicle towed) {
        if (!TowLinks.uncouple(towed)) {
            source.sendFailure(Component.literal("nothing is towing that"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("uncoupled").withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    /**
     * Drives a lap of {@code course}; verdict reported to the caller (4 lines + row-per-tick file name), not just logged.
     * @param vehicleId solo only: vehicle to drive; null => harness's default tractor. Paired modes always use the fixed test pair.
     */
    private static int trial(CommandSourceStack source, String course, int radius, double laps,
                             double speed, String modeName, @Nullable ResourceLocation vehicleId) {
        boolean solo = "solo".equals(modeName);
        TowMode mode = TowMode.CABLE.lowerName().equals(modeName) ? TowMode.CABLE : TowMode.HITCH;
        ResourceLocation tractorId = solo && vehicleId != null
                ? vehicleId : com.wf.combinedassault.CombinedAssault.modLocation("m983");
        String failure = com.wf.combinedassault.tow.trial.TowTrials.start(source.getLevel(),
                source.getPosition(), course, radius, laps, speed, tractorId,
                solo ? null
                        : mode == TowMode.CABLE
                                ? com.wf.combinedassault.CombinedAssault.modLocation("m983")
                                : com.wf.combinedassault.CombinedAssault.modLocation("mim104"),
                mode);
        if (failure != null) {
            source.sendFailure(Component.literal(failure));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("trial started on " + course)
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    /** Running trial's progress, or the last one's verdict. */
    private static int report(CommandSourceStack source) {
        for (String line : com.wf.combinedassault.tow.trial.TowTrials.report()) {
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int status(CommandSourceStack source, AbstractVehicle vehicle) {
        AbstractVehicle head = TowLinks.head(vehicle);
        List<AbstractVehicle> train = TowLinks.train(head);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "train of %d, load behind the head %.2f", train.size(), TowLinks.loadBehind(head)))
                .withStyle(ChatFormatting.GRAY), false);
        for (AbstractVehicle member : train) {
            TowLink link = member.towLink();
            String line;
            if (link == null) {
                line = String.format(Locale.ROOT, "  %s: the head, %.2f b/t",
                        member.getVehicleId().getPath(), member.getDeltaMovement().length());
            } else {
                Vec3 coupling = TowLinks.couplingPoint(member);
                HitchInfo hitch = link.hitch();
                double gap = hitch == null ? Double.NaN
                        : TowLinks.hitchPoint(link.tractor(), hitch).distanceTo(coupling);
                line = String.format(Locale.ROOT,
                        "  %s: on %s by %s, gap %.2f, strain %.2f for %d ticks, articulation %.1f",
                        member.getVehicleId().getPath(), link.hitchId(), link.mode().lowerName(),
                        gap, link.strain(), link.strainTicks(),
                        net.minecraft.util.Mth.wrapDegrees(member.getYRot() - link.tractor().getYRot()));
            }
            String rendered = line;
            source.sendSuccess(() -> Component.literal(rendered).withStyle(ChatFormatting.GRAY), false);
        }
        return train.size();
    }

}
