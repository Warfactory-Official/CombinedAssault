package com.wf.combinedassault.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllBlockEntities;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.recipe.VehiclePrintingRecipe;


public class MachineMaxBlockEntity extends BlockEntity {

    private boolean crafting;
    private boolean hasProduct;
    public float progress;
    public float step;
    public ResourceLocation craftingVehicleId;

    public MachineMaxBlockEntity(BlockPos pos, BlockState state) {
        super(AllBlockEntities.MACHINE_MAX_BLOCK_ENTITY.get(), pos, state);
    }

    public void craft(ResourceLocation craftingVehicleId, VehiclePrintingRecipe vehiclePrintingRecipe) {
        this.craftingVehicleId = craftingVehicleId;
        this.progress = 0;
        this.crafting = true;
        this.hasProduct = false;
        this.step = (float) 1 / vehiclePrintingRecipe.getPrintingTime();
        sync();
    }

    public AbstractVehicle takeProduct() {
        if (!this.hasProduct) {
            return null;
        }
        AbstractVehicle vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData()
                .get(craftingVehicleId).construct(level, Vec3.ZERO, 0, 0);
        vehicle.initData();
        this.craftingVehicleId = null;
        this.progress = 0;
        this.hasProduct = false;
        sync();
        return vehicle;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, MachineMaxBlockEntity blockEntity) {
        if (!level.isClientSide) {
            if (blockEntity.crafting) {
                blockEntity.progress = Math.min(1, blockEntity.progress + blockEntity.step);
                if (blockEntity.progress >= 1) {
                    blockEntity.crafting = false;
                    blockEntity.hasProduct = true;
                }
                blockEntity.sync();
            }
        } else {
            if (blockEntity.crafting && level.random.nextFloat() < 0.2F) {
                Vec3 worldPos = pos.getCenter();
                double xOffset = (level.random.nextDouble() - 0.5D) * 0.5D;
                double zOffset = (level.random.nextDouble() - 0.5D) * 0.5D;
                level.addParticle(
                        ParticleTypes.POOF,
                        worldPos.x + xOffset, worldPos.y + 1D, worldPos.z + zOffset,
                        0.0D, 0.1D, 0.0D
                );
            }
        }
    }

    public void sync() {
        if (level != null && !level.isClientSide) {
            setChanged();
            level.sendBlockUpdated(
                    worldPosition,
                    getBlockState(),
                    getBlockState(),
                    3
            );
        }
    }

    public boolean isCrafting() {
        return crafting;
    }

    public boolean hasProduct() {
        return hasProduct;
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (craftingVehicleId != null) {
            tag.putString("craftingVehicleId", craftingVehicleId.toString());
        }
        tag.putBoolean("crafting", crafting);
        tag.putBoolean("hasProduct", hasProduct);
        tag.putFloat("progress", progress);
        tag.putFloat("step", step);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        craftingVehicleId = tag.contains("craftingVehicleId")
                ? CombinedAssault.resourceLocation(tag.getString("craftingVehicleId"))
                : null;
        crafting = tag.getBoolean("crafting");
        hasProduct = tag.getBoolean("hasProduct");
        progress = tag.getFloat("progress");
        step = tag.getFloat("step");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }

}
