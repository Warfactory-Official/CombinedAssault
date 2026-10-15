package com.wf.combinedassault.vehicle.part;

import com.wf.combinedassault.audio.CueLoop;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.combinedassault.audio.VehicleSounds;
import com.wf.wflib.audio.WfAudio;
import net.minecraft.server.level.ServerLevel;
import java.util.List;
import com.wf.combinedassault.compat.wflib.Munitions;
import com.wf.wflib.kinetic.KineticPreset;

import com.wf.combinedassault.vehicle.crew.WeaponLoading;
import com.wf.wflib.drone.cam.CameraMode;
import com.mojang.math.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.airdefense.AirDefense;
import com.wf.combinedassault.api.custom.sync.SyncDataSerializers;
import com.wf.combinedassault.api.event.VehicleFireEvent;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.combinedassault.custom.sync.SyncDataHolder;
import com.wf.combinedassault.custom.weapon.VehicleWeaponIndex;
import com.wf.combinedassault.ballistics.component.ComponentDamage;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.network.message.ClientVehicleAction;
import com.wf.combinedassault.network.message.ServerVehicleFire;
import com.wf.combinedassault.network.message.ServerVehicleWarn;
import com.wf.combinedassault.util.CcipUtil;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.indirect.GunLaying;
import com.wf.combinedassault.indirect.IndirectFire;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.loadout.Loadouts;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.pojo.Bolt;
import com.wf.combinedassault.vehicle.pojo.WarnType;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;
import com.wf.combinedassault.vehicle.weapon.*;
import com.wf.combinedassault.vehicle.weapon.seeker.ElectroOptical;
import com.wf.combinedassault.vehicle.weapon.seeker.Infrared;
import com.wf.combinedassault.vehicle.weapon.seeker.Radar;

import java.util.*;

public class WeaponUnit extends RotatableUnit<WeaponUnitData> {

    /** No bolts declared: fire from the pitch pivot. */
    private static final List<Bolt> PIVOT_BOLT = List.of(new Bolt(Vec3.ZERO, 0, 0, 0));
    private int ammoCapacity;
    private int currentBoltIndex;
    private WeaponUnitData.FiringMode firingMode;
    private final int coldLaunchTimeTick;
    private final Vec3 coldLaunchVelocity;
    /** Camera offset from the pivot, ADS (scoped) view. */
    private final Vec3 opticalSightOffset;
    /** Camera offset from the pivot, operator view. */
    private final Vec3 operatorViewOffset;
    private boolean operatorOnWeaponUnit = true;
    public WeaponUnitData.OpticalSightType opticalSightType;
    private final boolean withStabilizer;
    private final boolean withFocusLocker;
    private final boolean withThermalImager;
    private final boolean withNightVision;
    private final boolean withIr;
    private final WeaponLoading loading;
    private final float zoomMin;
    private final float zoomMax;
    private float zoom;
    private WeaponUnit parentWeaponUnit;
    private final List<WeaponUnit> subWeaponUnits = new ArrayList<>();
    private final WeaponUnitData.FireControlSensorType fireControlSensorType;
    private Vec3 focusLockPos;
    private Entity lockedEntity;
    private int loseLockTick;
    private boolean parentWeaponUnitAim;
    private final List<RadarUnit> radarUnits = new ArrayList<>();
    private boolean seekerOn;
    private int lockCoolingTick;
    private Vec3 worldAimVec = Vec3.ZERO;
    private Vec3 worldVec = Vec3.ZERO;
    /** Third-person crosshair only. */
    public WeaponUnitData.CrosshairStyle crosshairStyle;
    private VehicleCubeGroup xTurnGroup;
    /** |aim pitch| beyond this => yaw undefined, held. */
    private static final float NEAR_VERTICAL = 85.0f;
    public float xBarrelSelfRot;
    public float yBarrelSelfRot;
    public final List<AbstractVehicleWeapon<?>> weapons = new ArrayList<>();
    public final List<AbstractVehicleWeapon<?>> secondaryWeapons = new ArrayList<>();
    public final List<AbstractVehicleWeapon<?>> fullSalvoWeapons = new ArrayList<>();
    public final List<AbstractVehicleWeapon<?>> independentWeapons = new ArrayList<>();
    public final List<AbstractVehicleWeapon<?>> indexedWeapons = new ArrayList<>();
    public final Map<AbstractVehicleWeapon<?>, WeaponBayUnit> weaponBayUnits = new HashMap<>();
    private int currentWeaponIndex = -1;
    public SyncDataHolder<Integer> currentWeaponIndexHolder;
    private int currentSecondaryWeaponIndex = -1;
    public SyncDataHolder<Integer> currentSecondaryIndexHolder;
    public boolean rotByAim = true;
    public Vec3 weaponHitPos;
    public Vec3 weaponHitPosO;
    public int ignoreRemoteRotTick;
    /** Client only. */
    private CueLoop irTrackAlarm;
    private final GunLaying indirectFire = new GunLaying();
    @Nullable
    private final AirDefense airDefense;
    /** Client: operator's standing fire mission. */
    @Nullable
    private com.wf.combinedassault.indirect.FireMission fireMission;

    public WeaponUnit(int index, AbstractVehicle vehicle, WeaponUnitData data) {
        super(index, vehicle, data);
        this.seatOffset = data.getSeatOffset();
        this.ammoCapacity = data.getAmmoCapacity();
        this.firingMode = data.getFiringMode();
        this.coldLaunchTimeTick = data.getColdLaunchTimeTick();
        this.coldLaunchVelocity = data.getColdLaunchVelocity();
        this.parentWeaponUnitAim = data.isParentWeaponUnitAim();
        this.opticalSightOffset = data.getOpticalSightOffset();
        this.operatorViewOffset = data.getOperatorViewOffset();
        this.operatorOnWeaponUnit = data.isOperatorOnWeaponUnit();
        this.fireControlSensorType = data.getFireControlSensorType();
        this.opticalSightType = data.getOpticalSightType();
        this.withStabilizer = data.withStabilizer();
        this.withFocusLocker = data.withFocusLocker();
        this.withThermalImager = data.withThermalImager();
        this.withNightVision = data.withNightVision();
        this.withIr = data.withIr();
        this.loading = data.getLoading();
        this.zoomMin = data.getZoomMin();
        this.zoomMax = data.getZoomMax();
        this.zoom = this.zoomMin;
        this.crosshairStyle = data.getCrosshairStyle();
        this.airDefense = data.getAirDefense() == null ? null : new AirDefense(this, data.getAirDefense());
        this.currentWeaponIndexHolder = this.getSyncData().define(
                SyncDataSerializers.INT,
                this::setCurrentWeaponIndex,
                this::getCurrentWeaponIndex,
                currentWeaponIndex
        );
        this.currentSecondaryIndexHolder = this.getSyncData().define(
                SyncDataSerializers.INT,
                this::setCurrentSecondaryWeaponIndex,
                this::getCurrentSecondaryWeaponIndex,
                currentSecondaryWeaponIndex
        );
        currentWeaponIndex = 0;
        currentSecondaryWeaponIndex = 0;
    }

