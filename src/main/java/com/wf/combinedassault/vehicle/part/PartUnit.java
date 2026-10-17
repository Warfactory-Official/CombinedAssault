package com.wf.combinedassault.vehicle.part;

import com.wf.combinedassault.vehicle.crew.CrewRole;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.util.INBTSerializable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.all.AllEntities;
import com.wf.combinedassault.api.custom.sync.SyncDataSerializers;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.part.data.PartUnitData;
import com.wf.combinedassault.custom.sync.PartUnitSyncData;
import com.wf.combinedassault.custom.sync.SyncDataEntry;
import com.wf.combinedassault.entity.misc.VehiclePart;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.network.message.ClientVehicleAction;
import com.wf.combinedassault.vehicle.pojo.DefenseStats;
import com.wf.wflib.util.OBB;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PartUnit<T extends PartUnitData> implements INBTSerializable<CompoundTag> {

    protected final int index;
    protected final String id;
    protected final Component name;
    protected final AbstractVehicle vehicle;
    protected VehicleCubeGroup structureGroup;
    protected List<VehicleCubeOBB> partCubeOBBs;
    protected LivingEntity owner;
    protected int ownerId;
    protected boolean detached;
    protected float health;
    protected float maxHealth;
    protected DefenseStats defenseStats = new DefenseStats();
    protected String renderBoneName;
    protected boolean isSeat;
    protected float seatRot;
    protected Vec3 seatOffset = Vec3.ZERO;
    protected Vec3 dismountOffset;
    protected Vec3 ownerViewOffset = null;
    protected Vec3 pivotOffset = Vec3.ZERO;
    protected ResourceLocation displayId;
    protected Vec3 displayOffset;
    protected PartUnit<?> parentPartUnit;
    protected List<PartUnit<?>> subPartUnits = new ArrayList<>();
    protected PartUnit<?> basePartUnit;
    protected List<PartUnit<?>> attPartUnits = new ArrayList<>();
    protected T data;
    protected PartUnitSyncData syncData;

    public PartUnit(int index, AbstractVehicle vehicle, T data) {
        this.index = index;
        this.id = data.getId();
        this.name = Component.translatable(data.getName());
        this.vehicle = vehicle;
        this.data = data;
        this.health = data.getMaxHealth();
        this.maxHealth = data.getMaxHealth();
        this.defenseStats = data.getDefenseStats();
        this.renderBoneName = data.getRenderBone();
        this.isSeat = data.isSeat();
        this.seatRot = data.getSeatRot();
        this.seatOffset = data.getSeatOffset();
        this.dismountOffset = data.getDismountOffset();
        this.ownerViewOffset = data.getOwnerViewOffset();
        this.pivotOffset = data.getPivotOffset();
        this.displayId = data.getDisplayId();
        this.displayOffset = data.getDisplayOffset();
        this.syncData = new PartUnitSyncData(this);
        this.syncData.define(SyncDataSerializers.BOOLEAN, this::setDetached, this::isDetached, this.detached);
        this.syncData.define(SyncDataSerializers.FLOAT, this::setHealth, this::getHealth, this.health);
        this.syncData.define(SyncDataSerializers.VEC3, this::setSeatOffset, this::getSeatOffset, Vec3.ZERO);
    }

    public void buildStructure(Map<VehicleCubeGroup, VehicleCubeGroup> vehicleCubeGroupCopy) {
        this.partCubeOBBs = data.getRawPartCubeOBBs().stream().map(VehicleCubeOBB::new).collect(Collectors.toList());
        this.partCubeOBBs.forEach(cubeOBB -> cubeOBB.group = vehicleCubeGroupCopy.get(cubeOBB.group));
        this.structureGroup = vehicleCubeGroupCopy.get(data.getRawStructureGroup());
    }

    /** Per part, after all parts of the vehicle exist; vehicle not yet attached to parts or added to the world. */
    public void combineAndInit(@UnmodifiableView Map<String, PartUnit<?>> partUnitsView, AbstractVehicle vehicle) {
        for (String subPartUnitId : data.getSubPartUnitIds()) {
            PartUnit<?> subPartUnit = partUnitsView.get(subPartUnitId);
            if (subPartUnit != null) {
                addSubPartUnit(subPartUnit);
                subPartUnit.setParentPartUnit(this);
            }
        }
        for (PartUnit<?> partUnit : partUnitsView.values()) {
            if (structureGroup != null && structureGroup.children.contains(partUnit.structureGroup)) {
                attPartUnits.add(partUnit);
                partUnit.basePartUnit = this;
            }
        }
    }

    public void onRemoved() {}

    @OnlyIn(Dist.CLIENT)
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick) {}

    public void tick() {
        if (!this.getVehicle().level().isClientSide()) {
            syncData.tick();
        } else {
            tickParticle();
        }
    }

    @OnlyIn(Dist.CLIENT)
    protected void tickParticle() {}

    public void afterVehicleRot(float dXRot, float dYRot, float dZRot) {}

    public boolean onInteract(Player player, InteractionHand hand) {
        return true;
    }

    public void hurt(DamageSource damageSource, float amount) {
        setHealth(Math.max(0, health - amount));
    }

    public PartUnitSyncData getSyncData() {
        return syncData;
    }

    public List<VehicleCubeOBB> getPartCubeOBBs() {
        return partCubeOBBs;
    }

    public List<OBB> getOBBs() {
        return partCubeOBBs.stream().map(VehicleCubeOBB::obb).toList();
    }

    public VehicleCubeOBB getLargestCube() {
        VehicleCubeOBB largestCube = null;
        double largestVolume = 0;
        for (VehicleCubeOBB cubeOBB : getPartCubeOBBs()) {
            double volume = cubeOBB.volume();
            if (volume > largestVolume) {
                largestVolume = volume;
                largestCube = cubeOBB;
            }
        }
        return largestCube;
    }

    public Vec3 worldPivotPosition() {
        return worldPositionWithBaseRot(pivotOffset);
    }

    public Vec3 worldSeatPosition() {
        float eyeHeight = getOwner() == null ? 2 : owner.getEyeHeight();
        return worldPositionWithSelfRot(new Vec3(seatOffset.x, seatOffset.y  - eyeHeight, seatOffset.z));
    }

    /** {@code sensor}: weapon part this seat looks through (spotter); null => none. */
    @Nullable
    public WeaponUnit sensorUnit() {
        String sensor = data.getSensor();
        return sensor == null ? null : (WeaponUnit) vehicle.getPartUnit(sensor).orElseThrow();
    }

    /** Another occupant spots through this sight: their slew owns it. */
    public boolean spottedBy(@Nullable LivingEntity except) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part != this && part.getOwner() != null && part.getOwner() != except && part.sensorUnit() == this) {
                return true;
            }
        }
        return false;
    }

    public Vec3 worldDismountPosition() {
        if (dismountOffset == null) {
            return null;
        }
        return worldPosition(dismountOffset);
    }

    /** Remote camera lens (UAV feed); server safe. */
    public Vec3 worldCameraPosition() {
        return worldOwnerViewPosition(1.0F);
    }

    public Vec3 worldOwnerViewPosition(float partialTick) {
        float eyeHeight = getOwner() == null ? 2 : owner.getEyeHeight();
        if (ownerViewOffset == null) {
            return worldPosition(new Vec3(0, eyeHeight, 0), partialTick);
        }
        return worldPosition(ownerViewOffset, partialTick);
    }

    /**
     * {@code offsetFromVehicle}: pivot-relative offset in the unrotated frame; rotated through hull/part/attachment
     * to world space.
     */
    public Vec3 worldPosition(Vec3 offsetFromVehicle) {
        return worldPosition(offsetFromVehicle, 1.0F);
    }

    public Vec3 worldPositionWithBaseRot(Vec3 offsetFromVehicle) {
        return worldPositionWithBaseRot(offsetFromVehicle, 1.0F);
    }

    public Vec3 worldPositionWithSelfRot(Vec3 offsetFromVehicle) {
        return worldPositionWithSelfRot(offsetFromVehicle, 1.0F);
    }

    public Vec3 worldPosition(Vec3 offsetFromVehicle, float partialTick) {
        if (offsetFromVehicle == null) {
            return vehicle.position(partialTick);
        }
        return worldPositionWithSelfRot(offsetFromVehicle, partialTick);
    }

    public Vec3 worldPositionWithBaseRot(Vec3 offsetFromVehicle, float partialTick) {
        if (structureGroup == null || structureGroup.parent == null) {
            return worldPositionWithVehicleRot(offsetFromVehicle, partialTick);
        }
        return worldPositionWithGroupRot(offsetFromVehicle, structureGroup.parent, partialTick);
    }

    public Vec3 worldPositionWithSelfRot(Vec3 offsetFromVehicle, float partialTick) {
        if (structureGroup == null) {
            return worldPositionWithVehicleRot(offsetFromVehicle, partialTick);
        }
        return worldPositionWithGroupRot(offsetFromVehicle, structureGroup, partialTick);
    }

    public Vec3 worldPositionWithGroupRot(Vec3 offsetFromVehicle, VehicleCubeGroup group, float partialTick) {
        Vec3 rotatedOffset = group.globalTransform(offsetFromVehicle.subtract(group.pivotOffset), true,
                        currentGroup -> getViewGroupRotation(currentGroup, partialTick),
                        currentGroup -> currentGroup.offsetO.lerp(currentGroup.offset, partialTick))
                .offset()
                .subtract(vehicle.centerOffset);
        Vector3f worldOffset = vehicle.rotYXZ(partialTick).transform(rotatedOffset.toVector3f());
        return vehicle.position(partialTick).add(vehicle.centerOffset)
                .add(worldOffset.x, worldOffset.y, worldOffset.z);
    }

    private Vec3 worldPositionWithVehicleRot(Vec3 offsetFromVehicle, float partialTick) {
        Vector3f worldOffset = vehicle.rotYXZ(partialTick)
                .transform(offsetFromVehicle.subtract(vehicle.centerOffset).toVector3f());
        return vehicle.position(partialTick).add(vehicle.centerOffset)
                .add(worldOffset.x, worldOffset.y, worldOffset.z);
    }

    protected Quaternionf getViewGroupRotation(VehicleCubeGroup group, float partialTick) {
        if (partialTick != 1.0F && group.rotationO != null) {
            return new Quaternionf(group.rotationO).slerp(group.rotation, partialTick);
        }
        return new Quaternionf(group.rotation);
    }

    public Component getName() {
        return name;
    }

    public float getHealth() {
        return health;
    }

    /** kg carried on top of curb weight: fitted hardware, payload. */
    public float mass() {
        return 0;
    }

    public void setHealth(float health) {
        this.health = health;
    }

    public float getMaxHealth() {
        return maxHealth;
    }

    public void setMaxHealth(float maxHealth) {
        this.maxHealth = maxHealth;
    }

    public DefenseStats getDefenseStats() {
        return defenseStats;
    }

    public String getRenderBoneName() {
        return renderBoneName;
    }

    public void setRenderBoneName(String renderBoneName) {
        this.renderBoneName = renderBoneName;
    }

    public int getIndex() {
        return index;
    }

    @NotNull
    public AbstractVehicle getVehicle() {
        return vehicle;
    }

    public VehicleCubeGroup getStructureGroup() {
        return structureGroup;
    }

    public boolean isSeat() {
        return isSeat;
    }

    public LivingEntity getOwner() {
        if (this.owner == null && this.ownerId != -1) {
            if (this.vehicle.level().getEntity(this.ownerId) instanceof LivingEntity livingEntity) {
                this.owner = livingEntity;
            }
        }
        return this.owner;
    }

    public void setOwner(LivingEntity owner) {
        if (owner == null) {
            this.owner = null;
            this.ownerId = -1;
        } else {
            this.owner = owner;
            this.ownerId = owner.getId();
        }
    }

    public void setOwnerId(int ownerId) {
        this.ownerId = ownerId;
    }

    public Vec3 getOwnerViewOffset() {
        return ownerViewOffset;
    }

    public Vec3 getPivotOffset() {
        return pivotOffset;
    }

    public Vec3 getOffset() {
        return structureGroup == null ? Vec3.ZERO : structureGroup.offset;
    }

    public void setOffset(Vec3 offset) {
        if (structureGroup != null) {
            structureGroup.offset = offset;
        }
    }

    public void setPivotOffset(Vec3 pivotOffset) {
        this.pivotOffset = pivotOffset;
    }

    public ResourceLocation getDisplayId() {
        return displayId;
    }

    public Vec3 getDisplayOffset() {
        return displayOffset;
    }

    public void setOwnerViewOffset(Vec3 ownerViewOffset) {
        this.ownerViewOffset = ownerViewOffset;
    }

    public float getSeatRot() {
        return vehicle.getYRot() + seatRot;
    }

    public void applySeatRot(LivingEntity passenger) {
        float takeSeatRot = getSeatRot();
        passenger.setYRot(takeSeatRot);
        passenger.setYBodyRot(takeSeatRot);
        passenger.setYHeadRot(takeSeatRot);
    }

    public Vec3 getSeatOffset() {
        return seatOffset;
    }

    public void setSeatOffset(Vec3 seatOffset) {
        this.seatOffset = seatOffset;
    }

    public Vec3 getDismountOffset() {
        return dismountOffset;
    }

    public void setDismountOffset(Vec3 dismountOffset) {
        this.dismountOffset = dismountOffset;
    }

    public void setParentPartUnit(PartUnit<?> parentPartUnit) {
        this.parentPartUnit = parentPartUnit;
    }

    public void addSubPartUnit(PartUnit<?> partUnit) {
        this.subPartUnits.add(partUnit);
    }

    public List<PartUnit<?>> getSubPartUnits() {
        return subPartUnits;
    }

    public PartUnit<?> getBasePartUnit() {
        return basePartUnit;
    }

    public List<PartUnit<?>> getAttPartUnits() {
        return attPartUnits;
    }

    @OnlyIn(Dist.CLIENT)
    public void onUpdateReceived(List<SyncDataEntry<?>> entries) {
        syncData.onUpdateReceived(entries);
    }

    public void onClientMessageReceived(ClientVehicleAction message, Player player) {
        // Aim and fire name any part by index: crew on => only that part's own gunner, or aim from its spotter seat.
        PartUnit<?> target = vehicle.getPartUnits().get(message.partUnitIndex);
        PartUnit<?> own = vehicle.getOwnOperatorUnit(player);
        boolean spotting = !message.shoot && own != null && own.sensorUnit() == target;
        if (CrewRules.enabled() && !spotting && (target.getOwner() != player || !CrewRules.has(vehicle, player, CrewRole.GUNNER))) {
            return;
        }
        if (message.shoot) {
            vehicle.shoot(message.partUnitIndex, message.weaponIndex, message.aimContexts, player);
        } else {
            PartUnit<?> partUnit = vehicle.getPartUnits().get(message.partUnitIndex);
            if (partUnit instanceof RotatableUnit<?> rotatableUnit) {
                rotatableUnit.setXAimRot(message.xAimRot);
                rotatableUnit.setYAimRot(message.yAimRot);
                if (rotatableUnit instanceof WeaponUnit weaponUnit) {
                    weaponUnit.updateWorldAimVec();
                }
            }
        }
    }

    public T getData() {
        return data;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Detached", detached);
        tag.putFloat("Health", health);
        return tag;
    }

    @Override
    public void deserializeNBT(@org.jetbrains.annotations.NotNull HolderLookup.Provider provider, @org.jetbrains.annotations.NotNull CompoundTag nbt) {
        if (nbt.contains("Detached", Tag.TAG_ANY_NUMERIC)) {
            this.detached = nbt.getBoolean("Detached");
        }
        if (nbt.contains("Health", Tag.TAG_ANY_NUMERIC)) {
            this.health = nbt.getFloat("Health");
        }
    }

    public VehiclePart detach() {
        VehicleCubeOBB largestCube = getLargestCube();
        if (largestCube == null) {
            return null;
        }
        largestCube.update(vehicle);
        OBB worldObb = largestCube.obb();
        Vec3 cubeCenterWorld = new Vec3(worldObb.center().x, worldObb.center().y, worldObb.center().z);
        double halfHeight = largestCube.height / 2;
        VehiclePart vehiclePart = new VehiclePart(AllEntities.VEHICLE_PART.get(), vehicle.level());
        vehiclePart.initPart(this);
        if (this instanceof RotatableUnit<?> rotatableUnit) {
            Vec2 rot = rotatableUnit.worldRot();
            vehiclePart.setXRot(rot.x);
            vehiclePart.setYRot(rot.y);
            vehiclePart.setZRot(rotatableUnit.worldZRot());
        } else {
            vehiclePart.setXRot(vehicle.getXRot());
            vehiclePart.setYRot(vehicle.getYRot());
            vehiclePart.setZRot(vehicle.getZRot());
        }
        Vector3f bottomWorldOffset = vehiclePart.rotYXZ().transform(new Vector3f(0, (float) halfHeight, 0));
        Vec3 bottomWorld = cubeCenterWorld.subtract(bottomWorldOffset.x, bottomWorldOffset.y, bottomWorldOffset.z);
        vehiclePart.setPos(bottomWorld.x, bottomWorld.y, bottomWorld.z);
        setDetached(true);
        return vehiclePart;
    }

    @NotNull
    public String getId() {
        return id;
    }

    public boolean isDefensive() {
        return health > 0;
    }

    public boolean isDestroyed() {
        return health == 0;
    }

    public boolean isDetachable() {
        return data.isDetachable() && renderBoneName != null && !renderBoneName.isBlank() && structureGroup != null;
    }

    public boolean isDetached() {
        return detached || (basePartUnit != null && basePartUnit.isDetached());
    }

    public void setDetached(boolean detached) {
        this.detached = detached;
    }

}
