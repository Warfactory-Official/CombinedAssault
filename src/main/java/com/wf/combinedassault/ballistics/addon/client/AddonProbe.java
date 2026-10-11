package com.wf.combinedassault.ballistics.addon.client;

import com.wf.wflib.probe.ProbeAction;
import com.wf.wflib.probe.ProbeContext;
import com.wf.wflib.probe.ProbeElement;
import com.wf.wflib.probe.ProbeInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.wf.combinedassault.ballistics.addon.AddonActions;
import com.wf.combinedassault.ballistics.addon.AddonFit;
import com.wf.combinedassault.ballistics.addon.AddonKind;
import com.wf.combinedassault.ballistics.addon.AddonMount;
import com.wf.combinedassault.ballistics.addon.AddonMountState;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.SystemRepair;

/** Node panel of one ERA/slat mount. */
public final class AddonProbe {

    private static final String KEY = "addon_armor.combinedassault.";
    private static final int SCHEMATIC_WIDTH = 72;
    private static final int LIVE = 0xFF9A9A60;
    private static final int SPENT = 0xFF5A1E18;
    private static final int FRAME = 0xFF3A3F30;
    private static final int GOOD = 0xFF8A9A70;
    private static final int BENT = 0xFFC08A40;
    private static final int GONE = 0xFF301010;

    private AddonProbe() {
    }

    /** Mount {@code index} of {@code vehicle}: state, schematic, install / replace / repair / remove rows. */
    public static void fill(ProbeInfo info, ProbeContext ctx, AbstractVehicle vehicle, int index) {
        AddonMount mount = vehicle.addonArmor().mounts().get(index);
        AddonMountState state = vehicle.addonArmor().state(index);
        Component place = Component.literal(mount.info.id.replace('_', ' '));
        if (state.isEmpty()) {
            info.title(Component.translatable(KEY + "empty." + mount.info.kind.name().toLowerCase(java.util.Locale.ROOT)));
            info.entry(Component.translatable(KEY + "place"), place);
            if (mount.info.kind == AddonKind.ERA) {
                offerInstall(info, ctx.player(), mount, AddonFit.ERA_LIGHT);
                offerInstall(info, ctx.player(), mount, AddonFit.ERA_HEAVY);
            } else {
                offerInstall(info, ctx.player(), mount, AddonFit.SLAT);
            }
            return;
        }
        ItemStack icon = new ItemStack(state.fit.item());
        info.title(Component.translatable(state.fit.translationKey()));
        info.titleIcon(new ProbeElement.Icon(icon, 12, false));
        info.entry(Component.translatable(KEY + "place"), place);
        if (state.fit.kind == AddonKind.ERA) {
            era(info, ctx.player(), index, mount, state);
        } else {
            slat(info, ctx, index, mount, state);
        }
        int back = state.fit.kind == AddonKind.ERA ? state.liveCells()
                : (int) Math.floor(mount.slatCost() * state.slatCondition() + 1.0e-4);
        info.action(ProbeAction.of(AddonActions.REMOVE, AddonActions.arg(index, state.fit),
                Component.translatable(KEY + "action.remove", back)));
    }

    private static void offerInstall(ProbeInfo info, Player player, AddonMount mount, AddonFit fit) {
        int cost = mount.installCost(fit);
        ProbeAction action = ProbeAction.of(AddonActions.INSTALL, AddonActions.arg(mount.index, fit),
                        Component.translatable(KEY + "action.install", Component.translatable(fit.translationKey()), cost))
                .withIcon(new ProbeElement.Icon(new ItemStack(fit.item()), 10, false));
        if (AddonActions.carried(player, fit.item()) < cost) {
            action = action.unavailable(Component.translatable(KEY + "need", cost, fit.item().getDescription()));
        }
        info.action(action);
    }

    private static void era(ProbeInfo info, Player player, int index, AddonMount mount, AddonMountState state) {
        int live = state.liveCells();
        int cells = mount.cells();
        info.entry(Component.translatable(KEY + "bricks"), Component.literal(live + " / " + cells)
                .withStyle(live == cells ? ChatFormatting.GREEN : live == 0 ? ChatFormatting.RED : ChatFormatting.YELLOW));
        info.add(schematic(mount, (graphics, x, y, w, h) -> {
            float cw = (float) w / mount.columns;
            float ch = (float) h / mount.rows;
            graphics.fill(x, y, x + w, y + h, FRAME);
            for (int cell = 0; cell < cells; cell++) {
                // row 0 = panel bottom; GUI y grows down
                int column = cell % mount.columns;
                int row = mount.rows - 1 - cell / mount.columns;
                int x0 = x + Mth.floor(column * cw) + 1;
                int y0 = y + Mth.floor(row * ch) + 1;
                graphics.fill(x0, y0, x + Mth.floor((column + 1) * cw) - 1, y + Mth.floor((row + 1) * ch) - 1,
                        state.isLive(cell) ? LIVE : SPENT);
            }
        }));
        int spent = cells - live;
        if (spent > 0) {
            ProbeAction action = ProbeAction.of(AddonActions.REPLACE, AddonActions.arg(index, state.fit),
                    Component.translatable(KEY + "action.replace", spent))
                    .withIcon(new ProbeElement.Icon(new ItemStack(state.fit.item()), 10, false));
            if (AddonActions.carried(player, state.fit.item()) < 1) {
                action = action.unavailable(Component.translatable(KEY + "need", 1, state.fit.item().getDescription()));
            }
            info.action(action);
        }
    }

    private static void slat(ProbeInfo info, ProbeContext ctx, int index, AddonMount mount, AddonMountState state) {
        int intact = state.intactBars();
        int bent = state.bentBars();
        int broken = mount.bars - intact;
        info.entry(Component.translatable(KEY + "bars"), Component.translatable(KEY + "bars.value", intact, mount.bars, bent)
                .withStyle(broken == 0 && bent == 0 ? ChatFormatting.GREEN : broken > 0 ? ChatFormatting.RED : ChatFormatting.YELLOW));
        info.add(schematic(mount, (graphics, x, y, w, h) -> {
            graphics.fill(x, y, x + w, y + h, FRAME);
            for (int k = 0; k < mount.bars; k++) {
                float health = state.bars[k];
                int row = y + h - 1 - Mth.floor((k + 0.5f) / mount.bars * h);
                int colour = health <= 0 ? GONE : health < AddonMountState.BENT ? BENT : GOOD;
                graphics.fill(x + 1, row, x + w - 1, row + 1, colour);
            }
        }));
        if (broken > 0 || bent > 0) {
            ProbeAction action = ProbeAction.of(AddonActions.REPAIR, AddonActions.arg(index, state.fit),
                    Component.translatable(KEY + "action.repair", broken + bent));
            if (SystemRepair.tool(ctx.player(), ComponentKind.HULL).isEmpty() && !ctx.player().getAbilities().instabuild) {
                action = action.unavailable(Component.translatable(KEY + "need_welder", SystemRepair.toolName(ComponentKind.HULL)));
            } else if (broken > 0) {
                action = action.noted(Component.translatable(KEY + "stock_cost", broken));
            }
            info.action(action);
        }
    }

    private interface Draw {
        void draw(GuiGraphics graphics, int x, int y, int w, int h);
    }

    private static ProbeElement schematic(AddonMount mount, Draw draw) {
        int height = Mth.clamp(Math.round(SCHEMATIC_WIDTH * mount.info.height / mount.info.width), 8, 40);
        return new ProbeElement.Custom((graphics, x, y, w, h, partialTick) -> draw.draw(graphics, x, y, w, h),
                SCHEMATIC_WIDTH, height);
    }
}
