package com.wf.combinedassault.render.entity.vehicle;

import com.maydaymemory.mae.basic.Pose;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.RotorStrike;
import com.wf.combinedassault.aircraft.WingLoss;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.ballistics.addon.client.AddonArmorRenderer;
import com.wf.combinedassault.camo.client.VehicleCamoRenderer;
import com.wf.combinedassault.cargo.client.CargoRenderer;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.combinedassault.pack.IAnimationEntity;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplay;
import com.wf.combinedassault.props.part.DecorationUnit;
import com.wf.combinedassault.render.animation.VehicleAnimationInstance;
import com.wf.combinedassault.render.animation.context.EntityContext;
import com.wf.combinedassault.render.gem.GemScreens;
import com.wf.combinedassault.render.model.VehicleModelInstance;
import com.wf.combinedassault.render.part.RoundStations;
import com.wf.combinedassault.render.particle.BulletHoleParticle;
import com.wf.combinedassault.render.util.DebugRenderTypes;
import com.wf.combinedassault.render.util.HullRenderer;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.client.VehicleKeys;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.event.VehicleFireEvent;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.geom.api.OBB;
import com.wf.wflib.geom.api.client.OBBRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import static com.wf.combinedassault.render.animation.util.PoseBlenders.BLENDER;

public class VehicleRender<T extends AbstractVehicle> extends EntityRenderer<T> {

    public VehicleRender(EntityRendererProvider.Context pContext) {
        super(pContext);
    }

    /** Blocks above the hull top of the last light probe. */
    private static final float PROBE_CLEAR = 0.5F;

    /** Centre inside a block (sunk, clipped into terrain) reads 0 => whole model black: max with hull top, above it. */
    @Override
    protected int getBlockLightLevel(T vehicle, BlockPos pos) {
        return probeMax(vehicle, LightLayer.BLOCK, super.getBlockLightLevel(vehicle, pos));
    }

    @Override
    protected int getSkyLightLevel(T vehicle, BlockPos pos) {
        return probeMax(vehicle, LightLayer.SKY, super.getSkyLightLevel(vehicle, pos));
    }

    private static int probeMax(AbstractVehicle vehicle, LightLayer layer, int centre) {
        VehicleCubeOBB hull = vehicle.getMainCubeOBB();
        if (centre >= 15 || hull == null) {
            return centre;
        }
        OBB obb = hull.obb();
        Vector3f c = obb.center(), up = obb.getAxes()[1];
        float top = obb.extents().y;
        int best = centre;
        for (float k : new float[]{top, top + PROBE_CLEAR}) {
            best = Math.max(best, vehicle.level().getBrightness(layer, BlockPos.containing(c.x + up.x * k, c.y + up.y * k, c.z + up.z * k)));
        }
        return best;
    }

