package com.wf.combinedassault.uav.fpv.payload;

import com.google.gson.JsonObject;
import com.wf.wflib.grenade.api.ChargeItem;
import com.wf.wflib.grenade.api.ChargeSpec;
import com.wf.wflib.grenade.api.ThrownCharge;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Bomber: {@code #mineassault:drone_droppable} hand charges, released one per trigger with the drone's velocity. */
public class GrenadeRackPayload extends StrappedPayload {

    public static final TagKey<Item> DROPPABLE = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("mineassault", "drone_droppable"));

    public GrenadeRackPayload(FpvPayloadType type, JsonObject json) {
        super(type, json, 4, 0);
    }

    @Override
    protected float massOf(ItemStack stack) {
        return ((ChargeItem) stack.getItem()).massKg(stack);
    }

    @Override
    public boolean canLoad(ItemStack stack) {
        return this.items.size() < this.capacity && stack.is(DROPPABLE) && stack.getItem() instanceof ChargeItem;
    }

    @Override
    public Component displayName() {
        return Component.translatable("fpv.combinedassault.payload.grenade_rack");
    }

    /** Operator release only: pins stay in through impact, shoot-down and link loss. */
    @Override
    public boolean accepts(FpvTrigger trigger, double closingSpeed) {
        return trigger == FpvTrigger.OPERATOR && !this.items.isEmpty();
    }

    @Override
    public boolean consumesDrone(FpvTrigger trigger) {
        return false;
    }

    /** Spoon released at the rack: full fuse from here. */
    @Override
    public FpvPayloadResult trigger(PayloadUnit hardpoint, FpvTrigger trigger, Vec3 at, @Nullable LivingEntity operator) {
        Level level = hardpoint.getVehicle().level();
        if (level.isClientSide()) {
            return FpvPayloadResult.NOTHING;
        }
        ItemStack stack = this.items.removeFirst();
        ChargeSpec spec = ((ChargeItem) stack.getItem()).charge(stack);
        Entity owner = operator != null ? operator : hardpoint.getVehicle();
        level.addFreshEntity(ThrownCharge.create(level, at, hardpoint.getVehicle().getDeltaMovement(), stack, spec,
                spec.fuseTicks(), owner));
        return FpvPayloadResult.SPENT;
    }
}
