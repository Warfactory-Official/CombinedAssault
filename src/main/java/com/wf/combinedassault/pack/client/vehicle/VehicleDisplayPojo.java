package com.wf.combinedassault.pack.client.vehicle;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;

public class VehicleDisplayPojo extends BaseDisplayPojo {

    /** glTF drawn instead of {@code model} from the operator seat. */
    @SerializedName("cabin_model")
    public ResourceLocation cabinModel;
}
