package com.wf.combinedassault.weapon;

import com.wf.wflib.magazine.api.MagFill;
import com.wf.wflib.magazine.api.Run;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One selectable kind, count 1: a round (item + WFLib variant; {@code count} rounds) or a magazine ({@code count}
 * magazines). {@code count} {@link #UNLIMITED} = creative supply.
 */
public record RoundCount(ItemStack round, int count) {

    public static final int UNLIMITED = -1;

    public static boolean same(List<RoundCount> a, List<RoundCount> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).count != b.get(i).count || !ItemStack.isSameItemSameComponents(a.get(i).round, b.get(i).round)) {
                return false;
            }
        }
        return true;
    }

    /** Magazines: same item + {@link #variants}, any fill. Else item + components. */
    public static boolean sameKind(ItemStack a, ItemStack b) {
        if (MagFill.isMagazine(a) && MagFill.isMagazine(b)) {
            return a.getItem() == b.getItem() && variants(a).equals(variants(b));
        }
        return ItemStack.isSameItemSameComponents(a, b);
    }

    /** Distinct variants, bottom first: firing leaves the order. */
    public static List<ResourceLocation> variants(ItemStack mag) {
        List<ResourceLocation> out = new ArrayList<>();
        for (Run run : MagFill.contents(mag).runs()) {
            if (!out.contains(run.round())) {
                out.add(run.round());
            }
        }
        return out;
    }
}
