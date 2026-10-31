package com.wf.combinedassault.pack.sync;

import com.wf.wflib.ammo.api.AmmoCodecs;
import com.wf.wflib.barrel.api.BarrelState;
import com.wf.wflib.magazine.api.MagContents;
import com.wf.wflib.magazine.api.MagazineItems;
import com.wf.wflib.magazine.api.Run;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.weapon.RoundCount;

import java.util.ArrayList;
import java.util.List;

public class SyncDataSerializers {

    public static final SyncDataSerializer<Boolean> BOOLEAN = SyncDataSerializer.create(
            FriendlyByteBuf::writeBoolean,
            FriendlyByteBuf::readBoolean,
            Boolean::equals
    );

    public static final SyncDataSerializer<Integer> INT = SyncDataSerializer.create(
            FriendlyByteBuf::writeInt,
            FriendlyByteBuf::readInt,
            Integer::equals
    );

    public static final SyncDataSerializer<Float> FLOAT = SyncDataSerializer.create(
            FriendlyByteBuf::writeFloat,
            FriendlyByteBuf::readFloat,
            Float::equals
    );

    public static final SyncDataSerializer<Double> DOUBLE = SyncDataSerializer.create(
            FriendlyByteBuf::writeDouble,
            FriendlyByteBuf::readDouble,
            Double::equals
    );

    public static final SyncDataSerializer<String> STRING = SyncDataSerializer.create(
            FriendlyByteBuf::writeUtf,
            FriendlyByteBuf::readUtf,
            String::equals
    );

    public static final SyncDataSerializer<Vec3> VEC3 = SyncDataSerializer.create(
            (friendlyByteBuf, vec3) -> friendlyByteBuf.writeVector3f(vec3.toVector3f()),
            friendlyByteBuf -> new Vec3(friendlyByteBuf.readFloat(), friendlyByteBuf.readFloat(), friendlyByteBuf.readFloat()),
            Vec3::equals
    );

    /** Registry id only; no count, no components. */
    public static final SyncDataSerializer<Item> ITEM = SyncDataSerializer.create(
            (friendlyByteBuf, item) -> friendlyByteBuf.writeVarInt(BuiltInRegistries.ITEM.getId(item)),
            friendlyByteBuf -> BuiltInRegistries.ITEM.byId(friendlyByteBuf.readVarInt()),
            Item::equals
    );

    /** One round: item + WFLib ammo variant + magazine contents; count/other components dropped. */
    public static final SyncDataSerializer<ItemStack> ROUND = SyncDataSerializer.create(
            (buf, stack) -> {
                buf.writeVarInt(BuiltInRegistries.ITEM.getId(stack.getItem()));
                ResourceLocation variant = stack.get(com.wf.wflib.ammo.api.AmmoItems.VARIANT.get());
                buf.writeBoolean(variant != null);
                if (variant != null) {
                    buf.writeResourceLocation(variant);
                }
                MagContents contents = stack.get(MagazineItems.CONTENTS.get());
                buf.writeBoolean(contents != null);
                if (contents != null) {
                    buf.writeVarInt(contents.runs().size());
                    for (Run run : contents.runs()) {
                        AmmoCodecs.VARIANT_STREAM.encode(buf, run.round());
                        buf.writeVarInt(run.count());
                    }
                    buf.writeVarInt(contents.links());
                }
            },
            buf -> {
                Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
                ItemStack stack = item == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
                if (buf.readBoolean()) {
                    ResourceLocation variant = buf.readResourceLocation();
                    if (!stack.isEmpty()) {
                        stack.set(com.wf.wflib.ammo.api.AmmoItems.VARIANT.get(), variant);
                    }
                }
                if (buf.readBoolean()) {
                    int size = buf.readVarInt();
                    List<Run> runs = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        runs.add(new Run(AmmoCodecs.VARIANT_STREAM.decode(buf), buf.readVarInt()));
                    }
                    stack.set(MagazineItems.CONTENTS.get(), MagContents.of(runs, buf.readVarInt()));
                }
                return stack;
            },
            ItemStack::isSameItemSameComponents
    );

    /** {@link #ROUND} + count per kind. */
    public static final SyncDataSerializer<List<RoundCount>> ROUND_STOCK = SyncDataSerializer.create(
            (buf, stock) -> {
                buf.writeVarInt(stock.size());
                for (RoundCount entry : stock) {
                    ROUND.write(buf, entry.round());
                    buf.writeVarInt(entry.count() + 1);
                }
            },
            buf -> {
                int size = buf.readVarInt();
                List<RoundCount> stock = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    stock.add(new RoundCount(ROUND.read(buf), buf.readVarInt() - 1));
                }
                return List.copyOf(stock);
            },
            RoundCount::same
    );

    public static final SyncDataSerializer<BarrelState> BARREL = SyncDataSerializer.create(
            BarrelState.STREAM_CODEC::encode, BarrelState.STREAM_CODEC::decode,
            BarrelState::equals);

    private SyncDataSerializers() {}

}