    @Override
    public void buildStructure(Map<VehicleCubeGroup, VehicleCubeGroup> vehicleCubeGroupCopy) {
        super.buildStructure(vehicleCubeGroupCopy);
        this.xTurnGroup = vehicleCubeGroupCopy.get(data.getRawXTurnGroup());
        if (this.xTurnGroup != null) {
            Vector3f barrelSelfRot = new Vector3f();
            this.xTurnGroup.baseRotation.getEulerAnglesYXZ(barrelSelfRot);
            this.xBarrelSelfRot = (float) Math.toDegrees(barrelSelfRot.x);
            this.yBarrelSelfRot = (float) Math.toDegrees(-barrelSelfRot.y);
        }
    }

    public void switchWeapon(boolean secondary, boolean next, boolean modding) {
        if (!modding && !secondary && swapsMounts()) {
            return;
        }
        int size = secondary ? secondaryWeapons.size() : weapons.size();
        if (size < 2) {
            return;
        }
        int index = ((secondary ? currentSecondaryWeaponIndex : currentWeaponIndex) + (next ? 1 : size - 1)) % size;
        selectWeaponIndex(secondary, index, modding);
    }

    public void selectWeaponIndex(boolean secondary, int index, boolean modding) {
        if (secondary) {
            this.getCurrentSecondaryWeapon().ifPresent(AbstractVehicleWeapon::onSwitchFrom);
            setCurrentSecondaryWeaponIndex(index);
            this.getCurrentSecondaryWeapon().ifPresent(AbstractVehicleWeapon::onSwitchTo);
        } else {
            this.getCurrentWeapon().ifPresent(AbstractVehicleWeapon::onSwitchFrom);
            setCurrentWeaponIndex(index);
            this.getCurrentWeapon().ifPresent(AbstractVehicleWeapon::onSwitchTo);
        }
        SoundSpec cue = modding ? getCurrentWeapon().get().getData().sound("reload") : VehicleSounds.SELECT_WEAPON;
        if (cue != null) {
            VehicleSounds.at(vehicle, worldPivotPosition(), cue, AudioCategory.WEAPONS);
        }
        if (getOwner() instanceof Player player) {
            (secondary ? getCurrentSecondaryWeapon() : getCurrentWeapon()).ifPresent(weapon -> player.displayClientMessage(Component.translatable("tips.use_weapon", weapon.getDisplayName()), true));
        }
    }

    public void cycleMultiWeapon(boolean next) {
        this.getCurrentWeapon().ifPresent(weapon -> {
            if (weapon instanceof VehicleMultiWeapons multiWeapons) {
                multiWeapons.cycleSubWeapon(next);
                VehicleSounds.at(vehicle, worldPivotPosition(), VehicleSounds.SELECT_WEAPON, AudioCategory.WEAPONS);
                if (getOwner() instanceof Player player) {
                    player.displayClientMessage(Component.translatable("tips.use_weapon", multiWeapons.getDisplayName()), true);
                }
            }
        });
    }

    public void initWeapon(int index) {
        if (index < 0 || index >= weapons.size() || index == currentWeaponIndex) {
            return;
        }
        this.currentWeaponIndex = index;
    }

    @Override
    public void combineAndInit(Map<String, PartUnit<?>> partUnitsView, AbstractVehicle vehicle) {
        super.combineAndInit(partUnitsView, vehicle);
        int index = 0;
        WeaponUnit parentWeaponUnit = this;
        for (var weaponInfo : data.getWeapons()) {
            if (weaponInfo.ids != null && !weaponInfo.ids.isEmpty()) {
                WeaponUnit subWeaponUnit = null;
                if (weaponInfo.partUnitId != null && partUnitsView.get(weaponInfo.partUnitId) instanceof WeaponUnit weaponUnit) {
                    subWeaponUnit = weaponUnit;
                }
                List<AbstractVehicleWeapon<?>> multiWeapons = new ArrayList<>();
                for (ResourceLocation id : weaponInfo.ids) {
                    VehicleWeaponIndex<?, ?> subIndex = CommonAssetsManager.vehicleWeaponManager().getIndex(id).orElse(null);
                    if (subIndex != null) {
                        String subSaveId = weaponInfo.saveId + "_" + id.toString().replace(':', '_');
                        WeaponUnit weaponUnit = (subWeaponUnit != null && subWeaponUnit != parentWeaponUnit) ? subWeaponUnit : parentWeaponUnit;
                        AbstractVehicleWeapon<?> sub = subIndex.create(vehicle, weaponUnit, multiWeapons.size(), subSaveId);
                        sub.defineSyncData(this.getSyncData());
                        multiWeapons.add(sub);
                    }
                }
                if (!multiWeapons.isEmpty()) {
                    VehicleMultiWeapons multiWeapon;
                    if (subWeaponUnit != null && subWeaponUnit != parentWeaponUnit) {
                        if (subWeaponUnit.getParentWeaponUnit() == null) {
                            subWeaponUnit.setParentWeaponUnit(parentWeaponUnit);
                        }
                        parentWeaponUnit.addSubWeaponUnit(subWeaponUnit);
                        multiWeapon = new VehicleMultiWeapons(vehicle, subWeaponUnit, index, multiWeapons, weaponInfo.saveId);
                    } else {
                        multiWeapon = new VehicleMultiWeapons(vehicle, parentWeaponUnit, index, multiWeapons, weaponInfo.saveId);
                    }
                    multiWeapon.defineSyncData(this.getSyncData());
                    if (weaponInfo.secondary) {
                        secondaryWeapons.add(multiWeapon);
                    } else {
                        weapons.add(multiWeapon);
                    }
                    if (weaponInfo.fullSalvo) {
                        fullSalvoWeapons.add(multiWeapon);
                    }
                    indexedWeapons.add(multiWeapon);
                    if (weaponInfo.weaponBayUnitId != null
                            && partUnitsView.get(weaponInfo.weaponBayUnitId) instanceof WeaponBayUnit weaponBayUnit) {
                        weaponBayUnits.put(multiWeapon, weaponBayUnit);
                    }
                    index += 1;
                }
            }
            else if (weaponInfo.id == null && weaponInfo.partUnitId != null) {
                if (partUnitsView.get(weaponInfo.partUnitId) instanceof WeaponUnit agentWeaponUnit && agentWeaponUnit != parentWeaponUnit) {
                    if (agentWeaponUnit.getParentWeaponUnit() == null) {
                        agentWeaponUnit.setParentWeaponUnit(parentWeaponUnit);
                    }
                    parentWeaponUnit.addSubWeaponUnit(agentWeaponUnit);
                    if (agentWeaponUnit.data.getWeapons().isEmpty()) {
                        continue;
                    }
                    VehicleWeaponAgent weaponAgent = new VehicleWeaponAgent(vehicle, agentWeaponUnit, index);
                    if (weaponInfo.secondary) {
                        secondaryWeapons.add(weaponAgent);
                    } else {
                        weapons.add(weaponAgent);
                    }
                    if (weaponInfo.fullSalvo) {
                        fullSalvoWeapons.add(weaponAgent);
                    }
                    indexedWeapons.add(weaponAgent);
                    if (weaponInfo.weaponBayUnitId != null
                            && partUnitsView.get(weaponInfo.weaponBayUnitId) instanceof WeaponBayUnit weaponBayUnit) {
                        weaponBayUnits.put(weaponAgent, weaponBayUnit);
                    }
                    index += 1;
                }
            }
            else {
                VehicleWeaponIndex<?, ?> vehicleWeaponIndex = CommonAssetsManager.vehicleWeaponManager().getIndex(weaponInfo.id).orElse(null);
                if (vehicleWeaponIndex != null) {
                    AbstractVehicleWeapon<?> weapon;
                    if (weaponInfo.partUnitId != null
                            && partUnitsView.get(weaponInfo.partUnitId) instanceof WeaponUnit subWeaponUnit
                            && subWeaponUnit != parentWeaponUnit) {
                        if (subWeaponUnit.getParentWeaponUnit() == null) {
                            subWeaponUnit.setParentWeaponUnit(parentWeaponUnit);
                        }
                        parentWeaponUnit.addSubWeaponUnit(subWeaponUnit);
                        weapon = vehicleWeaponIndex.create(vehicle, subWeaponUnit, index, weaponInfo.saveId);
                    } else {
                        weapon = vehicleWeaponIndex.create(vehicle, parentWeaponUnit, index, weaponInfo.saveId);
                    }
                    weapon.defineSyncData(this.getSyncData());
                    weapon.setRoundBones(weaponInfo.roundBones);
                    if (vehicleWeaponIndex.data().independent) {
                        independentWeapons.add(weapon);
                    } else if (weaponInfo.secondary) {
                        secondaryWeapons.add(weapon);
                    } else {
                        weapons.add(weapon);
                    }
                    if (weaponInfo.fullSalvo) {
                        fullSalvoWeapons.add(weapon);
                    }
                    indexedWeapons.add(weapon);
                    if (weaponInfo.weaponBayUnitId != null
                            && partUnitsView.get(weaponInfo.weaponBayUnitId) instanceof WeaponBayUnit weaponBayUnit) {
                        weaponBayUnits.put(weapon, weaponBayUnit);
                    }
                    index += 1;
                }
            }
        }
        for (String subPartUnitId : data.getSubPartUnitIds()) {
            if (partUnitsView.get(subPartUnitId) instanceof RadarUnit radarUnit) {
                radarUnits.add(radarUnit);
                addSubPartUnit(radarUnit);
                radarUnit.setParentPartUnit(this);
            }
        }
        // Ringless sub-turret: no own structureGroup, pivots via the parent's xTurnGroup.
        for (PartUnit<?> partUnit : partUnitsView.values()) {
            if (partUnit instanceof WeaponUnit weaponUnit) {
                if (weaponUnit.structureGroup == null && weaponUnit.xTurnGroup != null) {
                    if (structureGroup != null && structureGroup.children.contains(weaponUnit.xTurnGroup)) {
                        attPartUnits.add(weaponUnit);
                        weaponUnit.basePartUnit = this;
                    }
                }
            }
        }
    }

