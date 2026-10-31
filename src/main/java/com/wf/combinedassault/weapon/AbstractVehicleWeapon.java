package com.wf.combinedassault.weapon;

import net.minecraft.world.level.Level;
import com.wf.combinedassault.vehicle.audio.WeaponSounds;
import com.wf.combinedassault.weapon.barrel.GunBarrel;
import com.wf.combinedassault.weapon.client.BarrelFx;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wf.combinedassault.vehicle.client.VehicleKeys;
import com.wf.combinedassault.pack.sync.SyncDataSerializers;
import com.wf.combinedassault.vehicle.event.VehicleFireEvent;
import com.wf.combinedassault.weapon.client.CoordinateInputScreen;
import com.wf.combinedassault.weapon.ordnance.Munitions;
import com.wf.combinedassault.weapon.part.data.WeaponUnitData;
import com.wf.combinedassault.pack.sync.PartUnitSyncData;
import com.wf.combinedassault.pack.sync.SyncDataHolder;
import com.wf.combinedassault.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.message.ClientVehicleAction;
import com.wf.combinedassault.render.particle.SmokeCloudOption;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.weapon.part.AutoWeaponUnit;
import com.wf.combinedassault.weapon.part.WeaponBayUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.wflib.ammo.api.AmmoItems;
import com.wf.wflib.fx.api.WFLights;
import com.wf.wflib.magazine.api.MagContents;
import com.wf.wflib.magazine.api.MagFill;
import com.wf.wflib.magazine.api.MagazineItems;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public abstract class AbstractVehicleWeapon<T extends BaseVehicleWeaponData> implements INBTSerializable<CompoundTag> {
    /** Muzzle light peak per mm calibre (120 mm => 720: E ~1 at 27 blocks). */
    static final float MUZZLE_LIGHT = 6;
    /** mm; muzzle flash/smoke floor for rounds without a calibre (missiles, countermeasures). */
    static final float DEFAULT_FLASH_CALIBER = 20;
    /** Muzzle lights started, client, cumulative: a light outlives no {@code client.eval} tick. */
    private static int muzzleLights;

    public static int muzzleLights() {
        return muzzleLights;
    }

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
    /** Kind {@link #reload()} loads; empty = first in the stores. Not in the stores => first, and it becomes the pick. */
    protected ItemStack selectedRound = ItemStack.EMPTY;
    /** Seated magazine, {@code remainAmmo} = its rounds; empty = loose feed. Server. */
    private ItemStack magazine = ItemStack.EMPTY;
    /** Seated magazine's capacity, 0 = none; synced for the client's {@link #getMaxCapacity}. */
    private int magazineCapacity;
    private SyncDataHolder<ItemStack> selectedRoundHolder;
    /** Server: this weapon's kinds in the stores, refreshed every {@link #STOCK_INTERVAL} ticks. */
    private List<RoundCount> stock = List.of();
    private static final int STOCK_INTERVAL = 10;
    protected int reloadTime = 0;
    /** {@link #reloadTime} at its start; synced => client reload progress. */
    private int reloadSpan;
    /** Firings, synced: a change stamps {@link #shotTick} on the client. */
    private int shots;
    /** {@code vehicle.tickCount} at the last firing; MIN_VALUE = never. */
    private int shotTick = Integer.MIN_VALUE;
    /** {@code vehicle.tickCount} at the first firing of the current sustained burst ({@link #cycleSeconds}). */
    private int burstTick;
    protected SyncDataHolder<Integer> remainAmmoHolder;
    protected SyncDataHolder<Integer> reloadTimeHolder;
    protected Vec3 targetLocation;
    private List<String> roundBones = List.of();
    @Nullable
    private final GunBarrel barrel;

    /** Prefer the factory in {@code CommonAssetsManager.vehicleWeaponManager()} over this constructor directly. */
    protected AbstractVehicleWeapon(AbstractVehicle vehicle, WeaponUnit weaponUnit, int index, T data, String serializeId) {
        this.vehicle = vehicle;
        this.weaponUnit = weaponUnit;
        this.index = index;
        this.data = data;
        this.displayName = Component.translatable(data.getName());
        this.serializeId = serializeId;
        this.barrel = data.getBarrel() == null ? null : new GunBarrel(this, data.getBarrel());
    }

    /** Null: weapon has no {@code barrel}. */
    @Nullable
    public GunBarrel barrel() {
        return barrel;
    }

    public void defineSyncData(PartUnitSyncData syncData) {
        this.remainAmmoHolder = syncData.define(SyncDataSerializers.INT, this::setRemainAmmo, this::getRemainAmmo, remainAmmo);
        this.reloadTimeHolder = syncData.define(SyncDataSerializers.INT, this::setReloadTime, this::getReloadTime, reloadTime);
        // Client lays the gun and picks lock rules off the breech round.
        syncData.define(SyncDataSerializers.ROUND, this::setLoadedRound, () -> loadedRound, loadedRound);
        this.selectedRoundHolder = syncData.define(SyncDataSerializers.ROUND, round -> this.selectedRound = round,
                () -> selectedRound, selectedRound);
        syncData.define(SyncDataSerializers.ROUND_STOCK, stock -> this.stock = stock, () -> stock, stock);
        syncData.define(SyncDataSerializers.INT, capacity -> this.magazineCapacity = capacity,
                () -> magazineCapacity, magazineCapacity);
        syncData.define(SyncDataSerializers.INT, span -> this.reloadSpan = span, () -> reloadSpan, reloadSpan);
        syncData.define(SyncDataSerializers.INT, this::setShots, () -> shots, shots);
        if (barrel != null) {
            barrel.defineSyncData(syncData);
        }
    }

    private void setShots(int shots) {
        if (shots != this.shots) {
            stampShot();
        }
        this.shots = shots;
    }

    private void stampShot() {
        if (secondsSinceShot(0) > sustainGap()) {
            burstTick = vehicle.tickCount;
        }
        shotTick = vehicle.tickCount;
    }

    /** Gap that still continues a burst, s: one interval + one tick. */
    private float sustainGap() {
        return getShootInterval() / 1000f + 0.05f;
    }

    /** Server, once per firing: cooldown + {@code .fire} clip. */
    protected void markShot() {
        this.lastShootTime = System.currentTimeMillis();
        this.shots++;
        stampShot();
    }

    /** +inf before the first firing. */
    public float secondsSinceShot(float partialTick) {
        return shotTick == Integer.MIN_VALUE ? Float.POSITIVE_INFINITY : (vehicle.tickCount - shotTick + partialTick) / 20f;
    }

    /**
     * Seconds into the current round's cycle ({@code .fire} clips): interval < 1 tick, burst sustained => phase at
     * {@code shoot_interval} from the burst start (rounds are strung out within ticks); else since the last firing
     * (1..2 tick intervals fire every 2 ticks: measured M2 60 ms => 100 ms).
     */
    public float cycleSeconds(float partialTick) {
        float since = secondsSinceShot(partialTick);
        float interval = getShootInterval() / 1000f;
        return interval >= 0.05f || since > sustainGap() ? since
                : (vehicle.tickCount - burstTick + partialTick) / 20f % interval;
    }

    /** [0,1] through the current reload; 0 when not reloading. */
    public float reloadProgress(float partialTick) {
        if (reloadTime <= 0 || reloadSpan <= 0) {
            return 0;
        }
        return Mth.clamp(1 - (reloadTime - partialTick) / reloadSpan, 0, 1);
    }

    public abstract boolean shoot(List<AimContext> aimContexts, LivingEntity shooter);

    /**
     * Cooldown, reload, ammo, then the breech round once per aim context. Shots arrive <= 1/tick =>
     * {@code shoot_interval < 50} ms fires {@code 50 / shoot_interval} rounds per shot, strung out over the tick.
     */
    protected boolean shootLoaded(List<AimContext> aimContexts) {
        boolean cookOff = barrel != null && barrel.cookingOff();
        if (!weaponUnit.supported()) {
            return false;
        }
        if (!cookOff && (isCoolingDown() || isReloading()) || barrel != null && !barrel.canFire()) {
            return false;
        }
        int rounds = 1;
        if (!cookOff && getShootInterval() < 50) {
            burstCarry += 50.0 / getShootInterval();
            rounds = (int) burstCarry;
            burstCarry -= rounds;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        int fired = 0;
        for (; fired < rounds && consumeAmmo(aimContexts); fired++) {
            float lead = (float) fired / rounds;
            float spread = barrel == null ? 0 : barrel.fired();
            for (AimContext aimContext : aimContexts) {
                if (Munitions.fire(vehicle, weaponUnit, aimContext, feed(), data.getInaccuracy() + spread,
                        root.getLockedEntity(), cookOff ? null : root.getOwner(), lead,
                        barrel == null ? 1 : barrel.velocity())) {
                    vehicle.queueRecoil(weaponUnit, data.getRecoil());
                    if (data.getBackblast() != null && vehicle.level() instanceof ServerLevel level) {
                        data.getBackblast().fire(level, vehicle, aimContext,
                                weaponUnit.barrelLength(weaponUnit.getCurrentBolt()), root.getOwner());
                    }
                }
            }
            if (barrel != null && barrel.rollJam(vehicle.getRandom())) {
                fired++;
                break;
            }
        }
        if (fired == 0) {
            return false;
        }
        markShot();
        return true;
    }

    /** Server: one round out of the breech unfired (jam cleared). */
    public void discardRound() {
        if (remainAmmo > 0) {
            remainAmmo--;
            feed();
        }
    }

    /** Server: partly fired => breech back to the stores, {@link #tick()} reloads. */
    public boolean manualReload() {
        if (isReloading() || remainAmmo <= 0 || remainAmmo >= getMaxCapacity() || !canReload()) {
            return false;
        }
        unload();
        return true;
    }

    /** Round for one shot already counted off {@code remainAmmo}: the magazine's top, else {@link #loadedRound}. */
    private ItemStack feed() {
        if (magazine.isEmpty()) {
            return loadedRound;
        }
        MagContents contents = MagFill.contents(magazine);
        ItemStack round = AmmoItems.stack(contents.top(), 1);
        contents = contents.pop();
        magazine.set(MagazineItems.CONTENTS.get(), contents);
        loadedRound = contents.isEmpty() ? ItemStack.EMPTY : AmmoItems.stack(contents.top(), 1);
        return round;
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
                Component tips = Component.translatable("tips.preset_coordinate_required", VehicleKeys.PRESET_COORDINATE_INPUT.getTranslatedKeyMessage());
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
        if (barrel != null && !barrel.canFire()) {
            BarrelFx.dryClick(this);
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
        return VectorUtil.hitPosition(LocalVehiclePlayer.instance().getPlayer(), start, end);
    }

    @OnlyIn(Dist.CLIENT)
    public void onClientFire() {
        Level level = vehicle.level();
        WeaponSounds.fire(this);
        List<AimContext> aimContexts;
        int[] bolts;
        if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.RIPPLE) {
            aimContexts = Collections.singletonList(weaponUnit.aimContext());
            bolts = new int[]{weaponUnit.getCurrentBoltIndex()};
            weaponUnit.countFire(1);
        } else if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.SALVO) {
            aimContexts = weaponUnit.aimContexts();
            bolts = java.util.stream.IntStream.range(0, aimContexts.size()).toArray();
            weaponUnit.countFire(aimContexts.size());
        } else {
            return;
        }
        com.wf.combinedassault.render.fx.CasingFx.shot(weaponUnit, bolts, solutionRound(), data.getCasingDelay());
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

    public ItemStack getSelectedRound() {
        return selectedRound;
    }

    /** Client: shown until the {@code ClientSelectRound} echo. */
    public void predictSelectedRound(ItemStack round) {
        selectedRoundHolder.predict(round);
    }

    /** Kinds the operator picks from; stores order. */
    public List<RoundCount> roundOptions() {
        return stock;
    }

    /**
     * Server: operator's pick, a round or a magazine kind. Another kind loaded => back to the stores, then reload
     * ({@link #tick()}).
     */
    public void selectRound(ItemStack round) {
        if (!isAmmoForWeapon(round) && !isMagazineForWeapon(round)) {
            return;
        }
        selectedRound = round.copyWithCount(1);
        if (remainAmmo > 0 && !RoundCount.sameKind(magazine.isEmpty() ? loadedRound : magazine, selectedRound)) {
            unload();
        }
    }

    /** Breech / magazine back to the stores, overflow dropped; creative supply => discarded. */
    private void unload() {
        if (magazine.isEmpty()) {
            store(creativeSupply() ? ItemStack.EMPTY : loadedRound.copyWithCount(remainAmmo));
        }
        ejectMagazine();
        remainAmmo = 0;
        loadedRound = ItemStack.EMPTY;
    }

    /** Seated magazine, as fired down, back to the stores; creative supply => discarded. */
    private void ejectMagazine() {
        if (!magazine.isEmpty() && !creativeSupply()) {
            store(magazine);
        }
        magazine = ItemStack.EMPTY;
        magazineCapacity = 0;
    }

    private void store(ItemStack back) {
        var handler = vehicle.getItemHandler();
        for (int i = 0; i < handler.getSlots() && !back.isEmpty(); i++) {
            back = handler.insertItem(i, back, false);
        }
        if (!back.isEmpty()) {
            vehicle.spawnAtLocation(back);
        }
    }

    /**
     * Empty magazine out, a filled one in: creative => the pick's kind full, else the first; stores => the pick's kind
     * with most rounds, else the fullest. Loose pick on a {@code loose} weapon with loose rounds to load => none.
     *
     * @return false: none seated, loose feed next
     */
    private boolean seatMagazine() {
        ejectMagazine();
        var reload = data.getReload();
        boolean loosePick = !selectedRound.isEmpty() && !MagFill.isMagazine(selectedRound);
        if (loosePick && (creativeSupply() || vehicle.getItemStacks().stream().anyMatch(this::isAmmoForWeapon))) {
            return false;
        }
        ItemStack next = ItemStack.EMPTY;
        if (creativeSupply()) {
            List<ItemStack> full = reload.fullMagazines();
            next = full.stream().filter(m -> RoundCount.sameKind(m, selectedRound)).findFirst()
                    .orElse(full.isEmpty() ? ItemStack.EMPTY : full.getFirst());
        } else {
            var handler = vehicle.getItemHandler();
            int best = -1;
            boolean bestPicked = false;
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack stack = handler.getStackInSlot(i);
                if (!isMagazineForWeapon(stack)) {
                    continue;
                }
                boolean picked = RoundCount.sameKind(stack, selectedRound);
                if (best < 0 || picked && !bestPicked || picked == bestPicked
                        && MagFill.contents(stack).total() > MagFill.contents(handler.getStackInSlot(best)).total()) {
                    best = i;
                    bestPicked = picked;
                }
            }
            if (best >= 0) {
                next = handler.extractItem(best, 1, false);
            }
        }
        if (next.isEmpty()) {
            return false;
        }
        magazine = next;
        MagContents contents = MagFill.contents(magazine);
        magazineCapacity = contents.capacity(MagFill.magazine(magazine).perLink());
        remainAmmo = contents.total();
        loadedRound = AmmoItems.stack(contents.top(), 1);
        if (!RoundCount.sameKind(magazine, selectedRound)) {
            selectedRound = magazine.copy();
        }
        return true;
    }

    private boolean creativeSupply() {
        return vehicle.getItemStacks().stream().anyMatch(stack -> stack.getItem() == WeaponContent.AMMO_CREATIVE.get());
    }

    private List<RoundCount> countStock() {
        if (data.getReload() == null) {
            return List.of();
        }
        List<RoundCount> out = new ArrayList<>();
        if (creativeSupply()) {
            for (ItemStack mag : data.getReload().fullMagazines()) {
                out.add(new RoundCount(mag, RoundCount.UNLIMITED));
            }
            if (data.getReload().takesLoose()) {
                for (ItemStack round : data.getReload().rounds()) {
                    out.add(new RoundCount(round, RoundCount.UNLIMITED));
                }
            }
        } else {
            for (ItemStack stack : vehicle.getItemStacks()) {
                if (isMagazineForWeapon(stack)) {
                    add(out, stack, 1);
                } else if (isAmmoForWeapon(stack)) {
                    add(out, stack, stack.getCount());
                }
            }
        }
        if (!magazine.isEmpty()) {
            add(out, magazine, 0);
        } else if (!loadedRound.isEmpty()) {
            add(out, loadedRound, 0);
        }
        return List.copyOf(out);
    }

    private static void add(List<RoundCount> out, ItemStack round, int count) {
        for (int i = 0; i < out.size(); i++) {
            RoundCount entry = out.get(i);
            if (RoundCount.sameKind(entry.round(), round)) {
                if (entry.count() != RoundCount.UNLIMITED) {
                    out.set(i, new RoundCount(entry.round(), entry.count() + count));
                }
                return;
            }
        }
        out.add(new RoundCount(round.copyWithCount(1), count));
    }

    public int getRemainAmmo() {
        return remainAmmo;
    }

    public int getMaxCapacity() {
        if (magazineCapacity > 0) {
            return magazineCapacity;
        }
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
            if (isMagazineForWeapon(stack)) {
                total += MagFill.contents(stack).total();
            } else if (isAmmoForWeapon(stack)) {
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
            if (stack.getItem() == WeaponContent.AMMO_CREATIVE.get()) {
                return true;
            }
            if (isAmmoForWeapon(stack) || isMagazineForWeapon(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Loose round it loads; a magazine-only weapon takes none. */
    public boolean isAmmoForWeapon(ItemStack stack) {
        return data.getReload() != null && data.getReload().takesLoose() && data.getReload().isAmmo(stack);
    }

    /** Magazine it takes, holding rounds. */
    public boolean isMagazineForWeapon(ItemStack stack) {
        return data.getReload() != null && data.getReload().isMagazine(stack) && !MagFill.contents(stack).isEmpty();
    }

    public boolean canReload() {
        return hasStorageAmmo() && !isReloading() && (weaponUnit.getOwner() != null || weaponUnit instanceof AutoWeaponUnit)
                && CrewRules.canLoad(weaponUnit);
    }

    public void startReload() {
        this.reloadTime = CrewRules.reloadTicks(weaponUnit, data.getReload().getTime());
        this.reloadSpan = reloadTime;
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
        if (remainAmmo <= 0) {
            // Breech empty: next load decides round kind.
            this.loadedRound = ItemStack.EMPTY;
        }
        var reload = data.getReload();
        if (reload != null && reload.magazineFed() && remainAmmo <= 0 && seatMagazine()) {
            return;
        }
        if (reload != null && !reload.takesLoose()) {
            return;
        }
        int maxCap = getMaxCapacity();
        if (creativeSupply()) {
            remainAmmo = maxCap;
            if (this.loadedRound.isEmpty() && data.getReload() != null) {
                this.loadedRound = selectedRound.isEmpty() ? data.getReload().defaultRound() : selectedRound.copy();
            }
        } else {
            if (this.loadedRound.isEmpty() && !selectedRound.isEmpty() && vehicle.getItemStacks().stream()
                    .anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, selectedRound) && isAmmoForWeapon(stack))) {
                this.loadedRound = selectedRound.copy();
            }
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
                    this.selectedRound = this.loadedRound.copy();
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
        if (vehicle.tickCount % STOCK_INTERVAL == 0) {
            stock = countStock();
        }
        if (barrel != null) {
            barrel.tick();
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
        if (!selectedRound.isEmpty()) {
            tag.put("SelectedRound", selectedRound.save(provider));
        }
        if (!magazine.isEmpty()) {
            tag.put("Magazine", magazine.save(provider));
        }
        if (barrel != null) {
            barrel.save(tag);
        }
        return tag;
    }

    @Override
    public void deserializeNBT(@org.jetbrains.annotations.NotNull HolderLookup.Provider provider, @org.jetbrains.annotations.NotNull CompoundTag nbt) {
        this.remainAmmo = nbt.getInt("RemainAmmo");
        this.loadedRound = nbt.contains("LoadedRound")
                ? ItemStack.parse(provider, nbt.get("LoadedRound")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        this.selectedRound = nbt.contains("SelectedRound")
                ? ItemStack.parse(provider, nbt.get("SelectedRound")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        this.magazine = nbt.contains("Magazine")
                ? ItemStack.parse(provider, nbt.get("Magazine")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        this.magazineCapacity = magazine.isEmpty() ? 0
                : MagFill.contents(magazine).capacity(MagFill.magazine(magazine).perLink());
        if (barrel != null) {
            barrel.load(nbt);
        }
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