    @Override
    public void render(T vehicle, float pEntityYaw, float pPartialTick, PoseStack pPoseStack, MultiBufferSource bufferSource, int pPackedLight) {
        renderHitbox(vehicle, pPoseStack, bufferSource);
        VehicleDisplay<?, ?> display = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
        if (display == null) {
            return;
        }
        boolean isCabinView = isCabinView(vehicle, display);
        VehicleModelInstance modelInstance = isCabinView ? vehicle.getCabinModelInstance() : vehicle.getVehicleModelInstance();
        if (modelInstance == null) {
            return;
        }
        modelInstance.driveParts(vehicle, pPartialTick);
        int modelLight = pPackedLight;
        if (vehicle.isDestroyed()) {
            int blockLight = (int) (LightTexture.block(pPackedLight) / 1.5f);
            int skyLight = (int) (LightTexture.sky(pPackedLight) / 1.5f);
            modelLight = LightTexture.pack(blockLight, skyLight);
        }
        pPoseStack.pushPose();
        try {
            super.render(vehicle, pEntityYaw, pPartialTick, pPoseStack, bufferSource, pPackedLight);
            applyDetachedPart(vehicle, modelInstance);
            applyViewHide(vehicle, modelInstance);
            applyVehicleRotation(vehicle, pPartialTick, pPoseStack);
            applyAnimationPose(vehicle, pPartialTick, modelInstance, isCabinView);
            RoundStations.apply(vehicle, modelInstance);
            modelInstance.publish();
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                if (partUnit.isDetached()) {
                    continue;
                }
                partUnit.render(pPoseStack, bufferSource, modelLight, pPartialTick);
            }
            for (DecorationUnit decoration : vehicle.getDecorationUnits().values()) {
                decoration.render(pPoseStack, bufferSource, pPackedLight, pPartialTick);
            }
            RoundStations.render(vehicle, modelInstance, pPoseStack, bufferSource, modelLight);
            GemScreens.draw(vehicle, modelInstance, pPoseStack.last().pose(), modelLight, pPartialTick);
            CargoRenderer.render(vehicle, pPoseStack, modelLight);
            VehicleCamoRenderer.enqueue(vehicle, pPoseStack, bufferSource, pPackedLight, pPartialTick);
            AddonArmorRenderer.enqueue(vehicle, pPoseStack, bufferSource, pPackedLight, pPartialTick);
            for (BulletHoleParticle bulletHoleParticle : vehicle.getBulletHoleParticles()) {
                bulletHoleParticle.renderOnVehicle(pPartialTick, pPoseStack, bufferSource, modelInstance);
            }
            vehicle.lastRenderTime = System.currentTimeMillis();
        } finally {
            pPoseStack.popPose();
        }
    }

    /** Piloted UAV seen from its own lens; scripts hide the camera head ({@code isOwnLensView}). */
    public static boolean ownLensView(AbstractVehicle vehicle) {
        return vehicle.uav && vehicle == LocalVehiclePlayer.instance().vehicle
                && LocalVehiclePlayer.instance().viewType != LocalVehiclePlayer.ViewType.THIRD_PERSON;
    }

    /** True: operator view of own vehicle with a cabin display (swaps in the cabin model). */
    public static boolean isCabinView(AbstractVehicle vehicle, VehicleDisplay<?, ?> display) {
        return LocalVehiclePlayer.instance().vehicle == vehicle
                && LocalVehiclePlayer.instance().viewType == LocalVehiclePlayer.ViewType.OPERATOR
                && display.getCabinDisplay() != null;
    }

    /** Cabin model from inside, hull model otherwise; null if no display registered. */
    @Nullable
    public static VehicleModelInstance activeModelInstance(AbstractVehicle vehicle) {
        VehicleDisplay<?, ?> display = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
        if (display == null) {
            return null;
        }
        return isCabinView(vehicle, display) ? vehicle.getCabinModelInstance() : vehicle.getVehicleModelInstance();
    }

    /**
     * Collision pieces queued this frame, drawn at {@link #onRenderLevel}. Keyed by vehicle: shadow / scope passes
     * render entities again from other cameras => origin from the main camera at draw time, never the queuing stack.
     */
    private static final Map<AbstractVehicle, List<VehicleCubeOBB>> PENDING = new LinkedHashMap<>();

    /**
     * Collision pieces: queued to {@link #onRenderLevel}, no depth test. GemRender draws the model after the entity
     * batch => an entity-pass line, depth test or not, is painted over (hold floor / walls inside the skin).
     */
    public static void renderHitbox(AbstractVehicle vehicle, PoseStack poseStack, MultiBufferSource bufferSource) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        boolean all = dispatcher.shouldRenderHitBoxes() && com.wf.wflib.core.api.client.DebugGate.allowed()
                || vehicle == LocalVehiclePlayer.instance().vehicle && VehicleKeys.INSPECT_VEHICLE.isDown();
        boolean meshed = vehicle.hits() != null;
        List<PartUnit<?>> partUnits = new ArrayList<>();
        if (all) {
            if (!meshed) {
                partUnits.addAll(vehicle.getPartUnits());
            }
            partUnits.addAll(vehicle.getDecorationUnits().values());
        } else {
            PartUnit<?> partUnit = LocalVehiclePlayer.instance().lookAtPartUnit;
            if (VehicleKeys.INSPECT_VEHICLE.isDown() && partUnit != null) {
                partUnits.add(partUnit);
            }
        }
        partUnits.removeIf(p -> p.isDetached() || meshed && !(p instanceof DecorationUnit));
        if (!all && partUnits.isEmpty()) {
            return;
        }

        List<VehicleCubeOBB> cubes = new ArrayList<>();
        if (all) {
            cubes.addAll(vehicle.getVehicleCubeOBBs());
        }
        if (all && meshed) {
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                if (!partUnit.isDetached()) {
                    cubes.addAll(partUnit.getPartCubeOBBs());
                }
            }
            if (!cubes.contains(vehicle.getMainCubeOBB())) {
                cubes.add(vehicle.getMainCubeOBB());
            }
        }
        for (PartUnit<?> partUnit : partUnits) {
            cubes.addAll(partUnit.getPartCubeOBBs());
        }
        if (!(all && meshed)) {
            cubes.removeIf(cube -> !cube.hasHull()); // box drawn green / yellow below
        }
        if (!cubes.isEmpty()) {
            PENDING.put(vehicle, cubes);
        }

        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());
        if (all) {
            if (!meshed) {
                if (vehicle.getMainCubeOBB() != null) {
                    OBBRenderer.render(vehicle.position(), List.of(vehicle.getMainCubeOBB().obb()),
                            poseStack, buffer, 0, 0, 1, 1);
                }
                for (VehicleCubeOBB cube : vehicle.getVehicleCubeOBBs()) {
                    // Solid: real hull shape. Faint: broad-phase bound box, can be looser than the hull.
                    OBBRenderer.render(vehicle.position(), List.of(cube.obb()),
                            poseStack, buffer, 0, 1, 0, cube.hasHull() ? 0.25F : 1);
                }
                if (vehicle instanceof FixedWingVehicle fixedWingVehicle && fixedWingVehicle.aerodynamicCubeOBB != null) {
                    OBBRenderer.render(vehicle.position(), List.of(fixedWingVehicle.aerodynamicCubeOBB.obb()),
                            poseStack, buffer, 0, 1, 1, 1);
                }
            }
            HullRenderer.renderHits(vehicle, poseStack, buffer);
        }
        for (PartUnit<?> partUnit : partUnits) {
            if (partUnit instanceof WeaponUnit weaponUnit) {
                OBBRenderer.render(vehicle.position(), weaponUnit.getOBBs(), poseStack, buffer, 1, 0, 0, 1);
            } else if (partUnit instanceof DecorationUnit decorationUnit) {
                OBBRenderer.render(vehicle.position(), decorationUnit.getOBBs(), poseStack, buffer, 1, 0, 1, 1);
            } else {
                OBBRenderer.render(vehicle.position(), partUnit.getOBBs(), poseStack, buffer, 1, 1, 0, 1);
            }
        }
    }

    /** Hull pieces white (see-through when not colliding); hull-less pieces of a meshed vehicle yellow boxes. */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || PENDING.isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer buffer = bufferSource.getBuffer(DebugRenderTypes.LINES_NO_DEPTH);
        // AFTER_LEVEL: NeoForge passes no stack and the view rotation is already popped => apply it here.
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(event.getModelViewMatrix());
        Vec3 camera = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        for (Map.Entry<AbstractVehicle, List<VehicleCubeOBB>> pending : PENDING.entrySet()) {
            AbstractVehicle vehicle = pending.getKey();
            Vec3 at = vehicle.getPosition(partialTick).subtract(camera);
            poseStack.pushPose();
            poseStack.translate(at.x, at.y, at.z);
            for (VehicleCubeOBB cube : pending.getValue()) {
                if (cube.hasHull()) {
                    HullRenderer.render(vehicle.position(), cube, vehicle.collisionBody().solvesAgainst(cube), poseStack, buffer);
                } else {
                    OBBRenderer.render(vehicle.position(), List.of(cube.obb()), poseStack, buffer, 1, 1, 0, 1);
                }
            }
            poseStack.popPose();
        }
        PENDING.clear();
        bufferSource.endBatch(DebugRenderTypes.LINES_NO_DEPTH);
    }

    /** Hides the bones of parts that have fallen off, and of rotors and wings whose pool is spent ({@link RotorStrike}, {@link WingLoss}). */
    public static void applyDetachedPart(AbstractVehicle vehicle, VehicleModelInstance modelInstance) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            String renderBoneName = partUnit.getRenderBoneName();
            if (renderBoneName != null && modelInstance.boneIndex(renderBoneName) >= 0) {
                modelInstance.setBoneVisible(renderBoneName, !partUnit.isDetached());
            }
        }
        VehicleHits hits = vehicle.hits();
        if (hits == null) {
            return;
        }
        HullComponents pools = vehicle.hullComponents();
        List<HitModel.Node> nodes = hits.model().nodes();
        for (int i = 0; i < nodes.size(); i++) {
            HitModel.Node node = nodes.get(i);
            if (node.mesh().disc() != null && RotorStrike.rotor(node.kind())) {
                int pool = pools.indexOf(node.systemId());
                modelInstance.setBoneVisible(node.mesh().name(), pool < 0 || pools.health(pool) > 0);
            } else if (node.kind() == ComponentKind.WING && node.mesh().mesh() != null) {
                modelInstance.setBoneVisible(node.mesh().name(), !WingLoss.spent(node, pools));
            }
        }
    }

    /** Per instance: the {@code view_hide} nodes it hid last frame. */
    private static final Map<VehicleModelInstance, List<String>> VIEW_HIDDEN = new java.util.WeakHashMap<>();

    /** Local rider's seat {@code view_hide} hidden while in first person (after {@link #applyDetachedPart}: wins). */
    public static void applyViewHide(AbstractVehicle vehicle, VehicleModelInstance modelInstance) {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance();
        List<String> now = local.vehicle == vehicle && local.seat != null
                && local.viewType != LocalVehiclePlayer.ViewType.THIRD_PERSON
                ? local.seat.partUnit.getData().getViewHide() : List.of();
        List<String> before = VIEW_HIDDEN.getOrDefault(modelInstance, List.of());
        for (String node : before) {
            if (!now.contains(node)) {
                modelInstance.setBoneVisible(node, true);
            }
        }
        for (String node : now) {
            modelInstance.setBoneVisible(node, false);
        }
        if (now.isEmpty()) {
            VIEW_HIDDEN.remove(modelInstance);
        } else {
            VIEW_HIDDEN.put(modelInstance, now);
        }
    }

    public static void applyVehicleRotation(AbstractVehicle vehicle, float partialTick, PoseStack poseStack) {
        Vec3 root = vehicle.centerOffset;
        Quaternionf rot = vehicle.rotYXZ(partialTick);
        poseStack.rotateAround(rot, (float) root.x, (float) root.y, (float) root.z);
    }

    public static void applyAnimationPose(AbstractVehicle vehicle, float partialTick, VehicleModelInstance modelInstance, boolean isCabinView) {
        if (vehicle instanceof IAnimationEntity<?, ?> animationEntity) {
            var animationInstance = animationEntity.getAnimationInstance();
            if (animationInstance instanceof VehicleAnimationInstance<? extends EntityContext<?>> vehicleAnimationInstance) {
                if (isCabinView) {
                    animationInstance = vehicleAnimationInstance.getCabinAnimationInstance();
                }
                animationInstance.getContext().setPartialTick(partialTick);
                animationInstance.tick();
                Pose animation = animationInstance.getCurrentPose();
                modelInstance.claim(animation);
                modelInstance.applyPose(BLENDER.blend(modelInstance.bindPose(), animation));
            }
        }
    }

    @SubscribeEvent
    public static void onFire(VehicleFireEvent.Post event) {
        if (!event.isClientSide()) {
            return;
        }
        AbstractVehicle vehicle = event.getVehicle();
        VehicleDisplay<?, ?> display = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId()).orElse(null);
        if (display == null) {
            return;
        }
        if (vehicle instanceof IAnimationEntity<?, ?> animationEntity) {
            if (animationEntity.getAnimationInstance() instanceof VehicleAnimationInstance<? extends EntityContext<?>> vehicleAnimationInstance) {
                String partId = event.getWeapon().getWeaponUnit().getId();
                boolean isCabinView = LocalVehiclePlayer.instance().vehicle == vehicle
                        && LocalVehiclePlayer.instance().viewType == LocalVehiclePlayer.ViewType.OPERATOR
                        && display.getCabinDisplay() != null;
                if (isCabinView) {
                    var cabinAnimationInstance = vehicleAnimationInstance.getCabinAnimationInstance();
                    if (cabinAnimationInstance != null) {
                        cabinAnimationInstance.getContext().offerEvent(partId + "_fire");
                    }
                } else {
                    vehicleAnimationInstance.getContext().offerEvent(partId + "_fire");
                }
            }
        }
    }

    @Override
    public ResourceLocation getTextureLocation(@NotNull T pEntity) {
        return null;
    }

}