    @Override
    public void tick() {
        if (vehicle.level().isClientSide()) {
            tickFireControl();
        } else {
            if (vehicle.tickCount % 2 == 0 && getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF && !radarUnits.isEmpty()) {
                RadarUnit mainRadarUnit = getMainRadarUnit();
                Entity radarLockedEntity = mainRadarUnit.getLockedEntity();
                if (radarLockedEntity != null) {
                    ServerVehicleWarn packet = new ServerVehicleWarn(vehicle.getId(), radarLockedEntity.getId(), WarnType.RADAR_LOCK, mainRadarUnit.getRadarType());
                    PacketDistributor.sendToPlayersTrackingEntity(radarLockedEntity, packet);
                }
            }
            if (airDefense != null) {
                airDefense.tick();
            }
        }
        super.tick();
        indexedWeapons.forEach(AbstractVehicleWeapon::tick);
    }

    @Override
    protected void tickRot() {
        super.tickRot();
        if (withStabilizer && getOwner() != null
                && !isDestroyed() && (!needPower || vehicle.hasPower())
                && VectorUtil.angleBetween(worldAimVec, worldVec) < Math.PI / 36) {
            Vec2 aimLocalRot = worldVecToLocalRot(worldAimVec);
            Vec2 currentLocalRot = worldVecToLocalRot(worldVec);
            float targetXRot = Mth.approachDegrees(currentLocalRot.x, aimLocalRot.x, xRotSpeed);
            float targetYRot = Mth.approachDegrees(currentLocalRot.y, aimLocalRot.y, yRotSpeed);
            targetXRot = Mth.clamp(targetXRot, xRotMin - xSelfRot, xRotMax - xSelfRot);
            targetYRot = clampYaw(targetYRot, yRotMin - ySelfRot, yRotMax - ySelfRot);
            setXAimRot(targetXRot);
            setYAimRot(targetYRot);
            setXRot(targetXRot);
            setYRot(targetYRot);
            float dXRot = xRot - xRotO;
            float dYRot = yRot - yRotO;
            if (Math.abs(dXRot) > 180) {
                xRotO += Math.signum(dXRot) * 360;
            }
            if (Math.abs(dYRot) > 180) {
                yRotO += Math.signum(dYRot) * 360;
            }
            updateRot();
        }
        worldVec = worldVec();
    }

    @Override
    protected void tickRemoteRot() {
        if (!vehicle.level().isClientSide()) {
            return;
        }
        if (ignoreRemoteRotTick > 0 && VectorUtil.angleBetween(worldVec(xRemoteAimRot, yRemoteAimRot), worldVec) < Math.PI / 36) {
            ignoreRemoteRotTick -= 1;
            return;
        } else {
            ignoreRemoteRotTick = 0;
        }
        if (getOwner() != LocalVehiclePlayer.instance.getPlayer() || !rotByAim) {
            xAimRot = xRemoteAimRot;
            yAimRot = yRemoteAimRot;
            updateWorldAimVec();
        }
    }

    public void afterVehicleRot(float dXRot, float dYRot, float dZRot) {
        if (!vehicle.level().isClientSide()) {
            return;
        }
        if (Math.abs(dXRot) > 0.01 || Math.abs(dYRot) > 0.01 || Math.abs(dZRot) > 0.01) {
            ignoreRemoteRotTick = 3;
        }
    }

    @Override
    public void updateRot() {
        if (structureGroup != null) {
            structureGroup.rotationO = new Quaternionf(structureGroup.rotation);
        }
        if (xTurnGroup != null && xTurnGroup != structureGroup) {
            xTurnGroup.rotationO = new Quaternionf(xTurnGroup.rotation);
        }
        poseRot();
    }

    @Override
    protected void poseRot() {
        if (structureGroup != null) {
            structureGroup.rotation = new Quaternionf(structureGroup.baseRotation).mul(Axis.YN.rotationDegrees(yRot));
            if (xTurnGroup == structureGroup) {
                structureGroup.rotation = structureGroup.rotation.mul(Axis.XP.rotationDegrees(xRot));
                return;
            }
        }
        if (xTurnGroup != null) {
            xTurnGroup.rotation = new Quaternionf(xTurnGroup.baseRotation).mul(Axis.XP.rotationDegrees(xRot));
        }
    }

