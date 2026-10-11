package com.wf.combinedassault.client.render.entity.vehicle;

import com.wf.combinedassault.client.render.part.RoundStations;

import com.maydaymemory.mae.basic.Pose;

import com.wf.combinedassault.ballistics.addon.client.AddonArmorRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import com.wf.wflib.util.OBB;
import org.joml.Vector3f;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import com.wf.combinedassault.all.AllKeys;
import com.wf.combinedassault.api.animation.IAnimationEntity;
import com.wf.combinedassault.api.event.VehicleFireEvent;
import com.wf.combinedassault.client.particle.BulletHoleParticle;
import com.wf.combinedassault.client.render.animation.VehicleAnimationInstance;
import com.wf.combinedassault.client.render.animation.context.EntityContext;
import com.wf.combinedassault.client.render.util.HullRenderer;
import com.wf.wflib.client.render.OBBRenderer;
import com.wf.combinedassault.client.resource.ClientAssetsManager;
import com.wf.combinedassault.client.render.util.VehicleCamoRenderer;
import com.wf.combinedassault.client.model.VehicleModel;
import com.wf.combinedassault.client.model.VehicleModelInstance;
import com.wf.combinedassault.client.resource.vehicle.VehicleDisplay;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.FixedWingVehicle;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.DecorationUnit;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;

import static com.wf.combinedassault.client.render.animation.util.PoseBlenders.BLENDER;

@EventBusSubscriber(value = Dist.CLIENT)
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

    /** True: operator view of own vehicle with a cabin display (swaps in the cabin model). */
    public static boolean isCabinView(AbstractVehicle vehicle, VehicleDisplay<?, ?> display) {
        return LocalVehiclePlayer.instance.vehicle == vehicle
                && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.OPERATOR
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

    public static void renderHitbox(AbstractVehicle vehicle, PoseStack poseStack, MultiBufferSource bufferSource) {
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        boolean renderSelfVehicleHitbox = vehicle == LocalVehiclePlayer.instance.vehicle && AllKeys.INSPECT_VEHICLE.isDown();
        List<PartUnit<?>> partUnits = new ArrayList<>();
        if ((dispatcher.shouldRenderHitBoxes() || renderSelfVehicleHitbox) && vehicle.hits() != null) {
            renderMeshVehicleHitbox(vehicle, poseStack, buffer);
            partUnits.addAll(vehicle.getDecorationUnits().values());
        } else if (dispatcher.shouldRenderHitBoxes() || renderSelfVehicleHitbox) {
            if (vehicle.getMainCubeOBB() != null) {
                OBBRenderer.render(vehicle.position(),
                        List.of(vehicle.getMainCubeOBB().obb()),
                        poseStack, buffer, 0, 0, 1, 1);
            }
            for (VehicleCubeOBB vehicleCubeOBB : vehicle.getVehicleCubeOBBs()) {
                // Solid: real hull shape. Faint: broad-phase bound box, can be looser than the hull.
                boolean hulled = vehicleCubeOBB.hasHull();
                OBBRenderer.render(vehicle.position(),
                        List.of(vehicleCubeOBB.obb()),
                        poseStack, buffer, 0, 1, 0, hulled ? 0.25F : 1);
                if (hulled) {
                    HullRenderer.render(vehicle.position(), vehicleCubeOBB,
                            vehicle.collisionBody().solvesAgainst(vehicleCubeOBB),
                            poseStack, buffer);
                }
            }
            HullRenderer.renderHits(vehicle, poseStack, buffer);
            if (vehicle instanceof FixedWingVehicle fixedWingVehicle
                    && fixedWingVehicle.aerodynamicCubeOBB != null) {
                OBBRenderer.render(vehicle.position(),
                        List.of(fixedWingVehicle.aerodynamicCubeOBB.obb()),
                        poseStack, buffer, 0, 1, 1, 1);
            }
            partUnits.addAll(vehicle.getPartUnits());
            partUnits.addAll(vehicle.getDecorationUnits().values());
        } else {
            PartUnit<?> partUnit = LocalVehiclePlayer.instance.lookAtPartUnit;
            if (AllKeys.INSPECT_VEHICLE.isDown() && partUnit != null) {
                partUnits.add(partUnit);
            }
        }
        for (PartUnit<?> partUnit : partUnits) {
            if (partUnit.isDetached()) {
                continue;
            }
            if (vehicle.hits() != null && !(partUnit instanceof DecorationUnit)) {
                continue;
            }
            for (VehicleCubeOBB partCube : partUnit.getPartCubeOBBs()) {
                HullRenderer.render(vehicle.position(), partCube,
                        vehicle.collisionBody().solvesAgainst(partCube), poseStack, buffer);
            }
            if (partUnit instanceof WeaponUnit weaponUnit) {
                OBBRenderer.render(vehicle.position(), weaponUnit.getOBBs(),
                        poseStack, buffer, 1, 0, 0, 1);
            } else if (partUnit instanceof DecorationUnit decorationUnit) {
                OBBRenderer.render(vehicle.position(), decorationUnit.getOBBs(),
                        poseStack, buffer, 1, 0, 1, 1);
            } else {
                OBBRenderer.render(vehicle.position(), partUnit.getOBBs(),
                        poseStack, buffer, 1, 1, 0, 1);
            }
        }
    }

    /** Collision hull pieces, main cube deduped in, + hit geometry. */
    private static void renderMeshVehicleHitbox(AbstractVehicle vehicle, PoseStack poseStack, VertexConsumer buffer) {
        List<VehicleCubeOBB> cubes = new ArrayList<>(vehicle.getVehicleCubeOBBs());
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!partUnit.isDetached()) {
                cubes.addAll(partUnit.getPartCubeOBBs());
            }
        }
        if (!cubes.contains(vehicle.getMainCubeOBB())) {
            cubes.add(vehicle.getMainCubeOBB());
        }
        for (VehicleCubeOBB cube : cubes) {
            if (cube.hasHull()) {
                HullRenderer.render(vehicle.position(), cube, vehicle.collisionBody().solvesAgainst(cube), poseStack, buffer);
            } else {
                OBBRenderer.render(vehicle.position(), List.of(cube.obb()), poseStack, buffer, 1, 1, 0, 1);
            }
        }
        HullRenderer.renderHits(vehicle, poseStack, buffer);
    }

    /** Hides the bones of parts that have fallen off. */
    public static void applyDetachedPart(AbstractVehicle vehicle, VehicleModelInstance modelInstance) {
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            String renderBoneName = partUnit.getRenderBoneName();
            if (renderBoneName != null && modelInstance.boneIndex(renderBoneName) >= 0) {
                modelInstance.setBoneVisible(renderBoneName, !partUnit.isDetached());
            }
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
                boolean isCabinView = LocalVehiclePlayer.instance.vehicle == vehicle
                        && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.OPERATOR
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
