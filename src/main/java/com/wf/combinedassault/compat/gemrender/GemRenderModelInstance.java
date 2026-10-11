package com.wf.combinedassault.compat.gemrender;

import com.maydaymemory.mae.basic.ArrayPoseBuilder;
import com.maydaymemory.mae.basic.BoneTransform;
import com.maydaymemory.mae.basic.DummyPose;
import com.maydaymemory.mae.basic.Pose;
import com.maydaymemory.mae.basic.PoseBuilder;
import com.maydaymemory.mae.basic.ZYXBoneTransformFactory;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfPose;
import com.wf.gemrender.gltf.NodeTable;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import com.wf.combinedassault.client.model.VehicleModel;
import com.wf.combinedassault.client.model.VehicleModelInstance;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.vehicle.hit.ModelRig;
import com.wf.combinedassault.vehicle.part.PartClipTracks;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.RadarUnit;
import com.wf.combinedassault.vehicle.part.RotatableUnit;
import com.wf.combinedassault.vehicle.part.SuspensionUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One vehicle's pose on a GemRender model: written on render thread, read by {@link VehicleGemVisual}
 * on a Flywheel task thread.
 *
 * <p>Pose translation: 1/16 block (script + binding convention); node translation: blocks => x16 / ÷16.
 */
@OnlyIn(Dist.CLIENT)
final class GemRenderModelInstance implements VehicleModelInstance {

    private static final ZYXBoneTransformFactory TRANSFORMS = new ZYXBoneTransformFactory();

    /** A pose ready to upload. Immutable once published. */
    record Snapshot(Matrix4f[] palette, int jointCount, Vector4f sphere, float @Nullable [] morphBlock) {
    }

    private final GemRenderVehicleModel model;

    /** Rest + graph pose. */
    private float[] state = new float[0];
    /** {@link #state} + clips + hidden, as last composed; unchanged => palette reused. */
    private float[] composed = new float[0];
    private float[] work = new float[0];
    private GemRenderGltfModel composedModel;
    private PartClips clips;
    private GemRenderGltfModel clipsModel;
    private PartClipTracks clipsTracks;
    private boolean clipsMoved;
    /** Suspension render-bone travel, vehicle frame (= model space), xyz per slot; {@link #travelled} marks slots. */
    private float[] travel = new float[0];
    private boolean[] travelled = new boolean[0];
    private boolean travelMoved;
    /** Two of everything, so the palette being written is never the one the visual is reading. */
    private final Matrix4f[][] palettes = new Matrix4f[2][];
    private final Vector4f[] spheres = { new Vector4f(), new Vector4f() };
    private float[] morphBlock;
    private int flip;
    private final GltfPose.Scratch scratch = new GltfPose.Scratch();
    private final Set<String> hidden = new HashSet<>();
    /** Scale {@link #hide} zeroed, xyz per slot: {@link #globalTransform} of a hidden bone stays its shown pose. */
    private float[] shownScale = new float[0];
    /** {@link #isolate}: kept slots; null => whole model. */
    @Nullable
    private String isolateBone;
    private Set<String> isolateExcluded = Set.of();
    private boolean[] keep;
    private GemRenderGltfModel keepModel;
    private boolean keepDirty;

    private volatile Snapshot published;

    /** Weapon / radar {@code part} + {@code pitch} frames, turned by their unit unless the graph poses that joint. */
    private record FrameDrive(int slot, RotatableUnit<?> unit, boolean yaw, boolean pitch) {
    }

    private List<FrameDrive> frameDrives = List.of();
    private GemRenderGltfModel framesModel;
    private AbstractVehicle framesVehicle;
    private float framesPartialTick;
    private boolean[] claimed = new boolean[0];

    private Pose bindPose;
    private int bindPoseBones = -1;
    private GemRenderGltfModel bindPoseModel;

    GemRenderModelInstance(GemRenderVehicleModel model) {
        this.model = model;
    }

    @Override
    public VehicleModel model() {
        return model;
    }

    @Nullable
    Snapshot snapshot() {
        return published;
    }

