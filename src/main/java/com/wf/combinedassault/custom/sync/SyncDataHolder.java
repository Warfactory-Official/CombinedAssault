package com.wf.combinedassault.custom.sync;

import com.wf.combinedassault.api.custom.sync.SyncDataSerializer;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** {@link #refresh} is the server-side dirty-detect path; {@link #onUpdate} is the client-side apply path. */
public class SyncDataHolder<T> {
    private final SyncDataSerializer<T> serializer;
    private final Consumer<T> setter;
    private final Supplier<T> getter;
    private final int index;
    private boolean isDirty = true;
    private int version = 0;

    private T value;

    protected SyncDataHolder(
            int index,
            SyncDataSerializer<T> serializer,
            Consumer<T> setter,
            Supplier<T> getter,
            T initialValue
    ) {
        this.serializer = serializer;
        this.setter = setter;
        this.getter = getter;
        this.index = index;
        this.value = initialValue;
    }

    @SuppressWarnings("unchecked")
    protected void onUpdate(SyncDataEntry<?> entry) {
        if (entry.version() <= this.version) {
            return;
        }
        this.version = entry.version();
        this.value = (T) entry.value();
        this.setter.accept(this.value);
    }

    public void refresh() {
        T currentValue = getter.get();
        if (serializer.compare(this.value, currentValue)) {
            return;
        }
        this.value = currentValue;
        this.isDirty = true;
        this.version++;
    }

    public T get() {
        return value;
    }

    public boolean isDirty() {
        return isDirty;
    }

    public void setDirty(boolean dirty) {
        isDirty = dirty;
    }

    public SyncDataEntry<T> createEntry() {
        return new SyncDataEntry<>(index, serializer, value, version);
    }
}
