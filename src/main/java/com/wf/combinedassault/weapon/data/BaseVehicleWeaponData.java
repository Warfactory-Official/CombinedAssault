package com.wf.combinedassault.weapon.data;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import com.mojang.serialization.JsonOps;
import com.wf.combinedassault.weapon.Backblast;
import com.wf.wflib.audio.api.SoundSpec;
import com.wf.wflib.barrel.api.BarrelSpec;
import com.wf.wflib.barrel.api.BarrelTypes;
import java.util.Optional;
import com.wf.wflib.ammo.api.Ammo;
import com.wf.wflib.ammo.api.AmmoItems;
import com.wf.wflib.ammo.api.AmmoVariant;
import com.wf.wflib.magazine.api.MagFill;
import com.wf.wflib.magazine.api.Run;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Launcher only: the loaded WFLib round owns flight, warhead and damage. */
public class BaseVehicleWeaponData {

    private ResourceLocation weaponId;

    @SerializedName("name")
    private String name = "vehicle.weapon.unknown";

    @SerializedName("inaccuracy")
    private float inaccuracy = 0.5f;

    @SerializedName("recoil")
    private float recoil = 0f;

    @SerializedName("shoot_interval")
    private long shootInterval = 100;

    @SerializedName("max_capacity")
    private int maxCapacity = 64;

    @SerializedName("reload")
    private Reload reload;

    @SerializedName("independent")
    public boolean independent;

    @SerializedName("backblast")
    @Nullable
    private Backblast backblast;

    /** Seconds from shot to case out of the {@code eject} socket. */
    @SerializedName("casing_delay")
    private float casingDelay;

    /** {@code fire} (at the pivot), {@code shell} (half an interval later), {@code reload}. */
    @SerializedName("sounds")
    private Map<String, SoundSpec> sounds = Map.of();

    /** wflib {@link BarrelSpec}; absent = no heat, jams or barrel change. */
    @SerializedName("barrel")
    @Nullable
    private JsonElement barrelJson;

    /** Seconds; jam clear without a {@code <part>.unjam} clip. */
    @SerializedName("unjam_time")
    private float unjamTime = 1.5f;

    @Nullable
    private transient BarrelSpec barrel;

    public static class Reload {

        @SerializedName("time")
        private int time;

        /** Item, {@code #tag}, or a list of either. */
        @SerializedName("ammo")
        private Ingredient ammo;

        /** WFLib ammo variants a cartridge must carry; absent = any variant of the cartridge. */
        @SerializedName("variants")
        @Nullable
        private List<ResourceLocation> variants;

        /** WFLib magazine items it takes (item, {@code #tag}, list); absent = loose rounds only. */
        @SerializedName("magazines")
        @Nullable
        private Ingredient magazines;

        /** With {@code magazines}: loose rounds from the stores too. */
        @SerializedName("loose")
        private boolean loose;

        public Reload(int time, Ingredient ammo) {
            this.time = time;
            this.ammo = ammo;
        }

        public int getTime() {
            return time;
        }

        public void setTime(int time) {
            this.time = time;
        }

        public Ingredient getAmmo() {
            return ammo;
        }

        public void setAmmo(Ingredient ammo) {
            this.ammo = ammo;
        }

        public boolean isAmmo(ItemStack stack) {
            if (!ammo.test(stack)) {
                return false;
            }
            if (variants == null) {
                return true;
            }
            var variant = AmmoItems.variantOf(stack);
            return variant != null && variants.contains(variant.id());
        }

        public boolean magazineFed() {
            return magazines != null;
        }

        @Nullable
        public Ingredient getMagazines() {
            return magazines;
        }

        public boolean takesLoose() {
            return magazines == null || loose;
        }

