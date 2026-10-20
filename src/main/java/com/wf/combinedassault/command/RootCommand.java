package com.wf.combinedassault.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.wf.combinedassault.ballistics.BallisticsCommand;
import com.wf.combinedassault.command.sub.CamoCommand;
import com.wf.combinedassault.indirect.IndirectFireCommand;
import com.wf.combinedassault.command.sub.DebugCommand;
import com.wf.combinedassault.command.sub.PhysicsTraceCommand;
import com.wf.combinedassault.command.sub.ReloadCommand;
import com.wf.combinedassault.command.sub.SummonCommand;

public class RootCommand {

    private static final String ROOT_NAME = "combinedassault";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT_NAME)
                .requires((source -> source.hasPermission(2)));
        root.then(DebugCommand.get());
        root.then(PhysicsTraceCommand.get());
        root.then(CamoCommand.get());
        root.then(BallisticsCommand.get());
        root.then(IndirectFireCommand.get());
        root.then(SummonCommand.get());
        root.then(com.wf.combinedassault.naval.NavalCommand.get());
        root.then(com.wf.combinedassault.tow.TowCommand.get());
        root.then(ReloadCommand.get());
        root.then(com.wf.combinedassault.vehicle.crew.CrewCommand.get());
        root.then(com.wf.combinedassault.barrel.BarrelCommand.get());
        dispatcher.register(root);
    }

}
