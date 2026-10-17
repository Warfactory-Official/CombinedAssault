package com.wf.combinedassault.vehicle.weapon;

import com.wf.combinedassault.client.ClientDelay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import com.wf.combinedassault.api.custom.sync.SyncDataSerializers;
import com.wf.combinedassault.custom.sync.PartUnitSyncData;
import com.wf.combinedassault.custom.sync.SyncDataHolder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.wf.combinedassault.compat.wflib.Munitions;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.missile.SeekerMode;
import net.minecraft.world.item.ItemStack;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.combinedassault.custom.weapon.data.VehicleMissileWeaponData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.vehicle.part.AutoWeaponUnit;
import com.wf.combinedassault.vehicle.part.MissileHolderUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;

import java.util.List;

public class VehicleMissile extends AbstractVehicleWeapon<VehicleMissileWeaponData> {

    /** Operator's pick among the rack's mixed rounds; AIR = lowest loaded slot. Synced. */
    private Item rackRound = Items.AIR;
    private SyncDataHolder<Item> rackRoundSync;

    public VehicleMissile(AbstractVehicle vehicle, WeaponUnit unit, int index, VehicleMissileWeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    @Override
    public void defineSyncData(PartUnitSyncData syncData) {
        super.defineSyncData(syncData);
        rackRoundSync = syncData.define(SyncDataSerializers.ITEM, item -> this.rackRound = item,
                () -> this.rackRound, Items.AIR);
    }

    /** Rack: "any" (empty, all loaded) then each racked kind; else the stores' kinds. */
    @Override
    public List<RoundCount> roundOptions() {
        MissileHolderUnit rack = missileHolder();
        if (rack == null) {
            return super.roundOptions();
        }
        List<RoundCount> out = new java.util.ArrayList<>();
        out.add(new RoundCount(ItemStack.EMPTY, rack.loaded()));
        for (Item kind : rack.kinds()) {
            out.add(new RoundCount(kind.getDefaultInstance(), rack.count(kind)));
        }
        return out;
    }

    @Override
    public ItemStack getSelectedRound() {
        return missileHolder() == null ? super.getSelectedRound()
                : rackRound == Items.AIR ? ItemStack.EMPTY : rackRound.getDefaultInstance();
    }

    /** Rack: next shot's kind, breech untouched; a kind none of which is racked fires nothing. */
    @Override
    public void selectRound(ItemStack round) {
        if (missileHolder() == null) {
            super.selectRound(round);
        } else {
            this.rackRound = round.isEmpty() ? Items.AIR : round.getItem();
        }
    }

    @Override
    public void predictSelectedRound(ItemStack round) {
        if (missileHolder() == null) {
            super.predictSelectedRound(round);
        } else {
            rackRoundSync.predict(round.isEmpty() ? Items.AIR : round.getItem());
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean doClientShoot() {
        VehicleMissileWeaponData data = this.getData();
        WeaponUnit missileWeaponUnit = getWeaponUnit();
        missileWeaponUnit = missileWeaponUnit.getRootParentWeaponUnit();
        if (missileWeaponUnit.getXRot() < data.getXRotMin()
                || missileWeaponUnit.getXRot() > data.getXRotMax()
                || missileWeaponUnit.getYRot() < data.getYRotMin()
                || missileWeaponUnit.getYRot() > data.getYRotMax()) {
            com.wf.combinedassault.client.hud.HudTips.show(Component.translatable("ui.out_of_launch_limits"));
            return false;
        }
        if (homing()) {
            if (missileWeaponUnit.getLockedEntity() == null
                    && missileWeaponUnit.getFireControlSensorType() != WeaponUnitData.FireControlSensorType.EO) {
                LocalVehiclePlayer.instance.sendMessage("ui.need_lock_entity");
                return false;
            }
        }
        return super.doClientShoot();
    }

    @OnlyIn(Dist.CLIENT)
    public void onClientFire() {
        int coldLaunchTimeTick = getWeaponUnit().getColdLaunchTimeTick();
        if (coldLaunchTimeTick > 0) {
            ClientDelay.after(coldLaunchTimeTick, super::onClientFire);
        } else {
            super.onClientFire();
        }
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        if (!check(aimContexts, shooter)) {
            return false;
        }
        if (isCoolingDown() || isReloading() || !consumeAmmo(aimContexts)) {
            return false;
        }
        markShot();

        var data = this.getData();
        WeaponUnit rootWeaponUnit = getWeaponUnit().getRootParentWeaponUnit();
        MissileHolderUnit holder = missileHolder();
        for (AimContext aimContext : aimContexts) {
            int slot = holder == null ? -1 : holder.nextLoaded(rackRound);
            if (slot >= 0 && launchSlot(holder, slot, aimContext, rootWeaponUnit.getLockedEntity(),
                    rootWeaponUnit.getOwner())) {
                continue;
            }
            if (data.isHolderOnly() || rackRound != Items.AIR) {
                continue;
            }
            if (Munitions.fire(vehicle, getWeaponUnit(), aimContext, loadedRound, data.getInaccuracy(),
                    rootWeaponUnit.getLockedEntity(), rootWeaponUnit.getOwner())) {
                vehicle.queueRecoil(getWeaponUnit(), data.getRecoil());
            }
        }
        return true;
    }

    /** Round the next shot leaves with: rack slot of the selected kind, else the magazine's. */
    public ItemStack currentRound() {
        MissileHolderUnit holder = missileHolder();
        int slot = holder == null ? -1 : holder.nextLoaded(rackRound);
        return slot >= 0 ? holder.round(slot) : loadedRound;
    }

    /** Null: current round is no WFLib missile. */
    @Nullable
    public SeekerMode seekerMode() {
        MissilePreset preset = Munitions.missile(currentRound());
        return preset == null ? null : preset.seekerMode();
    }

    /** Round has its own seeker: needs a lock unless the station is EO. */
    public boolean homing() {
        SeekerMode mode = seekerMode();
        return mode == SeekerMode.INFRARED || mode == SeekerMode.OPTICAL || mode == SeekerMode.SEMI_ACTIVE_RADAR
                || mode == SeekerMode.ACTIVE_RADAR;
    }

    /** Degrees; 30 without a missile round. */
    public float seekerFov() {
        MissilePreset preset = Munitions.missile(currentRound());
        return preset == null ? 30f : preset.seekerFov();
    }

    /** Flies a fixed high-altitude profile to the point (no seeker). */
    public boolean presetFlight() {
        MissilePreset preset = Munitions.missile(currentRound());
        return preset != null && preset.highAltitude() && preset.seekerMode() == SeekerMode.DESIGNATED;
    }

    /**
     * Server, no rider: one racked round matching {@code round}, at {@code target}, from its station as laid.
     *
     * @return true if it left
     */
    public boolean fireRound(java.util.function.Predicate<net.minecraft.world.item.ItemStack> round,
                             net.minecraft.world.entity.Entity target) {
        MissileHolderUnit holder = missileHolder();
        int slot = holder == null ? -1 : holder.nextLoaded(round);
        if (slot < 0 || isCoolingDown()) {
            return false;
        }
        AimContext aim = new AimContext();
        aim.position = target.position();
        if (!launchSlot(holder, slot, aim, target, null)) {
            return false;
        }
        markShot();
        return true;
    }

    private boolean launchSlot(MissileHolderUnit holder, int slot, AimContext aimContext,
                               @Nullable net.minecraft.world.entity.Entity locked,
                               @Nullable net.minecraft.world.entity.Entity operator) {
        if (!Munitions.fire(getVehicle(), getWeaponUnit(), fromStation(holder, slot, aimContext), holder.round(slot),
                0f, locked, operator)) {
            return false;
        }
        holder.take(slot);
        getVehicle().queueRecoil(getWeaponUnit(), getData().getRecoil());
        return true;
    }

    /** The station's launch pose with the shot's aim point; the shot itself where the slot has no station. */
    private static AimContext fromStation(MissileHolderUnit holder, int slot, AimContext aimContext) {
        AimContext station = holder.launchContext(slot);
        if (station == null) {
            return aimContext;
        }
        station.position = aimContext.position;
        return station;
    }

    @Override
    public void tick() {
        if (!getData().isHolderOnly()) {
            super.tick();
            return;
        }
        if (!vehicle.level().isClientSide()) {
            MissileHolderUnit holder = missileHolder();
            remainAmmo = holder == null ? 0 : holder.loaded();
            if (holder != null && holder.takesPods() && remainAmmo == 0) {
                if (isReloading()) {
                    tickReload();
                } else if (holder.storedPod() >= 0 && crewed()) {
                    reloadTime = CrewRules.reloadTicks(getWeaponUnit(), holder.getData().getPodSwapTime());
                }
            }
        }
    }

    private boolean crewed() {
        return (getWeaponUnit().getOwner() != null || getWeaponUnit() instanceof AutoWeaponUnit)
                && CrewRules.canLoad(getWeaponUnit());
    }

    /** Pod rack: empty pod out, a loaded one from the stores in. */
    @Override
    public void reload() {
        MissileHolderUnit holder = missileHolder();
        if (getData().isHolderOnly() && holder != null && holder.takesPods()) {
            holder.swapFromStores();
        } else {
            super.reload();
        }
    }

    /** Rack this launcher draws from first, or null: none named, or the vehicle lacks the part. */
    @Nullable
    public MissileHolderUnit missileHolder() {
        String id = getData().getMissileHolder();
        if (id == null || id.isEmpty()) {
            return null;
        }
        return getVehicle().getPartUnit(id)
                .filter(MissileHolderUnit.class::isInstance)
                .map(MissileHolderUnit.class::cast)
                .orElse(null);
    }

    @Override
    public net.minecraft.nbt.CompoundTag serializeNBT(net.minecraft.core.HolderLookup.Provider provider) {
        net.minecraft.nbt.CompoundTag tag = super.serializeNBT(provider);
        if (rackRound != Items.AIR) {
            tag.putString("RackRound", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(rackRound)
                    .toString());
        }
        return tag;
    }

    @Override
    public void deserializeNBT(net.minecraft.core.HolderLookup.Provider provider, net.minecraft.nbt.CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        this.rackRound = nbt.contains("RackRound")
                ? net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.parse(nbt.getString("RackRound")))
                : Items.AIR;
    }

    @Override
    public boolean withSeeker() {
        return true;
    }

}
