package com.wf.combinedassault.api.custom.sync;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

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

    /** One round: item + WFLib ammo variant; count/other components dropped. */
    public static final SyncDataSerializer<ItemStack> ROUND = SyncDataSerializer.create(
            (buf, stack) -> {
                buf.writeVarInt(BuiltInRegistries.ITEM.getId(stack.getItem()));
                ResourceLocation variant = stack.get(com.wf.wflib.ammo.AmmoItems.VARIANT.get());
                buf.writeBoolean(variant != null);
                if (variant != null) {
                    buf.writeResourceLocation(variant);
                }
            },
            buf -> {
                Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
                ItemStack stack = item == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
                if (buf.readBoolean()) {
                    ResourceLocation variant = buf.readResourceLocation();
                    if (!stack.isEmpty()) {
                        stack.set(com.wf.wflib.ammo.AmmoItems.VARIANT.get(), variant);
                    }
                }
                return stack;
            },
            ItemStack::isSameItemSameComponents
    );

    private SyncDataSerializers() {}

}
