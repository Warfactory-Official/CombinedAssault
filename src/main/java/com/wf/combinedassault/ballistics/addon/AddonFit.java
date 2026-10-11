package com.wf.combinedassault.ballistics.addon;

import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.all.AllItems;

import java.util.Locale;

/** What is installed on a mount point. Ordinal is the save/sync id. */
public enum AddonFit {
    NONE(null, null),
    ERA_LIGHT(AddonKind.ERA, EraSpec.LIGHT),
    ERA_HEAVY(AddonKind.ERA, EraSpec.HEAVY),
    SLAT(AddonKind.SLAT, null);

    private static final AddonFit[] VALUES = values();

    @Nullable
    public final AddonKind kind;
    @Nullable
    public final EraSpec era;

    AddonFit(@Nullable AddonKind kind, @Nullable EraSpec era) {
        this.kind = kind;
        this.era = era;
    }

    public static AddonFit byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
    }

    /** The item one unit of this fit is installed from and removed as. */
    public Item item() {
        return switch (this) {
            case ERA_LIGHT -> AllItems.ERA_BRICK.get();
            case ERA_HEAVY -> AllItems.HEAVY_ERA_BRICK.get();
            case SLAT -> AllItems.SLAT_PANEL.get();
            case NONE -> throw new IllegalStateException("nothing installed");
        };
    }

    @Nullable
    public static AddonFit ofItem(Item item) {
        for (AddonFit fit : VALUES) {
            if (fit != NONE && fit.item() == item) {
                return fit;
            }
        }
        return null;
    }

    public String translationKey() {
        return "addon_armor.combinedassault.fit." + name().toLowerCase(Locale.ROOT);
    }
}
