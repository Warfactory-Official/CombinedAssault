package com.wf.combinedassault.ballistics.addon;

import com.wf.wflib.probe.ProbeActions;
import com.wf.wflib.probe.ProbeInventory;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.SystemRepair;

import java.util.function.Predicate;

/** Install, replace, remove, repair: the server half, for the probe and for a right-click with a kit. */
public final class AddonActions {

    public static final ResourceLocation INSTALL = id("addon_install");
    public static final ResourceLocation REPLACE = id("addon_replace");
    public static final ResourceLocation REMOVE = id("addon_remove");
    public static final ResourceLocation REPAIR = id("addon_repair");
    /** One broken bar rewelded. */
    public static final TagKey<Item> SLAT_STOCK = TagKey.create(Registries.ITEM, id("slat_repair_stock"));

    private static final double REACH_SQR = (AddonTargeting.REACH + 1) * (AddonTargeting.REACH + 1);

    private AddonActions() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, path);
    }

    /** {@code arg = mount | fit << 8}. */
    public static int arg(int mount, AddonFit fit) {
        return mount | fit.ordinal() << 8;
    }

    public static void register() {
        ProbeActions.registerEntity(INSTALL, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && install(player, vehicle, arg & 0xFF, AddonFit.byId(arg >> 8)));
        ProbeActions.registerEntity(REPLACE, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && replace(player, vehicle, arg & 0xFF));
        ProbeActions.registerEntity(REMOVE, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && remove(player, vehicle, arg & 0xFF));
        ProbeActions.registerEntity(REPAIR, (player, entity, arg) -> entity instanceof AbstractVehicle vehicle
                && repair(player, vehicle, arg & 0xFF));
    }

    private static boolean inReach(Player player, AbstractVehicle vehicle, int mount) {
        AddonArmorLayer layer = vehicle.addonArmor();
        return mount >= 0 && mount < layer.mounts().size()
                && layer.pose(vehicle, mount).centre().distanceToSqr(player.getEyePosition()) <= REACH_SQR;
    }

    public static boolean install(ServerPlayer player, AbstractVehicle vehicle, int mount, AddonFit fit) {
        if (!inReach(player, vehicle, mount) || fit == AddonFit.NONE) {
            return false;
        }
        AddonArmorLayer layer = vehicle.addonArmor();
        AddonMount m = layer.mounts().get(mount);
        AddonMountState state = layer.state(mount);
        if (!state.isEmpty() || fit.kind != m.info.kind) {
            return false;
        }
        int cost = m.installCost(fit);
        if (!take(player, stack -> stack.is(fit.item()), cost)) {
            refuse(player, Component.translatable("addon_armor.combinedassault.need", cost, fit.item().getDescription()));
            return false;
        }
        state.install(m, fit);
        layer.changed();
        sound(player, vehicle, mount, fit.kind == AddonKind.ERA ? SoundEvents.ARMOR_EQUIP_IRON.value()
                : SoundEvents.CHAIN_PLACE);
        return true;
    }

    /** ERA: fill spent cells, as many as there are bricks for. */
    public static boolean replace(ServerPlayer player, AbstractVehicle vehicle, int mount) {
        if (!inReach(player, vehicle, mount)) {
            return false;
        }
        AddonArmorLayer layer = vehicle.addonArmor();
        AddonMount m = layer.mounts().get(mount);
        AddonMountState state = layer.state(mount);
        if (state.fit.kind != AddonKind.ERA) {
            return false;
        }
        int filled = 0;
        for (int cell = 0; cell < m.cells(); cell++) {
            if (state.isLive(cell)) {
                continue;
            }
            if (!take(player, stack -> stack.is(state.fit.item()), 1)) {
                break;
            }
            state.live |= 1L << cell;
            filled++;
        }
        if (filled == 0) {
            refuse(player, Component.translatable("addon_armor.combinedassault.need", 1, state.fit.item().getDescription()));
            return false;
        }
        layer.changed();
        sound(player, vehicle, mount, SoundEvents.ARMOR_EQUIP_IRON.value());
        return true;
    }

    /** Live bricks come back; spent ones are scrap. Slat comes back as far as it is still whole. */
    public static boolean remove(ServerPlayer player, AbstractVehicle vehicle, int mount) {
        if (!inReach(player, vehicle, mount)) {
            return false;
        }
        AddonArmorLayer layer = vehicle.addonArmor();
        AddonMount m = layer.mounts().get(mount);
        AddonMountState state = layer.state(mount);
        if (state.isEmpty()) {
            return false;
        }
        int back = state.fit.kind == AddonKind.ERA ? state.liveCells()
                : (int) Math.floor(m.slatCost() * state.slatCondition() + 1.0e-4);
        if (!player.getAbilities().instabuild && back > 0) {
            ItemStack stack = new ItemStack(state.fit.item(), back);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        state.clear();
        layer.changed();
        sound(player, vehicle, mount, SoundEvents.CHAIN_BREAK);
        return true;
    }

    /** Slat only: a carried {@code #hull_tool} straightens bent bars; a broken one also costs one bar of stock. */
    public static boolean repair(ServerPlayer player, AbstractVehicle vehicle, int mount) {
        if (!inReach(player, vehicle, mount)) {
            return false;
        }
        AddonArmorLayer layer = vehicle.addonArmor();
        AddonMountState state = layer.state(mount);
        ItemStack tool = SystemRepair.tool(player, ComponentKind.HULL);
        if (state.fit.kind != AddonKind.SLAT || (tool.isEmpty() && !player.getAbilities().instabuild)) {
            return false;
        }
        int repaired = 0;
        boolean lacking = false;
        for (int k = 0; k < state.bars.length; k++) {
            if (state.bars[k] >= AddonMountState.BENT) {
                continue;
            }
            if (state.bars[k] <= 0 && !take(player, stack -> stack.is(SLAT_STOCK), 1)) {
                lacking = true;
                continue;
            }
            state.bars[k] = 1.0f;
            repaired++;
        }
        if (lacking) {
            refuse(player, Component.translatable("addon_armor.combinedassault.need_stock"));
        }
        if (repaired == 0) {
            return false;
        }
        if (!tool.isEmpty() && !player.getAbilities().instabuild) {
            tool.hurtAndBreak(repaired, player.serverLevel(), player, item -> {});
        }
        layer.changed();
        sound(player, vehicle, mount, SoundEvents.ANVIL_USE);
        return true;
    }

    private static boolean take(Player player, Predicate<ItemStack> matches, int count) {
        return ProbeInventory.take(player, matches, count);
    }

    /** Units of {@code item} the player carries; creative counts as plenty. */
    public static int carried(Player player, Item item) {
        if (player.getAbilities().instabuild) {
            return Integer.MAX_VALUE;
        }
        return player.getInventory().countItem(item);
    }

    private static void refuse(Player player, Component why) {
        player.displayClientMessage(why.copy().withStyle(ChatFormatting.RED), true);
    }

    private static void sound(Player player, AbstractVehicle vehicle, int mount, net.minecraft.sounds.SoundEvent sound) {
        Vec3 at = vehicle.addonArmor().pose(vehicle, mount).centre();
        player.level().playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, 1.0f, 1.0f);
    }
}
