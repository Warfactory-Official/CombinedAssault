package com.wf.combinedassault.props;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;

public final class PropsContent {

    static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, CombinedAssault.MOD_ID);
    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, CombinedAssault.MOD_ID);
    static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CombinedAssault.MOD_ID);

    public static final DeferredHolder<Block, Block> FIGURE_BOX_BLOCK = BLOCKS.register("figure_box_block",
            () -> new FigureBoxBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).noOcclusion()));
    public static final DeferredHolder<Block, Block> MACHINE_MAX_BLOCK = BLOCKS.register("machine_max_block",
            () -> new MachineMaxBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).noOcclusion()));

    public static final DeferredHolder<Item, PlainTextureItem> PLAIN_TEXTURE_ITEM = ITEMS.register("plain_texture_item",
            () -> new PlainTextureItem(new Item.Properties().durability(1)));
    public static final DeferredHolder<Item, Item> FIGURE_BOX = ITEMS.register("figure_box",
            () -> new FigureBoxItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> MACHINE_MAX_BLOCK_ITEM = ITEMS.register("machine_max_block",
            () -> new MachineMaxBlockItem(MACHINE_MAX_BLOCK.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, DecorationItem> DECORATION_ITEM = ITEMS.register("decoration_item", DecorationItem::new);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FigureBoxBlockEntity>> FIGURE_BOX_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("figure_box_block_entity", () ->
                    BlockEntityType.Builder.of(FigureBoxBlockEntity::new, FIGURE_BOX_BLOCK.get(), Blocks.AIR).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineMaxBlockEntity>> MACHINE_MAX_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("machine_max_block_entity", () ->
                    BlockEntityType.Builder.of(MachineMaxBlockEntity::new, MACHINE_MAX_BLOCK.get(), Blocks.AIR).build(null));

    private PropsContent() {
    }
}
