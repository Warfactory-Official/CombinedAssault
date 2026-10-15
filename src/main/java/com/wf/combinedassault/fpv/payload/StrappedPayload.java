package com.wf.combinedassault.fpv.payload;

import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Payload of strapped items, one per slot; mass = the items'. */
public abstract class StrappedPayload implements FpvPayload {

    protected final FpvPayloadType type;
    /** Config survives NBT restore ({@link FpvPayloadType#empty} has no JSON). */
    protected int capacity;
    protected int armingTicks;
    protected final List<ItemStack> items = new ArrayList<>();
    private boolean armed;

    protected StrappedPayload(FpvPayloadType type, JsonObject json, int defaultCapacity, int defaultArming) {
        this.type = type;
        this.capacity = GsonHelper.getAsInt(json, "slots", defaultCapacity);
        this.armingTicks = GsonHelper.getAsInt(json, "arming_ticks", defaultArming);
    }

    /** kg of one strapped item; only items {@link #canLoad} admitted. */
    protected abstract float massOf(ItemStack stack);

    @Override
    public FpvPayloadType type() {
        return this.type;
    }

    @Override
    public int remaining() {
        return this.items.size();
    }

    @Override
    public int capacity() {
        return this.capacity;
    }

    @Override
    public float mass() {
        float kg = 0;
        for (ItemStack stack : this.items) {
            kg += massOf(stack);
        }
        return kg;
    }

    @Override
    public List<ItemStack> contents() {
        return List.copyOf(this.items);
    }

    @Override
    public void load(ItemStack stack) {
        this.items.add(stack.copyWithCount(1));
    }

    @Override
    public List<ItemStack> unload() {
        List<ItemStack> out = List.copyOf(this.items);
        this.items.clear();
        return out;
    }

    @Override
    public boolean isArmed() {
        return this.armed;
    }

    @Override
    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    @Override
    public int armingTicks() {
        return this.armingTicks;
    }

    @Override
    public void save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putBoolean("Armed", this.armed);
        tag.putInt("Slots", this.capacity);
        tag.putInt("ArmingTicks", this.armingTicks);
        ListTag list = new ListTag();
        for (ItemStack stack : this.items) {
            list.add(stack.save(provider));
        }
        tag.put("Items", list);
    }

    @Override
    public void load(CompoundTag tag, HolderLookup.Provider provider) {
        this.armed = tag.getBoolean("Armed");
        this.capacity = tag.getInt("Slots");
        this.armingTicks = tag.getInt("ArmingTicks");
        this.items.clear();
        ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack.parse(provider, list.getCompound(i)).ifPresent(this.items::add);
        }
    }
}
