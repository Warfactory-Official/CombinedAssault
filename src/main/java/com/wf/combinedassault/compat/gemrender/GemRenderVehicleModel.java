package com.wf.combinedassault.compat.gemrender;

import com.wf.combinedassault.client.model.BoneIndexProvider;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.NodeTable;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.client.model.BoneIndexProvider;
import com.wf.combinedassault.client.model.VehicleModel;
import com.wf.combinedassault.client.model.VehicleModelInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A display model held by GemRender.
 *
 * <p>Bone index allocated on first name lookup, before import: scripts/controllers compile pre-import,
 * numbering can't be the importer's. Unknown name still indexed (compiler can't be told "no"); never moves.
 * Index -> palette slot map built once asset arrives.
 */
@OnlyIn(Dist.CLIENT)
final class GemRenderVehicleModel implements VehicleModel {

    private final ResourceLocation modelPath;
    private final ModelCache.Handle<GemRenderGltfModel> handle;

    private final Map<String, Integer> indices = new ConcurrentHashMap<>();
    private final List<String> names = new ArrayList<>();

    /** Bone index to palette slot, -1 for a name the model does not have. Rebuilt on reload. */
    private volatile int[] slots = new int[0];
    private volatile GemRenderGltfModel mapped;

    GemRenderVehicleModel(ResourceLocation modelPath, ModelCache.Handle<GemRenderGltfModel> handle) {
        this.modelPath = modelPath;
        this.handle = handle;
    }

    @Override
    public ResourceLocation modelPath() {
        return modelPath;
    }

    @Override
    public BoneIndexProvider boneIndexProvider() {
        return this::index;
    }

    @Override
    public VehicleModelInstance createInstance() {
        return new GemRenderModelInstance(this);
    }

    /** Null until GemRender has imported the asset, and again after every failed import. */
    @Nullable
    GemRenderGltfModel gltf() {
        return handle.get();
    }

    /** Index for this bone name; allocated on first use. */
    int index(String boneName) {
        Integer existing = indices.get(boneName);
        if (existing != null) {
            return existing;
        }
        synchronized (names) {
            return indices.computeIfAbsent(boneName, name -> {
                names.add(name);
                return names.size() - 1;
            });
        }
    }

    int boneCount() {
        synchronized (names) {
            return names.size();
        }
    }

    /** Palette slot for a bone index; -1 if absent or not posable. Recomputed on reload or new name. */
    int slot(int boneIndex) {
        int[] current = slots;
        if (boneIndex < 0) {
            return -1;
        }
        if (boneIndex >= current.length || mapped != handle.get()) {
            current = remap();
        }
        return boneIndex < current.length ? current[boneIndex] : -1;
    }

    private synchronized int[] remap() {
        GemRenderGltfModel model = handle.get();
        List<String> snapshot;
        synchronized (names) {
            snapshot = List.copyOf(names);
        }
        int[] built = new int[snapshot.size()];
        if (model == null) {
            java.util.Arrays.fill(built, -1);
            slots = built;
            mapped = null;
            return built;
        }
        NodeTable table = model.layout().nodeTable();
        int missing = 0;
        for (int i = 0; i < snapshot.size(); i++) {
            int slot = table.slotOfName(snapshot.get(i));
            if (slot >= 0 && !table.isPosable(slot)) {
                CombinedAssault.LOGGER.warn("{} bone '{}' is declared as a matrix, which glTF forbids animating; "
                        + "it will not move", modelPath, snapshot.get(i));
                slot = -1;
            }
            if (slot < 0) {
                missing++;
            }
            built[i] = slot;
        }
        if (missing > 0) {
            CombinedAssault.LOGGER.warn("{}: {} of {} animated bone names are not in the model", modelPath,
                    missing, snapshot.size());
        }
        slots = built;
        mapped = model;
        return built;
    }
}
