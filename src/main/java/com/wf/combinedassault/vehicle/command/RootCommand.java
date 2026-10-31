package com.wf.combinedassault.vehicle.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.wf.wflib.core.api.OrderedRegistry;

import java.util.function.Supplier;

public class RootCommand {

    public static final OrderedRegistry<Supplier<LiteralArgumentBuilder<CommandSourceStack>>> SUBCOMMANDS =
            OrderedRegistry.create("combinedassault:subcommand");

    private static final String ROOT_NAME = "combinedassault";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT_NAME)
                .requires((source -> source.hasPermission(2)));
        SUBCOMMANDS.values().forEach(sub -> root.then(sub.get()));
        dispatcher.register(root);
    }

}
