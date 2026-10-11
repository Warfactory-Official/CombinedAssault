package com.wf.combinedassault.vehicle.part;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import com.wf.combinedassault.api.custom.sync.SyncDataSerializers;
import com.wf.combinedassault.client.render.part.RackedRoundRenderer;
import com.wf.combinedassault.custom.part.data.MissileHolderUnitData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.pojo.MissileSize;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.Arrays;

/**
 * Fit is datapack-only: round's size tag vs holder's accept list; cross-mod the moment a pack tags the round.
 * Slot i = holder bone's i-th cube, round's nose at cube's +Z face. Past last cube: no station. Synced by item id only.
 */
public class MissileHolderUnit extends PartUnit<MissileHolderUnitData> {

    private final ItemStack[] slots;

    public MissileHolderUnit(int index, AbstractVehicle vehicle, MissileHolderUnitData data) {
        super(index, vehicle, data);
        this.slots = new ItemStack[data.getCapacity()];
        Arrays.fill(slots, ItemStack.EMPTY);
        for (int i = 0; i < slots.length; i++) {
            int slot = i;
            syncData.define(SyncDataSerializers.ITEM,
                    item -> slots[slot] = item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item),
                    () -> slots[slot].getItem(), Items.AIR);
        }
    }

    /**
     * Non-tagged-missile {@code stack} => false; plain right-click-to-mount stays unaffected.
     * @return true if taken, in which case {@code stack} loses one item.
     */
    public static boolean tryLoad(AbstractVehicle vehicle, ItemStack stack) {
        if (MissileSize.of(stack) == null) {
            return false;
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof MissileHolderUnit holder && holder.load(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Any rack here has room for {@code stack}; client-safe (slots synced). */
    public static boolean couldLoad(AbstractVehicle vehicle, ItemStack stack) {
        if (MissileSize.of(stack) == null) {
            return false;
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof MissileHolderUnit holder && holder.canLoad(stack)) {
                return true;
            }
        }
        return false;
    }

    public boolean canLoad(ItemStack stack) {
        return firstEmpty() >= 0 && data.accepts(MissileSize.of(stack));
    }

    /** @return true if taken, in which case {@code stack} loses one item. */
    public boolean load(ItemStack stack) {
        if (!canLoad(stack)) {
            return false;
        }
        slots[firstEmpty()] = stack.split(1);
        return true;
    }

    /** Lowest loaded slot; -1 if none. */
    public int nextLoaded() {
        for (int i = 0; i < slots.length; i++) {
            if (!slots[i].isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** Lowest slot holding {@code kind}; {@code AIR} = any. -1 if none. */
    public int nextLoaded(net.minecraft.world.item.Item kind) {
        if (kind == Items.AIR) {
            return nextLoaded();
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].is(kind)) {
                return i;
            }
        }
        return -1;
    }

    /** Lowest slot whose round passes {@code kind}; -1 if none. */
    public int nextLoaded(java.util.function.Predicate<ItemStack> kind) {
        for (int i = 0; i < slots.length; i++) {
            if (!slots[i].isEmpty() && kind.test(slots[i])) {
                return i;
            }
        }
        return -1;
    }

    /** Distinct round items loaded, slot order. */
    public java.util.List<net.minecraft.world.item.Item> kinds() {
        java.util.List<net.minecraft.world.item.Item> out = new java.util.ArrayList<>();
        for (ItemStack slot : slots) {
            if (!slot.isEmpty() && !out.contains(slot.getItem())) {
                out.add(slot.getItem());
            }
        }
        return out;
    }

    public int count(net.minecraft.world.item.Item kind) {
        int count = 0;
        for (ItemStack slot : slots) {
            if (!slot.isEmpty() && (kind == Items.AIR || slot.is(kind))) {
                count++;
            }
        }
        return count;
    }

    private int firstEmpty() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** {@link #consume} without removing. */
    public ItemStack peek() {
        int slot = nextLoaded();
        return slot < 0 ? ItemStack.EMPTY : slots[slot];
    }

    public ItemStack round(int slot) {
        return slots[slot];
    }

    public ItemStack take(int slot) {
        ItemStack round = slots[slot];
        slots[slot] = ItemStack.EMPTY;
        return round;
    }

    public ItemStack consume() {
        int slot = nextLoaded();
        return slot < 0 ? ItemStack.EMPTY : take(slot);
    }

    public int loaded() {
        int count = 0;
        for (ItemStack slot : slots) {
            if (!slot.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public int getCapacity() {
        return slots.length;
    }

    public boolean accepts(MissileSize size) {
        return data.accepts(size);
    }

    /** Null: slot past last cube (no station). */
    @Nullable
    public VehicleCubeOBB station(int slot) {
        return slot < getPartCubeOBBs().size() ? getPartCubeOBBs().get(slot) : null;
    }

    /** Launch point: station's tail, +Z heading, world space. Null without a station. */
    @Nullable
    public AimContext launchContext(int slot) {
        VehicleCubeOBB station = station(slot);
        if (station == null || station.position == null) {
            return null;
        }
        Vector3f forward = station.rotation.transform(new Vector3f(0, 0, 1));
        AimContext context = new AimContext();
        context.from = station.position.subtract(new Vec3(forward).scale(station.depth / 2));
        context.direction = new Vec2((float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, forward.y)))),
                (float) Math.toDegrees(Math.atan2(-forward.x, forward.z)));
        return context;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick) {
        for (int i = 0; i < slots.length; i++) {
            VehicleCubeOBB station = station(i);
            if (!slots[i].isEmpty() && station != null) {
                RackedRoundRenderer.render(getVehicle(), station, slots[i], poseStack, bufferSource, packedLight);
            }
        }
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = super.serializeNBT(provider);
        ListTag list = new ListTag();
        for (int i = 0; i < slots.length; i++) {
            if (!slots[i].isEmpty()) {
                CompoundTag entry = (CompoundTag) slots[i].save(provider);
                entry.putByte("Slot", (byte) i);
                list.add(entry);
            }
        }
        tag.put("Rounds", list);
        return tag;
    }

    @Override
    public void deserializeNBT(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        if (!nbt.contains("Rounds", Tag.TAG_LIST)) {
            return;
        }
        Arrays.fill(slots, ItemStack.EMPTY);
        ListTag list = nbt.getList("Rounds", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.getByte("Slot");
            // A round whose mod is gone parses to empty: an empty rack, not an unloadable vehicle.
            if (slot < slots.length) {
                ItemStack.parse(provider, entry).ifPresent(stack -> slots[slot] = stack);
            }
        }
    }

}
