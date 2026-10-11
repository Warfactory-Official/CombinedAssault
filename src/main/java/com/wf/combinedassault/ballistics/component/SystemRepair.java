package com.wf.combinedassault.ballistics.component;

import com.wf.wflib.probe.ProbeActions;
import com.wf.wflib.probe.ProbeInventory;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

/**
 * Probe action "repair one system one band", {@code arg} = {@link VehicleSystems.Cell#key()}. Timed
 * ({@link VehicleSystems.Cell#stepTicks}); tool durability paid and spare part taken at the end.
 *
 * <p>Tool: any carried stack in {@code #<code>_tool}; {@code #all_tool} is in every one.
 */
public final class SystemRepair {

    public static final ResourceLocation ACTION = CombinedAssault.modLocation("repair_system");
    private static final String KEY = "repair.combinedassault.";

    private SystemRepair() {
    }

    public static void register() {
        ProbeActions.registerTimedEntity(ACTION, new ProbeActions.TimedEntity() {
            @Override
            public int ticks(ServerPlayer player, Entity entity, int arg) {
                VehicleSystems.Cell cell = cell(entity, arg);
                if (cell == null) {
                    return 0;
                }
                Component refusal = refusal(player, cell);
                if (refusal != null) {
                    player.displayClientMessage(refusal.copy().withStyle(ChatFormatting.RED), true);
                    return 0;
                }
                return cell.stepTicks();
            }

            @Override
            public boolean holds(ServerPlayer player, Entity entity, int arg) {
                VehicleSystems.Cell cell = cell(entity, arg);
                return cell != null && refusal(player, cell) == null;
            }

            @Override
            public boolean perform(ServerPlayer player, Entity entity, int arg) {
                VehicleSystems.Cell cell = cell(entity, arg);
                return cell != null && complete(player, (AbstractVehicle) entity, cell);
            }
        });
    }

    @Nullable
    private static VehicleSystems.Cell cell(Entity entity, int key) {
        return entity instanceof AbstractVehicle vehicle && !vehicle.isDestroyed()
                ? VehicleSystems.cell(vehicle, key) : null;
    }

    /** Whether the tag has any members: an empty parts tag asks for nothing. */
    public static boolean needsParts(VehicleSystems.Cell cell) {
        return cell.failed() && BuiltInRegistries.ITEM.getTag(cell.kind().partsTag)
                .map(set -> set.size() > 0).orElse(false);
    }

    public static ItemStack tool(Player player, ComponentKind kind) {
        return ProbeInventory.find(player, stack -> stack.is(kind.toolTag) && stack.getDamageValue() < stack.getMaxDamage());
    }

    /** Null = can start; else why not. Same answer for the probe row (client) and the handler (server). */
    @Nullable
    public static Component refusal(Player player, VehicleSystems.Cell cell) {
        if (cell.ratio() >= 1) {
            return Component.translatable(KEY + "intact");
        }
        if (!player.getAbilities().instabuild && tool(player, cell.kind()).isEmpty()) {
            return Component.translatable(KEY + "need_tool", toolName(cell.kind()));
        }
        if (needsParts(cell) && ProbeInventory.count(player, stack -> stack.is(cell.kind().partsTag)) < 1) {
            return Component.translatable(KEY + "need_parts", partsName(cell.kind()));
        }
        return null;
    }

    public static Component toolName(ComponentKind kind) {
        return Component.translatable("tag.item." + CombinedAssault.MOD_ID + "." + kind.code + "_tool");
    }

    public static Component partsName(ComponentKind kind) {
        return Component.translatable("tag.item." + CombinedAssault.MOD_ID + "." + kind.code + "_parts");
    }

    /** Re-checked: the inventory may have changed during the timer. */
    static boolean complete(ServerPlayer player, AbstractVehicle vehicle, VehicleSystems.Cell cell) {
        if (refusal(player, cell) != null) {
            return false;
        }
        if (needsParts(cell) && !ProbeInventory.take(player, stack -> stack.is(cell.kind().partsTag), 1)) {
            return false;
        }
        if (!cell.step()) {
            return false;
        }
        if (!player.getAbilities().instabuild) {
            ItemStack tool = tool(player, cell.kind());
            tool.hurtAndBreak(cell.kind().durability, player.serverLevel(), player, item -> {});
        }
        vehicle.level().playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(), SoundEvents.ANVIL_USE,
                SoundSource.PLAYERS, 0.8f, 1.2f);
        return true;
    }
}
