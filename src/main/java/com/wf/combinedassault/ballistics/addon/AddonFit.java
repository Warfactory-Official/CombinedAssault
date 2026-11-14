package com.wf.combinedassault.ballistics.addon;

import com.wf.combinedassault.ballistics.BallisticsContent;
import com.wf.wflib.armor.api.hull.EraType;
import com.wf.wflib.armor.api.hull.EraTypes;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** What is installed on a mount point. Ordinal is the save/sync id. */
public enum AddonFit {
    NONE(null, null, 0),
    ERA_LIGHT(AddonKind.ERA, EraTypePacks.LIGHT, 5.6f),
    ERA_HEAVY(AddonKind.ERA, EraTypePacks.HEAVY, 13.0f),
    SLAT(AddonKind.SLAT, null, 25.0f);

    private static final AddonFit[] VALUES = values();

    @Nullable
    public final AddonKind kind;
    @Nullable
    private final String eraId;
    /** kg per installed item: ERA brick, slat panel (1 m²). */
    public final float kilograms;

    AddonFit(@Nullable AddonKind kind, @Nullable String eraId, float kilograms) {
        this.kind = kind;
        this.eraId = eraId;
        this.kilograms = kilograms;
    }

    /** Brick type of an ERA fit, from the pack registry. */
    public EraType era() {
        if (eraId == null) {
            throw new IllegalStateException(this + " is not an ERA fit");
        }
        return EraTypes.get(eraId);
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
