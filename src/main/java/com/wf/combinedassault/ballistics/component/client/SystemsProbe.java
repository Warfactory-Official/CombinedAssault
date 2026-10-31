package com.wf.combinedassault.ballistics.component.client;

import com.wf.wflib.probe.api.ProbeAction;
import com.wf.wflib.probe.api.ProbeContext;
import com.wf.wflib.probe.api.ProbeElement;
import com.wf.wflib.probe.api.ProbeInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.SystemRepair;
import com.wf.combinedassault.ballistics.component.VehicleSystems;

import java.util.List;

/** Vehicle node panels: system grid; one repair row per damaged system. */
public final class SystemsProbe {

    private static final String KEY = "repair.combinedassault.";

    private SystemsProbe() {
    }

    public static void grid(ProbeInfo info, List<VehicleSystems.Cell> cells) {
        info.add(new ProbeElement.Custom((graphics, x, y, w, h, partialTick) -> SystemsHud.grid(graphics, cells, x, y),
                SystemsHud.gridWidth(), SystemsHud.gridHeight(cells.size())));
    }

    /** Damaged only. */
    public static void repair(ProbeInfo info, ProbeContext ctx, VehicleSystems.Cell cell) {
        ComponentState from = cell.state();
        ComponentState to = ComponentState.of(from.stepTarget());
        ProbeAction action = ProbeAction.of(SystemRepair.ACTION, cell.key(), Component.translatable(KEY + "action",
                        cell.label(), Component.translatable(from.translationKey()),
                        Component.translatable(to.translationKey())))
                .timed(cell.stepTicks());
        ItemStack tool = SystemRepair.tool(ctx.player(), cell.kind());
        if (!tool.isEmpty()) {
            action = action.withIcon(new ProbeElement.Icon(tool, 10, false));
        }
        Component refusal = SystemRepair.refusal(ctx.player(), cell);
        if (refusal != null) {
            action = action.unavailable(refusal);
        } else if (SystemRepair.needsParts(cell)) {
            action = action.noted(Component.translatable(KEY + "parts_cost", SystemRepair.partsName(cell.kind())));
        }
        info.action(action);
    }
}
