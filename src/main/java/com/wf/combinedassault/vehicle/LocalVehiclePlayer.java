package com.wf.combinedassault.vehicle;

import com.wf.wflib.client.cam.DroneCamHud;
import com.wf.wflib.client.cam.ScreenSensor;
import com.wf.wflib.pda.client.PdaClient;
import com.wf.wflib.drone.cam.CameraMode;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.all.AllKeys;
import com.wf.combinedassault.api.entity.RemoteTickEntity;
import com.wf.combinedassault.client.screen.CoordinateInputScreen;
import com.wf.combinedassault.client.shader.OverloadHandler;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.combinedassault.fpv.FpvFlight;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.FixedWingVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.wflib.MissileEntity;
import com.wf.combinedassault.network.message.ClientVehicleAction;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.control.ControlUnit;
import com.wf.combinedassault.vehicle.control.InputHandler;
import com.wf.combinedassault.vehicle.light.IrSightMessage;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.ads.StationAds;
import com.wf.combinedassault.recoil.StationRecoil;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;

import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public class LocalVehiclePlayer {

    public static LocalVehiclePlayer instance;
    public static final float CAMERA_UPWARD_ANGLE = 10;
    /** Stick deflection per mouse turn unit (vanilla turn delta; x0.15 = deg). */
    private static final float STICK_GAIN = 0.01F;
    private static final float STICK_RECENTRE = 0.8F;
    private static final float HEAD_RECENTRE = 0.6F;
    private static final float HELI_PITCH = 30;
    private static final float HELI_HEADING_LEAD = 60;
    public float cameraAimRotX;
    public float cameraAimRotY;
    public float cameraAimRotZ;
    public float cameraAimRotXO;
    public float cameraAimRotYO;
    public float cameraAimRotZO;
    public float playerLerpSteps;
    public float playerLerpXRot;
    public float playerLerpYRot;
    public float playerLocalXRot;
    public float playerLocalYRot;
    /** FPV mouse turn since the last input sample, body frame, deg ({@link ControlUnit#turnX} axes). */
    public float fpvTurnX;
    public float fpvTurnY;
    /** Fixed-wing cockpit stick ({@link ControlUnit#stickX}/{@link ControlUnit#stickY}); recentres per tick. */
    public float stickX;
    public float stickY;
    /** Helicopter cockpit: commanded pitch / heading ({@link ControlUnit#xRot}/{@link ControlUnit#yRot}) the mouse moves. */
    public float heliPitch;
    public float heliHeading;
    private boolean wasCockpitPilot;
    public float currentG = 1;
    public float stamina = 100;
    public boolean lostControl;
    public int endureTick;
    public int unconsciousnessTick;
    public Vec3 lastVelocity = Vec3.ZERO;
    public double aimLocationDistance;
    public boolean outOfRangeFinding;
    public CameraMode sightMode = CameraMode.OPTICAL;
    private int irSightVehicle = -1;
    private boolean irSight;
    /** False right after scope entry: camera keeps the pre-scope aim direction until mouse travel clears the {@code 0.5} deg deadzone. */
    public boolean mouseTurnedAfterScope;
    /** Mouse travel since scope entry; per-event deltas at high fps / low sensitivity never reach the deadzone alone. */
    private double scopeTurnX;
    private double scopeTurnY;
    public AbstractVehicle.Seat seat;
    public boolean toLeave;
    public AbstractVehicle vehicle;
    public ViewType viewType = ViewType.THIRD_PERSON;
    /** {@link ScreenSensor} written by this view; other owners (wflib drone pilot) keep it otherwise. */
    private boolean sensing;
    public int onVehicleTickCount;
    public AbstractVehicle lookAtVehicle;
    public PartUnit<?> lookAtPartUnit;
    public ConcurrentHashMap<Integer, ServerEntity> serverEntities = new ConcurrentHashMap<>();
    /** Own guided rounds in flight (HUD "homing"), last tick seen. */
    public ConcurrentHashMap<MissileEntity, Integer> missiles = new ConcurrentHashMap<>();
    private static final double OWN_MISSILE_RANGE = 1024.0;
    static {
        instance = new LocalVehiclePlayer();
    }

    public enum ViewType {
        THIRD_PERSON, SCOPE, OPERATOR
    }

    public Player getPlayer() {
        return Minecraft.getInstance().player;
    }

    public boolean onVehicle() {
        return vehicle instanceof AbstractVehicle;
    }

    public static double renderDistance() {
        return Minecraft.getInstance().options.renderDistance().get() * 16;
    }

    public void tick() {
        tickLookAt();
        tickOverload();
        tickRemote();
        tickLerp();
        tickAim();
        checkState();
    }

    private void tickLookAt() {
        Player player = getPlayer();
        float rot = 0;
        if (onVehicle()) {
            rot = viewType != LocalVehiclePlayer.ViewType.SCOPE ? LocalVehiclePlayer.CAMERA_UPWARD_ANGLE : 0;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        float xRot = camera.getXRot() - rot;
        float yRot = camera.getYRot();
        Vec3 start = camera.getPosition();
        Vec3 end = start.add(VectorUtil
                .rotToVec(xRot, yRot)
                .normalize()
                .scale(LocalVehiclePlayer.renderDistance()));
        Pair<Entity, Vec3> hitResult = VectorUtil.hitObbPosition(player, start, end);
        if (hitResult != null) {
            Entity entity = hitResult.getLeft();
            if (entity instanceof AbstractVehicle vehicle && !vehicle.equals(player.getVehicle())) {
                lookAtVehicle = vehicle;
                lookAtPartUnit = VectorUtil.pickPartUnit(lookAtVehicle, start, end);
            }
        } else {
            lookAtVehicle = null;
            lookAtPartUnit = null;
        }
    }

    private void tickOverload() {
        if (!AllConfigs.common.overload.get()) {
            stamina = 100;
            lostControl = false;
            endureTick = 0;
            unconsciousnessTick = 0;
            currentG = 1;
            lastVelocity = Vec3.ZERO;
            if (OverloadHandler.isActive()) {
                OverloadHandler.setActive(false);
            }
            return;
        }
        if (stamina != 100) {
            stamina += stamina < 100 ? 1 : -1;
        }
        if (lostControl) {
            if (unconsciousnessTick > 0) {
                unconsciousnessTick -= 1;
                if (unconsciousnessTick <= 0) {
                    lostControl = false;
                }
            }
        }
        AbstractVehicle vehicle = this.vehicle;
        if (vehicle == null || vehicle.uav || vehicle.onGround()) {
            endureTick = 0;
            currentG = 1;
            lastVelocity = Vec3.ZERO;
            return;
        } else {
            if (!OverloadHandler.isActive()) {
                OverloadHandler.setActive(true);
            }
        }
        float gravity = PhysicsEngine.G * 400;
        Vec3 currentVelocity = vehicle.getDeltaMovement();
        Vec3 deltaV = currentVelocity.subtract(lastVelocity);
        Vec3 accelerationVec = deltaV.scale(400);
        Vec3 gravityVec = new Vec3(0, -gravity, 0);
        Vec3 apparentAcceleration = accelerationVec.subtract(gravityVec);
        Vec3 upDirection = new Vec3(vehicle.getMainCubeOBB().obb().getAxes()[1]).normalize();
        double verticalAcceleration = apparentAcceleration.dot(upDirection);
        currentG = (float) (verticalAcceleration / gravity);
        if (!lostControl) {
            if (currentG >= 2 || currentG <= -1) {
                endureTick += 1;
            } else if (endureTick > 0) {
                endureTick -= 1;
            }
            float endureG = currentG - 1;
            float overloadCapacityMultiplier = AllConfigs.common.overloadCapacityMultiplier.get().floatValue();
            float positiveGLimit = 100 - 100 * overloadCapacityMultiplier;
            float negativeGLimit = 100 + 60 * overloadCapacityMultiplier;
            stamina = Mth.clamp(stamina - endureG * 0.8f, positiveGLimit, negativeGLimit);
            if (stamina <= positiveGLimit || stamina >= negativeGLimit) {
                lostControl = true;
                unconsciousnessTick = 60;
            }
        }
        lastVelocity = currentVelocity;
    }

    private void tickRemote() {
        serverEntities.values().forEach(serverEntity -> {
            if (serverEntity.entity instanceof RemoteTickEntity entity) {
                entity.remoteTick();
            }
        });
    }

    private void tickLerp() {
        if (playerLerpSteps > 0) {
            Player player = getPlayer();
            float dXRot = (float) (Mth.wrapDegrees(playerLerpXRot - player.getXRot()) / Math.pow(playerLerpSteps, 0.2));
            float dYRot = (float) (Mth.wrapDegrees(playerLerpYRot - player.getYRot()) / Math.pow(playerLerpSteps, 0.2));
            player.xRotO += dXRot;
            player.yRotO += dYRot;
            player.setXRot(player.getXRot() + dXRot);
            player.setYRot(player.getYRot() + dYRot);
            playerLerpSteps -= 1;
        }
    }

    private void tickAim() {
        if (!onVehicle()) {
            return;
        }
        AbstractVehicle vehicle = this.vehicle;
        vehicle.getPartUnits().forEach(partUnit -> {
            if (partUnit instanceof WeaponUnit weaponUnit) {
                weaponUnit.tickHit();
            }
        });
        if (Minecraft.getInstance().screen instanceof CoordinateInputScreen) {
            return;
        }
        Player player = getPlayer();
        PartUnit<?> partUnit = vehicle.getOwnOperatorUnit(player);
        if (partUnit == null) {
            return;
        }
        StationRecoil.tick(this, partUnit);
        cameraAimRotXO = cameraAimRotX;
        cameraAimRotYO = cameraAimRotY;
        cameraAimRotZO = cameraAimRotZ;
        FpvFlight fpv = fpvFlight();
        if (fpv != null && viewType != ViewType.SCOPE) {
            try {
                fpvCamera(fpv, partUnit);
            } finally {
                fixLerp();
            }
        } else if (viewType == ViewType.THIRD_PERSON || viewType == ViewType.OPERATOR) {
            try {
                if (partUnit instanceof WeaponUnit weaponUnit) {
                    if ((!(InputHandler.freeCamera || playerLerpSteps > 0) || vehicle instanceof RotaryWingVehicle || vehicle.uav)
                            && !weaponUnit.spottedBy(player)) {
                        weaponUnit.aim(freeAimPos());
                    }
                    if (lensSight()) {
                        rangeFinding(freeAimPos());
                    }
                }
                if (cockpitLook()) {
                    Quaternionf vehicleRot = vehicle.rotYXZ();
                    if (aircraftCockpit()) {
                        // Head fixed in the airframe; free look released => recentres on the nose.
                        if (!InputHandler.freeCamera) {
                            playerLocalXRot *= HEAD_RECENTRE;
                            playerLocalYRot *= HEAD_RECENTRE;
                        }
                    } else {
                        Quaternionf playerWorldRot = new Quaternionf();
                        playerWorldRot.rotateY((float) Math.toRadians(-player.getYRot()));
                        playerWorldRot.rotateX((float) Math.toRadians(player.getXRot()));
                        Quaternionf invVehicleRot = new Quaternionf(vehicleRot).invert();
                        Quaternionf localRot = invVehicleRot.mul(playerWorldRot);
                        Vector3f localEuler = new Vector3f();
                        localRot.getEulerAnglesYXZ(localEuler);
                        playerLocalXRot = (float) Math.toDegrees(localEuler.x);
                        playerLocalYRot = (float) Math.toDegrees(-localEuler.y);
                    }
                    vehicleRot.rotateY((float) Math.toRadians(-playerLocalYRot));
                    vehicleRot.rotateX((float) Math.toRadians(playerLocalXRot));
                    Vector3f rot = new Vector3f();
                    vehicleRot.getEulerAnglesYXZ(rot);
                    cameraAimRotX = (float) Math.toDegrees(rot.x);
                    cameraAimRotY = (float) Math.toDegrees(-rot.y);
                    cameraAimRotZ = (float) Math.toDegrees(rot.z);
                    if (aircraftCockpit()) {
                        player.setXRot(cameraAimRotX);
                        player.setYRot(cameraAimRotY);
                    }
                } else {
                    cameraAimRotX = player.getXRot();
                    cameraAimRotY = player.getYRot();
                    cameraAimRotZ = 0;
                }
            } catch (Exception exception) {
                exception.printStackTrace();
            } finally {
                fixLerp();
            }
        } else if (viewType == ViewType.SCOPE) {
            if (sightUnit() instanceof WeaponUnit weaponUnit
                    && weaponUnit.getOpticalSightType() != WeaponUnitData.OpticalSightType.NONE) {
                try {
                    cameraAimWith(weaponUnit);
                    Vec3 hitPosition = weaponUnit.aimHitPosition();
                    rangeFinding(hitPosition);
                    if (weaponUnit.spottedBy(player)) {
                        return;
                    }
                    ClientVehicleAction control = new ClientVehicleAction();
                    control.vehicleEntityId = vehicle.getId();
                    control.partUnitIndex = weaponUnit.getIndex();
                    control.xAimRot = weaponUnit.getXAimRot();
                    control.yAimRot = weaponUnit.getYAimRot();
                    PacketDistributor.sendToServer(control);
                    weaponUnit.getSubWeaponUnits().forEach(subWeaponUnit -> subWeaponUnit.aim(hitPosition));
                } catch (Exception exception) {
                    exception.printStackTrace();
                } finally {
                    fixLerp();
                }
            } else {
                switchViewType(ViewType.OPERATOR);
            }
        }
    }

    /** Left vehicle => server clears the vacated seat; no message. */
    private void syncIrSight(AbstractVehicle vehicle, boolean on) {
        int id = vehicle == null ? -1 : vehicle.getId();
        if (id != irSightVehicle) {
            irSightVehicle = id;
            irSight = false;
        }
        if (vehicle != null && on != irSight) {
            irSight = on;
            PacketDistributor.sendToServer(new IrSightMessage(id, on));
        }
    }

    public void switchViewType(ViewType toViewType) {
        if (!onVehicle()) {
            return;
        }
        try {
            AbstractVehicle vehicle = this.vehicle;
            PartUnit<?> own = this.vehicle.getOwnOperatorUnit(getPlayer());
            PartUnit<?> partUnit = sightUnit() != null ? sightUnit() : own;
            if (lensUav() && (toViewType == null || toViewType == ViewType.SCOPE)) {
                // Lens = scope: SWITCH_SCOPE steps its zoom (InputHandler).
                toViewType = toViewType == null && viewType == ViewType.OPERATOR ? ViewType.THIRD_PERSON : ViewType.OPERATOR;
            }
            if (partUnit instanceof WeaponUnit weaponUnit) {
                if (toViewType == null) {
                    if (viewType == ViewType.THIRD_PERSON) {
                        if (weaponUnit.getOpticalSightType() == WeaponUnitData.OpticalSightType.NONE) {
                            toViewType = ViewType.OPERATOR;
                        } else {
                            toViewType = ViewType.SCOPE;
                        }
                    } else if (viewType == ViewType.SCOPE) {
                        toViewType = ViewType.OPERATOR;
                    } else if (viewType == ViewType.OPERATOR) {
                        toViewType = ThirdPersonRules.allows(vehicle) ? ViewType.THIRD_PERSON
                                : weaponUnit.getOpticalSightType() == WeaponUnitData.OpticalSightType.NONE
                                ? ViewType.OPERATOR : ViewType.SCOPE;
                    }
                }
                if (toViewType == ViewType.THIRD_PERSON && !ThirdPersonRules.allows(vehicle)) {
                    toViewType = ViewType.OPERATOR;
                }
                if (toViewType == ViewType.THIRD_PERSON || toViewType == ViewType.OPERATOR) {
                    thirdPersonCameraAimAt(weaponUnit.aimHitPosition(), vehicle);
                } else if (toViewType == ViewType.SCOPE) {
                    if (weaponUnit.getOpticalSightType() == WeaponUnitData.OpticalSightType.NONE) {
                        return;
                    }
                    scopeAimWeaponHit(weaponUnit);
                    cameraAimRotZ = weaponUnit.worldZRot();
                    mouseTurnedAfterScope = false;
                    scopeTurnX = 0;
                    scopeTurnY = 0;
                }
                cameraAimRotXO = cameraAimRotX;
                cameraAimRotYO = cameraAimRotY;
                cameraAimRotZO = cameraAimRotZ;
            } else {
                if (toViewType == null) {
                    if (viewType == ViewType.THIRD_PERSON) {
                        toViewType = ViewType.OPERATOR;
                    } else if (viewType == ViewType.OPERATOR) {
                        toViewType = ViewType.THIRD_PERSON;
                    }
                }
                if (toViewType == ViewType.THIRD_PERSON && !ThirdPersonRules.allows(vehicle)) {
                    toViewType = ViewType.OPERATOR;
                }
            }
            if (toViewType == ViewType.OPERATOR && viewType != ViewType.OPERATOR) {
                playerLocalXRot = 0;
                playerLocalYRot = 0;
            }
            viewType = toViewType;
        } catch (Exception exception) {
            CombinedAssault.LOGGER.error("Failed while switch view type", exception);
        }
    }

    public void toSeat(AbstractVehicle.Seat seat, AbstractVehicle vehicle) {
        boolean enter = this.seat == null && seat != null;
        if (seat == null) {
            AbstractVehicle left = this.vehicle;
            toLeave = false;
            clear();
            if (left != null && left.uav) {
                PdaClient.watch(left.getId());
            }
            return;
        }
        this.seat = seat;
        this.vehicle = vehicle;
        if (enter) {
            // First person on boarding; off a vehicle OPERATOR would steer the on-foot camera (FirstPersonHandler).
            viewType = ViewType.OPERATOR;
        }
        if (seat.partUnit instanceof WeaponUnit weaponUnit) {
            if (viewType == ViewType.SCOPE) {
                scopeAimWeaponHit(weaponUnit);
                cameraAimRotXO = cameraAimRotX;
                cameraAimRotYO = cameraAimRotY;
                cameraAimRotZO = cameraAimRotZ;
            } else {
                Vec3 position = enter ? weaponUnit.aimHitPosition() : weaponUnit.toAimPosition();
                Vec3 cameraAnchor = vehicle.thirdPersonPosition(1.0F);
                Vec3 direction = position.subtract(cameraAnchor);
                if (direction.lengthSqr() < 1.0E-8) {
                    return;
                }
                Vec2 targetRot = VectorUtil.vecToRot(direction);
                playerLerpXRot = Mth.wrapDegrees(targetRot.x + CAMERA_UPWARD_ANGLE);
                playerLerpYRot = Mth.wrapDegrees(targetRot.y);
                playerLerpSteps = 8;
            }
        } else {
            playerLerpXRot = getPlayer().getXRot();
            playerLerpYRot = seat.partUnit.getSeatRot();
            playerLerpSteps = 8;
        }
    }

    /** Acro model when this player flies an FPV-mode drone; null otherwise. */
    @Nullable
    public FpvFlight fpvFlight() {
        return onVehicle() && vehicle instanceof RotaryWingVehicle rotary && vehicle.controlUnit.getOperator() == getPlayer()
                ? rotary.fpvAttitude() : null;
    }

    /** OPERATOR: lens fixed to the body (+ uptilt). THIRD_PERSON: chase on the body's heading. */
    private void fpvCamera(FpvFlight fpv, PartUnit<?> partUnit) {
        Quaternionf body = vehicle.rotYXZ();
        if (viewType == ViewType.OPERATOR) {
            Vector3f euler = new Quaternionf(body).rotateX((float) Math.toRadians(-fpv.cameraTilt()))
                    .getEulerAnglesYXZ(new Vector3f());
            cameraAimRotX = (float) Math.toDegrees(euler.x);
            cameraAimRotY = cameraAimRotY + Mth.wrapDegrees((float) Math.toDegrees(-euler.y) - cameraAimRotY);
            cameraAimRotZ = (float) Math.toDegrees(euler.z);
        } else {
            Vector3f forward = body.transform(new Vector3f(0, 0, 1));
            if (forward.x * forward.x + forward.z * forward.z > 0.04f) {
                float heading = VectorUtil.vecToRot(new Vec3(forward.x, 0, forward.z)).y;
                cameraAimRotY = cameraAimRotY + Mth.wrapDegrees(heading - cameraAimRotY);
            }
            cameraAimRotX = CAMERA_UPWARD_ANGLE;
            cameraAimRotZ = 0;
        }
        Player player = getPlayer();
        player.setXRot(cameraAimRotX);
        player.setYRot(cameraAimRotY);
        if (partUnit instanceof WeaponUnit weaponUnit) {
            weaponUnit.aim(cameraAimHit(viewType == ViewType.OPERATOR ? 0 : CAMERA_UPWARD_ANGLE));
        }
    }

    /** UAV whose OPERATOR view is its camera lens (multirotors); fixed-wing UAVs get a nose cockpit + SCOPE pod. */
    public boolean lensUav() {
        return vehicle != null && vehicle.uav && !(vehicle instanceof FixedWingVehicle);
    }

    /** {@link #lensUav} OPERATOR through a sight: world-stabilised gimbal lens with the scope's zoom, modes, reticle, range. */
    public boolean lensSight() {
        return viewType == ViewType.OPERATOR && lensUav() && getWeaponUnit() instanceof WeaponUnit unit
                && unit.getOpticalSightType() != WeaponUnitData.OpticalSightType.NONE;
    }

    /** Looking through a sight: SCOPE, or {@link #lensSight}. */
    public boolean sighted() {
        return viewType == ViewType.SCOPE || lensSight();
    }

    /** OPERATOR look relative to the hull; {@link #lensUav} OPERATOR = lens camera, world-frame look like third person. */
    public boolean cockpitLook() {
        return viewType == ViewType.OPERATOR && !lensUav();
    }

    /** Aircraft OPERATOR: head fixed in the airframe, mouse turns it only in free look. */
    public boolean aircraftCockpit() {
        return cockpitLook() && (vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle && !vehicle.uav)
                && fpvFlight() == null;
    }

    /** {@link #aircraftCockpit} as the pilot: mouse = stick (fixed wing) or attitude command (helicopter) outside free look. */
    public boolean cockpitPilot() {
        return aircraftCockpit() && vehicle.controlUnit.getOperator() == getPlayer();
    }

    /** Client tick: seeds the commands from the airframe on taking the cockpit; stick recentres. */
    public void tickCockpitPilot() {
        boolean pilot = cockpitPilot();
        if (pilot && !wasCockpitPilot) {
            stickX = 0;
            stickY = 0;
            heliPitch = vehicle.getXRot();
            heliHeading = vehicle.getYRot();
        }
        wasCockpitPilot = pilot;
        stickX *= STICK_RECENTRE;
        stickY *= STICK_RECENTRE;
    }

    public boolean handlePlayerTurn(double pYRot, double pXRot) {
        if (pYRot == 0 && pXRot == 0) {
            return false;
        }
        Player player = getPlayer();
        if (!onVehicle()) {
            return false;
        }
        if (pYRot > 0.05 || pXRot > 0.05) {
            playerLerpSteps = 0;
        }
        if (viewType != ViewType.SCOPE && fpvFlight() != null) {
            fpvTurnX += (float) (pXRot * 0.15F);
            fpvTurnY += (float) (pYRot * 0.15F);
            return true;
        }
        if (cockpitPilot() && !InputHandler.freeCamera) {
            if (vehicle instanceof FixedWingVehicle) {
                stickX = Mth.clamp(stickX + (float) pYRot * STICK_GAIN, -1, 1);
                stickY = Mth.clamp(stickY + (float) pXRot * STICK_GAIN, -1, 1);
            } else {
                heliPitch = Mth.clamp(heliPitch + (float) pXRot * 0.15F, -HELI_PITCH, HELI_PITCH);
                heliHeading = vehicle.getYRot() + Mth.clamp(Mth.wrapDegrees(heliHeading + (float) pYRot * 0.15F - vehicle.getYRot()),
                        -HELI_HEADING_LEAD, HELI_HEADING_LEAD);
            }
            return true;
        }
        if (viewType == ViewType.THIRD_PERSON || viewType == ViewType.OPERATOR && !cockpitLook()) {
            if ((player.getXRot() > 85 && pXRot > 0) || (player.getXRot() < -85 && pXRot < 0)) {
                pYRot += Math.abs(pXRot) * (pYRot > 0 ? 1 : -1);
                pXRot = 0;
            }
        } else if (viewType == ViewType.OPERATOR) {
            if (playerLocalXRot >= 60 && pXRot > 0) {
                pXRot = 0;
            } else if (playerLocalXRot <= -60 && pXRot < 0) {
                pXRot = 0;
            }
        }
        AbstractVehicle vehicle = this.vehicle;
        if (sightUnit() instanceof WeaponUnit weaponUnit) {
            if (viewType == LocalVehiclePlayer.ViewType.SCOPE) {
                if (weaponUnit.spottedBy(player)) {
                    return true;
                }
                if (!mouseTurnedAfterScope) {
                    scopeTurnX += pXRot;
                    scopeTurnY += pYRot;
                }
                if (!mouseTurnedAfterScope && (Math.abs(scopeTurnX) >= 0.5 || Math.abs(scopeTurnY) >= 0.5)) {
                    weaponUnit.setXAimRot(weaponUnit.getXRot());
                    weaponUnit.setYAimRot(weaponUnit.getYRot());
                    mouseTurnedAfterScope = true;
                }
                if (!mouseTurnedAfterScope) {
                    return true;
                }
                if (weaponUnit.getFocusLockPos() != null) {
                    AimContext aimContext = weaponUnit.aimContext();
                    Vec3 start = weaponUnit.worldCurrentBoltPosition();
                    Vec3 end = start.add(VectorUtil
                            .rotToVec((float) (aimContext.direction.x + pXRot * 0.33f), (float) (aimContext.direction.y + pYRot * 0.33f))
                            .normalize()
                            .scale(1024));
                    weaponUnit.setFocusLockPos(VectorUtil.hitPosition(vehicle, start, end));
                    return true;
                }
                float turn = 0.15f / StationAds.turnDivisor(weaponUnit, Minecraft.getInstance().options.fov().get());
                pXRot *= turn;
                pYRot *= turn;
                float vx = scopeAimSensitivity(weaponUnit.getXRotSpeed(), weaponUnit.getXAimRot() - weaponUnit.getXRot());
                if ((pXRot > 0 && weaponUnit.getXAimRot() < weaponUnit.xRotMax - weaponUnit.xSelfRot) || (pXRot < 0 && weaponUnit.getXAimRot() > weaponUnit.xRotMin - weaponUnit.xSelfRot)) {
                    float rx = (float) (weaponUnit.getXAimRot() + pXRot * vx);
                    if (rx > 180) {
                        rx = -180 + (rx - 180);
                    } else if (rx < -180) {
                        rx = 180 + (rx + 180);
                    }
                    weaponUnit.setXAimRot(rx);
                }
                float vy = scopeAimSensitivity(weaponUnit.getYRotSpeed(), weaponUnit.getYAimRot() - weaponUnit.getYRot());
                if ((pYRot > 0 && weaponUnit.getYAimRot() < weaponUnit.yRotMax - weaponUnit.ySelfRot) || (pYRot < 0 && weaponUnit.getYAimRot() > weaponUnit.yRotMin - weaponUnit.ySelfRot)) {
                    float ry = (float) (weaponUnit.getYAimRot() + pYRot * vy);
                    if (ry > 180) {
                        ry = -180 + (ry - 180);
                    } else if (ry < -180) {
                        ry = 180 + (ry + 180);
                    }
                    weaponUnit.setYAimRot(ry);
                }
                weaponUnit.updateWorldAimVec();
                return true;
            }
        }
        float zoom = lensSight() ? getWeaponUnit().getZoom() : 1;
        float fX = (float) (pXRot * 0.15F / zoom);
        float fY = (float) (pYRot * 0.15F / zoom);
        Quaternionf rot;
        if (cockpitLook()) {
            playerLocalYRot = Mth.wrapDegrees(playerLocalYRot + fY);
            playerLocalXRot = Mth.wrapDegrees(playerLocalXRot + fX);
            rot = vehicle.rotYXZ();
            rot.rotateY((float) Math.toRadians(-playerLocalYRot));
            rot.rotateX((float) Math.toRadians(playerLocalXRot));
        } else {
            rot = new Quaternionf();
            rot.rotateY((float) Math.toRadians(-player.getYRot() - fY));
            rot.rotateX((float) Math.toRadians(player.getXRot() + fX));
        }
        Vector3f euler = new Vector3f();
        rot.getEulerAnglesYXZ(euler);
        float xRot = (float) Math.toDegrees(euler.x);
        float yRot = (float) Math.toDegrees(-euler.y);
        player.xRotO += xRot - player.getXRot();
        player.yRotO += yRot - player.getYRot();
        player.setXRot(xRot);
        player.setYRot(yRot);
        vehicle.onPassengerTurned(player);
        return true;
    }

    private float scopeAimSensitivity(float rotationSpeed, float rotationError) {
        if (rotationSpeed <= 0) {
            return 0;
        }
        float effectiveSpeed = Math.max(rotationSpeed, 1);
        float trackingTime = Mth.abs(Mth.wrapDegrees(rotationError)) / effectiveSpeed;
        return Math.min(effectiveSpeed / 16, effectiveSpeed / Math.max(trackingTime * 10, 1));
    }

    public void scopeAimWeaponHit(WeaponUnit weaponUnit) {
        Vec3 hitPosition = weaponUnit.aimHitPosition();
        Vec3 cameraPosition = weaponUnit.getOpticalSightType() != WeaponUnitData.OpticalSightType.OPERATOR
                ? weaponUnit.worldOpticalSightPosition(1.0F)
                : weaponUnit.worldOwnerViewPosition(1.0F);
        cameraAimAt(cameraPosition, hitPosition);
    }

    public void cameraAimAt(Vec3 cameraPosition, Vec3 worldPos) {
        if (cameraPosition != null && worldPos != null) {
            Vec2 rot = VectorUtil.vecToRot(worldPos.subtract(cameraPosition));
            cameraAimRotX = rot.x;
            cameraAimRotY = rot.y;
            getPlayer().setXRot(rot.x);
            getPlayer().setYRot(rot.y);
        }
    }

    public void thirdPersonCameraAimAt(Vec3 worldPos, AbstractVehicle vehicle) {
        Vec3 cameraAnchor = vehicle.thirdPersonPosition(1.0F);
        Vec3 direction = worldPos.subtract(cameraAnchor);
        if (direction.lengthSqr() < 1.0E-8) {
            return;
        }
        Vec2 targetRot = VectorUtil.vecToRot(direction);
        cameraAimRotX = Mth.wrapDegrees(targetRot.x + CAMERA_UPWARD_ANGLE);
        cameraAimRotY = Mth.wrapDegrees(targetRot.y);
        cameraAimRotZ = 0;
        getPlayer().setXRot(cameraAimRotX);
        getPlayer().setYRot(cameraAimRotY);
    }

    public Vec3 cameraAimHit(float xRot) {
        Vec3 start = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 direction = cameraDirection(xRot);
        Vec3 end = start.add(direction.scale(renderDistance()));
        return VectorUtil.hitPosition(getPlayer(), start, end);
    }

    public void cameraAimWith(WeaponUnit weaponUnit) {
        Vec2 rot = weaponUnit.worldRot(weaponUnit.getXRot() + weaponUnit.xBarrelSelfRot, weaponUnit.getYRot() + weaponUnit.yBarrelSelfRot);
        cameraAimRotX = rot.x;
        cameraAimRotY = rot.y;
        cameraAimRotZ = weaponUnit.worldZRot();
        getPlayer().setXRot(rot.x);
        getPlayer().setYRot(rot.y);
    }

    public Vec3 cameraDirection(float upward) {
        Quaternionf cameraRot = cameraRotation().rotateX((float) Math.toRadians(-upward));
        Vector3f direction = new Vector3f(0, 0, 1);
        cameraRot.transform(direction);
        return new Vec3(direction);
    }

    public Quaternionf cameraRotation() {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-cameraAimRotY))
                .rotateX((float) Math.toRadians(cameraAimRotX))
                .rotateZ((float) Math.toRadians(cameraAimRotZ));
    }

    /** Render camera; FPV lens per frame from the led attitude, else the ticked aim slerped. */
    public Quaternionf frameCameraRotation(float partialTick) {
        FpvFlight fpv = fpvFlight();
        if (fpv != null && viewType == ViewType.OPERATOR) {
            return vehicle.rotYXZ(partialTick).rotateX((float) Math.toRadians(-fpv.cameraTilt()));
        }
        return cameraRotationO().slerp(cameraRotation(), partialTick);
    }

    public Quaternionf cameraRotationO() {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-cameraAimRotYO))
                .rotateX((float) Math.toRadians(cameraAimRotXO))
                .rotateZ((float) Math.toRadians(cameraAimRotZO));
    }

    public Vec3 cameraPosition(PartUnit<?> operatorUnit, float partialTick) {
        Vec3 position;
        if (viewType == LocalVehiclePlayer.ViewType.SCOPE && sightUnit() instanceof WeaponUnit weaponUnit) {
            Vec3 eye = StationAds.eye(weaponUnit, partialTick);
            position = eye != null ? eye : weaponUnit.getOpticalSightType() != WeaponUnitData.OpticalSightType.OPERATOR ?
                    weaponUnit.worldOpticalSightPosition(partialTick) : weaponUnit.worldOwnerViewPosition(partialTick);
        } else if (viewType == LocalVehiclePlayer.ViewType.OPERATOR && lensUav()
                && operatorUnit instanceof WeaponUnit weaponUnit
                && weaponUnit.getOpticalSightType() != WeaponUnitData.OpticalSightType.NONE) {
            position = weaponUnit.worldOpticalSightPosition(partialTick);
        } else if (viewType == LocalVehiclePlayer.ViewType.OPERATOR) {
            position = operatorUnit.worldOwnerViewPosition(partialTick);
        } else if (viewType == LocalVehiclePlayer.ViewType.THIRD_PERSON) {
            position = vehicle.thirdPersonPosition(partialTick);
        } else {
            position = vehicle.position().add(0, vehicle.getBoundingBox().getYsize() + getPlayer().getEyeHeight(), 0);
        }
        return position;
    }

    private void fixLerp() {
        if (Math.abs(cameraAimRotX - cameraAimRotXO) > 180) {
            cameraAimRotXO += cameraAimRotXO < 0 ? 360f : -360f;
        }
        if (Math.abs(cameraAimRotY - cameraAimRotYO) > 180) {
            cameraAimRotYO += cameraAimRotYO < 0 ? 360f : -360f;
        }
        if (Math.abs(cameraAimRotZ - cameraAimRotZO) > 180) {
            cameraAimRotZO += cameraAimRotZO < 0 ? 360f : -360f;
        }
    }

    public void checkState() {
        if (onVehicle()) {
            onVehicleTickCount += 1;
            if (!ThirdPersonRules.allows(vehicle) && viewType == ViewType.THIRD_PERSON) {
                switchViewType(ViewType.OPERATOR);
            }
            // Views are viewType's; F5 cycles them (InputHandler).
            Options options = Minecraft.getInstance().options;
            if (!options.getCameraType().isFirstPerson()) {
                options.setCameraType(CameraType.FIRST_PERSON);
            }
        } else {
            viewType = ViewType.THIRD_PERSON;
            onVehicleTickCount = 0;
        }
        Player player = getPlayer();
        AbstractVehicle vehicle = this.vehicle;
        WeaponUnit weaponUnit = vehicle != null ? sightUnit() : null;
        boolean ir = false;
        float link = vehicle != null && vehicle.uav ? vehicle.link() : 1.0f;
        if (vehicle != null && vehicle.uav) {
            DroneCamHud.pilot(link, AllKeys.LEAVE_VEHICLE, InputHandler.uavFreeLook);
        }
        if (sighted() && weaponUnit != null
                && weaponUnit.getOpticalSightType() == WeaponUnitData.OpticalSightType.CRT) {
            ir = sightMode == CameraMode.IR && weaponUnit.fitted(sightMode);
            ScreenSensor.set(weaponUnit.fitted(sightMode) ? sightMode : CameraMode.OPTICAL, link);
            sensing = true;
        } else if (vehicle != null && vehicle.uav && viewType != ViewType.THIRD_PERSON) {
            ScreenSensor.set(CameraMode.OPTICAL, link);
            sensing = true;
        } else if (sensing) {
            ScreenSensor.set(null, 1.0f);
            sensing = false;
        }
        syncIrSight(vehicle, ir);
        if (OverloadHandler.isActive()) {
            if (vehicle == null) {
                OverloadHandler.setActive(false);
            }
        }
        serverEntities.values().removeIf(serverEntity -> serverEntity.updateTick + AllConfigs.server.serverBroadcastEntitiesInterval.get() * 5 < player.tickCount);
        for (MissileEntity missile : player.level().getEntitiesOfClass(MissileEntity.class,
                player.getBoundingBox().inflate(OWN_MISSILE_RANGE), m -> m.getOwner() == player)) {
            missiles.put(missile, player.tickCount);
        }
        missiles.values().removeIf(tickCount -> tickCount + 2 < player.tickCount);
    }

    public void clear() {
        seat = null;
        vehicle = null;
        serverEntities.clear();
        missiles.clear();
        if (sensing) {
            ScreenSensor.set(null, 1.0f);
            sensing = false;
        }
        OverloadHandler.setActive(false);
    }

    public Vec3 freeAimPos() {
        return cameraAimHit(CAMERA_UPWARD_ANGLE);
    }

    public Vec3 scopeAimPos() {
        return cameraAimHit(0);
    }

    public void rangeFinding(Vec3 worldPos) {
        double renderDistance = renderDistance();
        double aimLocationDistance = viewOrigin().distanceTo(worldPos);
        this.outOfRangeFinding = aimLocationDistance > renderDistance;
        this.aimLocationDistance = Math.min(aimLocationDistance, renderDistance);
    }

    public Vec3 viewOrigin() {
        AbstractVehicle vehicle = this.vehicle;
        if (vehicle != null && vehicle.getDetachedBodyAnchor(getPlayer()) != null) {
            return vehicle.position();
        }
        return getPlayer().position();
    }

    /** Own weapon, else the sight the own seat spots through ({@code sensor}). */
    @Nullable
    public WeaponUnit sightUnit() {
        PartUnit<?> own = vehicle == null ? null : vehicle.getOwnOperatorUnit(getPlayer());
        return own instanceof WeaponUnit unit ? unit : own == null ? null : own.sensorUnit();
    }

    /** Seat looks through another station's {@code sensor}; fires nothing. */
    public boolean spotting() {
        PartUnit<?> own = vehicle == null ? null : vehicle.getOwnOperatorUnit(getPlayer());
        return own != null && !(own instanceof WeaponUnit) && own.sensorUnit() != null;
    }

    public WeaponUnit getWeaponUnit() {
        if (getPlayer().getVehicle() instanceof AbstractVehicle vehicle) {
            if (vehicle.getOwnOperatorUnit(getPlayer()) instanceof WeaponUnit weaponUnit) {
                return weaponUnit;
            }
        }
        return null;
    }

    public void sendMessage(String message) {
        getPlayer().displayClientMessage(Component.translatable(message), true);
    }

    public static class ServerEntity {
        public Entity entity;
        public Integer updateTick;
    }

}
