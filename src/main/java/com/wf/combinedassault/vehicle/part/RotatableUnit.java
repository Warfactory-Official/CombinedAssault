package com.wf.combinedassault.vehicle.part;

import com.wf.wflib.audio.api.AudioCategory;
import com.wf.combinedassault.vehicle.audio.VehicleSounds;
import com.wf.combinedassault.vehicle.audio.HullLoop;
import com.mojang.math.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Math;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.pack.sync.SyncDataSerializers;
import com.wf.combinedassault.vehicle.part.data.RotatableUnitData;
import com.wf.combinedassault.ballistics.component.ComponentDamage;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.util.ParticleUtil;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.wflib.geom.api.OBB;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;

import java.util.List;
import java.util.Map;

public class RotatableUnit<T extends RotatableUnitData> extends PartUnit<T> {

    protected float xAimRot;
    protected float yAimRot;
    protected float xRot;
    protected float yRot;
    public float xRotO;
    public float yRotO;
    protected float xRemoteAimRot;
    protected float yRemoteAimRot;
    public float xSelfRot;
    public float ySelfRot;
    public float xRotSpeed;
    public float yRotSpeed;
    public float xRotMax = 90;
    public float xRotMin = -90;
    public float yRotMax = Float.MAX_VALUE;
    public float yRotMin = -Float.MAX_VALUE;
    public boolean needPower = true;
    @OnlyIn(Dist.CLIENT)
    private HullLoop servoY;
    @OnlyIn(Dist.CLIENT)
    private HullLoop servoX;

    public RotatableUnit(int index, AbstractVehicle vehicle, T data) {
        super(index, vehicle, data);
        var rotInfo = data.getRotInfo();
        this.xRotSpeed = rotInfo.xRotSpeed;
        this.yRotSpeed = rotInfo.yRotSpeed;
        this.xRotMax = rotInfo.xRotMax;
        this.xRotMin = rotInfo.xRotMin;
        this.yRotMax = rotInfo.yRotMax;
        this.yRotMin = rotInfo.yRotMin;
        this.xRot = rotInfo.xRot;
        this.xAimRot = this.xRot;
        this.yRot = rotInfo.yRot;
        this.yAimRot = this.yRot;
        this.needPower = rotInfo.needPower;
        this.getSyncData().define(SyncDataSerializers.FLOAT, this::setXRemoteAimRot, this::getXAimRot, 0f);
        this.getSyncData().define(SyncDataSerializers.FLOAT, this::setYRemoteAimRot, this::getYAimRot, 0f);
    }

