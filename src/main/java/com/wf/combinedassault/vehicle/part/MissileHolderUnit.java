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
import com.wf.combinedassault.vehicle.pojo.Station;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;
import org.joml.Quaternionf;
import org.joml.Vector2fc;
import com.wf.wflib.magazine.MagazineItems;
import com.wf.wflib.magazine.PodItem;
import com.wf.wflib.magazine.PodTubes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.Arrays;
import java.util.List;

/**
 * Fit is datapack-only: round's size tag vs holder's accept list; cross-mod the moment a pack tags the round.
 * Slot i = glTF {@code station} socket i; past the last station: internal slot. Synced by item id only.
 * {@code pods}: slot i = seated pod's tube i, posed in the stations' frame (centroid, station 0 rotation); no pod => none.
 */
public class MissileHolderUnit extends PartUnit<MissileHolderUnitData> {

    private final ItemStack[] slots;
    /** Seated pod; null = none. */
    @Nullable
    private PodItem pod;

    public MissileHolderUnit(int index, AbstractVehicle vehicle, MissileHolderUnitData data) {
        super(index, vehicle, data);
        this.slots = new ItemStack[data.getCapacity()];
        Arrays.fill(slots, ItemStack.EMPTY);
        this.pod = data.getDefaultPod();
        syncData.define(SyncDataSerializers.ITEM, item -> pod = item instanceof PodItem p ? p : null,
                () -> pod == null ? Items.AIR : pod, Items.AIR);
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

    /**
     * Pod on a holder taking it: the seated one, as loaded, goes to {@code player}.
     * @return true if seated, in which case {@code stack} loses it.
     */
    public static boolean trySeatPod(AbstractVehicle vehicle, Player player, ItemStack stack) {
        MissileHolderUnit holder = podHolder(vehicle, stack);
        if (holder == null) {
            return false;
        }
        ItemStack old = holder.removePod();
        holder.seatPod(stack.split(1));
        if (!old.isEmpty()) {
            player.getInventory().placeItemBackInInventory(old);
        }
        return true;
    }

    @Nullable
    public static MissileHolderUnit podHolder(AbstractVehicle vehicle, ItemStack stack) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof MissileHolderUnit holder && holder.data.takesPod(stack)
                    && ((PodItem) stack.getItem()).tubes().size() <= holder.slots.length) {
                return holder;
            }
        }
        return null;
    }

    public boolean takesPods() {
        return data.takesPods();
    }

    /** Pod as it stands, tubes and all; empty = none seated. */
    public ItemStack removePod() {
        if (pod == null) {
            return ItemStack.EMPTY;
        }
        ItemStack out = new ItemStack(pod);
        out.set(MagazineItems.POD_TUBES.get(), new PodTubes(List.of(Arrays.copyOf(slots, pod.tubes().size()))));
        Arrays.fill(slots, ItemStack.EMPTY);
        pod = null;
        return out;
    }

    /** {@code stack}: one pod item, none seated. */
    public void seatPod(ItemStack stack) {
        if (pod != null || !data.takesPod(stack)) {
            throw new IllegalStateException("holder " + data.getId() + " cannot seat " + stack);
        }
        pod = (PodItem) stack.getItem();
        List<ItemStack> tubes = PodItem.contents(stack).tubes();
        for (int i = 0; i < tubes.size(); i++) {
            slots[i] = tubes.get(i);
        }
    }

    /** Stores slot of a pod this seats with a round in it; -1 if none. */
    public int storedPod() {
        var handler = getVehicle().getItemHandler();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (data.takesPod(stack) && ((PodItem) stack.getItem()).tubes().size() <= slots.length
                    && PodItem.contents(stack).loaded() > 0) {
                return i;
            }
        }
        return -1;
    }

    /** Seated pod to the stores (overflow dropped), {@link #storedPod} in. */
    public void swapFromStores() {
        int slot = storedPod();
        if (slot < 0) {
            return;
        }
        var handler = getVehicle().getItemHandler();
        ItemStack next = handler.extractItem(slot, 1, false);
        ItemStack old = removePod();
        for (int i = 0; i < handler.getSlots() && !old.isEmpty(); i++) {
            old = handler.insertItem(i, old, false);
        }
        if (!old.isEmpty()) {
            getVehicle().spawnAtLocation(old);
        }
        seatPod(next);
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
        return firstEmpty() >= 0 && fits(stack);
    }

    /** Slot {@code slot} empty and {@code stack} fits. */
    public boolean canLoad(int slot, ItemStack stack) {
        return slot < getCapacity() && slots[slot].isEmpty() && fits(stack);
    }

    /** Pods: the seated pod's tube tag. Else size accepted. */
    private boolean fits(ItemStack stack) {
        return data.takesPods() ? pod != null && pod.accepts(stack) : data.accepts(MissileSize.of(stack));
    }

    /** {@code round}: one item, kept as is. */
    public void load(int slot, ItemStack round) {
        if (!canLoad(slot, round)) {
            throw new IllegalStateException("slot " + slot + " cannot take " + round);
        }
        slots[slot] = round;
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
        for (int i = 0; i < getCapacity(); i++) {
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

    /** Usable slots: the seated pod's tubes, 0 without; else all. */
    public int getCapacity() {
        return !data.takesPods() ? slots.length : pod == null ? 0 : pod.tubes().size();
    }

    public boolean accepts(MissileSize size) {
        return data.accepts(size);
    }

    /** Station {@code slot} in the vehicle model frame; null: slot past the last station. */
    @Nullable
    public VehicleCubeGroup.GlobalTransform stationInModel(int slot, float partialTick) {
        List<Station> stations = data.getStations();
        if (structureGroup == null || stations.isEmpty() || slot >= (data.takesPods() ? getCapacity() : stations.size())) {
            return null;
        }
        Vector3f offset;
        Quaternionf rotation;
        if (data.takesPods()) {
            rotation = stations.getFirst().rotation();
            offset = new Vector3f();
            stations.forEach(s -> offset.add(s.offset.toVector3f()));
            offset.div(stations.size());
            Vector2fc tube = pod.tubes().get(slot);
            offset.add(rotation.transform(new Vector3f(tube.x(), tube.y(), 0)));
        } else {
            rotation = stations.get(slot).rotation();
            offset = stations.get(slot).offset.toVector3f();
        }
        VehicleCubeGroup.GlobalTransform frame = partialTick == 1 ? structureGroup.globalTransform()
                : structureGroup.globalTransform(partialTick);
        Vector3f at = frame.rotation().transform(offset).add(frame.offset().toVector3f());
        return new VehicleCubeGroup.GlobalTransform(new Vec3(at), frame.rotation().mul(rotation, new Quaternionf()));
    }

    /** Round length station {@code slot} takes. */
    public float stationLength(int slot) {
        return data.takesPods() ? pod.length() : data.getStations().get(slot).length;
    }

    /** World pose of station {@code slot} this tick; null: slot past the last station. */
    @Nullable
    public StationPose station(int slot) {
        VehicleCubeGroup.GlobalTransform model = stationInModel(slot, 1);
        if (model == null) {
            return null;
        }
        AbstractVehicle vehicle = getVehicle();
        Vector3f arm = vehicle.rotYXZ().transform(model.offset().subtract(vehicle.centerOffset).toVector3f());
        Vec3 origin = vehicle.position().add(vehicle.centerOffset).add(arm.x, arm.y, arm.z);
        return new StationPose(origin, vehicle.rotYXZ().mul(model.rotation(), new Quaternionf()),
                stationLength(slot));
    }

    /** @param origin round centre; nose +Z of {@code rotation} */
    public record StationPose(Vec3 origin, Quaternionf rotation, float length) {
        public Vector3f forward() {
            return rotation.transform(new Vector3f(0, 0, 1));
        }
    }

    /** Launch point: station's tail, +Z heading, world space. Null without a station. */
    @Nullable
    public AimContext launchContext(int slot) {
        StationPose station = station(slot);
        if (station == null) {
            return null;
        }
        Vector3f forward = station.forward();
        AimContext context = new AimContext();
        context.from = station.origin().subtract(new Vec3(forward).scale(station.length() / 2));
        context.direction = new Vec2((float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, forward.y)))),
                (float) Math.toDegrees(Math.atan2(-forward.x, forward.z)));
        return context;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick) {
        for (int i = 0; i < slots.length; i++) {
            VehicleCubeGroup.GlobalTransform station = slots[i].isEmpty() ? null : stationInModel(i, partialTick);
            if (station != null) {
                RackedRoundRenderer.render(getVehicle(), station, stationLength(i), slots[i],
                        poseStack, bufferSource, packedLight);
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
        if (data.takesPods()) {
            tag.putString("Pod", pod == null ? "" : BuiltInRegistries.ITEM.getKey(pod).toString());
        }
        return tag;
    }

    @Override
    public void deserializeNBT(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        if (nbt.contains("Pod", Tag.TAG_STRING)) {
            String id = nbt.getString("Pod");
            pod = id.isEmpty() ? null : BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)) instanceof PodItem p ? p : null;
        }
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
