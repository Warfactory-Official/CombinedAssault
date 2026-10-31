package com.wf.combinedassault.pack.sync;

import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import com.wf.combinedassault.vehicle.message.ServerEntityDataUpdate;
import com.wf.combinedassault.vehicle.part.PartUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Per-{@link PartUnit} sync; each unit owns one instance. Synced values: primitive types only, read-only client-side.
 * Declaration order via {@link #define} must match exactly both sides; index = call order. Rebuild after the
 * vehicle's {@link PartUnit} structure changes.
 */
public class PartUnitSyncData {

    private final PartUnit<?> partUnit;
    private final int intervalTick;
    private List<SyncDataHolder<?>> dataHolders = new ArrayList<>();
    private boolean isInitialized = false;

    // TODO: support different sync modes
    public enum SyncMode {
        TRACKING,
        PASSENGERS_ONLY
    }

    public PartUnitSyncData(@NotNull PartUnit<?> partUnit, int intervalTick) {
        this.intervalTick = intervalTick;
        this.partUnit = partUnit;
    }

    public PartUnitSyncData(@NotNull PartUnit<?> partUnit) {
        this(partUnit, 1);
    }

    public void initialize() {
        this.dataHolders = List.copyOf(dataHolders);
        this.isInitialized = true;
    }

    public <T> SyncDataHolder<T> define(SyncDataSerializer<T> serializer, Consumer<T> setter,
                                        Supplier<T> getter, T initialValue) {
        if (isInitialized) {
            throw new IllegalStateException("Cannot define new sync data after initialization");
        }
        int index = dataHolders.size();
        var holder = new SyncDataHolder<>(index, serializer, setter, getter, initialValue);
        dataHolders.add(holder);
        return holder;
    }


    /** Server: versions of {@code old} (the part this one replaces) carried by holder index. */
    public void continueFrom(PartUnitSyncData old) {
        for (int i = 0; i < Math.min(dataHolders.size(), old.dataHolders.size()); i++) {
            dataHolders.get(i).continueFrom(old.dataHolders.get(i).version());
        }
    }

    public void onUpdateReceived(List<SyncDataEntry<?>> entries) {
        for (var entry : entries) {
            var holder = dataHolders.get(entry.index());
            holder.onUpdate(entry);
        }
    }

    public void tick() {
        int entityId = partUnit.getVehicle().getId();
        if (partUnit.getVehicle().tickCount % intervalTick == 0) {
            var dirtyEntries = packDirty(false);
            if (!dirtyEntries.isEmpty()) {
                var message = new ServerEntityDataUpdate(entityId, partUnit.getIndex(), dirtyEntries);
                PacketDistributor.sendToPlayersTrackingEntity(partUnit.getVehicle(), message);
            }
        }
    }

    public List<SyncDataEntry<?>> packDirty(boolean force) {
        List<SyncDataEntry<?>> dirtyEntries = new ArrayList<>();
        List<SyncDataHolder<?>> holdersToClean = new ArrayList<>();

        for (SyncDataHolder<?> holder : dataHolders) {
            holder.refresh();

            if (force) {
                dirtyEntries.add(holder.createEntry());
            } else if (holder.isDirty()) {
                dirtyEntries.add(holder.createEntry());
                holdersToClean.add(holder);
            }
        }

        for (SyncDataHolder<?> holder : holdersToClean) {
            holder.setDirty(false);
        }

        return dirtyEntries;
    }

}
