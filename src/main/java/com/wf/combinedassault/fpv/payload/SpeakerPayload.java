package com.wf.combinedassault.fpv.payload;

import com.google.gson.JsonObject;
import com.wf.wflib.item.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** One {@code wflib:loudspeaker}: the carrier's radio plays loud ({@code RadioVoice.SPEAKER}); no triggers. */
public class SpeakerPayload extends StrappedPayload {

    private static final float MASS_KG = 0.6F;

    public SpeakerPayload(FpvPayloadType type, JsonObject json) {
        super(type, json, 1, 0);
    }

    @Override
    protected float massOf(ItemStack stack) {
        return MASS_KG;
    }

    @Override
    public boolean canLoad(ItemStack stack) {
        return this.items.size() < this.capacity && stack.is(ModItems.LOUDSPEAKER.get());
    }

    @Override
    public Component displayName() {
        return Component.translatable("fpv.combinedassault.payload.speaker");
    }

    @Override
    public boolean accepts(FpvTrigger trigger, double closingSpeed) {
        return false;
    }

    @Override
    public boolean consumesDrone(FpvTrigger trigger) {
        return false;
    }

    @Override
    public FpvPayloadResult trigger(PayloadUnit hardpoint, FpvTrigger trigger, Vec3 at, @Nullable LivingEntity operator) {
        return FpvPayloadResult.NOTHING;
    }
}
