package com.wf.combinedassault.vehicle.item;

import com.wf.combinedassault.hud.tooltip.VehicleTooltips;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.render.item.VehicleSpawnItemRenderer;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.naval.data.ShipVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.naval.ShipPlacement;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import static com.wf.combinedassault.vehicle.entity.ICustomVehicle.TAG_VEHICLE_ID;

public class VehicleSpawnItem extends Item {
    /** Picked-up vehicle's save data ({@code AbstractVehicle#addAdditionalSaveData}); applied on placement. */
    public static final String TAG_STATE = "VehicleState";

    public VehicleSpawnItem(Properties pProperties) {
        super(pProperties);
    }

    public ItemStack createInstance(ResourceLocation vehicleId) {
        ItemStack itemStack = new ItemStack(this);
        CompoundTag tag = itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putString(TAG_VEHICLE_ID, vehicleId.toString());
        itemStack.set(DataComponents.ITEM_NAME, Component.translatable(vehicleId.getNamespace() + "." + vehicleId.getPath()));
        itemStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return itemStack;
    }

    @Override
    @SuppressWarnings("removal")
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {

            private VehicleSpawnItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                Minecraft minecraft = Minecraft.getInstance();
                if (renderer == null) {
                    renderer = new VehicleSpawnItemRenderer(minecraft.getBlockEntityRenderDispatcher(), minecraft.getEntityModels());
                }
                return renderer;
            }

        });
    }

    /** {@code ITEM_NAME} absent (command-made stack): the vehicle's name. */
    @Override
    public Component getName(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ResourceLocation id = tag.contains(TAG_VEHICLE_ID) ? ResourceLocation.tryParse(tag.getString(TAG_VEHICLE_ID)) : null;
        return id == null ? super.getName(stack) : Component.translatable(id.getNamespace() + "." + id.getPath());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BaseVehicleData<?> data = vehicleData(stack);
        if (data != null) {
            VehicleTooltips.vehicle(data, tooltip);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.sidedSuccess(itemStack, true);
        }
        BaseVehicleData<?> vehicleData = vehicleData(itemStack);
        if (vehicleData == null) {
            return InteractionResultHolder.fail(itemStack);
        }
        // Ship: water placement checked before the block raycast.
        if (vehicleData instanceof ShipVehicleData ship && placeOnWater(level, player, ship, itemStack)) {
            return InteractionResultHolder.sidedSuccess(itemStack, false);
        }
        HitResult hitResult = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            if (vehicleData instanceof ShipVehicleData) {
                player.displayClientMessage(Component.translatable("ui.combinedassault.ship.no_water"), true);
                return InteractionResultHolder.fail(itemStack);
            }
            return InteractionResultHolder.pass(itemStack);
        }
        if (VectorUtil.hitEntity(player, player.getEyePosition(), hitResult.getLocation()) != null) {
            return InteractionResultHolder.pass(itemStack);
        }
        return place(level, vehicleData, hitResult.getLocation(), player.getYRot(), itemStack)
                ? InteractionResultHolder.sidedSuccess(itemStack, false)
                : InteractionResultHolder.fail(itemStack);
    }

    private static BaseVehicleData<?> vehicleData(ItemStack itemStack) {
        CompoundTag tag = itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains(TAG_VEHICLE_ID)) {
            return null;
        }
        ResourceLocation vehicleId = CombinedAssault.resourceLocation(tag.getString(TAG_VEHICLE_ID));
        Optional<BaseVehicleData> vehicleData = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicleId);
        return vehicleData.orElse(null);
    }

    private static boolean placeOnWater(Level level, Player player, ShipVehicleData ship, ItemStack itemStack) {
        ShipPlacement.Spot spot = ShipPlacement.aim(level, player, ship, player.getYRot());
        if (spot == null || !place(level, ship, spot.pos(), player.getYRot(), itemStack)) {
            return false;
        }
        if (spot.shallow()) {
            player.displayClientMessage(Component.translatable("ui.combinedassault.ship.placed_shallow",
                    String.format(Locale.ROOT, "%.1f", spot.depth()),
                    String.format(Locale.ROOT, "%.1f", spot.needed())), true);
        }
        return true;
    }

    public static boolean place(Level level, BaseVehicleData<?> vehicleData, Vec3 position, float yaw, ItemStack itemStack) {
        AbstractVehicle vehicle = vehicleData.construct(level, position, 0, yaw);
        CompoundTag tag = itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (tag.contains(TAG_STATE, Tag.TAG_COMPOUND)) {
            vehicle.readAdditionalSaveData(tag.getCompound(TAG_STATE));
        }
        if (!level.addFreshEntity(vehicle)) {
            return false;
        }
        itemStack.shrink(1);
        return true;
    }

}
