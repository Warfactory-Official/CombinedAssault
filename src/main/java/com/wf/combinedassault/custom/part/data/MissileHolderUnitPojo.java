package com.wf.combinedassault.custom.part.data;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.vehicle.pojo.MissileSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MissileHolderUnitPojo extends PartUnitPojo {

    /** Absent: every size class. Empty: none (holder never loads). */
    @SerializedName("accepts")
    public List<MissileSize> accepts = null;

    /** 0: = station sockets. Slots past the last station: internal (no station). */
    @SerializedName("capacity")
    public int capacity = 0;

    /** WFLib pod items it seats (item, {@code #tag}, list); set => rounds go into the pod's tubes, not stations. */
    @SerializedName("pods")
    @Nullable
    public Ingredient pods = null;

    /** Pod seated, empty, at spawn. */
    @SerializedName("default_pod")
    @Nullable
    public ResourceLocation defaultPod = null;

    /** Ticks to swap an empty pod for a loaded one from the stores. */
    @SerializedName("pod_swap_time")
    public int podSwapTime = 600;

}
