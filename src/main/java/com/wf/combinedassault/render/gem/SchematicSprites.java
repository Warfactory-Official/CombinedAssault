package com.wf.combinedassault.render.gem;

import com.mojang.blaze3d.platform.NativeImage;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.render.model.VehicleModelInstance;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfMesh;
import com.wf.gemrender.gltf.MeshGeometry;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Model;
import net.minecraft.Util;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Lazy top-down sprite sheets ({@link SchematicBake}) per (model, bone set), baked off-thread on first request and
 * held for the session. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class SchematicSprites {
    private static final Map<Key, Sheet> SHEETS = new HashMap<>();

    private SchematicSprites() {
    }

    private record Key(ResourceLocation model, List<String> bones) {
    }

    /**
     * The sheet for this instance's model; PENDING until the glTF is imported and baked. Not a Gem model => FAILED.
     * Re-request when {@link Sheet#valid()} turns false (model reloaded).
     */
    public static Sheet request(VehicleModelInstance instance, List<String> bones) {
        if (!(instance instanceof GemRenderModelInstance gem) || !(gem.model() instanceof GemRenderVehicleModel model)) {
            return Sheet.UNSUPPORTED;
        }
        Key key = new Key(model.modelPath(), List.copyOf(bones));
        GemRenderGltfModel gltf = model.gltf();
        Sheet held = SHEETS.get(key);
        if (held != null && held.gltf == gltf) {
            return held;
        }
        if (held != null) {
            held.close();
        }
        Sheet sheet = new Sheet(model, gltf, gltf == null ? null : bake(gltf, key.bones));
        SHEETS.put(key, sheet);
        return sheet;
    }

    private static CompletableFuture<SchematicBake.Result> bake(GemRenderGltfModel gltf, List<String> bones) {
        List<MeshGeometry> meshes = new ArrayList<>();
        for (Model.ConfiguredMesh configured : gltf.model().meshes()) {
            if (configured.mesh() instanceof GltfMesh mesh && configured.material().transparency() != Transparency.TRANSLUCENT) {
                meshes.add(mesh.geometry());
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            Matrix4f[] rest = gltf.restPalette();
            return SchematicBake.bake(meshes, gltf.layout(), rest, bones);
        }, Util.backgroundExecutor());
    }

    @OnlyIn(Dist.CLIENT)
    public static final class Sheet {
        public enum State {PENDING, READY, FAILED}

        static final Sheet UNSUPPORTED = new Sheet(null, null, null);

        private final @Nullable GemRenderVehicleModel owner;
        private final @Nullable GemRenderGltfModel gltf;
        private final @Nullable CompletableFuture<SchematicBake.Result> future;
        private @Nullable DynamicTexture texture;
        private @Nullable SchematicBake.Result result;
        private State state;

        private Sheet(@Nullable GemRenderVehicleModel owner, @Nullable GemRenderGltfModel gltf,
                      @Nullable CompletableFuture<SchematicBake.Result> future) {
            this.owner = owner;
            this.gltf = gltf;
            this.future = future;
            this.state = owner == null ? State.FAILED : State.PENDING;
        }

        /** False once the model it was baked from is gone or replaced. */
        public boolean valid() {
            return owner == null || owner.gltf() == gltf;
        }

        /** Uploads a finished bake; call once per frame before reading anything else. */
        public State state() {
            if (state == State.PENDING && future != null && future.isDone()) {
                try {
                    result = future.join();
                    if (result == null) {
                        throw new IllegalStateException("no geometry");
                    }
                    NativeImage image = new NativeImage(result.width(), result.height(), false);
                    int[] argb = result.argb();
                    for (int y = 0; y < result.height(); y++) {
                        for (int x = 0; x < result.width(); x++) {
                            int c = argb[y * result.width() + x];
                            image.setPixelRGBA(x, y, c & 0xFF00FF00 | (c & 0xFF) << 16 | (c >> 16) & 0xFF);
                        }
                    }
                    texture = new DynamicTexture(image);
                    texture.setFilter(true, false);
                    state = State.READY;
                } catch (RuntimeException e) {
                    CombinedAssault.LOGGER.warn("top-down sprites unavailable for {}", owner.modelPath(), e);
                    state = State.FAILED;
                }
            }
            return state;
        }

        /** GL name; READY only. */
        public int texture() {
            return texture.getId();
        }

        /** READY only. */
        public SchematicBake.Result result() {
            return result;
        }

        void close() {
            if (texture != null) {
                texture.close();
                texture = null;
            }
            state = State.FAILED;
        }
    }
}