    @Override
    public Pose bindPose() {
        GemRenderGltfModel gltf = model.gltf();
        if (gltf == null) {
            return DummyPose.INSTANCE;
        }
        int bones = model.boneCount();
        if (bindPose != null && bindPoseBones == bones && bindPoseModel == gltf) {
            return bindPose;
        }
        NodeTable table = gltf.layout().nodeTable();
        PoseBuilder builder = new ArrayPoseBuilder();
        Quaternionf rotation = new Quaternionf();
        for (int bone = 0; bone < bones; bone++) {
            int slot = model.slot(bone);
            if (slot < 0) {
                builder.addBoneTransform(TRANSFORMS.createBoneTransform(bone, new Vector3f(),
                        new Quaternionf(), new Vector3f(1.0f, 1.0f, 1.0f)));
                continue;
            }
            table.restRotation(slot, rotation);
            builder.addBoneTransform(TRANSFORMS.createBoneTransform(bone,
                    new Vector3f(table.restTranslation(slot, 0) * 16.0f,
                            table.restTranslation(slot, 1) * 16.0f,
                            table.restTranslation(slot, 2) * 16.0f),
                    new Quaternionf(rotation), new Vector3f(1.0f, 1.0f, 1.0f)));
        }
        bindPose = builder.toPose();
        bindPoseBones = bones;
        bindPoseModel = gltf;
        return bindPose;
    }

    @Override
    public void applyPose(Pose pose) {
        GemRenderGltfModel gltf = model.gltf();
        if (gltf == null) {
            return;
        }
        NodeTable table = gltf.layout().nodeTable();
        if (state.length != table.scratchFloats()) {
            state = table.newScratch();
        }
        table.resetToRest(state);

        for (BoneTransform transform : pose.getBoneTransforms()) {
            int slot = model.slot(transform.boneIndex());
            if (slot < 0) {
                continue;
            }
            Vector3fc translation = transform.translation();
            table.setTranslation(state, slot, translation.x() / 16.0f, translation.y() / 16.0f,
                    translation.z() / 16.0f);
            table.setRotation(state, slot, transform.rotation().asQuaternion());
            Vector3fc scale = transform.scale();
            table.setScale(state, slot, scale.x(), scale.y(), scale.z());
        }
        if (framesModel == gltf) {
            Quaternionf rotation = new Quaternionf();
            for (FrameDrive drive : frameDrives) {
                if (drive.slot < claimed.length && claimed[drive.slot]) {
                    continue;
                }
                table.restRotation(drive.slot, rotation);
                // Unit convention (RotatableUnit#getViewGroupRotation): rest * Ry(-yaw) * Rx(pitch).
                if (drive.yaw) {
                    rotation.rotateY((float) Math.toRadians(-drive.unit.getViewYRot(framesPartialTick)));
                }
                if (drive.pitch) {
                    rotation.rotateX((float) Math.toRadians(drive.unit.getViewXRot(framesPartialTick)));
                }
                table.setRotation(state, drive.slot, rotation);
            }
        }
        Arrays.fill(claimed, false);
        compose(gltf);
    }

    /** Joints the animation graph poses itself (scripts, controller bindings): frame drives leave them alone. */
    @Override
    public void claim(Pose animation) {
        GemRenderGltfModel gltf = model.gltf();
        if (gltf == null) {
            return;
        }
        int nodes = gltf.layout().nodeTable().nodeCount();
        if (claimed.length != nodes) {
            claimed = new boolean[nodes];
        }
        for (BoneTransform transform : animation.getBoneTransforms()) {
            int slot = model.slot(transform.boneIndex());
            if (slot >= 0) {
                claimed[slot] = true;
            }
        }
    }

