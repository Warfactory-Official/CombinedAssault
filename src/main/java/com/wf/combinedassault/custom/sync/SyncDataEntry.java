package com.wf.combinedassault.custom.sync;

import net.minecraft.network.FriendlyByteBuf;
import com.wf.combinedassault.api.custom.sync.SyncDataSerializer;

public record SyncDataEntry<T> (int index, SyncDataSerializer<T> serializer, T value, int version) {
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(version);
        serializer.write(buf, value);
    }
}
