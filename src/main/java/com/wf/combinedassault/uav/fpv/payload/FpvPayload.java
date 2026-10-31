package com.wf.combinedassault.uav.fpv.payload;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Keeps drone ignorant of ordnance specifics: a new payload kind is one class + one registration. */
public interface FpvPayload {

    FpvPayloadType type();

    /** Shown on the HUD next to the arming state. */
    Component displayName();

    /** Uses left of total: warhead 1 of 1, grenade rack 3 of 4. */
    int remaining();

    int capacity();

    /** kg, as carried now. */
    float mass();

    /** Strapped hardware, as it would come back off. */
    List<ItemStack> contents();

    /** @return true if one of {@code stack} may be strapped on now */
    boolean canLoad(ItemStack stack);

    /** Straps one of {@code stack} (copied, count 1); only after {@link #canLoad}. */
    void load(ItemStack stack);

    /** Everything strapped, removed and returned. */
    List<ItemStack> unload();

    /** Deploying arms nothing; only the operator's explicit choice does, keeping the drone recoverable. */
    boolean isArmed();

    void setArmed(boolean armed);

    /** Ticks arm -> live: fuze safety so arming at the operator's own feet doesn't take them too. */
    int armingTicks();

    /**
     * Ignores arming state. Fuze speed is the payload's property, not the drone's, hence passed rather
     * than read off the caller.
     *
     * @param closingSpeed blocks/tick at the trigger; zero when the trigger has no impact
     */
    boolean accepts(FpvTrigger trigger, double closingSpeed);

    /**
     * Asked before {@link #trigger}: hardpoint clears the operator and removes the drone first if this is
     * true. Keeps each payload from needing to know the host ejects a damaged pilot instantly.
     */
    boolean consumesDrone(FpvTrigger trigger);

    /**
     * Called only once armed, fuze elapsed, and {@link #accepts}. Must not remove the drone itself: the
     * hardpoint already did if {@link #consumesDrone} said to.
     *
     * @param operator null if the control link already died
     */
    FpvPayloadResult trigger(PayloadUnit hardpoint, FpvTrigger trigger, Vec3 at, @Nullable LivingEntity operator);

    /** Type id is written by the hardpoint, not here. */
    void save(CompoundTag tag, HolderLookup.Provider provider);

    void load(CompoundTag tag, HolderLookup.Provider provider);

}