        /** A magazine item it takes, every round in it {@link #isAmmo}; empty magazine included. */
        public boolean isMagazine(ItemStack stack) {
            if (magazines == null || !MagFill.isMagazine(stack) || !magazines.test(stack)) {
                return false;
            }
            for (Run run : MagFill.contents(stack).runs()) {
                if (!isAmmo(AmmoItems.stack(run.round(), 1))) {
                    return false;
                }
            }
            return true;
        }

        /** Creative supply: each magazine item full of each variant it and {@link #isAmmo} accept. */
        public List<ItemStack> fullMagazines() {
            List<ItemStack> out = new ArrayList<>();
            if (magazines == null) {
                return out;
            }
            for (ItemStack mag : magazines.getItems()) {
                if (!MagFill.isMagazine(mag)) {
                    continue;
                }
                for (AmmoVariant v : Ammo.variantsOf(MagFill.magazine(mag).calibre())) {
                    if (isAmmo(AmmoItems.stack(v, 1))) {
                        out.add(MagFill.full(mag, v.id()));
                    }
                }
            }
            return out;
        }

        /** Every kind this accepts, one each: a cartridge item expands to its calibre's variants. */
        public List<ItemStack> rounds() {
            List<ItemStack> out = new ArrayList<>();
            for (ItemStack item : ammo.getItems()) {
                AmmoVariant variant = AmmoItems.variantOf(item);
                if (variant == null) {
                    out.add(item.copyWithCount(1));
                    continue;
                }
                for (AmmoVariant v : Ammo.variantsOf(variant.calibre())) {
                    ItemStack round = AmmoItems.stack(v, 1);
                    if (isAmmo(round)) {
                        out.add(round);
                    }
                }
            }
            return out;
        }

        /** Round a creative magazine loads: first variant, else the ingredient's first item. */
        public ItemStack defaultRound() {
            if (variants != null && !variants.isEmpty()) {
                return AmmoItems.stack(variants.getFirst(), 1);
            }
            ItemStack[] items = ammo.getItems();
            return items.length == 0 ? ItemStack.EMPTY : items[0].copyWithCount(1);
        }

    }

    @Nullable
    public Backblast getBackblast() {
        return backblast;
    }

    public float getCasingDelay() {
        return casingDelay;
    }

    @Nullable
    public SoundSpec sound(String key) {
        return sounds.get(key);
    }

    public ResourceLocation getWeaponId() {
        return weaponId;
    }

    public void setWeaponId(ResourceLocation weaponId) {
        this.weaponId = weaponId;
    }

    /** Once parsed and id set, each side. No {@code reload} => {@link IllegalStateException}. */
    public void loaded() {
        if (reload == null && needsReload()) {
            throw new IllegalStateException("weapon " + weaponId + ": no reload.ammo");
        }
        if (barrelJson != null) {
            barrel = BarrelSpec.CODEC.parse(JsonOps.INSTANCE, barrelJson)
                    .getOrThrow(error -> new IllegalStateException("weapon " + weaponId + ": barrel: " + error));
        }
    }

    public Optional<BarrelTypes.Type> barrelType() {
        return barrel == null ? Optional.empty() : barrel.type().map(type -> new BarrelTypes.Type(type,
                Component.translatable("barrel_type." + type.getNamespace() + "." + type.getPath()), barrel));
    }

    @Nullable
    public BarrelSpec getBarrel() {
        return barrel;
    }

    public float getUnjamTime() {
        return unjamTime;
    }

    /** False: never reloads (rack-fed). */
    protected boolean needsReload() {
        return true;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getInaccuracy() {
        return inaccuracy;
    }

    public void setInaccuracy(float inaccuracy) {
        this.inaccuracy = inaccuracy;
    }

    public float getRecoil() {
        return recoil;
    }

    public void setRecoil(float recoil) {
        this.recoil = recoil;
    }

    public long getShootInterval() {
        return shootInterval;
    }

    public void setShootInterval(long shootInterval) {
        this.shootInterval = shootInterval;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    @Nullable
    public Reload getReload() {
        return reload;
    }

    public void setReload(Reload reload) {
        this.reload = reload;
    }

}
