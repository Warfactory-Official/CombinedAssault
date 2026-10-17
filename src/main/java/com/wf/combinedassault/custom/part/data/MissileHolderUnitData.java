package com.wf.combinedassault.custom.part.data;

import com.wf.combinedassault.vehicle.pojo.MissileSize;
import com.wf.wflib.magazine.PodItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.pojo.Station;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class MissileHolderUnitData extends PartUnitData {

    protected final EnumSet<MissileSize> accepts = EnumSet.noneOf(MissileSize.class);
    protected int capacity = 1;
    protected List<Station> stations = List.of();
    @Nullable
    protected Ingredient pods;
    @Nullable
    protected PodItem defaultPod;
    protected int podSwapTime;

    public MissileHolderUnitData(String id) {
        super(id);
        this.accepts.addAll(Set.of(MissileSize.VALUES));
    }

    public MissileHolderUnitData(MissileHolderUnitPojo pojo) {
        super(pojo);
        if (pojo.accepts == null) {
            this.accepts.addAll(Set.of(MissileSize.VALUES));
        } else {
            for (MissileSize size : pojo.accepts) {
                if (size != null) {
                    this.accepts.add(size);
                }
            }
        }
        this.capacity = Math.max(0, pojo.capacity);
        this.pods = pojo.pods;
        this.podSwapTime = pojo.podSwapTime;
        if (pojo.defaultPod != null) {
            if (!(BuiltInRegistries.ITEM.get(pojo.defaultPod) instanceof PodItem pod)) {
                throw new IllegalArgumentException("holder " + getId() + ": default_pod " + pojo.defaultPod
                        + " is not a pod");
            }
            this.defaultPod = pod;
        }
    }

    /** glTF {@code station} sockets: slot i = station i; {@code capacity} absent => station count, below it => load error. */
    public void applyStations(List<Station> stations) {
        if (capacity != 0 && capacity < stations.size()) {
            throw new IllegalArgumentException("holder " + getId() + ": capacity " + capacity + " < "
                    + stations.size() + " stations");
        }
        this.stations = List.copyOf(stations);
        this.capacity = Math.max(capacity, stations.size());
    }

    public List<Station> getStations() {
        return stations;
    }

    /** Empty {@code accepts}: valid; holder simply never loads (datapack disable). */
    public boolean accepts(MissileSize size) {
        return size != null && accepts.contains(size);
    }

    public Set<MissileSize> getAccepts() {
        return accepts;
    }

    public int getCapacity() {
        return capacity;
    }

    public boolean takesPods() {
        return pods != null;
    }

    public boolean takesPod(ItemStack stack) {
        return pods != null && stack.getItem() instanceof PodItem && pods.test(stack);
    }

    @Nullable
    public PodItem getDefaultPod() {
        return defaultPod;
    }

    public int getPodSwapTime() {
        return podSwapTime;
    }

}
