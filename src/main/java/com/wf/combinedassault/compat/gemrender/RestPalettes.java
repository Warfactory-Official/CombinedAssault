package com.wf.combinedassault.compat.gemrender;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** One rest palette per model => {@code BoneBuffer#addSharedPalette} stages it once a frame. Weak: reload drops old. */
@OnlyIn(Dist.CLIENT)
final class RestPalettes {

    private static final Map<GemRenderGltfModel, Matrix4f[]> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private RestPalettes() {
    }

    static Matrix4f[] of(GemRenderGltfModel gltf) {
        return CACHE.computeIfAbsent(gltf, GemRenderGltfModel::restPalette);
    }
}