    public void buildStructure(Map<VehicleCubeGroup, VehicleCubeGroup> vehicleCubeGroupCopy) {
        super.buildStructure(vehicleCubeGroupCopy);
        if (structureGroup != null) {
            Vector3f selfRot = new Vector3f();
            structureGroup.baseRotation.getEulerAnglesYXZ(selfRot);
            this.xSelfRot = (float) Math.toDegrees(selfRot.x);
            this.ySelfRot = (float) Math.toDegrees(-selfRot.y);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (vehicle.level().isClientSide()) {
            tickRemoteRot();
            tickSound();
        }
        tickRot();
        updateRot();
    }

    @OnlyIn(Dist.CLIENT)
    protected void tickSound() {
        boolean live = (!needPower || vehicle.hasPower()) && !isDestroyed();
        if (servoY == null) {
            servoY = new HullLoop(VehicleSounds.SERVO_H, AudioCategory.VEHICLES, 0.1F);
            servoX = new HullLoop(VehicleSounds.SERVO_V, AudioCategory.VEHICLES, 0.1F);
        }
        servoY.tick(vehicle, getPivotOffset(), live && yRotSpeed != 0 && Math.abs((yAimRot - yRot) % 360) > 1
                && yRot < yRotMax && yRot > yRotMin, 1);
        servoX.tick(vehicle, getPivotOffset(), live && xRotSpeed != 0 && Math.abs((xAimRot - xRot) % 360) > 1
                && xRot < xRotMax && xRot > xRotMin, 1);
    }

    protected void tickRemoteRot() {
        if (vehicle.level().isClientSide()
                && getOwner() != LocalVehiclePlayer.instance().getPlayer()) {
            xAimRot = xRemoteAimRot;
            yAimRot = yRemoteAimRot;
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected void tickParticle() {
        if (isDestroyed() && !vehicle.isDestroyed() && vehicle.tickCount % 20 == 0) {
            ParticleUtil.spawnWreckageSmoke(vehicle.level(), OBB.toAABB(List.of(getLargestCube().obb())), 5);
        }
    }

    /** Arc [min, max] may cross +-180 (rear mounts: 110..250); yaw past it => nearer edge. */
    public static float clampYaw(float yaw, float min, float max) {
        if (max - min >= 360) {
            return yaw;
        }
        float centre = (min + max) / 2;
        float half = (max - min) / 2;
        return centre + Mth.clamp(Mth.wrapDegrees(yaw - centre), -half, half);
    }

    public static boolean inYawArc(float yaw, float min, float max) {
        return max - min >= 360 || Math.abs(Mth.wrapDegrees(yaw - (min + max) / 2)) <= (max - min) / 2;
    }

    protected void tickRot() {
        xRotO = xRot;
        yRotO = yRot;
        if ((!needPower || vehicle.hasPower()) && !isDestroyed()) {
            // A damaged ring still turns, just not as fast, and a broken one not at all.
            float traverse = ComponentDamage.effectiveness(this);
            xRot = Mth.approachDegrees(xRot, xAimRot, xRotSpeed * traverse);
            xRot = Mth.wrapDegrees(xRot);
            xRot = Mth.clamp(xRot, xRotMin - xSelfRot, xRotMax - xSelfRot);
            float dXRot = xRot - xRotO;
            if (Math.abs(dXRot) > 180) {
                xRotO += Math.signum(dXRot) * 360;
            }
            yRot = Mth.approachDegrees(yRot, yAimRot, yRotSpeed * traverse);
            yRot = Mth.wrapDegrees(yRot);
            yRot = clampYaw(yRot, yRotMin - ySelfRot, yRotMax - ySelfRot);
            float dYRot = yRot - yRotO;
            if (Math.abs(dYRot) > 180) {
                yRotO += Math.signum(dYRot) * 360;
            }
        }
    }

    public void updateRot() {
        if (structureGroup != null) {
            structureGroup.rotationO = new Quaternionf(structureGroup.rotation);
        }
        poseRot();
    }

    /** Group rotations from xRot/yRot; rotationO untouched. */
    protected void poseRot() {
        if (structureGroup != null) {
            structureGroup.rotation = new Quaternionf(structureGroup.baseRotation).mul(Axis.YN.rotationDegrees(yRot).mul(Axis.XP.rotationDegrees(xRot)));
        }
    }

    public boolean turned() {
        return xRot != xRotO || yRot != yRotO;
    }

    private float heldXRot;
    private float heldYRot;

    /** Undoes this tick's traverse; {@link #resumeRot} restores it. */
    public void holdRot() {
        heldXRot = xRot;
        heldYRot = yRot;
        xRot = xRotO;
        yRot = yRotO;
        poseRot();
    }

    public void resumeRot() {
        xRot = heldXRot;
        yRot = heldYRot;
        poseRot();
    }

    @Override
    protected Quaternionf getViewGroupRotation(VehicleCubeGroup group, float partialTick) {
        if (partialTick != 1.0F && group == structureGroup) {
            return new Quaternionf(group.baseRotation)
                    .mul(Axis.YN.rotationDegrees(getViewYRot(partialTick))
                            .mul(Axis.XP.rotationDegrees(getViewXRot(partialTick))));
        }
        return super.getViewGroupRotation(group, partialTick);
    }

    public Vec2 worldRot() {
        return worldRot(xRot, yRot);
    }

    public Vec3 worldVec() {
        return worldVec(xRot, yRot);
    }

    public Vec2 worldRot(float xRot, float yRot) {
        Vec3 worldVec = worldVec(xRot, yRot);
        return VectorUtil.vecToRot(worldVec);
    }

    public Vec3 worldVec(float xRot, float yRot) {
        return new Vec3(baseRot().transform(VectorUtil.rotToVec(xRot, yRot).toVector3f()));
    }

    public Vec3 worldVecToLocalVec(Vec3 worldVec) {
        return new Vec3(baseRot().conjugate().transform(worldVec.toVector3f()));
    }

    public Vec2 worldVecToLocalRot(Vec3 worldVec) {
        return VectorUtil.vecToRot(worldVecToLocalVec(worldVec));
    }

    public float worldZRot() {
        Quaternionf rot = new Quaternionf();
        rot.rotateY(Math.toRadians(-yRot));
        rot.rotateX(Math.toRadians(-xRot));
        rot = baseRot().mul(rot);
        Vector3f eulerAngles = new Vector3f();
        rot.getEulerAnglesYXZ(eulerAngles);
        return (float) Math.toDegrees(eulerAngles.z);
    }

    public Quaternionf baseRot() {
        Quaternionf rotation = vehicle.rotYXZ();
        if (structureGroup != null) {
            rotation.mul(structureGroup.baseRotation);
            if (structureGroup.parent != null) {
                rotation.mul(structureGroup.parent.globalTransform().rotation());
            }
        }
        return rotation;
    }

    public Vec2 aimRot(Vec3 worldPosition) {
        if (structureGroup == null) {
            return new Vec2(0, 0);
        }
        Vec3 fromWorldPosition = vehicle.relativeRotPos(vehicle.position().add(structureGroup.globalTransform().offset()), false);
        Vec3 worldAim = new Vec3(worldPosition.x - fromWorldPosition.x, worldPosition.y - fromWorldPosition.y, worldPosition.z - fromWorldPosition.z);
        return worldVecToLocalRot(worldAim);
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = super.serializeNBT(provider);
        tag.putFloat("xRot", this.xRot);
        tag.putFloat("yRot", this.yRot);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        this.xRot = nbt.getFloat("xRot");
        this.xRotO = this.xRot;
        this.xAimRot = this.xRot;
        this.yRot = nbt.getFloat("yRot");
        this.yRotO = this.yRot;
        this.yAimRot = this.yRot;
    }

    public float getViewXRot(float pPartialTicks) {
        return pPartialTicks == 1.0F ? xRot : Mth.lerp(pPartialTicks, this.xRotO, xRot);
    }

    public float getViewYRot(float pPartialTicks) {
        return pPartialTicks == 1.0F ? yRot : Mth.lerp(pPartialTicks, this.yRotO, yRot);
    }

    public float getXRotSpeed() {
        return xRotSpeed;
    }

    public void setXRotSpeed(float xRotSpeed) {
        this.xRotSpeed = xRotSpeed;
    }

    public float getYRotSpeed() {
        return yRotSpeed;
    }

    public void setYRotSpeed(float yRotSpeed) {
        this.yRotSpeed = yRotSpeed;
    }

    public float getXRotMax() {
        return xRotMax;
    }

    public void setXRotMax(float xRotMax) {
        this.xRotMax = xRotMax;
    }

    public float getXRotMin() {
        return xRotMin;
    }

    public void setXRotMin(float xRotMin) {
        this.xRotMin = xRotMin;
    }

    public float getYRotMax() {
        return yRotMax;
    }

    public void setYRotMax(float yRotMax) {
        this.yRotMax = yRotMax;
    }

    public float getYRotMin() {
        return yRotMin;
    }

    public void setYRotMin(float yRotMin) {
        this.yRotMin = yRotMin;
    }

    public float getXAimRot() {
        return xAimRot;
    }

    public void setXAimRot(float xAimRot) {
        this.xAimRot = xAimRot;
    }

    public float getYAimRot() {
        return yAimRot;
    }

    public void setYAimRot(float yAimRot) {
        this.yAimRot = yAimRot;
    }

    public float getXRot() {
        return xRot;
    }

    public void setXRot(float xRot) {
        this.xRot = xRot;
    }

    public float getYRot() {
        return yRot;
    }

    public void setYRot(float yRot) {
        this.yRot = yRot;
    }

    public float getXRemoteAimRot() {
        return xRemoteAimRot;
    }

    public void setXRemoteAimRot(float xRemoteAimRot) {
        this.xRemoteAimRot = xRemoteAimRot;
    }

    public float getYRemoteAimRot() {
        return yRemoteAimRot;
    }

    public void setYRemoteAimRot(float yRemoteAimRot) {
        this.yRemoteAimRot = yRemoteAimRot;
    }

}
