package com.wf.combinedassault.storage;

import com.wf.combinedassault.compat.ultimateweight.UltimateWeightCompat;
import com.wf.wflib.ammo.Ammo;
import com.wf.wflib.ammo.CartridgeItem;
import com.wf.wflib.magazine.MagContents;
import com.wf.wflib.magazine.Magazine;
import com.wf.wflib.magazine.MagazineItems;
import com.wf.wflib.magazine.PodTubes;
import com.wf.wflib.api.ChargeItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Cargo kg per stack. Ultimate Weight value > 0 wins (its unconfigured items resolve 0); else built in:
 * block item = 1 L of its material, ingot = {@link #INGOT_LITRES}, wflib ammo / charges = their own mass.
 */
public final class ItemMass {
    /** Iron ingot 0.9 kg (Ultimate Weight default) / 7.87 kg/L; 9 ingots ~ 1 block. */
    static final double INGOT_LITRES = 0.114;
    static final double DEFAULT_KG = 0.25;
    private static final double BUCKET_KG = 0.3;
    private static final int MAX_DEPTH = 4;
    /** kg/L by c:{storage_blocks,ingots,nuggets,raw_materials,gems}/<material>. */
    private static final Map<String, Double> DENSITY = Map.ofEntries(
            Map.entry("iron", 7.87), Map.entry("steel", 7.85), Map.entry("copper", 8.96), Map.entry("gold", 19.3),
            Map.entry("netherite", 11.0), Map.entry("aluminum", 2.7), Map.entry("aluminium", 2.7),
            Map.entry("lead", 11.3), Map.entry("tin", 7.3), Map.entry("silver", 10.5), Map.entry("zinc", 7.1),
            Map.entry("nickel", 8.9), Map.entry("bronze", 8.8), Map.entry("brass", 8.5),
            Map.entry("titanium", 4.5), Map.entry("tungsten", 19.3), Map.entry("uranium", 19.1),
            Map.entry("platinum", 21.4), Map.entry("osmium", 22.6), Map.entry("diamond", 3.5),
            Map.entry("emerald", 2.7), Map.entry("lapis", 2.8), Map.entry("quartz", 2.65), Map.entry("coal", 1.4),
            Map.entry("redstone", 2.0), Map.entry("amethyst", 2.65));
    private static final double METAL = 8.0;
    private static final double STONE = 2.6;

    /** Per single item, NBT-free part; cleared on tag reload. */
    private static final Map<Item, Double> UNIT = new IdentityHashMap<>();

    private ItemMass() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> {
            synchronized (UNIT) {
                UNIT.clear();
            }
        });
    }

    /** Whole stack, kg. */
    public static double kilograms(ItemStack stack) {
        return kilograms(stack, 0);
    }

    private static double kilograms(ItemStack stack, int depth) {
        if (stack.isEmpty()) {
            return 0;
        }
        double uw = UltimateWeightCompat.kilograms(stack);
        if (uw > 0) {
            return uw;
        }
        double each = stack.getItem() instanceof ChargeItem charge ? charge.massKg(stack) : unit(stack.getItem());
        double total = each * stack.getCount();
        if (depth < MAX_DEPTH) {
            ItemContainerContents container = stack.get(DataComponents.CONTAINER);
            if (container != null) {
                for (ItemStack inner : container.nonEmptyItems()) {
                    total += kilograms(inner, depth + 1) * stack.getCount();
                }
            }
            BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
            if (bundle != null) {
                for (ItemStack inner : bundle.items()) {
                    total += kilograms(inner, depth + 1) * stack.getCount();
                }
            }
            MagContents rounds = stack.get(MagazineItems.CONTENTS.get());
            if (rounds != null && stack.getItem() instanceof Magazine mag) {
                total += rounds.total() * Ammo.calibre(mag.calibre()).massKg() * stack.getCount();
            }
            PodTubes tubes = stack.get(MagazineItems.POD_TUBES.get());
            if (tubes != null) {
                for (ItemStack inner : tubes.tubes()) {
                    total += kilograms(inner, depth + 1) * stack.getCount();
                }
            }
        }
        return total;
    }

    private static double unit(Item item) {
        synchronized (UNIT) {
            Double cached = UNIT.get(item);
            if (cached != null) {
                return cached;
            }
        }
        double kg = resolve(item);
        synchronized (UNIT) {
            UNIT.put(item, kg);
        }
        return kg;
    }

    static double resolve(Item item) {
        if (item instanceof CartridgeItem cartridge) {
            return cartridge.calibre().massKg();
        }
        if (item instanceof BucketItem bucket && bucket.content != Fluids.EMPTY) {
            return BUCKET_KG + bucket.content.getFluidType().getDensity() / 1000.0;
        }
        @SuppressWarnings("deprecation")
        var holder = item.builtInRegistryHolder();
        for (TagKey<Item> tag : (Iterable<TagKey<Item>>) holder.tags()::iterator) {
            if (!tag.location().getNamespace().equals("c")) {
                continue;
            }
            String path = tag.location().getPath();
            int slash = path.indexOf('/');
            if (slash < 0) {
                continue;
            }
            double litres = switch (path.substring(0, slash)) {
                case "storage_blocks" -> 1.0;
                case "ingots", "gems" -> INGOT_LITRES;
                case "raw_materials" -> INGOT_LITRES * 1.5;
                case "nuggets" -> INGOT_LITRES / 9;
                case "dusts" -> INGOT_LITRES / 2;
                default -> 0;
            };
            if (litres > 0) {
                return litres * DENSITY.getOrDefault(path.substring(slash + 1), METAL);
            }
        }
        if (item instanceof BlockItem block) {
            return density(block.getBlock().defaultBlockState());
        }
        return DEFAULT_KG;
    }

    /** kg/L of a block's material. */
    private static double density(BlockState state) {
        if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) {
            return 0.1;
        }
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.MINEABLE_WITH_HOE)) {
            return 0.3;
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return 0.6;
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return 1.6;
        }
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return STONE;
        }
        return 1.0;
    }
}
