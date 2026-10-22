package com.wf.combinedassault.compat.gemrender;

import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfPose;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.iris.ShaderPacks;
import com.wf.gemrender.texture.Paint;
import com.wf.gemrender.render.BoneBuffer;
import com.wf.gemrender.render.GemRenderInstance;
import com.wf.gemrender.render.GemRenderInstanceTypes;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.task.TaskExecutor;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.EntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import com.wf.combinedassault.ads.StationPip;
import com.wf.combinedassault.all.AllEntities;
import com.wf.combinedassault.client.model.VehicleModelInstance;
import com.wf.combinedassault.client.render.entity.vehicle.VehicleRender;
import com.wf.combinedassault.custom.part.data.PartMount;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartClipTracks;
import com.wf.combinedassault.vehicle.part.PartUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a vehicle whose display was read by {@link GemRenderModelLoader}: one Flywheel instance for the
 * whole hull, posed from the palette {@link GemRenderModelInstance} publishes.
 *
 * <p>Entity renderer still runs (hitboxes, parts, decorations, camo, bullet holes, animation advance) but
 * emits no hull geometry (model not on CPU) => {@code skipVanillaRender} false, required not optional.
 *
 * <p>Position from entity, not published pose: off-screen vehicle reappears in place immediately, only
 * animation can lag.
 */
