package com.wf.combinedassault.item;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.wf.combinedassault.client.CamoBrush;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.camo.CamoKind;
import com.wf.combinedassault.vehicle.camo.CamoTargeting;

import java.util.List;

/**
 * Server re-traces the ray against hull collision cubes itself; client aim is advisory.
 * See {@link com.wf.combinedassault.network.message.ClientCamoAction}.
 */
public class CamoKitItem extends VehicleItem {

    public static final String TAG_BLOCK = "CombinedAssaultCamoBlock";
    public static final String TAG_KIND = "CombinedAssaultCamoKind";
    public static final String TAG_SCALE = "CombinedAssaultCamoScale";
    public static final String TAG_JITTER = "CombinedAssaultCamoJitter";

    public static final float DEFAULT_SCALE = 0.45f;
    public static final float DEFAULT_JITTER = 1.0f;

    public CamoKitItem() {
        super(new Properties().stacksTo(1).durability(1024));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        // Vanilla entity-pick range-limited; past it, a click meant for the vehicle lands on its own ground block here instead.
        Player aiming = context.getPlayer();
        if (aiming != null && paintIfAimedAtHull(level, aiming, context.getItemInHand())) {
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        BlockState state = level.getBlockState(context.getClickedPos());
        if (state.isAir()) {
            return InteractionResult.PASS;
        }
        ItemStack itemStack = context.getItemInHand();
        setBlock(itemStack, state);
        Player player = context.getPlayer();
        if (player != null) {
            level.playSound(player, player.blockPosition(), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 0.6f, 1.4f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResultHolder.pass(itemStack);
        }
        // useOn needs a block hit; this covers aiming at a hull over open sky.
        if (paintIfAimedAtHull(level, player, itemStack)) {
            return InteractionResultHolder.sidedSuccess(itemStack, level.isClientSide());
        }
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(itemStack);
        }
        setKind(itemStack, getKind(itemStack) == CamoKind.CUBE ? CamoKind.PLATE : CamoKind.CUBE);
        level.playSound(player, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5f, 1.6f);
        return InteractionResultHolder.sidedSuccess(itemStack, level.isClientSide());
    }

    @Override
    public InteractionResult interactEntity(ItemStack itemStack, Player player, Entity target, InteractionHand hand) {
        if (player.level().isClientSide() && hand == InteractionHand.MAIN_HAND && target instanceof AbstractVehicle vehicle) {
            clickVehicle(vehicle, player, itemStack);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Runs identically on both sides so client/server agree on treating this as a hull hit;
     * disagreement would desync the kit's stored block tag (one side skips the block-sample branch, the other doesn't).
     *
     * @return click was on a hull; caller must not also treat it as a block
     */
    private static boolean paintIfAimedAtHull(Level level, Player player, ItemStack itemStack) {
        AbstractVehicle vehicle = CamoTargeting.underCrosshair(player);
        if (vehicle == null) {
            return false;
        }
        if (level.isClientSide()) {
            clickVehicle(vehicle, player, itemStack);
        }
        return true;
    }

    @OnlyIn(Dist.CLIENT)
    private static void clickVehicle(AbstractVehicle vehicle, Player player, ItemStack itemStack) {
        if (player.isShiftKeyDown()) {
            CamoBrush.erase(vehicle);
        } else {
            CamoBrush.dab(vehicle, itemStack);
        }
    }

    @Override
    public void appendHoverText(ItemStack itemStack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BlockState state = getBlock(itemStack, context.registries());
        tooltip.add(Component.translatable("ui.camo_kit.material", state.getBlock().getName())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("ui.camo_kit.kind." + getKind(itemStack).name().toLowerCase(java.util.Locale.ROOT))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("ui.camo_kit.hint").withStyle(ChatFormatting.DARK_GRAY));
    }

    public static CompoundTag tag(ItemStack itemStack) {
        return itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void store(ItemStack itemStack, CompoundTag tag) {
        itemStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static void setBlock(ItemStack itemStack, BlockState state) {
        CompoundTag tag = tag(itemStack);
        tag.putString(TAG_BLOCK, BlockStateParser.serialize(state));
        store(itemStack, tag);
    }

    /** Null {@code registries} (no resource context) => default block returned. */
    public static BlockState getBlock(ItemStack itemStack, HolderLookup.Provider registries) {
        String serialized = tag(itemStack).getString(TAG_BLOCK);
        if (serialized.isEmpty() || registries == null) {
            return Blocks.OAK_LEAVES.defaultBlockState();
        }
        try {
            HolderLookup.RegistryLookup<Block> blocks = registries.lookupOrThrow(Registries.BLOCK);
            return BlockStateParser.parseForBlock(blocks, serialized, false).blockState();
        } catch (Exception exception) {
            return Blocks.OAK_LEAVES.defaultBlockState();
        }
    }

    /** Serialized form; what the paint packet carries. */
    public static String getBlockId(ItemStack itemStack) {
        String serialized = tag(itemStack).getString(TAG_BLOCK);
        return serialized.isEmpty() ? "minecraft:oak_leaves" : serialized;
    }

    public static void setKind(ItemStack itemStack, CamoKind kind) {
        CompoundTag tag = tag(itemStack);
        tag.putByte(TAG_KIND, (byte) kind.ordinal());
        store(itemStack, tag);
    }

    public static CamoKind getKind(ItemStack itemStack) {
        return CamoKind.byId(tag(itemStack).getByte(TAG_KIND));
    }

    public static float getScale(ItemStack itemStack) {
        CompoundTag tag = tag(itemStack);
        return tag.contains(TAG_SCALE) ? tag.getFloat(TAG_SCALE) : DEFAULT_SCALE;
    }

    public static float getJitter(ItemStack itemStack) {
        CompoundTag tag = tag(itemStack);
        return tag.contains(TAG_JITTER) ? tag.getFloat(TAG_JITTER) : DEFAULT_JITTER;
    }
}
