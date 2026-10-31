package com.wf.combinedassault.ballistics.hit;

import com.wf.combinedassault.vehicle.light.VehicleLight;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @param source glTF resource location
 * @param hits   null: {@code "hitbox": false}
 * @param clips  null: no clips
 */
public record ModelData(String source, ModelRig rig, @Nullable HitMesh hits, ModelMounts mounts, @Nullable ClipRig clips,
                        List<VehicleLight> lights) {
}
