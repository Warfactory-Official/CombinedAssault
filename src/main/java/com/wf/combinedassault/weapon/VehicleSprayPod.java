package com.wf.combinedassault.weapon;

import com.wf.combinedassault.pack.sync.PartUnitSyncData;
import com.wf.combinedassault.pack.sync.SyncDataSerializers;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.data.VehicleSprayPodWeaponData;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.wflib.compat.api.WarforgeCompat;
import com.wf.wflib.mist.spray.SprayPayloads;
import com.wf.wflib.mist.spray.Sprays;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;
import java.util.UUID;

/** One tank, any fluid with a {@code SprayPayload}; {@code remainAmmo} = mB. Emits at the muzzle; direction ignored (curtain falls, gas puffs). */
public class VehicleSprayPod extends AbstractVehicleWeapon<VehicleSprayPodWeaponData> implements IFluidHandler {

    /** Gas: one puff per this many shots (flood fill + cells are dear); mB still drawn every shot. */
    private static final int GAS_EVERY = 5;
    private int gasPhase;
    private String fluidId = BuiltInRegistries.FLUID.getKey(Fluids.EMPTY).toString();

    public VehicleSprayPod(AbstractVehicle vehicle, WeaponUnit unit, int index, VehicleSprayPodWeaponData data,
                           String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    @Override
    public void defineSyncData(PartUnitSyncData syncData) {
        super.defineSyncData(syncData);
        syncData.define(SyncDataSerializers.STRING, id -> this.fluidId = id, () -> this.fluidId, this.fluidId);
    }

    public Fluid fluid() {
        return BuiltInRegistries.FLUID.get(ResourceLocation.parse(fluidId));
    }

    @Override
    public boolean canReload() {
        return false;
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        if (!weaponUnit.supported() || isCoolingDown() || fluid() == Fluids.EMPTY || remainAmmo < getData().mbPerShot
                || aimContexts.isEmpty() || !(vehicle.level() instanceof ServerLevel level)) {
            return false;
        }
        LivingEntity owner = weaponUnit.getRootParentWeaponUnit().getOwner();
        UUID faction = owner == null ? null : WarforgeCompat.factionOfPlayer(owner.getUUID());
        Fluid fluid = fluid();
        if (!Sprays.isGas(fluid) || gasPhase++ % GAS_EVERY == 0) {
            Sprays.emit(level, fluid, aimContexts.get(0).from, getData().radius, faction);
        }
        remainAmmo -= getData().mbPerShot;
        if (remainAmmo == 0) {
            fluidId = BuiltInRegistries.FLUID.getKey(Fluids.EMPTY).toString();
        }
        markShot();
        return true;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = super.serializeNBT(provider);
        tag.putString("Fluid", fluidId);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        if (nbt.contains("Fluid")) {
            this.fluidId = nbt.getString("Fluid");
        }
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        Fluid fluid = fluid();
        return remainAmmo <= 0 || fluid == Fluids.EMPTY ? FluidStack.EMPTY : new FluidStack(fluid, remainAmmo);
    }

    @Override
    public int getTankCapacity(int tank) {
        return getMaxCapacity();
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return !stack.isEmpty() && SprayPayloads.fillable(stack.getFluid())
                && (remainAmmo <= 0 || stack.getFluid() == fluid());
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (!isFluidValid(0, resource)) {
            return 0;
        }
        int filled = Math.min(getMaxCapacity() - remainAmmo, resource.getAmount());
        if (filled > 0 && action.execute()) {
            fluidId = BuiltInRegistries.FLUID.getKey(resource.getFluid()).toString();
            remainAmmo += filled;
        }
        return Math.max(filled, 0);
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        return resource.isEmpty() || resource.getFluid() != fluid() ? FluidStack.EMPTY
                : drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        Fluid fluid = fluid();
        int drained = Math.min(maxDrain, remainAmmo);
        if (drained <= 0 || fluid == Fluids.EMPTY) {
            return FluidStack.EMPTY;
        }
        if (action.execute()) {
            remainAmmo -= drained;
            if (remainAmmo == 0) {
                fluidId = BuiltInRegistries.FLUID.getKey(Fluids.EMPTY).toString();
            }
        }
        return new FluidStack(fluid, drained);
    }
}
