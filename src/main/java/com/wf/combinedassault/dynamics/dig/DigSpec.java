package com.wf.combinedassault.dynamics.dig;

import com.google.gson.JsonObject;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Block;

import java.util.Locale;
import java.util.Set;

/**
 * Collider {@code dig} tag: the block-breaking zone of one tool.
 *
 * @param id     name a {@code tool} weapon selects it by
 * @param tool   mineable tag the speed applies to; {@link Tool#NONE} => no block gets {@code speed}
 * @param tier   mining level; a block needing a tool and incorrect for the tier is a wall
 * @param speed  destroy speed on blocks the tool is right for (vanilla: iron pickaxe 6, diamond 8); others 1
 * @param drops  true => loot as mined by the tier's tool; false => blocks vanish
 */
public record DigSpec(String id, Tool tool, Tiers tier, float speed, boolean drops) {

    private static final Set<String> KEYS = Set.of("id", "tool", "level", "speed", "drops");

    public enum Tool {
        PICKAXE(BlockTags.MINEABLE_WITH_PICKAXE), AXE(BlockTags.MINEABLE_WITH_AXE),
        SHOVEL(BlockTags.MINEABLE_WITH_SHOVEL), HOE(BlockTags.MINEABLE_WITH_HOE), NONE(null);

        public final TagKey<Block> mineable;

        Tool(TagKey<Block> mineable) {
            this.mineable = mineable;
        }
    }

    public static DigSpec parse(JsonObject meta, String where) {
        for (String key : meta.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException(where + ": dig: unknown key " + key);
            }
        }
        if (!meta.has("id")) {
            throw new IllegalArgumentException(where + ": dig: no id");
        }
        Tool tool = Tool.PICKAXE;
        if (meta.has("tool")) {
            tool = Tool.valueOf(meta.get("tool").getAsString().toUpperCase(Locale.ROOT));
        }
        Tiers tier = Tiers.IRON;
        if (meta.has("level")) {
            tier = Tiers.valueOf(meta.get("level").getAsString().toUpperCase(Locale.ROOT));
        }
        float speed = meta.has("speed") ? meta.get("speed").getAsFloat() : 6.0f;
        if (!(speed > 0)) {
            throw new IllegalArgumentException(where + ": dig: speed must be > 0");
        }
        return new DigSpec(meta.get("id").getAsString(), tool, tier, speed,
                !meta.has("drops") || meta.get("drops").getAsBoolean());
    }

    /** Stack loot is rolled against: the tier's tool of this kind. */
    public ItemStack toolStack() {
        Item item = switch (tool) {
            case AXE -> switch (tier) {
                case WOOD -> Items.WOODEN_AXE; case STONE -> Items.STONE_AXE; case IRON -> Items.IRON_AXE;
                case DIAMOND -> Items.DIAMOND_AXE; case GOLD -> Items.GOLDEN_AXE; case NETHERITE -> Items.NETHERITE_AXE;
            };
            case SHOVEL -> switch (tier) {
                case WOOD -> Items.WOODEN_SHOVEL; case STONE -> Items.STONE_SHOVEL; case IRON -> Items.IRON_SHOVEL;
                case DIAMOND -> Items.DIAMOND_SHOVEL; case GOLD -> Items.GOLDEN_SHOVEL; case NETHERITE -> Items.NETHERITE_SHOVEL;
            };
            case HOE -> switch (tier) {
                case WOOD -> Items.WOODEN_HOE; case STONE -> Items.STONE_HOE; case IRON -> Items.IRON_HOE;
                case DIAMOND -> Items.DIAMOND_HOE; case GOLD -> Items.GOLDEN_HOE; case NETHERITE -> Items.NETHERITE_HOE;
            };
            default -> switch (tier) {
                case WOOD -> Items.WOODEN_PICKAXE; case STONE -> Items.STONE_PICKAXE; case IRON -> Items.IRON_PICKAXE;
                case DIAMOND -> Items.DIAMOND_PICKAXE; case GOLD -> Items.GOLDEN_PICKAXE; case NETHERITE -> Items.NETHERITE_PICKAXE;
            };
        };
        return new ItemStack(item);
    }
}
