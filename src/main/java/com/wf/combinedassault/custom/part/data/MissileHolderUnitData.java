package com.wf.combinedassault.custom.part.data;

import com.wf.combinedassault.vehicle.pojo.MissileSize;

import java.util.EnumSet;
import java.util.Set;

public class MissileHolderUnitData extends PartUnitData {

    protected final EnumSet<MissileSize> accepts = EnumSet.noneOf(MissileSize.class);
    protected int capacity = 1;

    public MissileHolderUnitData(String id) {
        super(id);
        this.accepts.addAll(Set.of(MissileSize.VALUES));
    }

    public MissileHolderUnitData(MissileHolderUnitPojo pojo) {
        super(pojo);
        if (pojo.accepts == null || pojo.accepts.isEmpty()) {
            this.accepts.addAll(Set.of(MissileSize.VALUES));
        } else {
            for (MissileSize size : pojo.accepts) {
                if (size != null) {
                    this.accepts.add(size);
                }
            }
        }
        this.capacity = Math.max(0, pojo.capacity);
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

}