@OnlyIn(Dist.CLIENT)
public class VehicleGemVisual<T extends AbstractVehicle> extends AbstractEntityVisual<T>
        implements DynamicVisual {

    private static final int DAMAGE_REFRESH_TICKS = 10;

    private final Quaternionf rotation = new Quaternionf();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

    private GemRenderInstance body;
    private GemRenderGltfModel bound;
    /** {@link DamageSkin#slotKeys} of {@link #bound}; null when it has no damage variants. */
    private int[] damageKeys;
    private float[] damageUv;
    private org.joml.Matrix4f[] restPalette;
    private boolean damaged;
    /** Last refresh; -REFRESH = due. MIN_VALUE FORBIDDEN: tickCount - MIN_VALUE overflows negative, never due. */
    private int damageTick = -DAMAGE_REFRESH_TICKS;
    private final TowCableVisual cable;
    private final List<Mount> mounts = new ArrayList<>();
    private boolean mountsRead;

    /** One of a part's {@link PartMount}s: own instance at its bone's posed frame; rest palette, or its clips posed. */
    private static final class Mount {
        final PartUnit<?> part;
        final PartMount mount;
        final String bone;
        final ModelCache.Handle<GemRenderGltfModel> handle;
        GemRenderGltfModel bound;
        GemRenderInstance instance;
        Matrix4f[] rest;
        final Vector4f sphere = new Vector4f();
        PartClipTracks clipsTracks;
        @Nullable
        PartClips clips;
        @Nullable
        MountNodes nodes;
        float[] state = new float[0];
        Matrix4f[] palette;
        final GltfPose.Scratch scratch = new GltfPose.Scratch();

        /** Rest + clips at current progress. */
        Matrix4f[] posed(GemRenderGltfModel gltf, float partialTick) {
            NodeTable table = gltf.layout().nodeTable();
            if (state.length != table.scratchFloats()) {
                state = table.newScratch();
            }
            table.resetToRest(state);
            if (clips != null) {
                clips.advance(partialTick);
                clips.apply(state);
            }
            if (nodes != null) {
                nodes.apply(table, state, part);
            }
            int joints = gltf.jointCount();
            if (palette == null || palette.length != joints) {
                palette = new Matrix4f[joints];
                for (int i = 0; i < joints; i++) {
                    palette[i] = new Matrix4f();
                }
            }
            GltfPose.evaluate(gltf.layout(), state, palette, gltf.morphs(), null, scratch);
            return palette;
        }

        Mount(PartUnit<?> part, PartMount mount) {
            this.part = part;
            this.mount = mount;
            this.bone = mount.node();
            this.handle = GemRenderModels.handle(mount.model());
        }
    }

    public VehicleGemVisual(VisualizationContext context, T entity, float partialTick) {
        super(context, entity, partialTick);
        this.cable = new TowCableVisual(context);
        updatePose(partialTick);
    }

    static void register() {
        for (EntityType<? extends AbstractVehicle> type : AllEntities.vehicleTypes()) {
            bind(type);
        }
    }

    private static <T extends AbstractVehicle> void bind(EntityType<T> type) {
        VisualizerRegistry.setVisualizer(type, new EntityVisualizer<T>() {
            @Override
            public EntityVisual<? super T> createVisual(VisualizationContext context, T entity, float partialTick) {
                return new VehicleGemVisual<>(context, entity, partialTick);
            }

            @Override
            public boolean skipVanillaRender(T entity) {
                return false;
            }
        });
    }

    private void updatePose(float partialTick) {
        // Before model's early returns: cable independent of which loader draws the hull.
        cable.update(entity, renderOrigin(), partialTick);
        VehicleModelInstance active = VehicleRender.activeModelInstance(entity);
        GemRenderModelInstance gem = active instanceof GemRenderModelInstance instance ? instance : null;
        GemRenderGltfModel gltf = gem == null ? null : ((GemRenderVehicleModel) gem.model()).gltf();
        if (gltf == null) {
            hide();
            return;
        }
        if (bound != gltf) {
            if (body != null) {
                body.delete();
            }
            bound = gltf;
            restPalette = gltf.paintable() ? RestPalettes.of(gltf) : null;
            damageKeys = gltf.variantCount() > 1 ? DamageSkin.slotKeys(gltf) : null;
            damageUv = damageKeys == null ? null : new float[damageKeys.length * 2];
            damageTick = -DAMAGE_REFRESH_TICKS;
            body = instancerProvider()
                    .instancer(GemRenderInstanceTypes.SKINNED, gltf.model())
                    .createInstance();
            body.colorArgb(0xFFFFFFFF);
        }

        GemRenderModelInstance.Snapshot snapshot = gem.snapshot();
        if (snapshot == null) {
            hide();
            return;
        }

        Vec3i origin = renderOrigin();
        float x = (float) (Mth.lerp(partialTick, entity.xo, entity.getX()) - origin.getX());
        float y = (float) (Mth.lerp(partialTick, entity.yo, entity.getY()) - origin.getY());
        float z = (float) (Mth.lerp(partialTick, entity.zo, entity.getZ()) - origin.getZ());
        Vec3 centre = entity.centerOffset;
        rotation.set(entity.rotYXZ(partialTick));

        body.pose.translation(x, y, z)
                .translate((float) centre.x, (float) centre.y, (float) centre.z)
                .rotate(rotation)
                .translate((float) -centre.x, (float) -centre.y, (float) -centre.z);
        body.boneBase = BoneBuffer.getInstance().addPalette(snapshot.palette(), snapshot.jointCount());
        body.morphBase = snapshot.morphBlock() == null ? 0
                : BoneBuffer.getInstance().addMorphBlock(snapshot.morphBlock(), snapshot.morphBlock().length);
        body.boneSphere.set(snapshot.sphere());
        if (damageKeys != null) {
            if (entity.tickCount - damageTick >= DAMAGE_REFRESH_TICKS || entity.tickCount < damageTick) {
                damageTick = entity.tickCount;
                damaged = DamageSkin.fill(gltf, damageKeys, entity, damageUv);
            }
            body.jointUvBase = damaged ? BoneBuffer.getInstance().addFloatBlock(damageUv, damageUv.length)
                    : GemRenderInstance.NO_JOINT_VARIANTS;
        }
        Paint paint = ShaderPacks.inUse() ? Paint.NONE : VehiclePaints.forVehicle(entity, gltf);
        body.paint(paint, gltf.paintReference(),
                paint.isNone() ? 0 : BoneBuffer.getInstance().addSharedPalette(restPalette, gltf.jointCount()));
        int light = light(partialTick);
        body.light(light);
        body.setChanged();
        updateMounts(gltf, snapshot, paint, light, partialTick);
    }

    private void updateMounts(GemRenderGltfModel vehicle, GemRenderModelInstance.Snapshot snapshot, Paint paint,
                              int light, float partialTick) {
        if (!mountsRead) {
            mountsRead = true;
            for (PartUnit<?> part : entity.getPartUnits()) {
                for (PartMount mount : part.getData().getMounts()) {
                    mounts.add(new Mount(part, mount));
                }
            }
        }
        for (Mount mount : mounts) {
            GemRenderGltfModel gltf = mount.handle.get();
            int slot = vehicle.layout().nodeTable().slotOfName(mount.bone);
            if (gltf == null || slot < 0 || slot >= snapshot.jointCount() || mount.part.isDetached()
                    || !mount.mount.carriedBy(mount.part)) {
                if (mount.instance != null) {
                    mount.instance.setZeroTransform();
                    mount.instance.setChanged();
                }
                continue;
            }
            if (mount.bound != gltf) {
                if (mount.instance != null) {
                    mount.instance.delete();
                }
                mount.bound = gltf;
                mount.rest = RestPalettes.of(gltf);
                gltf.bounds().evaluate(mount.rest, mount.sphere);
                mount.instance = instancerProvider()
                        .instancer(GemRenderInstanceTypes.SKINNED, gltf.model())
                        .createInstance();
                mount.instance.colorArgb(0xFFFFFFFF);
                mount.clipsTracks = null;
                mount.nodes = MountNodes.of(gltf.layout().nodeTable());
            }
            PartClipTracks tracks = entity.clipTracks();
            if (mount.clipsTracks != tracks) {
                mount.clipsTracks = tracks;
                mount.clips = tracks == null ? null : PartClips.bind(gltf, tracks, mount.part.getId());
            }
            GemRenderInstance instance = mount.instance;
            instance.pose.set(body.pose).mul(snapshot.palette()[slot]);
            int palette = mount.clips == null && mount.nodes == null ? BoneBuffer.getInstance().addSharedPalette(mount.rest, gltf.jointCount())
                    : BoneBuffer.getInstance().addPalette(mount.posed(gltf, partialTick), gltf.jointCount());
            instance.boneBase = palette;
            instance.boneSphere.set(mount.sphere);
            Paint own = gltf.paintable() ? paint : Paint.NONE;
            instance.paint(own, gltf.paintReference(), own.isNone() ? 0 : palette);
            instance.light(light);
            instance.setChanged();
            StationPip.mount(mount.part, gltf, instance.pose, renderOrigin());
        }
    }


    /** Vehicle light, dimmed the way the entity renderer dims a wreck. */
    private int light(float partialTick) {
        lightPos.set(Mth.floor(entity.getX()), Mth.floor(entity.getY() + entity.getBbHeight() * 0.5), Mth.floor(entity.getZ()));
        int packed = LevelRenderer.getLightColor(entity.level(), lightPos);
        if (!entity.isDestroyed()) {
            return packed;
        }
        int block = (int) (net.minecraft.client.renderer.LightTexture.block(packed) / 1.5f);
        int sky = (int) (net.minecraft.client.renderer.LightTexture.sky(packed) / 1.5f);
        return net.minecraft.client.renderer.LightTexture.pack(block, sky);
    }

    private void hide() {
        if (body != null) {
            body.setZeroTransform();
            body.setChanged();
        }
        for (Mount mount : mounts) {
            if (mount.instance != null) {
                mount.instance.setZeroTransform();
                mount.instance.setChanged();
            }
        }
    }

    @Override
    protected void _delete() {
        cable.delete();
        if (body != null) {
            body.delete();
            body = null;
        }
        for (Mount mount : mounts) {
            if (mount.instance != null) {
                mount.instance.delete();
            }
        }
        mounts.clear();
    }

    @Override
    public Plan<Context> planFrame() {
        return new Plan<>() {
            @Override
            public void execute(TaskExecutor taskExecutor, Context context, Runnable onCompletion) {
                updatePose(context.partialTick());
                onCompletion.run();
            }

            @Override
            public Plan<Context> then(Plan<Context> plan) {
                return null;
            }

            @Override
            public Plan<Context> and(Plan<Context> plan) {
                return null;
            }
        };
    }
}
