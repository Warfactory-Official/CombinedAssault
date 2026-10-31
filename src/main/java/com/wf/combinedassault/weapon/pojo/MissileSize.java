package com.wf.combinedassault.weapon.pojo;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Missile size class: what can carry it.
 *
 * <p>Discrete classes, not a number: only question asked is fit-or-not; a numeric size invites
 * "accepts 3.7 and under" launchers.
 *
 * <p>Membership = datapack item tag, not a code list => third-party missile can be tagged {@link #LARGE}
 * without either mod knowing the other exists.
 */
public enum MissileSize {

    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large"),
    HUGE("huge"),
    /** MLRS pod cell (M142/M270): GMLRS-sized, fits nothing else. */
    POD("pod"),
    /** Single-round MLRS pod (ATACMS). */
    POD_LARGE("pod_large");

    public static final MissileSize[] VALUES = values();

    private final TagKey<Item> tag;

    MissileSize(String path) {
        this.tag = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("wflib", "missile/" + path));
    }

    public TagKey<Item> tag() {
        return tag;
    }

    /**
     * Null: not tagged as any missile size.
     *
     * <p>First match wins: double-tagged round reads as the smaller size (pack error either way; smaller fits
     * fewer holders).
     */
    @Nullable
    public static MissileSize of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        for (MissileSize size : VALUES) {
            if (stack.is(size.tag)) {
                return size;
            }
        }
        return null;
    }

    @Nullable
    public static MissileSize byName(String name) {
        if (name == null) {
            return null;
        }
        for (MissileSize size : VALUES) {
            if (size.name().equalsIgnoreCase(name)) {
                return size;
            }
        }
        return null;
    }

}