    @Override
    protected Quaternionf getViewGroupRotation(VehicleCubeGroup group, float partialTick) {
        if (partialTick == 1.0F) {
            return super.getViewGroupRotation(group, 1.0F);
        }
        if (group == structureGroup) {
            Quaternionf rotation = new Quaternionf(group.baseRotation)
                    .mul(Axis.YN.rotationDegrees(getViewYRot(partialTick)));
            if (xTurnGroup == structureGroup) {
                rotation.mul(Axis.XP.rotationDegrees(getViewXRot(partialTick)));
            }
            return rotation;
        }
        if (group == xTurnGroup) {
            return new Quaternionf(group.baseRotation)
                    .mul(Axis.XP.rotationDegrees(getViewXRot(partialTick)));
        }
        return super.getViewGroupRotation(group, partialTick);
    }

    @OnlyIn(Dist.CLIENT)
    public void tickFireControl() {
        if (vehicle.getPassengers().isEmpty()) {
            return;
        }
        if (getOwner() != LocalVehiclePlayer.instance.getPlayer()) {
            lockedEntity = null;
            return;
        }
        if (indirectFire.isIndirect()) {
            IndirectFire.lay(this);
            com.wf.combinedassault.indirect.client.FireMissions.tick(this);
            return;
        }
        if (withFocusLocker && focusLockPos != null) {
            aim(focusLockPos);
            return;
        }
        VehicleMissile missileWeapon = null;
        Optional<AbstractVehicleWeapon<?>> weaponOptional = getCurrentWeapon();
        if (weaponOptional.isPresent()) {
            AbstractVehicleWeapon<?> weapon = weaponOptional.get();
            if (weapon instanceof VehicleMissile vehicleMissile) {
                missileWeapon = vehicleMissile;
            } else if (weapon instanceof VehicleMultiWeapons vehicleMultiWeapons
                    && vehicleMultiWeapons.getSelectedWeapon() instanceof VehicleMissile vehicleMissile) {
                missileWeapon = vehicleMissile;
            }
        }
        if (lockedEntity != null) {
            Vec3 center = lockedEntity.getBoundingBox().getCenter();
            aim(center);
            Entity checkEntity = null;
            switch (getFireControlSensorType()) {
                case IR -> checkEntity = Infrared.checkTarget(this, lockedEntity);
                case EO -> checkEntity = ElectroOptical.checkTarget(this, lockedEntity);
                case RF -> {
                    if (missileWeapon != null && missileWeapon.seekerMode() == com.wf.wflib.missile.SeekerMode.SEMI_ACTIVE_RADAR) {
                        checkEntity = getMainRadarUnit().getLockedEntity();
                    } else {
                        checkEntity = Radar.checkTarget(vehicle, getRadarDetectedEntities().stream()
                                .map(detectedObject -> detectedObject.entity).toList(), lockedEntity);
                    }
                }
                default -> {}
            }
            if (checkEntity != lockedEntity) {
                setLockedEntity(checkEntity);
            }
            // Same id, different object: radar can hand back a different local/remote twin of the entity.
            if (getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF && radarUnits != null && lockedEntity != null) {
                RadarUnit.DetectedObject detectedObject = null;
                for (RadarUnit radarUnit : radarUnits) {
                    detectedObject = radarUnit.getDetectedEntities().get(lockedEntity.getId());
                    if (detectedObject != null) {
                        break;
                    }
                }
                if (detectedObject != null && detectedObject.entity != lockedEntity) {
                    setLockedEntity(detectedObject.entity);
                }
            }
            if (lockedEntity != null) {
                if (!lockedEntity.isAlive()) {
                    if (loseLockTick > 20) {
                        setLockedEntity(null);
                    } else {
                        loseLockTick += 1;
                    }
                }
            }
        }
        else if (isSeekerOn() && lockedEntity == null) {
            lockCoolingTick += 1;
            if (lockCoolingTick > 20) {
                if (missileWeapon != null) {
                    Entity entity = null;
                    if (getFireControlSensorType() == WeaponUnitData.FireControlSensorType.IR) {
                        entity = Infrared.findTarget(missileWeapon.getWeaponUnit(), missileWeapon.seekerFov());
                    }
                    else if (getFireControlSensorType() == WeaponUnitData.FireControlSensorType.RF) {
                        if (missileWeapon.seekerMode() == com.wf.wflib.missile.SeekerMode.ACTIVE_RADAR) {
                            entity = Radar.findTarget(getMainRadarUnit(), missileWeapon.seekerFov(), this);
                        }
                        else if (missileWeapon.seekerMode() == com.wf.wflib.missile.SeekerMode.SEMI_ACTIVE_RADAR) {
                            entity = getMainRadarUnit().getLockedEntity();
                            if (entity != null) {
                                Vec3 vLock = entity.getBoundingBox().getCenter().subtract(worldPivotPosition());
                                Vec3 vAim = worldVec();
                                double degree = Math.toDegrees(VectorUtil.angleBetween(vLock, vAim));
                                if (degree > missileWeapon.seekerFov()) {
                                    entity = null;
                                }
                            }
                        }
                    }
                    if (entity != null) {
                        setLockedEntity(entity);
                    }
                }
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void tickHit() {
        weaponHitPosO = weaponHitPos;
        weaponHitPos = currentWeaponHitPosition();
    }

    @Override
    public boolean onInteract(Player player, InteractionHand hand) {
        if (!vehicle.level().isClientSide() && hand == InteractionHand.MAIN_HAND && isInteractive() && player.isShiftKeyDown()) {
            if (player.getItemInHand(hand).getItem() == AllItems.MODDING_TOOL.get() && !Loadouts.manages(vehicle, this)) {
                switchWeapon(false, true, true);
                AbstractVehicleWeapon<?> weapon = getCurrentWeapon().get();
                player.displayClientMessage(Component.translatable("tips.use_weapon", weapon.getDisplayName()), true);
            }
            return false;
        }
        return true;
    }

    @OnlyIn(Dist.CLIENT)
    public void fireControlLock() {
        if (!radarUnits.isEmpty()) {
            RadarUnit mainRadarUnit = getMainRadarUnit();
            if (mainRadarUnit.getLockedEntity() != null) {
                mainRadarUnit.setLockedEntity(null);
                return;
            }
        }
        if (lockedEntity != null) {
            setLockedEntity(null);
            return;
        }
        WeaponUnitData.FireControlSensorType sensorType = getFireControlSensorType();
        if (airDefense != null) {
            Vec3 lookAtPos = sightAimPos();
            if (lookAtPos != null) {
                setLockedEntity(airDefense.lockCandidate(lookAtPos));
            }
            return;
        }
        if (sensorType == WeaponUnitData.FireControlSensorType.EO) {
            Vec3 lookAtPos = sightAimPos();
            if (lookAtPos == null) {
                return;
            }
            Entity lockedEntity = ElectroOptical.findTarget(this, lookAtPos);
            setLockedEntity(lockedEntity);
            if (lockedEntity != null) {
                setFocusLockPos(null);
                return;
            }
        }
        if (sensorType == WeaponUnitData.FireControlSensorType.RF && !radarUnits.isEmpty()) {
            RadarUnit mainRadarUnit = getMainRadarUnit();
            Entity lockedEntity = Radar.findTarget(mainRadarUnit, mainRadarUnit.getScanSectorAngle(), this);
            if (lockedEntity != null) {
                mainRadarUnit.setLockedEntity(lockedEntity);
                return;
            }
        }
        if (withFocusLocker && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE) {
            if (focusLockPos == null) {
                setFocusLockPos(LocalVehiclePlayer.instance.scopeAimPos());
            } else {
                setFocusLockPos(null);
            }
        } else if (focusLockPos != null) {
            setFocusLockPos(null);
        }
    }

    /** Where the operator is looking; null in a view with no sight line. */
    @OnlyIn(Dist.CLIENT)
    @Nullable
    private Vec3 sightAimPos() {
        return switch (LocalVehiclePlayer.instance.viewType) {
            case THIRD_PERSON, OPERATOR -> LocalVehiclePlayer.instance.freeAimPos();
            case SCOPE -> LocalVehiclePlayer.instance.scopeAimPos();
            default -> null;
        };
    }

    @OnlyIn(Dist.CLIENT)
    public void toggleSeeker(Boolean on) {
        Optional<AbstractVehicleWeapon<?>> weaponOptional = getCurrentWeapon();
        if (weaponOptional.isPresent()) {
            AbstractVehicleWeapon<?> weapon = weaponOptional.get();
            if (weapon.withSeeker()) {
                if (weapon.getRemainAmmo() == 0) {
                    on = false;
                }
                if (on == null) {
                    seekerOn = !seekerOn;
                } else {
                    seekerOn = on;
                }
                lockCoolingTick = 0;
                if (lockedEntity != null) {
                    setLockedEntity(null);
                }
            }
        }
    }

    public void shoot(int weaponIndex, List<AimContext> aimContexts, @Nullable LivingEntity operator) {
        if (!Loadouts.fitted(vehicle, this) || ComponentDamage.state(this) == ComponentState.BROKEN
                || operator != null && vehicle.linkLost()) {
            // Wrecked well before the part is annihilated, which is when the stock gate fires.
            return;
        }
        if (weaponIndex < indexedWeapons.size()) {
            AbstractVehicleWeapon<?> weapon = indexedWeapons.get(weaponIndex);
            WeaponBayUnit weaponBayUnit = weaponBayUnits.get(weapon);
            if (weaponBayUnit != null && !weaponBayUnit.isOn()) {
                weaponBayUnit.setOn(true);
                return;
            }
            VehicleFireEvent.Pre __fireEvent = new VehicleFireEvent.Pre(vehicle, weapon, operator);
            NeoForge.EVENT_BUS.post(__fireEvent);
            if (__fireEvent.isCanceled()) {
                return;
            }
            if (weapon.shoot(aimContexts, operator)) {
                NeoForge.EVENT_BUS.post(new VehicleFireEvent.Post(vehicle, weapon, operator));
                int entityId = vehicle.getId();
                int operatorId = operator == null ? -1 : operator.getId();
                ServerVehicleFire packet = new ServerVehicleFire(entityId, operatorId, index, weapon.getIndex());
                PacketDistributor.sendToPlayersTrackingEntity(vehicle, packet);
                SoundSpec fire = weapon.getData().sound("fire");
                if (fire != null) {
                    WfAudio.at((ServerLevel) vehicle.level(), worldPivotPosition(), AudioCategory.WEAPONS,
                            WfAudio.trackers(vehicle), List.of(fire));
                }
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void toggleCurrentWeaponBay() {
        getCurrentWeapon().ifPresent(weapon -> {
            WeaponBayUnit weaponBayUnit = weaponBayUnits.get(weapon);
            if (weaponBayUnit != null) {
                ClientVehicleAction action = new ClientVehicleAction();
                action.vehicleEntityId = vehicle.getId();
                action.partUnitIndex = weaponBayUnit.getIndex();
                action.togglePartUnitState = true;
                PacketDistributor.sendToServer(action);
            }
        });
    }

    @OnlyIn(Dist.CLIENT)
    public void onClientFire() {
        Optional<AbstractVehicleWeapon<?>> weaponOptional = getCurrentWeapon();
        WeaponUnit rootParentWeaponUnit = getRootParentWeaponUnit();
        if (weaponOptional.isEmpty()) {
            if (rootParentWeaponUnit != null) {
                weaponOptional = rootParentWeaponUnit.getCurrentWeapon();
            }
        }
        if (weaponOptional.isPresent()) {
            if (weaponOptional.get() instanceof VehicleMissile vehicleMissile && vehicleMissile.homing()) {
                rootParentWeaponUnit.toggleSeeker(false);
            }
        }
    }

    public GunLaying indirectFire() {
        return indirectFire;
    }

    /** Null: no pack {@code air_defense}. */
    @Nullable
    public AirDefense airDefense() {
        return airDefense;
    }

    /** Null: direct fire only. */
    @Nullable
    public com.wf.combinedassault.indirect.IndirectFireSpec getIndirectFireSpec() {
        return data.getIndirectFire();
    }

    @Nullable
    public com.wf.combinedassault.indirect.FireMission fireMission() {
        return fireMission;
    }

    public void setFireMission(@Nullable com.wf.combinedassault.indirect.FireMission fireMission) {
        this.fireMission = fireMission;
    }

    /** The point a firing solution is measured from: where {@link #aimRot} takes its bearings. */
    public Vec3 aimPivot() {
        if (xTurnGroup == null) {
            return vehicle.position();
        }
        return vehicle.relativeRotPos(vehicle.position().add(xTurnGroup.globalTransform().offset()), false);
    }

    /** Lay the mount along a world direction rather than at a world point. */
    public void aimAlong(Vec3 direction, double distance) {
        aimAt(aimPivot().add(direction.normalize().scale(distance)));
    }

    public void aim(Vec3 worldPos) {
        // A gun laid to a figure is not aimed by looking at things; see IndirectFire.lay.
        if (indirectFire.isIndirect()) {
            return;
        }
        aimAt(worldPos);
    }

    private void aimAt(Vec3 worldPos) {
        if (rotByAim) {
            Vec2 rot = aimRot(worldPos);
            rot = new Vec2(rot.x - xBarrelSelfRot, rot.y - yBarrelSelfRot);
            if (xAimRot != rot.x || yAimRot != rot.y) {
                xAimRot = rot.x;
                yAimRot = rot.y;
                updateWorldAimVec();
                if (vehicle.level().isClientSide()) {
                    ClientVehicleAction control = new ClientVehicleAction();
                    control.vehicleEntityId = vehicle.getId();
                  control.partUnitIndex = index;
                  control.xAimRot = rot.x;
                  control.yAimRot = rot.y;
                  PacketDistributor.sendToServer(control);
              }
              xAimRot = rot.x;
              yAimRot = rot.y;
              updateWorldAimVec();
            }
        }
        subWeaponUnits.forEach(weaponUnit -> weaponUnit.aimAt(worldPos));
    }

    /**
     * Yaw about the traverse axis; pitch from the trunnion swung to that yaw. Trunnion at its current
     * position => origin moves with the turret => aim chases itself (forward trunnion, point over the hull).
     */
    @Override
    public Vec2 aimRot(Vec3 worldPosition) {
        if (xTurnGroup == null) {
            return new Vec2(0, 0);
        }
        Vec3 trunnion = worldGroupPivot(xTurnGroup);
        Vec3 axis = structureGroup == null ? trunnion : worldGroupPivot(structureGroup);
        Vec3 target = worldVecToLocalVec(worldPosition.subtract(axis));
        Vec2 about = VectorUtil.vecToRot(target);
        float yaw = Math.abs(about.x) > NEAR_VERTICAL || yRotSpeed == 0 ? yRot + yBarrelSelfRot : about.y;
        float swing = clampYaw(yaw - yBarrelSelfRot, yRotMin - ySelfRot, yRotMax - ySelfRot) - yRot;
        Vector3f arm = worldVecToLocalVec(trunnion.subtract(axis)).toVector3f();
        Axis.YN.rotationDegrees(swing).transform(arm);
        return new Vec2(VectorUtil.vecToRot(target.subtract(new Vec3(arm))).x, yaw);
    }

    public VehicleCubeGroup getXTurnGroup() {
        return xTurnGroup;
    }

    private Vec3 worldGroupPivot(VehicleCubeGroup group) {
        return vehicle.relativeRotPos(vehicle.position().add(group.globalTransform().offset()), false);
    }

    private Vec3 currentWeaponHitPosition() {
        Optional<AbstractVehicleWeapon<?>> vehicleWeaponOptional = getCurrentWeapon();
        if (vehicleWeaponOptional.isPresent()) {
            AbstractVehicleWeapon<?> vehicleWeapon = vehicleWeaponOptional.get();
            WeaponUnit currentWeaponUnit = vehicleWeapon.getWeaponUnit();
            if (currentWeaponUnit.isParentWeaponUnitAim()) {
                currentWeaponUnit = currentWeaponUnit.getRootParentWeaponUnit();
            }
            Vec3 aimHitPosition = null;
            if (getFireControlSensorType() == WeaponUnitData.FireControlSensorType.CCIP) {
                if (vehicleWeapon instanceof VehicleMultiWeapons vehicleMultiWeapons) {
                    vehicleWeapon = vehicleMultiWeapons.getSelectedWeapon();
                }
                List<Vec3> releasePositions = currentWeaponUnit.aimContexts().stream()
                        .map(context -> context.from)
                        .toList();
                double releaseX = releasePositions.stream().mapToDouble(pos -> pos.x).average().orElse(0);
                double releaseY = releasePositions.stream().mapToDouble(pos -> pos.y).average().orElse(0);
                double releaseZ = releasePositions.stream().mapToDouble(pos -> pos.z).average().orElse(0);
                Vec3 releasePos = new Vec3(releaseX, releaseY, releaseZ).add(vehicle.getDeltaMovement());
                // Pipper follows the breech round.
                KineticPreset round = Munitions.kinetic(vehicleWeapon.solutionRound());
                if (round != null) {
                    AimContext aimContext = currentWeaponUnit.aimContext();
                    Vec3 aimDir = VectorUtil.rotToVec(aimContext.direction.x, aimContext.direction.y).normalize();
                    aimHitPosition = CcipUtil.computeCcip(vehicle.level(), vehicle, releasePos,
                            aimDir.scale(round.muzzleSpeed()).add(vehicle.getDeltaMovement()), aimDir, round);
                }
            }
            if (aimHitPosition == null) {
                aimHitPosition = currentWeaponUnit.aimHitPosition();
            }
            return aimHitPosition;
        } else {
            return aimHitPosition();
        }
    }

    public Vec3 toAimPosition() {
        if (xTurnGroup == null) {
            return aimHitPosition();
        }
        VehicleCubeGroup.GlobalTransform globalTransform = aimGlobalTransform();
        List<Vec3> positions = getBolts().stream()
                .map(bolt -> aimContext(bolt, globalTransform).from)
                .toList();
        double x = positions.stream().mapToDouble(pos -> pos.x).average().orElse(0);
        double y = positions.stream().mapToDouble(pos -> pos.y).average().orElse(0);
        double z = positions.stream().mapToDouble(pos -> pos.z).average().orElse(0);
        AimContext aimContext = aimContext(getCurrentBolt(), globalTransform);
        Vec3 start = new Vec3(x, y, z);
        Vec3 end = start.add(VectorUtil.rotToVec(aimContext.direction.x, aimContext.direction.y).normalize().scale(1024));
        return VectorUtil.hitPosition(vehicle, start, end);
    }

    public Vec3 aimHitPosition() {
        List<Vec3> positions = aimContexts().stream().map(context -> context.from).toList();
        double x = positions.stream().mapToDouble(pos -> pos.x).average().orElse(0);
        double y = positions.stream().mapToDouble(pos -> pos.y).average().orElse(0);
        double z = positions.stream().mapToDouble(pos -> pos.z).average().orElse(0);
        AimContext aimContext = aimContext();
        Vec3 start = new Vec3(x, y, z);
        Vec3 end = start.add(VectorUtil.rotToVec(aimContext.direction.x, aimContext.direction.y).normalize().scale(1024));
        return VectorUtil.hitPosition(vehicle, start, end);
    }

    public List<AimContext> aimContexts() {
        List<AimContext> positions = new ArrayList<>();
        for (Bolt bolt : getBolts()) {
            positions.add(aimContext(bolt));
        }
        return positions;
    }

    public AimContext aimContext() {
        return aimContext(getCurrentBolt());
    }

    public AimContext aimContext(Bolt bolt) {
        if (xTurnGroup == null) {
            AimContext aimContext = new AimContext();
            aimContext.from = this.vehicle.position();
            aimContext.direction = new Vec2(this.vehicle.getXRot(), this.vehicle.getYRot());
            return aimContext;
        }
        return aimContext(bolt, xTurnGroup.globalTransform());
    }

    private AimContext aimContext(Bolt bolt, VehicleCubeGroup.GlobalTransform globalTransform) {
        AimContext aimContext = new AimContext();
        Quaternionf rotation = globalTransform.rotation();
        Vec3 boltPosition = worldBoltPosition(bolt, globalTransform);
        Vector3f worldRot = new Vector3f();
        vehicle.rotYXZ().mul(rotation).getEulerAnglesYXZ(worldRot);
        aimContext.direction = new Vec2((float) Math.toDegrees(worldRot.x) + bolt.xRot, (float) Math.toDegrees(-worldRot.y) + bolt.yRot);
        Vec3 direction = VectorUtil.rotToVec(aimContext.direction.x, aimContext.direction.y);
        aimContext.from = boltPosition.add(direction.scale(bolt.barrelLength));
        return aimContext;
    }

    public Vec3 worldCurrentBoltPosition() {
        Bolt bolt = getCurrentBolt();
        VehicleCubeGroup.GlobalTransform globalTransform = xTurnGroup.globalTransform();
        return worldBoltPosition(bolt, globalTransform);
    }

    public Vec3 worldBoltPosition(Bolt bolt, VehicleCubeGroup.GlobalTransform globalTransform) {
        Quaternionf rotation = globalTransform.rotation();
        Vector3f boltOffset = new Vector3f((float) bolt.offset.x, (float) bolt.offset.y, (float) bolt.offset.z);
        rotation.transform(boltOffset);
        Vec3 pivot = globalTransform.offset();
        Vector3f rotatedBoltOffset = vehicle.rotYXZ().transform(pivot
                .add(boltOffset.x, boltOffset.y, boltOffset.z).toVector3f()
                .sub(vehicle.centerOffset.toVector3f()));
        return vehicle.position().add(vehicle.centerOffset).add(rotatedBoltOffset.x, rotatedBoltOffset.y, rotatedBoltOffset.z);
    }

    @Override
    public Vec3 worldSeatPosition() {
        float eyeHeight = getOwner() == null ? 2 : owner.getEyeHeight();
        if (!operatorOnWeaponUnit) {
            return worldPositionWithBaseRot(new Vec3(seatOffset.x, seatOffset.y  - eyeHeight, seatOffset.z));
        }
        return worldPositionWithSelfRot(new Vec3(seatOffset.x, seatOffset.y  - eyeHeight, seatOffset.z));
    }

    @Override
    public Vec3 worldOwnerViewPosition(float partialTick) {
        if (operatorViewOffset == null) {
            float eyeHeight = owner == null ? 2 : owner.getEyeHeight();
            return worldPosition(pivotOffset.add(new Vec3(0, eyeHeight, 0)), partialTick);
        }
        Vec3 offsetFromVehicle = pivotOffset.add(operatorViewOffset);
        if (!operatorOnWeaponUnit && LocalVehiclePlayer.instance.viewType != LocalVehiclePlayer.ViewType.SCOPE) {
            return worldPositionWithBaseRot(offsetFromVehicle, partialTick);
        }
        if (getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR && xTurnGroup != null) {
            return worldPositionWithGroupRot(offsetFromVehicle, xTurnGroup, partialTick);
        }
        return worldPosition(offsetFromVehicle, partialTick);
    }

    /** Sight, else operator view, in the yaw frame: no client view state. */
    @Override
    public Vec3 worldCameraPosition() {
        Vec3 offset = opticalSightOffset != null ? opticalSightOffset : operatorViewOffset;
        return offset == null ? super.worldCameraPosition() : worldPosition(pivotOffset.add(offset), 1.0F);
    }

    public Vec3 worldOpticalSightPosition(float partialTick) {
        if (opticalSightOffset == null) {
            return worldOwnerViewPosition(partialTick);
        }
        Vec3 offsetFromVehicle = pivotOffset.add(opticalSightOffset);
        if (getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR && xTurnGroup != null) {
            return worldPositionWithGroupRot(offsetFromVehicle, xTurnGroup, partialTick);
        }
        return worldPosition(offsetFromVehicle, partialTick);
    }

    @Override
    public Quaternionf baseRot() {
        Quaternionf rotation = vehicle.rotYXZ();
        VehicleCubeGroup group = structureGroup;
        if (group == null) {
            group = xTurnGroup;
        }
        if (group != null) {
            rotation.mul(group.baseRotation);
            if (group.parent != null) {
                rotation.mul(group.parent.globalTransform().rotation());
            }
        }
        return rotation;
    }

    private VehicleCubeGroup.GlobalTransform aimGlobalTransform() {
        Quaternionf globalRotation;
        if (xTurnGroup == structureGroup) {
            globalRotation = new Quaternionf(xTurnGroup.baseRotation)
                    .mul(Axis.YN.rotationDegrees(yAimRot))
                    .mul(Axis.XP.rotationDegrees(xAimRot));
        } else {
            globalRotation = new Quaternionf(xTurnGroup.baseRotation)
                    .mul(Axis.XP.rotationDegrees(xAimRot));
        }
        Vector3f globalPivot = xTurnGroup.pivot.toVector3f();
        VehicleCubeGroup parentGroup = xTurnGroup.parent;
        while (parentGroup != null) {
            Quaternionf parentRotation = parentGroup == structureGroup
                    ? new Quaternionf(parentGroup.baseRotation).mul(Axis.YN.rotationDegrees(yAimRot))
                    : parentGroup.rotation;
            parentRotation.transform(globalPivot);
            globalPivot.add((float) parentGroup.pivot.x, (float) parentGroup.pivot.y, (float) parentGroup.pivot.z);
            globalRotation.premul(parentRotation);
            parentGroup = parentGroup.parent;
        }
        return new VehicleCubeGroup.GlobalTransform(new Vec3(globalPivot), globalRotation);
    }

    public void updateWorldAimVec() {
        worldAimVec = worldVec(xAimRot, yAimRot);
    }

    public int getAmmoCapacity() {
        return ammoCapacity;
    }

    public Bolt getCurrentBolt() {
        List<Bolt> bolts = getBolts();
        return bolts.get(currentBoltIndex % bolts.size());
    }

    /** The selected weapon's: a weapon-scoped mount brings its own. */
    public List<Bolt> getBolts() {
        List<Bolt> bolts = data.getBolts(currentWeaponId());
        return bolts != null ? bolts : PIVOT_BOLT;
    }

    /** Null while a loadout names this part and its hardware is off: mounts hidden, nothing fires. */
    @Nullable
    public ResourceLocation currentWeaponId() {
        if (!Loadouts.fitted(vehicle, this)) {
            return null;
        }
        return getCurrentWeapon().map(weapon -> weapon.getData().getWeaponId()).orElse(null);
    }

    /** Weapons swap mount models: chosen with the modding tool, never from the seat. */
    public boolean swapsMounts() {
        return data.getMounts().stream().anyMatch(mount -> mount.weapon() != null);
    }

    public WeaponUnitData.FiringMode getFiringMode() {
        return firingMode;
    }

    public int getColdLaunchTimeTick() {
        return coldLaunchTimeTick;
    }

    public Vec3 getColdLaunchVelocity() {
        return coldLaunchVelocity;
    }

    public void setFiringMode(WeaponUnitData.FiringMode firingMode) {
        this.firingMode = firingMode;
    }

    public void countFire(int times) {
        this.currentBoltIndex = (this.currentBoltIndex + times) % getBolts().size();
    }

    public WeaponUnitData.OpticalSightType getOpticalSightType() {
        return opticalSightType;
    }

    public boolean withStabilizer() {
        return withStabilizer;
    }

    public boolean withThermalImager() {
        return withThermalImager;
    }

    public float getZoomMax() {
        return zoomMax;
    }

    public WeaponLoading getLoading() {
        return loading;
    }

    /** CRT sight modes; OPTICAL always fitted. */
    public boolean fitted(CameraMode mode) {
        return switch (mode) {
            case OPTICAL -> true;
            case THERMAL -> withThermalImager;
            case INTENSIFIER -> withNightVision;
            case IR -> withIr;
        };
    }

    public CameraMode nextSightMode(CameraMode from) {
        CameraMode next = from;
        do {
            next = CameraMode.byOrdinal(next.ordinal() + 1);
        } while (!fitted(next));
        return next;
    }

    public boolean withFocusLocker() {
        return withFocusLocker;
    }

    public float getZoom() {
        return zoom;
    }

    public void switchZoom() {
        if (zoom == zoomMax) {
            zoom = zoomMin;
        } else {
            zoom = zoomMax;
        }
    }

    public Collection<RadarUnit.DetectedObject> getRadarDetectedEntities() {
        Set<RadarUnit.DetectedObject> radarDetectedEntities = new HashSet<>();
        for (RadarUnit radarUnit : radarUnits) {
            radarDetectedEntities.addAll(radarUnit.getDetectedEntities().values());
        }
        return radarDetectedEntities;
    }

    public Vec3 getFocusLockPos() {
        return focusLockPos;
    }

    public void setFocusLockPos(Vec3 focusLockPos) {
        this.focusLockPos = focusLockPos;
    }

    public Entity getLockedEntity() {
        return lockedEntity;
    }

    public void setLockedEntity(Entity lockedEntity) {
        this.lockedEntity = lockedEntity;
        loseLockTick = 0;
        if (vehicle.level().isClientSide()) {
            if (irTrackAlarm == null) {
                irTrackAlarm = new CueLoop(VehicleSounds.IR_TRACK_ALARM);
            }
            irTrackAlarm.set(lockedEntity != null
                    && getFireControlSensorType() == WeaponUnitData.FireControlSensorType.IR);
            ClientVehicleAction action = new ClientVehicleAction();
            action.vehicleEntityId = vehicle.getId();
            action.lockEntity = true;
            action.lockedEntityId = lockedEntity == null ? -1 : lockedEntity.getId();
            PacketDistributor.sendToServer(action);
        }
    }

    @Override
    public LivingEntity getOwner() {
        if (parentWeaponUnit != null) {
            return parentWeaponUnit.getOwner();
        }
        return super.getOwner();
    }

    public boolean isOperatorOnWeaponUnit() {
        return operatorOnWeaponUnit;
    }

    public void setOperatorOnWeaponUnit(boolean operatorOnWeaponUnit) {
        this.operatorOnWeaponUnit = operatorOnWeaponUnit;
    }

    public WeaponUnit getParentWeaponUnit() {
        return parentWeaponUnit;
    }

    public WeaponUnit getRootParentWeaponUnit() {
        WeaponUnit rootParentWeaponUnit = parentWeaponUnit;
        if (rootParentWeaponUnit == null) {
            return this;
        }
        while (rootParentWeaponUnit.getParentWeaponUnit() != null) {
            rootParentWeaponUnit = rootParentWeaponUnit.getParentWeaponUnit();
        }
        return rootParentWeaponUnit;
    }

    public void setParentWeaponUnit(WeaponUnit parentWeaponUnit) {
        this.parentWeaponUnit = parentWeaponUnit;
    }

    public void addSubWeaponUnit(WeaponUnit subWeaponUnit) {
        this.subWeaponUnits.add(subWeaponUnit);
    }

    public List<WeaponUnit> getSubWeaponUnits() {
        return subWeaponUnits;
    }

    public WeaponUnitData.FireControlSensorType getFireControlSensorType() {
        AbstractVehicleWeapon<?> current = currentWeapon();
        if (current != null && current.getWeaponUnit() != this) {
            return current.getWeaponUnit().getFireControlSensorType();
        }
        return fireControlSensorType;
    }

    public boolean isInteractive() {
        return parentWeaponUnit != null || swapsMounts();
    }

    public boolean isParentWeaponUnitAim() {
        return parentWeaponUnitAim;
    }

    public void setParentWeaponUnitAim(boolean parentWeaponUnitAim) {
        this.parentWeaponUnitAim = parentWeaponUnitAim;
    }

    public List<RadarUnit> getRadarUnits() {
        return radarUnits;
    }

    public RadarUnit getMainRadarUnit() {
        if (radarUnits.isEmpty()) {
            return null;
        }
        return radarUnits.get(0);
    }

    public boolean isSeekerOn() {
        return seekerOn;
    }

    public float getSeekerFov() {
        Optional<AbstractVehicleWeapon<?>> weaponOptional = getCurrentWeapon();
        if (weaponOptional.isPresent() && weaponOptional.get() instanceof VehicleMissile missile) {
            return missile.seekerFov();
        }
        return 30;
    }

    public int getLockCoolingTick() {
        return lockCoolingTick;
    }

    public AbstractVehicleWeapon<?> proxyWeapon(AbstractVehicleWeapon<?> weapon) {
        while (weapon instanceof VehicleWeaponAgent weaponAgent) {
            AbstractVehicleWeapon<?> current = weaponAgent.getWeaponUnit().currentWeapon();
            if (current == null) {
                break;
            }
            weapon = current;
        }
        return weapon;
    }

    public Optional<AbstractVehicleWeapon<?>> getCurrentWeapon() {
        return Optional.ofNullable(currentWeapon());
    }

    /** {@link #getCurrentWeapon()} without the Optional; per-frame callers. */
    @Nullable
    public AbstractVehicleWeapon<?> currentWeapon() {
        if (currentWeaponIndex >= 0 && currentWeaponIndex < weapons.size()) {
            return proxyWeapon(weapons.get(currentWeaponIndex));
        }
        return null;
    }

    public int getCurrentWeaponIndex() {
        return currentWeaponIndex;
    }

    public void setCurrentWeaponIndex(int index) {
        this.currentWeaponIndex = index;
        setLockedEntity(null);
    }

    public Optional<AbstractVehicleWeapon<?>> getCurrentSecondaryWeapon() {
        return Optional.ofNullable(currentSecondaryWeapon());
    }

    @Nullable
    public AbstractVehicleWeapon<?> currentSecondaryWeapon() {
        if (currentSecondaryWeaponIndex >= 0 && currentSecondaryWeaponIndex < secondaryWeapons.size()) {
            return secondaryWeapons.get(currentSecondaryWeaponIndex);
        }
        return null;
    }

    public int getCurrentSecondaryWeaponIndex() {
        return currentSecondaryWeaponIndex;
    }

    public void setCurrentSecondaryWeaponIndex(int currentSecondaryWeaponIndex) {
        this.currentSecondaryWeaponIndex = currentSecondaryWeaponIndex;
        setLockedEntity(null);
    }

    public List<AbstractVehicleWeapon<?>> getIndexedWeapons() {
        return indexedWeapons;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = super.serializeNBT(provider);
        tag.putInt("CurrentWeaponIndex", currentWeaponIndex);
        CompoundTag weaponTag = new CompoundTag();
        this.indexedWeapons.forEach(weapon -> {
            String serializeId = weapon.getSerializeId();
            if (serializeId != null) {
                CompoundTag weaponData = weapon.serializeNBT(provider);
                if (weaponData.isEmpty()) {
                    return;
                }
                weaponTag.put(serializeId, weaponData);
            }
        });
        tag.put("WeaponTag", weaponTag);
        if (airDefense != null) {
            tag.put("AirDefense", airDefense.save());
        }
        return tag;
    }

    @Override
      public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
          super.deserializeNBT(provider, nbt);
          worldVec = worldVec();
          worldAimVec = worldVec;
          this.initWeapon(nbt.getInt("CurrentWeaponIndex"));
        CompoundTag weaponTag = nbt.getCompound("WeaponTag");
        this.indexedWeapons.forEach(weapon -> {
            if (weaponTag.contains(weapon.getSerializeId(), Tag.TAG_COMPOUND)) {
                String serializeId = weapon.getSerializeId();
                if (serializeId != null) {
                    weapon.deserializeNBT(provider, weaponTag.getCompound(serializeId));
                }
            }
        });
        if (airDefense != null && nbt.contains("AirDefense", Tag.TAG_COMPOUND)) {
            airDefense.load(nbt.getCompound("AirDefense"));
        }
    }

}
