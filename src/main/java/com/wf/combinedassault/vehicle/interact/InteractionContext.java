package com.wf.combinedassault.vehicle.interact;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.function.Function;

/**
 * @param at       interaction node, world, current pose
 * @param partName display name of a part (client: pack name / lang; server: id)
 */
public record InteractionContext(Player player, AbstractVehicle vehicle, Interaction interaction, Vec3 at,
                                 Function<PartUnit<?>, Component> partName) {
}
