package com.wf.combinedassault.ballistics.hit;

import com.wf.combinedassault.vehicle.light.VehicleLight;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @param source glTF resource location
 * @param hits   null: {@code "hitbox": false}
 * @param clips  null: no clips
 * @param grafts part {@code mounts}, def order
 */
public record ModelData(String source, ModelRig rig, @Nullable HitMesh hits, ModelMounts mounts, @Nullable ClipRig clips,
                        List<VehicleLight> lights, List<Graft> grafts) {

    /** @param kit kit-scoped mount's kit, else null */
    public record Graft(String part, ResourceLocation model, String node, @Nullable String kit) {
    }
}
