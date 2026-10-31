package com.wf.combinedassault.weapon.barrel;

import com.wf.combinedassault.pack.sync.SyncDataSerializers;
import com.wf.combinedassault.pack.sync.PartUnitSyncData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.dynamics.part.PartClipTracks;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.barrel.api.BarrelSpec;
import com.wf.wflib.barrel.api.BarrelState;
import com.wf.wflib.barrel.api.Barrels;
import com.wf.wflib.barrel.api.SpareBarrel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One weapon's barrel: heat, jam + clear, barrel change (spare <-> barrel, barrel off without a spare, spare onto an
 * empty gun), {@link BarrelSpec#variants}. Server-authoritative, synced.
 */
public final class GunBarrel {
    /** Ticks since the last shot before a chambered round may cook off. */
    static final int COOK_OFF_QUIET = 10;
    /** Probe job unheard this long => abandoned. */
    static final int PROBE_GRACE = 2;

    private final AbstractVehicleWeapon<?> weapon;
    private final BarrelSpec spec;
    private BarrelState state = BarrelState.NEW;
    private boolean fitted = true;
    /** {@link BarrelSpec#variants} key; empty = standard. */
    private Optional<String> variant = Optional.empty();
    private boolean jammed;
    private int clearing;
    private int clearSpan;
    private int swapping;
    private int swapSpan;
    /** Server, while {@code swapping > 0}. */
    @Nullable
    private UUID swapper;
    private boolean seated;
    private long probeHeld;
    private long lastShot = Long.MIN_VALUE / 2;
    private boolean cookingOff;
    @Nullable
    private WeaponUnit holder;

    public GunBarrel(AbstractVehicleWeapon<?> weapon, BarrelSpec spec) {
        this.weapon = weapon;
        this.spec = spec;
    }

    public void defineSyncData(PartUnitSyncData sync) {
        sync.define(SyncDataSerializers.BARREL, s -> state = s, () -> state, state);
        sync.define(SyncDataSerializers.BOOLEAN, f -> fitted = f, () -> fitted, fitted);
        sync.define(SyncDataSerializers.STRING, v -> variant = v.isEmpty() ? Optional.empty() : Optional.of(v),
                () -> variant.orElse(""), "");
        sync.define(SyncDataSerializers.BOOLEAN, j -> jammed = j, () -> jammed, jammed);
        sync.define(SyncDataSerializers.INT, c -> clearing = c, () -> clearing, clearing);
        sync.define(SyncDataSerializers.INT, c -> clearSpan = c, () -> clearSpan, clearSpan);
        sync.define(SyncDataSerializers.INT, s -> swapping = s, () -> swapping, swapping);
        sync.define(SyncDataSerializers.INT, s -> swapSpan = s, () -> swapSpan, swapSpan);
    }

    public BarrelSpec spec() {
        return spec;
    }

    public AbstractVehicleWeapon<?> weapon() {
        return weapon;
    }

    private long now() {
        return weapon.getVehicle().level().getGameTime();
    }

    /** Cooled to now. */
    public BarrelState state() {
        return state.at(spec, now());
    }

    /** Test seam: replaces the barrel's state. */
    public void setState(BarrelState state) {
        this.state = state;
    }

    public boolean fitted() {
        return fitted;
    }

    public Optional<String> variant() {
        return variant;
    }

    /** Fitted variant's barrel length, m; null = the bolt's {@code barrel_length}. */
    @Nullable
    public Float length() {
        return variant.map(v -> spec.variants().get(v).length()).orElse(null);
    }

    /** Muzzle speed factor of the fitted barrel. */
    public float velocity() {
        return variant.map(v -> spec.variants().get(v).velocity()).orElse(1F);
    }

    public boolean jammed() {
        return jammed;
    }

    public void setJammed(boolean jammed) {
        this.jammed = jammed;
    }

    public boolean clearing() {
        return clearing > 0;
    }

    public boolean swapping() {
        return swapping > 0;
    }

    public boolean canFire() {
        return fitted && !jammed && clearing == 0 && swapping == 0;
    }

    public boolean cookingOff() {
        return cookingOff;
    }

    /** Server, one round: heat in; extra spread, degrees. */
    public float fired() {
        long now = now();
        state = state.fired(spec, now);
        lastShot = now;
        return state.spread(spec);
    }

    /** Server, after a round: stoppage roll. */
    public boolean rollJam(RandomSource random) {
        if (random.nextFloat() < state.jamChance(spec)) {
            jammed = true;
        }
        return jammed;
    }

    /** [0,1] through the jam clear; 0 = not clearing. */
    public float clearProgress(float partialTick) {
        return clearing <= 0 || clearSpan <= 0 ? 0 : Mth.clamp(1 - (clearing - partialTick) / clearSpan, 0, 1);
    }

    /** [0,1] through the barrel change; 0 = not changing. */
    public float swapProgress(float partialTick) {
        return swapping <= 0 || swapSpan <= 0 ? 0 : Mth.clamp(1 - (swapping - partialTick) / swapSpan, 0, 1);
    }

    /** {@code <part>.unjam} clip length, else the weapon's {@code unjam_time}. */
    public int clearTicks() {
        PartClipTracks tracks = weapon.getVehicle().clipTracks();
        PartClipTracks.Track clip = tracks == null ? null : tracks.track(weapon.getWeaponUnit().getId() + ".unjam");
        float seconds = clip != null && clip.span() > 0 ? clip.span() : weapon.getData().getUnjamTime();
        return Math.max(1, Math.round(seconds * 20));
    }

    /** Server: jammed, idle => clear starts. */
    public boolean startClear() {
        if (!jammed || clearing > 0 || swapping > 0) {
            return false;
        }
        clearing = clearSpan = clearTicks();
        return true;
    }

    /** Ticks {@code actor} needs at the muzzle: gunner manning the gun => {@code coop}, else {@code self}. */
    public int probeTicks(Player actor) {
        return weapon.getWeaponUnit().getOwner() instanceof Player gunner && gunner != actor
                ? spec.swap().coopTicks() : spec.swap().selfTicks();
    }

    /** Server: other than {@code player}'s change running. */
    public boolean busyFor(Player player) {
        return clearing > 0 || swapping > 0 && swapper != null && !swapper.equals(player.getUUID());
    }

    /** Server: {@code player}'s probe job started / still running. */
    public void holdProbeSwap(Player player, int ticks) {
        if (swapping == 0 && ticks > 0) {
            swapping = swapSpan = ticks;
            swapper = player.getUUID();
            seated = false;
        }
        probeHeld = now();
    }

    /** Server: seated gunner's own change, spare from their inventory or the stores. */
    public boolean startSeatedSwap(ServerPlayer gunner) {
        if (!spec.quickChange() || swapping > 0 || clearing > 0 || !fitted && spare(gunner).isEmpty()) {
            return false;
        }
        swapping = swapSpan = spec.swap().selfTicks();
        swapper = gunner.getUUID();
        seated = true;
        return true;
    }

    /**
     * Server, change done: fitted + {@link #spare} => exchanged in place; fitted, none => barrel off into
     * {@code player}'s inventory (full => dropped); empty gun => spare on.
     */
    public void change(Player player) {
        ItemStack spare = spare(player);
        ResourceLocation type = spec.type().orElseThrow();
        if (fitted && !spare.isEmpty()) {
            SpareBarrel in = Barrels.exchange(spare, variant, state, now());
            mount(in);
        } else if (fitted) {
            ItemStack off = Barrels.spare(new SpareBarrel(type, variant, state.at(spec, now())));
            if (!player.getInventory().add(off)) {
                player.drop(off, false);
            }
            fitted = false;
            variant = Optional.empty();
        } else if (!spare.isEmpty()) {
            SpareBarrel in = Barrels.spareOf(spare);
            spare.shrink(1);
            mount(new SpareBarrel(type, in.variant(), in.state().at(spec, now())));
        }
        stopSwap();
    }

    private void mount(SpareBarrel in) {
        if (in.variant().isPresent() && !spec.variants().containsKey(in.variant().get())) {
            throw new IllegalStateException(weapon.getData().getWeaponId() + ": spare variant " + in.variant().get()
                    + " not in barrel variants " + spec.variants().keySet());
        }
        state = in.state();
        variant = in.variant();
        fitted = true;
    }

    /**
     * Fitting spare: held in the main hand, else coolest of {@code player}'s inventory, then (seated) the vehicle's
     * stores; empty = none.
     */
    public ItemStack spare(Player player) {
        if (spec.type().isEmpty()) {
            return ItemStack.EMPTY;
        }
        SpareBarrel held = Barrels.spareOf(player.getMainHandItem());
        if (held != null && held.type().equals(spec.type().get())) {
            return player.getMainHandItem();
        }
        List<ItemStack> stacks = new ArrayList<>(player.getInventory().items);
        stacks.addAll(player.getInventory().offhand);
        if (player.getVehicle() == weapon.getVehicle()) {
            stacks.addAll(weapon.getVehicle().getItemStacks());
        }
        ItemStack best = Barrels.coolest(stacks, spec.type().get(), now());
        return best == null ? ItemStack.EMPTY : best;
    }

    /** Unit whose {@code indexedWeapons} lists the weapon. */
    public WeaponUnit holder() {
        if (holder == null) {
            holder = holderOf(weapon);
        }
        return holder;
    }

    public static WeaponUnit holderOf(AbstractVehicleWeapon<?> weapon) {
        for (PartUnit<?> part : weapon.getVehicle().getPartUnits()) {
            if (part instanceof WeaponUnit unit && unit.indexedWeapons.contains(weapon)) {
                return unit;
            }
        }
        throw new IllegalStateException("weapon " + weapon.getData().getWeaponId() + " on no station");
    }

    /** Server, from the weapon's tick. */
    public void tick() {
        AbstractVehicle vehicle = weapon.getVehicle();
        if (clearing > 0 && --clearing == 0) {
            jammed = false;
            weapon.discardRound();
        }
        if (swapping > 0) {
            tickSwap();
        }
        if (spec.cookOff().value() > 0 && canFire() && weapon.getRemainAmmo() > 0 && !weapon.isReloading()
                && holder().carries(weapon)
                && now() - lastShot >= COOK_OFF_QUIET
                && BarrelState.happens(state().cookOffRate(spec), 1 / 20.0, vehicle.getRandom().nextDouble())) {
            cookOff();
        }
    }

    private void stopSwap() {
        swapping = swapSpan = 0;
        swapper = null;
    }

    private void tickSwap() {
        if (!seated) {
            if (now() - probeHeld > PROBE_GRACE) {
                stopSwap();
            } else if (swapping > 1) {
                swapping--;
            }
            return;
        }
        if (!(weapon.getWeaponUnit().getOwner() instanceof ServerPlayer gunner) || !gunner.getUUID().equals(swapper)) {
            stopSwap();
            return;
        }
        if (--swapping > 0) {
            return;
        }
        if (!fitted && spare(gunner).isEmpty()) {
            stopSwap();
            return;
        }
        change(gunner);
        BarrelActions.sound(gunner, weapon);
    }

    /** One round through the station's own fire path, no operator. */
    public void cookOff() {
        WeaponUnit unit = holder();
        List<AimContext> aim = new ArrayList<>(List.of(weapon.getWeaponUnit().aimContext()));
        cookingOff = true;
        try {
            unit.shoot(weapon.getIndex(), aim, null);
        } finally {
            cookingOff = false;
        }
    }

    public void save(CompoundTag tag) {
        tag.put("Barrel", BarrelState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow());
        tag.putBoolean("Jammed", jammed);
        tag.putBoolean("Fitted", fitted);
        variant.ifPresent(v -> tag.putString("Variant", v));
    }

    public void load(CompoundTag tag) {
        state = tag.contains("Barrel")
                ? BarrelState.CODEC.parse(NbtOps.INSTANCE, tag.get("Barrel")).result().orElse(BarrelState.NEW)
                : BarrelState.NEW;
        jammed = tag.getBoolean("Jammed");
        fitted = !tag.contains("Fitted") || tag.getBoolean("Fitted");
        variant = tag.contains("Variant") ? Optional.of(tag.getString("Variant")) : Optional.empty();
        clearing = clearSpan = 0;
        stopSwap();
    }
}
