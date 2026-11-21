package com.wf.combinedassault.debug;

import com.mojang.brigadier.context.CommandContext;
import com.wf.combinedassault.render.util.CollisionMeshRenderer;
import com.wf.wflib.core.api.client.DebugGate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Client side of {@code /combinedassault}: unknown to this tree => the server's. */
public final class DebugCommands {

    private DebugCommands() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        var collision = Commands.literal("collision").requires(source -> DebugGate.allowed())
                .executes(context -> collision(context, CollisionMeshRenderer.next()));
        for (CollisionMeshRenderer.Mode mode : CollisionMeshRenderer.Mode.values()) {
            collision.then(Commands.literal(mode.name().toLowerCase(java.util.Locale.ROOT))
                    .executes(context -> collision(context, mode)));
        }
        event.getDispatcher().register(Commands.literal("combinedassault")
                .then(collision)
                .then(Commands.literal("telemetry")
                        .executes(context -> telemetry(context, !PhysicsTelemetry.enabled()))
                        .then(Commands.literal("on").executes(context -> telemetry(context, true)))
                        .then(Commands.literal("off").executes(context -> telemetry(context, false)))));
    }

    private static int collision(CommandContext<CommandSourceStack> context, CollisionMeshRenderer.Mode mode) {
        CollisionMeshRenderer.setMode(mode);
        context.getSource().sendSuccess(() -> Component.literal("collision overlay: " + mode.label()), false);
        return 1;
    }

    private static int telemetry(CommandContext<CommandSourceStack> context, boolean on) {
        PhysicsTelemetry.set(on);
        context.getSource().sendSuccess(() -> Component.literal("physics telemetry " + (on ? "on" : "off")), false);
        return 1;
    }
}
