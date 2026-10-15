package com.wf.combinedassault.vehicle.weapon;

import net.minecraft.world.level.Level;
import com.wf.combinedassault.audio.WeaponSounds;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.all.AllKeys;
import com.wf.combinedassault.api.CombinedAssaultAPI;
import com.wf.combinedassault.api.custom.sync.SyncDataSerializers;
import com.wf.combinedassault.api.event.VehicleFireEvent;
import com.wf.combinedassault.client.screen.CoordinateInputScreen;
import com.wf.combinedassault.compat.wflib.Munitions;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.combinedassault.custom.sync.PartUnitSyncData;
import com.wf.combinedassault.custom.sync.SyncDataHolder;
import com.wf.combinedassault.custom.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.network.message.ClientVehicleAction;
import com.wf.combinedassault.particle.SmokeCloudOption;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.AutoWeaponUnit;
import com.wf.combinedassault.vehicle.part.WeaponBayUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.wflib.client.fx.WFLights;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public abstract class AbstractVehicleWeapon<T extends BaseVehicleWeaponData> implements INBTSerializable<CompoundTag> {
    /** Muzzle light peak per mm calibre (120 mm => 720: E ~1 at 27 blocks). */
    static final float MUZZLE_LIGHT = 6;
    /** mm; muzzle flash/smoke floor for rounds without a calibre (missiles, countermeasures). */
    static final float DEFAULT_FLASH_CALIBER = 20;
    /** Muzzle lights started, client, cumulative: a light outlives no {@code client.eval} tick. */
    public static int muzzleLights;

    protected final AbstractVehicle vehicle;
    protected final WeaponUnit weaponUnit;
    private final int index;
    private final T data;
    private final String serializeId;
    private Component displayName;
    protected long lastShootTime = 0;
    /** Rounds owed below one, carried between bursts of a sub-tick {@code shoot_interval}. */
    private double burstCarry;
    protected int remainAmmo = 0;
    /**
     * WFLib round the magazine was loaded from; empty = none. One kind per magazine: {@link #reload()} takes the
     * first match, then only the same item + components (cartridge variant).
     */
    protected ItemStack loadedRound = ItemStack.EMPTY;
    protected int reloadTime = 0;
    protected SyncDataHolder<Integer> remainAmmoHolder;
    protected SyncDataHolder<Integer> reloadTimeHolder;
    protected Vec3 targetLocation;
    private List<String> roundBones = List.of();

    /** Prefer the factory in {@link CombinedAssaultAPI#getVehicleWeaponManager()} over this constructor directly. */
    protected AbstractVehicleWeapon(AbstractVehicle vehicle, WeaponUnit weaponUnit, int index, T data, String serializeId) {
        this.vehicle = vehicle;
        this.weaponUnit = weaponUnit;
        this.index = index;
        this.data = data;
        this.displayName = Component.translatable(data.getName());
        this.serializeId = serializeId;
    }

    public void defineSyncData(PartUnitSyncData syncData) {
        this.remainAmmoHolder = syncData.define(SyncDataSerializers.INT, this::setRemainAmmo, this::getRemainAmmo, remainAmmo);
        this.reloadTimeHolder = syncData.define(SyncDataSerializers.INT, this::setReloadTime, this::getReloadTime, reloadTime);
        // Client lays the gun and picks lock rules off the breech round.
        syncData.define(SyncDataSerializers.ROUND, this::setLoadedRound, () -> loadedRound, loadedRound);
    }

    public abstract boolean shoot(List<AimContext> aimContexts, LivingEntity shooter);

    /**
     * Cooldown, reload, ammo, then the breech round once per aim context. Shots arrive <= 1/tick =>
     * {@code shoot_interval < 50} ms fires {@code 50 / shoot_interval} rounds per shot, strung out over the tick.
     */
    protected boolean shootLoaded(List<AimContext> aimContexts) {
        if (isCoolingDown() || isReloading()) {
            return false;
        }
        int rounds = 1;
        if (getShootInterval() < 50) {
            burstCarry += 50.0 / getShootInterval();
            rounds = (int) burstCarry;
            burstCarry -= rounds;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        int fired = 0;
        for (; fired < rounds && consumeAmmo(aimContexts); fired++) {
            float lead = (float) fired / rounds;
            for (AimContext aimContext : aimContexts) {
                if (Munitions.fire(vehicle, weaponUnit, aimContext, loadedRound, data.getInaccuracy(),
                        root.getLockedEntity(), root.getOwner(), lead)) {
                    vehicle.queueRecoil(weaponUnit, data.getRecoil());
                }
            }
        }
        if (fired == 0) {
            return false;
        }
        this.lastShootTime = System.currentTimeMillis();
        return true;
    }

    public boolean check(List<AimContext> aimContexts, LivingEntity shooter) {
        return true;
    }

    public void onSwitchTo() {}

    public void onSwitchFrom() {}

    public void setRemainAmmo(int remainAmmo) {
        this.remainAmmo = remainAmmo;
    }

    protected void setReloadTime(int reloadTime) {
        this.reloadTime = reloadTime;
        if (reloadTime == 0 && vehicle.level().isClientSide()) {
            WeaponSounds.atPivot(this, getData().sound("reload"), 0);
        }
    }

    public boolean isCoolingDown() {
        return System.currentTimeMillis() - lastShootTime < (this.getShootInterval() - (vehicle.level().isClientSide() ? 0 : 20));
    }

    /** Wall-clock cooldown off; a sprinting gametest server runs 40 ticks in ~60 ms. */
    public void endCooldown() {
        lastShootTime = 0;
    }

    public long getShootInterval() {
        return this.getData().getShootInterval();
    }

    @Nullable
    public Vec3 getTargetLocation() {
        return targetLocation;
    }

    public void setTargetLocation(@Nullable Vec3 targetLocation) {
        this.targetLocation = targetLocation;
    }

    /** Coordinates are taken by a {@code loc} station, or any station laid {@code AUTO}. */
    public boolean takesCoordinates() {
        return weaponUnit.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.LOC
                || weaponUnit.indirectFire().mode() == com.wf.combinedassault.indirect.LayingMode.AUTO;
    }

    @OnlyIn(Dist.CLIENT)
    public void openCoordinateInputScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || !takesCoordinates()) {
            return;
        }
        Vec3 initialTarget = targetLocation != null ? targetLocation : currentAimHitPosition();
        minecraft.setScreen(new CoordinateInputScreen(initialTarget, position -> {
            if (takesCoordinates()) {
                targetLocation = position;
            }
        }));
    }

    @OnlyIn(Dist.CLIENT)
    protected boolean preClientShoot() {
        Minecraft minecraft = Minecraft.getInstance();
        WeaponUnit parentWeaponUnit = weaponUnit.getParentWeaponUnit();
        if (parentWeaponUnit != null) {
            WeaponBayUnit weaponBayUnit = parentWeaponUnit.weaponBayUnits.get(this);
            if (weaponBayUnit != null && !weaponBayUnit.isOn()) {
                parentWeaponUnit.toggleCurrentWeaponBay();
                return false;
            }
        }
        if (targetLocation == null && (weaponUnit.getFireControlSensorType() == WeaponUnitData.FireControlSensorType.LOC
                || weaponUnit.indirectFire().mode() == com.wf.combinedassault.indirect.LayingMode.AUTO
                && com.wf.combinedassault.indirect.IndirectFire.selfGuided(weaponUnit))) {
            if (minecraft.player != null) {
                Component tips = Component.translatable("tips.preset_coordinate_required", AllKeys.PRESET_COORDINATE_INPUT.getTranslatedKeyMessage());
                minecraft.player.displayClientMessage(tips, true);
            }
            return false;
        }
        return true;
    }

    @OnlyIn(Dist.CLIENT)
    public boolean doClientShoot() {
        if (!preClientShoot()) {
            return false;
        }
        VehicleFireEvent.Pre event = new VehicleFireEvent.Pre(vehicle, this, Minecraft.getInstance().player);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return false;
        }
        if (isCoolingDown()) {
            return false;
        }
        if (!hasAmmo()) {
            return false;
        }
        int partUnitIndex;
        Optional<AbstractVehicleWeapon<?>> weaponOptional = weaponUnit.getCurrentWeapon();
        if (weaponOptional.isPresent() && weaponOptional.get() == this) {
            partUnitIndex = weaponUnit.getIndex();
        } else {
            partUnitIndex = weaponUnit.getParentWeaponUnit() != null ? weaponUnit.getParentWeaponUnit().getIndex() : weaponUnit.getIndex();
        }
        List<AimContext> aimContexts;
        if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.RIPPLE) {
            aimContexts = Collections.singletonList(weaponUnit.aimContext());
        } else if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.SALVO || weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.FULL_SALVO) {
            aimContexts = weaponUnit.aimContexts();
        } else {
            return false;
        }
        Vec3 aimHitPosition = targetLocation != null ? targetLocation : currentAimHitPosition();
        aimContexts.forEach(aimContext -> {
            aimContext.from = aimContext.from.add(vehicle.getDeltaMovement());
            aimContext.position = aimHitPosition;
        });
        lastShootTime = System.currentTimeMillis();
        sendShoot(this.getVehicle(), partUnitIndex, getIndex(), aimContexts);
        return true;
    }

    @OnlyIn(Dist.CLIENT)
    protected Vec3 currentAimHitPosition() {
        WeaponUnit rootParentWeaponUnit = weaponUnit.getRootParentWeaponUnit();
        List<Vec3> positions = rootParentWeaponUnit.aimContexts().stream().map(context -> context.from).toList();
        double x = positions.stream().mapToDouble(pos -> pos.x).average().orElse(0);
        double y = positions.stream().mapToDouble(pos -> pos.y).average().orElse(0);
        double z = positions.stream().mapToDouble(pos -> pos.z).average().orElse(0);
        AimContext currentAimContext = rootParentWeaponUnit.aimContext();
        Vec3 targetVec = VectorUtil.rotToVec(currentAimContext.direction.x, currentAimContext.direction.y);
        Vec3 start = new Vec3(x, y, z);
        Vec3 end = start.add(targetVec.scale(LocalVehiclePlayer.renderDistance()));
        return VectorUtil.hitPosition(LocalVehiclePlayer.instance.getPlayer(), start, end);
    }

    @OnlyIn(Dist.CLIENT)
    public void onClientFire() {
        Level level = vehicle.level();
        WeaponSounds.fire(this);
        List<AimContext> aimContexts;
        if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.RIPPLE) {
            aimContexts = Collections.singletonList(weaponUnit.aimContext());
            weaponUnit.countFire(1);
        } else if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.SALVO) {
            aimContexts = weaponUnit.aimContexts();
            weaponUnit.countFire(aimContexts.size());
        } else {
            return;
        }
        float recoil = data.getRecoil();
        float caliber = Math.max(Munitions.caliber(loadedRound), DEFAULT_FLASH_CALIBER);
        float scaleDivisor = 20f + 30f * caliber / 1000f;
        float scale = caliber / scaleDivisor;
        for (Vec3 muzzlePos : aimContexts.stream().map(aimContext -> aimContext.from).toList()) {
            WFLights.muzzle(muzzlePos.x, muzzlePos.y, muzzlePos.z, Mth.clamp(MUZZLE_LIGHT * caliber, 30, 900),
                    1.5F + caliber / 40);
            muzzleLights++;
            for (int i = 0; i < 3 * recoil + 1; i++) {
                double dx = (level.random.nextDouble() - 0.5) * 0.4 * recoil;
                double dy = (level.random.nextDouble() - 0.5) * 0.2 * recoil;
                double dz = (level.random.nextDouble() - 0.5) * 0.4 * recoil;
                level.addParticle(new SmokeCloudOption(1f, 0.35f + (float) level.random.nextDouble() * 0.3f, 0f, 0.5f, 0f, 5, 0.18f * scale, 0.1f), true,
                        muzzlePos.x + dx, muzzlePos.y + dy, muzzlePos.z + dz,
                        0.015, 0.015, 0.015);
            }
            for (int i = 0; i < 3 * recoil + 1; i++) {
                double dx = (level.random.nextDouble() - 0.5) * 0.4 * recoil;
                double dy = (level.random.nextDouble() - 0.5) * 0.2 * recoil;
                double dz = (level.random.nextDouble() - 0.5) * 0.4 * recoil;
                level.addParticle(new SmokeCloudOption(0.7f, 0.7f, 0.7f, 0.5f, 0f, 10, 0.3f * scale, 0.1f), true,
                        muzzlePos.x + dx, muzzlePos.y + dy, muzzlePos.z + dz,
                        0.015, 0.015, 0.015);
            }
        }
    }

    public int getReloadTime() {
        return reloadTime;
    }

    /** The round in the breech, or empty. One kind per magazine; see {@link #loadedRound}. */
    public ItemStack getLoadedRound() {
        return loadedRound;
    }

    /** Round a solution / pipper is laid for: the breech's, else the weapon's default (empty gun still lays). */
    public ItemStack solutionRound() {
        return !loadedRound.isEmpty() || data.getReload() == null ? loadedRound : data.getReload().defaultRound();
    }

    public void setLoadedRound(ItemStack round) {
        this.loadedRound = round == null ? ItemStack.EMPTY : round;
    }

    public int getRemainAmmo() {
        return remainAmmo;
    }

    public int getMaxCapacity() {
        return weaponUnit.getAmmoCapacity() != -1 ? weaponUnit.getAmmoCapacity() : this.getData().getMaxCapacity();
    }

    @OnlyIn(Dist.CLIENT)
    public static void sendShoot(AbstractVehicle abstractVehicle, int partUnitIndex, int weaponIndex, List<AimContext> aimContexts) {
        ClientVehicleAction action = new ClientVehicleAction();
        action.vehicleEntityId = abstractVehicle.getId();
        action.partUnitIndex = partUnitIndex;
        action.shoot = true;
        action.weaponIndex = weaponIndex;
        action.aimContexts = aimContexts;
        PacketDistributor.sendToServer(action);
    }

    public boolean hasAmmo() {
        return remainAmmo > 0;
    }

    public boolean consumeAmmo(List<AimContext> aimContexts) {
        if (remainAmmo == 0) {
            return false;
        }
        if (remainAmmo >= aimContexts.size()) {
            remainAmmo -= aimContexts.size();
            return true;
        }
        aimContexts.subList(remainAmmo, aimContexts.size()).clear();
        remainAmmo = 0;
        return true;
    }

    public int getStorageAmmo() {
        if (vehicle.level().isClientSide()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < vehicle.getItemHandler().getSlots(); i++) {
            ItemStack stack = vehicle.getItemHandler().getStackInSlot(i);
            if (isAmmoForWeapon(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public boolean hasStorageAmmo() {
        if (vehicle.level().isClientSide()) {
            return false;
        }
        for (int i = 0; i < vehicle.getItemHandler().getSlots(); i++) {
            ItemStack stack = vehicle.getItemHandler().getStackInSlot(i);
            if (stack.getItem() == AllItems.AMMO_CREATIVE.get()) {
                return true;
            }
            if (isAmmoForWeapon(stack)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAmmoForWeapon(ItemStack stack) {
        return data.getReload() != null && data.getReload().isAmmo(stack);
    }

    public boolean canReload() {
        return hasStorageAmmo() && !isReloading() && (weaponUnit.getOwner() != null || weaponUnit instanceof AutoWeaponUnit)
                && CrewRules.canLoad(weaponUnit);
    }

    public void startReload() {
        this.reloadTime = CrewRules.reloadTicks(weaponUnit, data.getReload().getTime());
    }

    public void tickReload() {
        if (reloadTime > 0 && CrewRules.canLoad(weaponUnit)) {
            reloadTime--;
            if (reloadTime == 0) {
                reload();
            }
        }
    }

    public void reload() {
        int maxCap = getMaxCapacity();
        if (remainAmmo <= 0) {
            // Breech empty: next load decides round kind.
            this.loadedRound = ItemStack.EMPTY;
        }
        if (vehicle.getItemStacks().stream().anyMatch(stack -> stack.getItem() == AllItems.AMMO_CREATIVE.get())) {
            remainAmmo = maxCap;
            if (this.loadedRound.isEmpty() && data.getReload() != null) {
                this.loadedRound = data.getReload().defaultRound();
            }
        } else {
            for (var item : vehicle.getItemStacks()) {
                int need = maxCap - remainAmmo;
                if (need <= 0) {
                    break;
                }
                if (!this.isAmmoForWeapon(item)) {
                    continue;
                }
                if (!this.loadedRound.isEmpty() && !ItemStack.isSameItemSameComponents(this.loadedRound, item)) {
                    // Mixed stack != same gun ballistics-wise; leave for next reload.
                    continue;
                }
                if (this.loadedRound.isEmpty()) {
                    this.loadedRound = item.copyWithCount(1);
                }
                int toTake = Math.min(need, item.getCount());
                item.shrink(toTake);
                remainAmmo += toTake;
            }
        }
    }

    public boolean isReloading() {
        return reloadTime > 0;
    }

    public void tick() {
        if (vehicle.level().isClientSide()) {
            return;
        }
        if (remainAmmo == 0) {
            if (isReloading()) {
                tickReload();
            } else if (canReload()) {
                startReload();
            }
        }
    }

    public boolean withSeeker() {
        return false;
    }

    public T getData() {
        return data;
    }

    public int getIndex() {
        return index;
    }

    public AbstractVehicle getVehicle() {
        return vehicle;
    }

    public WeaponUnit getWeaponUnit() {
        return weaponUnit;
    }

    public void setDisplayName(Component displayName) {
        this.displayName = displayName;
    }

    public Component getDisplayName() {
        return displayName;
    }

    public boolean hasSyncData() {
        return true;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        tag.putInt("RemainAmmo", remainAmmo);
        if (!loadedRound.isEmpty()) {
            tag.put("LoadedRound", loadedRound.save(provider));
        }
        return tag;
    }

    @Override
    public void deserializeNBT(@org.jetbrains.annotations.NotNull HolderLookup.Provider provider, @org.jetbrains.annotations.NotNull CompoundTag nbt) {
        this.remainAmmo = nbt.getInt("RemainAmmo");
        this.loadedRound = nbt.contains("LoadedRound")
                ? ItemStack.parse(provider, nbt.get("LoadedRound")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
    }

    /** Model bones showing loaded rounds, firing order; see {@code RoundStations}. */
    public List<String> getRoundBones() {
        return roundBones;
    }

    public void setRoundBones(List<String> roundBones) {
        this.roundBones = roundBones;
    }

    public String getSerializeId() {
        return serializeId;
    }

}
