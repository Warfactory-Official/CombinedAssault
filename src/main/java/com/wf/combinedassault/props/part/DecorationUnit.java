package com.wf.combinedassault.props.part;

import com.wf.combinedassault.render.model.VehicleModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.client.vehicle.BaseDisplay;
import com.wf.combinedassault.props.client.DecorationSettingsScreen;
import com.wf.combinedassault.render.gem.GemModels;
import com.wf.combinedassault.vehicle.part.data.PartUnitData;
import com.wf.combinedassault.pack.sync.PartUnitSyncData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.props.pojo.DecorationAction;
import com.wf.wflib.geom.api.OBB;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;

import java.util.ArrayList;
import com.wf.combinedassault.vehicle.part.PartUnit;

public class DecorationUnit extends PartUnit<PartUnitData> {

    public String baseBoneName = "";
    public float scale;
    public float selfXRot;
    public float selfYRot;
    public float selfZRot;
    public Vec3 offsetFromBone;
    public Vec3 offsetFromVehicle;
    public Quaternionf rotation;

    public DecorationUnit(int index, AbstractVehicle vehicle, PartUnitData data) {
        super(index, vehicle, data);
        this.syncData = new PartUnitSyncData(this, 20);
        OBB obb = new OBB(vehicle.position().toVector3f(), new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf());
        this.partCubeOBBs = new ArrayList<>();
        this.partCubeOBBs.add(new VehicleCubeOBB(obb));
    }

    public void update(DecorationAction message) {
        displayId = CombinedAssault.resourceLocation(message.displayId);
        baseBoneName = message.baseBoneName;
        scale = message.scale;
        selfXRot = message.selfXRot;
        selfYRot = message.selfYRot;
        selfZRot = message.selfZRot;
        offsetFromBone = message.offsetFromBone;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = super.serializeNBT(provider);
        if (displayId != null) {
            tag.putString("decorationDisplayId", displayId.toString());
        }
        if (baseBoneName != null) {
            tag.putString("baseBoneName", baseBoneName);
        }
        tag.putFloat("x", (float) offsetFromBone.x);
        tag.putFloat("y", (float) offsetFromBone.y);
        tag.putFloat("z", (float) offsetFromBone.z);
        tag.putFloat("scale", scale);
        tag.putFloat("selfXRot", selfXRot);
        tag.putFloat("selfYRot", selfYRot);
        tag.putFloat("selfZRot", selfZRot);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        if (nbt.contains("decorationDisplayId")) {
            displayId = CombinedAssault.resourceLocation(nbt.getString("decorationDisplayId"));
        }
        if (nbt.contains("baseBoneName")) {
            baseBoneName = nbt.getString("baseBoneName");
        }
        offsetFromBone = new Vec3(nbt.getFloat("x"), nbt.getFloat("y"), nbt.getFloat("z"));
        scale = nbt.getFloat("scale");
        selfXRot = nbt.getFloat("selfXRot");
        selfYRot = nbt.getFloat("selfYRot");
        selfZRot = nbt.getFloat("selfZRot");
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void render(PoseStack pPoseStack, MultiBufferSource bufferSource, int pPackedLight, float partialTick) {
        BaseDisplay decorationDisplay = ClientAssetsManager.INSTANCE.getDecorationDisplay(displayId).orElse(null);
        if (decorationDisplay == null || decorationDisplay.getModelPath() == null) {
            return;
        }
        boolean rootAttachment = baseBoneName == null || baseBoneName.isBlank();
        VehicleModelInstance vehicleModelInstance = vehicle.getVehicleModelInstance();
        if (vehicleModelInstance == null) {
            return;
        }
        int attachmentBoneIndex = rootAttachment ? -1 : vehicleModelInstance.boneIndex(baseBoneName);
        if (!rootAttachment && !vehicleModelInstance.hasBone(attachmentBoneIndex)) {
            return;
        }
        Matrix4f attachmentTransform = attachmentBoneIndex < 0
                ? new Matrix4f()
                : vehicleModelInstance.globalTransform(attachmentBoneIndex);
        Quaternionf localRotation = new Quaternionf().rotateYXZ((float) Math.toRadians(-selfYRot),
                (float) Math.toRadians(selfXRot),
                (float) Math.toRadians(selfZRot));
        Quaternionf globalRotation = attachmentTransform.getUnnormalizedRotation(new Quaternionf())
                .normalize()
                .mul(localRotation);
        Vector3f globalPivot = offsetFromBone.toVector3f().mulPosition(attachmentTransform);
        Vector3f localEulerRotation = new Vector3f();
        localRotation.getEulerAnglesYXZ(localEulerRotation);
        pPoseStack.pushPose();
        {
            if (attachmentBoneIndex >= 0) {
                vehicleModelInstance.mulGlobalTransform(pPoseStack, attachmentBoneIndex);
            }
            pPoseStack.translate(offsetFromBone.x, offsetFromBone.y, offsetFromBone.z);
            pPoseStack.scale(scale, scale, scale);
            pPoseStack.rotateAround(Axis.YP.rotation(localEulerRotation.y), 0, 0, 0);
            pPoseStack.rotateAround(Axis.XP.rotation(localEulerRotation.x), 0, 0, 0);
            pPoseStack.rotateAround(Axis.ZP.rotation(localEulerRotation.z), 0, 0, 0);
            int light = pPackedLight;
            if (vehicle.isDestroyed()) {
                int blockLight = (int) (LightTexture.block(pPackedLight) / 1.5f);
                int skyLight = (int) (LightTexture.sky(pPackedLight) / 1.5f);
                light = LightTexture.pack(blockLight, skyLight);
            }
            offsetFromVehicle = new Vec3(globalPivot);
            rotation = globalRotation;
            GemModels.draw(decorationDisplay.getModelPath(), pPoseStack, light);
        }
        pPoseStack.popPose();
    }

    @Override
    public void tick() {
        super.tick();
        OBB obb = partCubeOBBs.get(0).obb();
        if (offsetFromVehicle != null && rotation != null) {
            obb.setCenter(vehicle.relativeRotPos(vehicle.position().add(offsetFromVehicle), false).toVector3f());
            obb.setRotation(vehicle.rotYXZ().mul(rotation));
        } else {
            obb.setCenter(vehicle.position().toVector3f());
        }
    }

    public boolean onInteract(Player player, InteractionHand hand) {
        if (player.level().isClientSide() && hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown()) {
            openScreen();
        }
        return true;
    }

    @OnlyIn(Dist.CLIENT)
    public void openScreen() {
        Minecraft.getInstance().setScreen(new DecorationSettingsScreen(this));
    }

}
