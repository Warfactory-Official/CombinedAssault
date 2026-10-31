package com.wf.combinedassault.pack.sync;

import net.minecraft.network.FriendlyByteBuf;

public record SyncDataEntry<T> (int index, SyncDataSerializer<T> serializer, T value, int version) {
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(version);
        serializer.write(buf, value);
    }
}
