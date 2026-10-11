package com.wf.combinedassault.client.model;

import net.minecraft.resources.ResourceLocation;

/** A display's glTF, shared by every vehicle wearing it; per-vehicle pose lives in {@link VehicleModelInstance}. */
public interface VehicleModel {

    ResourceLocation modelPath();

    /** Bone name -> index of every {@link com.maydaymemory.mae.basic.Pose} applied to an instance. */
    BoneIndexProvider boneIndexProvider();

    /** Fresh instance in the bind pose; one per vehicle. */
    VehicleModelInstance createInstance();
}