    private void bindFrames(AbstractVehicle vehicle, GemRenderGltfModel gltf) {
        framesModel = gltf;
        framesVehicle = vehicle;
        List<FrameDrive> drives = new ArrayList<>();
        ModelRig rig = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicle.getVehicleId())
                .map(BaseVehicleData::getModel).map(m -> m.rig()).orElse(null);
        if (rig != null) {
            NodeTable table = gltf.layout().nodeTable();
            for (PartUnit<?> part : vehicle.getPartUnits()) {
                if (!(part instanceof WeaponUnit) && !(part instanceof RadarUnit)) {
                    continue;
                }
                RotatableUnit<?> unit = (RotatableUnit<?>) part;
                int yawFrame = rig.part(part.getId());
                int pitchFrame = rig.pitch(part.getId());
                int yawSlot = yawFrame < 0 ? -1 : table.slotOfName(rig.frames().get(yawFrame).name());
                int pitchSlot = pitchFrame < 0 ? -1 : table.slotOfName(rig.frames().get(pitchFrame).name());
                if (yawSlot >= 0) {
                    drives.add(new FrameDrive(yawSlot, unit, true, pitchSlot < 0));
                }
                if (pitchSlot >= 0) {
                    drives.add(new FrameDrive(pitchSlot, unit, false, true));
                }
            }
        }
        frameDrives = List.copyOf(drives);
    }

    @Override
    public void driveParts(AbstractVehicle vehicle, float partialTick) {
        GemRenderGltfModel gltf = model.gltf();
        if (gltf == null) {
            return;
        }
        if (framesModel != gltf || framesVehicle != vehicle) {
            bindFrames(vehicle, gltf);
        }
        framesPartialTick = partialTick;
        PartClipTracks tracks = vehicle.clipTracks();
        if (clipsModel != gltf || clipsTracks != tracks) {
            clipsModel = gltf;
            clipsTracks = tracks;
            clips = tracks == null ? null : PartClips.bind(gltf, tracks);
        }
        if (clips != null) {
            clipsMoved |= clips.advance(partialTick);
        }
        NodeTable table = gltf.layout().nodeTable();
        if (travelled.length != table.nodeCount()) {
            travel = new float[table.nodeCount() * 3];
            travelled = new boolean[table.nodeCount()];
        }
        for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
            String bone = unit.getRenderBoneName();
            int slot = bone == null || unit.isDetached() ? -1 : table.slotOfName(bone);
            if (slot < 0) {
                continue;
            }
            Vec3 offset = unit.getVisualOffset(partialTick);
            int at = slot * 3;
            travelMoved |= travel[at] != (float) offset.x || travel[at + 1] != (float) offset.y || travel[at + 2] != (float) offset.z;
            travel[at] = (float) offset.x;
            travel[at + 1] = (float) offset.y;
            travel[at + 2] = (float) offset.z;
            travelled[slot] = true;
        }
    }

    /** Model-space travel -> parent-local translation: parent world rotation from {@code state}. */
    private void applyTravel(NodeTable table, float[] state) {
        int[] parents = table.parentSlots();
        Quaternionf world = new Quaternionf();
        Vector3f local = new Vector3f();
        for (int slot = 0; slot < travelled.length; slot++) {
            if (!travelled[slot]) {
                continue;
            }
            world.identity();
            for (int at = parents[slot]; at >= 0; at = parents[at]) {
                int base = at * NodeTable.TRS_STRIDE + NodeTable.ROTATION;
                world.premul(new Quaternionf(state[base], state[base + 1], state[base + 2], state[base + 3]));
            }
            world.conjugate().transform(local.set(travel[slot * 3], travel[slot * 3 + 1], travel[slot * 3 + 2]));
            int base = slot * NodeTable.TRS_STRIDE + NodeTable.TRANSLATION;
            state[base] += local.x;
            state[base + 1] += local.y;
            state[base + 2] += local.z;
        }
    }

    @Override
    public void resetPose() {
        GemRenderGltfModel gltf = model.gltf();
        if (gltf == null) {
            return;
        }
        NodeTable table = gltf.layout().nodeTable();
        if (state.length != table.scratchFloats()) {
            state = table.newScratch();
        }
        table.resetToRest(state);
        hidden.clear();
        compose(gltf);
    }

    /** A bone held invisible is collapsed to nothing, which takes its whole subtree with it. */
    private void hide(NodeTable table, float[] target) {
        if (shownScale.length != table.nodeCount() * 3) {
            shownScale = new float[table.nodeCount() * 3];
        }
        for (String name : hidden) {
            int slot = table.slotOfName(name);
            if (slot >= 0) {
                System.arraycopy(target, slot * NodeTable.TRS_STRIDE + NodeTable.SCALE, shownScale, slot * 3, 3);
                table.setScale(target, slot, 0.0f, 0.0f, 0.0f);
            }
        }
    }

    private void compose(GemRenderGltfModel gltf) {
        clipsMoved = false;
        if (work.length != state.length) {
            work = new float[state.length];
        }
        System.arraycopy(state, 0, work, 0, state.length);
        if (clips != null && clipsModel == gltf) {
            clips.apply(work);
        }
        travelMoved = false;
        if (travelled.length == gltf.layout().nodeTable().nodeCount()) {
            applyTravel(gltf.layout().nodeTable(), work);
        }
        hide(gltf.layout().nodeTable(), work);
        if (published != null && composedModel == gltf && !keepDirty && Arrays.equals(work, composed)) {
            return;
        }
        keepDirty = false;
        float[] swap = composed;
        composed = work;
        work = swap;
        composedModel = gltf;

        int joints = gltf.jointCount();
        Matrix4f[] palette = palettes[flip];
        if (palette == null || palette.length < joints) {
            palette = new Matrix4f[joints];
            for (int i = 0; i < joints; i++) {
                palette[i] = new Matrix4f();
            }
            palettes[flip] = palette;
        }
        int morphFloats = gltf.morphs().blockFloats();
        float[] morphs = null;
        if (!gltf.morphs().isEmpty()) {
            if (morphBlock == null || morphBlock.length < morphFloats) {
                morphBlock = new float[morphFloats];
            }
            morphs = morphBlock;
        }
        GltfPose.evaluate(gltf.layout(), composed, palette, gltf.morphs(), morphs, scratch);
        boolean[] kept = keep(gltf);
        if (kept != null) {
            for (int slot = 0; slot < kept.length && slot < joints; slot++) {
                if (!kept[slot]) {
                    palette[slot].zero();
                }
            }
        }
        Vector4f sphere = spheres[flip];
        gltf.bounds().evaluate(palette, sphere);
        published = new Snapshot(palette, joints, sphere, morphs);
        flip ^= 1;
    }

    @Override
    public void isolate(@Nullable String bone, Set<String> excluded) {
        if (!java.util.Objects.equals(bone, isolateBone) || !excluded.equals(isolateExcluded)) {
            isolateBone = bone;
            isolateExcluded = Set.copyOf(excluded);
            keepModel = null;
            keepDirty = true;
        }
    }

    /** Palette rows are world matrices: zeroing one hides that node's own mesh, not its subtree. */
    @Nullable
    private boolean[] keep(GemRenderGltfModel gltf) {
        if (isolateBone == null) {
            return null;
        }
        if (keepModel == gltf) {
            return keep;
        }
        NodeTable table = gltf.layout().nodeTable();
        int[] parents = table.parentSlots();
        int root = table.slotOfName(isolateBone);
        boolean[] kept = new boolean[table.nodeCount()];
        for (int slot = 0; slot < kept.length; slot++) {
            boolean inside = false;
            for (int at = slot; at >= 0; at = parents[at]) {
                if (isolateExcluded.contains(table.nodeName(at))) {
                    break;
                }
                if (at == root) {
                    inside = true;
                    break;
                }
            }
            kept[slot] = inside;
        }
        keep = kept;
        keepModel = gltf;
        return kept;
    }

    @Override
    public int boneIndex(String boneName) {
        GemRenderGltfModel gltf = model.gltf();
        if (gltf == null || gltf.layout().nodeTable().slotOfName(boneName) < 0) {
            return -1;
        }
        return model.index(boneName);
    }

    @Override
    public boolean hasBone(int boneIndex) {
        return model.slot(boneIndex) >= 0;
    }

    @Override
    public String boneName(int boneIndex) {
        GemRenderGltfModel gltf = model.gltf();
        int slot = model.slot(boneIndex);
        return gltf == null || slot < 0 ? "" : gltf.layout().nodeTable().nodeName(slot);
    }

    @Override
    public void setBoneVisible(String boneName, boolean visible) {
        if (visible) {
            hidden.remove(boneName);
        } else {
            hidden.add(boneName);
        }
    }

    @Override
    public void setBoneIlluminated(String boneName, boolean illuminated) {
        // GemRender lights whole instance; no per-bone light.
    }

    /** Node world matrix, model space. */
    @Override
    public Matrix4f globalTransform(int boneIndex) {
        Snapshot snapshot = published;
        int slot = model.slot(boneIndex);
        if (snapshot == null || slot < 0 || slot >= snapshot.jointCount()) {
            return new Matrix4f();
        }
        NodeTable table = composedModel.layout().nodeTable();
        if (!hidden.contains(table.nodeName(slot)) || !table.isPosable(slot)) {
            return new Matrix4f(snapshot.palette()[slot]);
        }
        int parent = table.parentSlots()[slot];
        Matrix4f out = parent < 0 ? new Matrix4f() : new Matrix4f(snapshot.palette()[parent]);
        int t = slot * NodeTable.TRS_STRIDE;
        int r = t + NodeTable.ROTATION;
        return out.mul(new Matrix4f().translationRotateScale(composed[t], composed[t + 1], composed[t + 2],
                composed[r], composed[r + 1], composed[r + 2], composed[r + 3],
                shownScale[slot * 3], shownScale[slot * 3 + 1], shownScale[slot * 3 + 2]));
    }

    @Override
    public boolean posed() {
        return published != null;
    }

    @Override
    public void mulGlobalTransform(PoseStack poseStack, int boneIndex) {
        Matrix4f transform = globalTransform(boneIndex);
        poseStack.last().pose().mul(transform);
        poseStack.last().normal().mul(new Matrix3f(transform));
    }

    /** Rest pose if never posed (display without a controller); recompose for clips/travel moved without {@link #applyPose}. */
    @Override
    public void publish() {
        if (published == null) {
            resetPose();
        } else if ((clipsMoved || travelMoved) && model.gltf() != null) {
            compose(model.gltf());
        }
    }
}
