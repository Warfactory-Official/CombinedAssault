package com.wf.combinedassault.pack.sync;


import java.util.ArrayDeque;
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
    /** Client: {@link #predict}ed values in flight, oldest first. */
    private final ArrayDeque<T> predicted = new ArrayDeque<>();

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
        // Match acks it + older; newer still in flight => echo ack-old, keep shown. No match => server diverged.
        while (!predicted.isEmpty()) {
            if (serializer.compare(predicted.poll(), this.value) && !predicted.isEmpty()) {
                return;
            }
        }
        this.setter.accept(this.value);
    }

    /** Client: show {@code value} ahead of the server; caller sends the request. */
    // TODO: request refused without a value change never echoes => prediction shown until the next server change.
    public void predict(T value) {
        predicted.add(value);
        setter.accept(value);
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

    /** Server: continue from {@code version} (rebuilt part; clients drop entries <= the version they hold). */
    public void continueFrom(int version) {
        this.version = Math.max(this.version, version);
    }

    public int version() {
        return version;
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
