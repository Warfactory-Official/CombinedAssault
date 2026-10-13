package com.wf.combinedassault.ballistics.component;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.ArmorRegions;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.probe.ProbeActions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Probe action: one {@link #BAND} of wear off every cell of a hull face, {@code arg} = face ordinal; hull tool. */
public final class ArmorRepair {

    public static final ResourceLocation ACTION = CombinedAssault.modLocation("repair_armor");
    public static final float BAND = 0.25f;
    public static final int TICKS = 60;
    static final int DURABILITY = 4;
    private static final String KEY = "repair.combinedassault.";

    private ArmorRepair() {
    }

    public static void register() {
        ProbeActions.registerTimedEntity(ACTION, new ProbeActions.TimedEntity() {
            @Override
            public int ticks(ServerPlayer player, Entity entity, int arg) {
                ArmorRegions.Face face = face(entity, arg);
                if (face == null) {
                    return 0;
                }
                Component refusal = refusal(player, (AbstractVehicle) entity, face);
                if (refusal != null) {
                    player.displayClientMessage(refusal.copy().withStyle(ChatFormatting.RED), true);
                    return 0;
                }
                return TICKS;
            }

            @Override
            public boolean holds(ServerPlayer player, Entity entity, int arg) {
                ArmorRegions.Face face = face(entity, arg);
                return face != null && refusal(player, (AbstractVehicle) entity, face) == null;
            }

            @Override
            public boolean perform(ServerPlayer player, Entity entity, int arg) {
                ArmorRegions.Face face = face(entity, arg);
                if (face == null || refusal(player, (AbstractVehicle) entity, face) != null
                        || !((AbstractVehicle) entity).armorRegions().repair(face, BAND)) {
                    return false;
                }
                if (!player.getAbilities().instabuild) {
                    ItemStack tool = SystemRepair.tool(player, ComponentKind.HULL);
                    tool.hurtAndBreak(DURABILITY, player.serverLevel(), player, item -> {});
                }
                entity.level().playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ANVIL_USE,
                        SoundSource.PLAYERS, 0.8f, 1.0f);
                return true;
            }
        });
    }

    @Nullable
    private static ArmorRegions.Face face(Entity entity, int arg) {
        return entity instanceof AbstractVehicle vehicle && !vehicle.isDestroyed() && arg >= 0
                && arg < ArmorRegions.Face.VALUES.length ? ArmorRegions.Face.VALUES[arg] : null;
    }

    /** Null = can start. Same answer for the probe row (client) and the handler (server). */
    @Nullable
    public static Component refusal(Player player, AbstractVehicle vehicle, ArmorRegions.Face face) {
        if (!vehicle.armorRegions().worn(face)) {
            return Component.translatable(KEY + "intact");
        }
        if (!player.getAbilities().instabuild && SystemRepair.tool(player, ComponentKind.HULL).isEmpty()) {
            return Component.translatable(KEY + "need_tool", SystemRepair.toolName(ComponentKind.HULL));
        }
        return null;
    }
}
