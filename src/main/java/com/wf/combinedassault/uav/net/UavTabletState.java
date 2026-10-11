package com.wf.combinedassault.uav.net;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.uav.client.UavTabletScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;

/** Tablet list + selected vehicle's readout. {@code open}: from item use => open the screen. */
public record UavTabletState(boolean open, int selected, List<Entry> entries) implements CustomPacketPayload {

    public static final Type<UavTabletState> TYPE = new Type<>(CombinedAssault.modLocation("uav_tablet_state"));

    public enum Status {
        ONLINE, OCCUPIED, ASLEEP, DESTROYED, OTHER_DIMENSION, UNKNOWN;

        public String key() {
            return "tablet.combinedassault.status." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Cell(String label, float ratio) {
        static final StreamCodec<ByteBuf, Cell> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Cell::label, ByteBufCodecs.FLOAT, Cell::ratio, Cell::new);
    }

    public record Ammo(Component name, int loaded, int capacity, int stored) {
        static final StreamCodec<RegistryFriendlyByteBuf, Ammo> CODEC = StreamCodec.composite(
                ComponentSerialization.TRUSTED_STREAM_CODEC, Ammo::name, ByteBufCodecs.VAR_INT, Ammo::loaded,
                ByteBufCodecs.VAR_INT, Ammo::capacity, ByteBufCodecs.VAR_INT, Ammo::stored, Ammo::new);
    }

    /**
     * @param feedId entity id when loaded in the player's level, else 0
     * @param fuel 0..1 fuel or charge ({@code electric})
     * @param link 0..1 datalink at {@code distance}; distance < 0 = unknown
     */
    public record Entry(UUID id, int feedId, Status status, Component name, List<Cell> cells,
                        float fuel, boolean electric, List<Ammo> ammo, float link, double distance) {

        static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.of(Entry::write, Entry::read);

        private static void write(RegistryFriendlyByteBuf buf, Entry e) {
            UUIDUtil.STREAM_CODEC.encode(buf, e.id);
            buf.writeVarInt(e.feedId);
            buf.writeEnum(e.status);
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, e.name);
            Cell.CODEC.apply(ByteBufCodecs.list()).encode(buf, e.cells);
            buf.writeFloat(e.fuel);
            buf.writeBoolean(e.electric);
            Ammo.CODEC.apply(ByteBufCodecs.list()).encode(buf, e.ammo);
            buf.writeFloat(e.link);
            buf.writeDouble(e.distance);
        }

        private static Entry read(RegistryFriendlyByteBuf buf) {
            return new Entry(UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readEnum(Status.class),
                    ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf),
                    Cell.CODEC.apply(ByteBufCodecs.list()).decode(buf), buf.readFloat(), buf.readBoolean(),
                    Ammo.CODEC.apply(ByteBufCodecs.list()).decode(buf), buf.readFloat(), buf.readDouble());
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, UavTabletState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, UavTabletState::open, ByteBufCodecs.VAR_INT, UavTabletState::selected,
            Entry.CODEC.apply(ByteBufCodecs.list()), UavTabletState::entries, UavTabletState::new);

    public static void handle(UavTabletState message, IPayloadContext context) {
        context.enqueueWork(() -> UavTabletScreen.accept(message));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
