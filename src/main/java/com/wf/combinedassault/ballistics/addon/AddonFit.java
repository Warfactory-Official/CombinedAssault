package com.wf.combinedassault.ballistics.addon;

import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import com.wf.combinedassault.ballistics.BallisticsContent;

/** What is installed on a mount point. Ordinal is the save/sync id. */
public enum AddonFit {
    NONE(null, null, 0),
    ERA_LIGHT(AddonKind.ERA, EraSpec.LIGHT, 5.6f),
    ERA_HEAVY(AddonKind.ERA, EraSpec.HEAVY, 13.0f),
    SLAT(AddonKind.SLAT, null, 25.0f);

    private static final AddonFit[] VALUES = values();

    @Nullable
    public final AddonKind kind;
    @Nullable
    public final EraSpec era;
    /** kg per installed item: ERA brick, slat panel (1 m²). */
    public final float kilograms;

    AddonFit(@Nullable AddonKind kind, @Nullable EraSpec era, float kilograms) {
        this.kind = kind;
        this.era = era;
        this.kilograms = kilograms;
    }

    public static AddonFit byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
    }

    /** The item one unit of this fit is installed from and removed as. */
    public Item item() {
        return switch (this) {
            case ERA_LIGHT -> BallisticsContent.ERA_BRICK.get();
            case ERA_HEAVY -> BallisticsContent.HEAVY_ERA_BRICK.get();
            case SLAT -> BallisticsContent.SLAT_PANEL.get();
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
