package com.wf.combinedassault.vehicle.control;

import com.wf.combinedassault.vehicle.crew.CrewRole;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.FixedWingVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.item.ParachutePackItem;
import com.wf.combinedassault.network.message.*;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.RadarUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.VehicleDecoyFlare;
import com.wf.combinedassault.vehicle.weapon.VehicleGrenade;

import static com.wf.combinedassault.all.AllKeys.*;

@EventBusSubscriber(value = Dist.CLIENT)
public class InputHandler {

    public static boolean freeCamera;
    public static float playerXRotO;
    public static float playerYRotO;
    public static float controlXRotO;
    public static float controlYRotO;
    private static long waitSwitchSeatTime;

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        handleVehicleAction(event.getKey(), event.getScanCode(), event.getAction());
    }

    @SubscribeEvent
    public static void onKey(InputEvent.MouseButton.Pre event) {
        handleVehicleAction(event.getButton(), 0, event.getAction());
        handleMagnificationChange();
    }

    private static boolean matchesKey(KeyMapping mapping, int key, int scanCode) {
        return mapping.matches(key, scanCode) || mapping.matchesMouse(key);
    }

    private static void handleVehicleAction(int key, int scanCode, int action) {
        var mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator() || mc.gameMode == null) {
            return;
        }
        if (mc.screen != null) {
            // Last input held, as the server would; a gap would stop the prediction.
            if (player.getVehicle() instanceof AbstractVehicle vehicle && player.equals(vehicle.controlUnit.getOperator())) {
                ControlUnit held = new ControlUnit(vehicle);
                held.update(vehicle.controlUnit);
                sendControl(vehicle, held);
            }
            return;
        }
        LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
        if (action == GLFW.GLFW_PRESS) {
            if (instance.onVehicle()) {
                AbstractVehicle vehicle = instance.vehicle;
                WeaponUnit weaponUnit = instance.getWeaponUnit();
                if (matchesKey(SWITCH_VIEW, key, scanCode)) {
                    instance.switchViewType(null);
                } else if (matchesKey(SWITCH_SCOPE, key, scanCode)) {
                    if (weaponUnit != null) {
                        if (instance.viewType != LocalVehiclePlayer.ViewType.SCOPE) {
                            instance.switchViewType(LocalVehiclePlayer.ViewType.SCOPE);
                        } else {
                            instance.switchViewType(LocalVehiclePlayer.ViewType.THIRD_PERSON);
                        }
                    }
                } else if (matchesKey(OPEN_INVENTORY, key, scanCode)) {
                    player.sendOpenInventory();
                } else if (matchesKey(TOGGLE_ENGINE, key, scanCode)) {
                    sendToggleEngine(vehicle);
                } else if (matchesKey(TOGGLE_LANDING_GEAR, key, scanCode)) {
                    sendToggleLandingGear(vehicle);
                } else if (matchesKey(TOGGLE_AIRBRAKE, key, scanCode)) {
                    sendToggleAirbrake(vehicle);
                } else if (matchesKey(FLAPS_DOWN, key, scanCode) || matchesKey(FLAPS_UP, key, scanCode)) {
                    sendFlapStep(vehicle, matchesKey(FLAPS_DOWN, key, scanCode) ? 1 : -1);
                } else if (matchesKey(FIRE_CONTROL_LOCK, key, scanCode)) {
                    if (weaponUnit != null) {
                        weaponUnit.fireControlLock();
                    }
                } else if (matchesKey(PRESET_COORDINATE_INPUT, key, scanCode)) {
                    if (weaponUnit != null) {
                        weaponUnit.getCurrentWeapon().ifPresent(AbstractVehicleWeapon::openCoordinateInputScreen);
                    }
                } else if (matchesKey(TOGGLE_HOVER_MODE, key, scanCode)) {
                    sendToggleHoverMode(vehicle);
                } else if (matchesKey(TOGGLE_RADAR, key, scanCode)) {
                    if (weaponUnit != null) {
                        for (RadarUnit radarUnit : weaponUnit.getRadarUnits()) {
                            radarUnit.toggle(null);
                        }
                    }
                } else if (matchesKey(SECONDARY_WEAPON_SWITCH, key, scanCode)) {
                    if (weaponUnit != null) {
                        PacketDistributor.sendToServer(new ClientVehicleSwitchWeapon(vehicle.getId(), ClientVehicleSwitchWeapon.WeaponSwitchType.SECONDARY, true));
                    }
                } else if (matchesKey(DECOY_FLARE_LAUNCH, key, scanCode)) {
                    if (weaponUnit != null) {
                        weaponUnit.independentWeapons.stream()
                                .filter(vehicleWeapon -> vehicleWeapon instanceof VehicleDecoyFlare)
                                .forEach(AbstractVehicleWeapon::doClientShoot);
                    }
                } else if (matchesKey(SMOKE_GRENADE_LAUNCH, key, scanCode)) {
                    if (weaponUnit != null) {
                        weaponUnit.independentWeapons.stream()
                                .filter(vehicleWeapon -> vehicleWeapon instanceof VehicleGrenade grenade
                                        && grenade.isAmmoForWeapon(new net.minecraft.world.item.ItemStack(
                                        com.wf.wflib.countermeasure.Countermeasures.SMOKE_GRENADE_ITEM.get())))
                                .forEach(AbstractVehicleWeapon::doClientShoot);
                    }
                } else if (matchesKey(TOGGLE_SEEKER, key, scanCode)) {
                    if (weaponUnit != null) {
                        weaponUnit.toggleSeeker(null);
                    }
                } else if (matchesKey(CYCLE_SIGHT_MODE, key, scanCode)) {
                    if (weaponUnit != null && weaponUnit.getOpticalSightType() == WeaponUnitData.OpticalSightType.CRT
                            && instance.viewType == LocalVehiclePlayer.ViewType.SCOPE) {
                        instance.sightMode = weaponUnit.nextSightMode(instance.sightMode);
                    }
                } else if (matchesKey(TOGGLE_WEAPON_BAY, key, scanCode)) {
                    if (weaponUnit != null) {
                        weaponUnit.toggleCurrentWeaponBay();
                    }
                } else if (matchesKey(TOGGLE_AEROBATIC_SMOKE, key, scanCode)) {
                    if (vehicle instanceof FixedWingVehicle) {
                        sendToggleAerobaticSmoke(vehicle);
                    }
                } else if (matchesKey(MULTI_WEAPON_SWITCH, key, scanCode)) {
                    if (weaponUnit != null || CrewRules.has(vehicle, player, CrewRole.LOADER)) {
                        PacketDistributor.sendToServer(new ClientVehicleSwitchWeapon(vehicle.getId(), ClientVehicleSwitchWeapon.WeaponSwitchType.MULTI, true));
                    }
                }
            } else {
                if (matchesKey(OPEN_PARACHUTE, key, scanCode) && ParachutePackItem.canOpen(player)) {
                    PacketDistributor.sendToServer(new ClientOpenParachute());
                }
            }
        } else if (action == GLFW.GLFW_RELEASE) {
            if (instance.onVehicle()) {
                if (matchesKey(FREE_CAMERA, key, scanCode)) {
                    instance.playerLerpXRot = playerXRotO;
                    instance.playerLerpYRot = playerYRotO;
                    instance.playerLerpSteps = 8;
                }
            }
        }
    }

    private static void handleMagnificationChange() {
        var mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator() || mc.gameMode == null || mc.screen != null) {
            return;
        }
        LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
        if (instance.onVehicle() && MAGNIFICATION_CHANGE.isDown() && instance.onVehicleTickCount > 5) {
            AbstractVehicle vehicle = instance.vehicle;
            if (vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit weaponUnit) {
                if (instance.viewType == LocalVehiclePlayer.ViewType.SCOPE) {
                    weaponUnit.switchZoom();
                }
            }
            if (instance.viewType == LocalVehiclePlayer.ViewType.OPERATOR
                    || instance.viewType == LocalVehiclePlayer.ViewType.THIRD_PERSON) {
                vehicle.toggleViewZoom();
            }
        }
    }

    @SubscribeEvent
    public static void checkKey(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator() || mc.gameMode == null || mc.screen != null) {
            return;
        }
        LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
        freeCamera = FREE_CAMERA.isDown();
        if (player.getVehicle() instanceof AbstractVehicle vehicle) {
            if (LEAVE_VEHICLE.isDown()) {
                sendLeaveVehicle(vehicle);
                return;
            }
            if (CHANGE_SEAT.isDown()) {
                long window = Minecraft.getInstance().getWindow().getWindow();
                for (int seatIndex = 0; seatIndex < 9; seatIndex++) {
                    int key = GLFW.GLFW_KEY_1 + seatIndex;
                    if (InputConstants.isKeyDown(window, key)) {
                        sendChangeSeat(vehicle, seatIndex);
                        waitSwitchSeatTime = System.currentTimeMillis();
                        return;
                    }
                }
                if (System.currentTimeMillis() - waitSwitchSeatTime > 500) {
                    int seatsCount = vehicle.seats.size();
                    AbstractVehicle.Seat playerSeat = instance.seat;
                    if (playerSeat != null) {
                        for (int seatIndex = (playerSeat.seatIndex + 1) % seatsCount; seatIndex < seatsCount; seatIndex++) {
                            if (vehicle.seats.get(seatIndex).partUnit.getOwner() == null) {
                                sendChangeSeat(vehicle, seatIndex);
                                waitSwitchSeatTime = System.currentTimeMillis();
                                return;
                            }
                        }
                    }
                }
            }
            if (!CHANGE_SEAT.isDown()) {
                waitSwitchSeatTime = System.currentTimeMillis();
            }
            if (player.equals(vehicle.controlUnit.getOperator())) {
                if (instance.lostControl) {
                    ControlUnit controlUnit = new ControlUnit(vehicle);
                    controlUnit.xRot = 0;
                    controlUnit.yRot = playerYRotO;
                    sendControl(vehicle, controlUnit);
                    return;
                }
                ControlUnit controlUnit = new ControlUnit(vehicle);
                controlUnit.forward = FORWARD.isDown();
                controlUnit.backward = BACKWARD.isDown();
                controlUnit.left = LEFT.isDown();
                controlUnit.right = RIGHT.isDown();
                controlUnit.up = UP.isDown();
                controlUnit.down = DOWN.isDown();
                controlUnit.leftYaw = LEFT_YAW.isDown();
                controlUnit.rightYaw = RIGHT_YAW.isDown();
                controlUnit.functionalUp = FUNCTIONAL_UP.isDown();
                controlUnit.functionalDown = FUNCTIONAL_DOWN.isDown();
                controlUnit.functionalLeft = FUNCTIONAL_LEFT.isDown();
                controlUnit.functionalRight = FUNCTIONAL_RIGHT.isDown();
                if (freeCamera || instance.playerLerpSteps > 0) {
                    controlUnit.xRot = controlXRotO;
                    controlUnit.yRot = controlYRotO;
                } else if (instance.viewType == LocalVehiclePlayer.ViewType.SCOPE && vehicle instanceof RotaryWingVehicle) {
                    controlUnit.xRot = 0;
                    controlUnit.yRotKeep = true;
                } else if (instance.viewType == LocalVehiclePlayer.ViewType.SCOPE && vehicle instanceof FixedWingVehicle) {
                    controlUnit.xRot = controlXRotO;
                    controlUnit.yRot = controlYRotO;
                } else {
                    Vec2 controlRot;
                    if (vehicle instanceof RotaryWingVehicle || vehicle instanceof FixedWingVehicle) {
                        controlRot = VectorUtil.vecToRot(instance.cameraDirection(LocalVehiclePlayer.CAMERA_UPWARD_ANGLE));
                    } else {
                        controlRot = VectorUtil.vecToRot(instance.cameraDirection(0));
                    }
                    controlUnit.xRot = controlRot.x;
                    controlUnit.yRot = controlRot.y;
                    controlXRotO = controlUnit.xRot;
                    controlYRotO = controlUnit.yRot;
                }
                sendControl(vehicle, controlUnit);
            }
            if (!freeCamera) {
                playerXRotO = player.getXRot();
                playerYRotO = player.getYRot();
            }
            handleShoot(vehicle, player);
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.MouseScrollingEvent event) {
        var mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator() || mc.gameMode == null) {
            return;
        }
        if (LocalVehiclePlayer.instance.onVehicle()) {
            AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
            boolean previous = event.getScrollDeltaY() < 0;
            if (vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit) {
                PacketDistributor.sendToServer(new ClientVehicleSwitchWeapon(vehicle.getId(), ClientVehicleSwitchWeapon.WeaponSwitchType.PRIMARY, previous));
                // Cancel: vanilla scroll would otherwise cycle the hotbar slot too.
                event.setCanceled(true);
            }
        }
    }

    private static void handleShoot(AbstractVehicle vehicle, LocalPlayer player) {
        if ((MAIN_WEAPON_SHOOT.isDown() || SECONDARY_WEAPON_SHOOT.isDown())
                && vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit own && !CrewRules.mayOperate(player, own)) {
            LocalVehiclePlayer.instance.sendMessage("tips.crew.gunner_only");
            return;
        }
        if (MAIN_WEAPON_SHOOT.isDown()) {
            if (vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit weaponUnit) {
                if (!CrewRules.canLoad(weaponUnit) && weaponUnit.getCurrentWeapon().map(w -> w.getRemainAmmo() == 0).orElse(false)) {
                    LocalVehiclePlayer.instance.sendMessage("tips.crew.needs_loader");
                }
                if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.FULL_SALVO) {
                    for (AbstractVehicleWeapon<?> weapon : weaponUnit.fullSalvoWeapons) {
                        weapon.doClientShoot();
                    }
                } else {
                    weaponUnit.getCurrentWeapon().ifPresent(AbstractVehicleWeapon::doClientShoot);
                }
            } else {
                LocalVehiclePlayer.instance.sendMessage("tips.spotter");
            }
        }
        if (SECONDARY_WEAPON_SHOOT.isDown()) {
            if (vehicle.getOwnOperatorUnit(player) instanceof WeaponUnit weaponUnit) {
                weaponUnit.getCurrentSecondaryWeapon().ifPresent(AbstractVehicleWeapon::doClientShoot);
            } else {
                LocalVehiclePlayer.instance.sendMessage("tips.spotter");
            }
        }
    }

    /** Client tick input seq, monotonic. */
    private static int nextSeq;

    private static void sendControl(AbstractVehicle vehicle, ControlUnit controlUnit) {
        controlUnit.seq = nextSeq++;
        vehicle.controlUnit.update(controlUnit);
        vehicle.predictInput(controlUnit);
        ClientVehicleMoveControl control = new ClientVehicleMoveControl();
        control.vehicleEntityId = vehicle.getId();
        control.seq = controlUnit.seq;
        control.forward = controlUnit.forward;
        control.backward = controlUnit.backward;
        control.left = controlUnit.left;
        control.right = controlUnit.right;
        control.up = controlUnit.up;
        control.down = controlUnit.down;
        control.leftYaw = controlUnit.leftYaw;
        control.rightYaw = controlUnit.rightYaw;
        control.functionalUp = controlUnit.functionalUp;
        control.functionalDown = controlUnit.functionalDown;
        control.functionalLeft = controlUnit.functionalLeft;
        control.functionalRight = controlUnit.functionalRight;
        control.xRot = controlUnit.xRot;
        control.xRotKeep = controlUnit.xRotKeep;
        control.yRot = controlUnit.yRot;
        control.yRotKeep = controlUnit.yRotKeep;
        PacketDistributor.sendToServer(control);
    }

    private static void sendChangeSeat(AbstractVehicle vehicle, int toSeat) {
        ClientVehicleChangeSeat changeSeat = new ClientVehicleChangeSeat();
        changeSeat.vehicleEntityId = vehicle.getId();
        changeSeat.toSeat = toSeat;
        PacketDistributor.sendToServer(changeSeat);
    }

    private static void sendLeaveVehicle(AbstractVehicle vehicle) {
        ClientVehicleAction action = new ClientVehicleAction();
        action.vehicleEntityId = vehicle.getId();
        action.leaveVehicle = true;
        PacketDistributor.sendToServer(action);
    }

    private static void sendToggleEngine(AbstractVehicle vehicle) {
        ClientVehicleAction action = new ClientVehicleAction();
        action.vehicleEntityId = vehicle.getId();
        action.toggleEngine = true;
        PacketDistributor.sendToServer(action);
    }

    private static void sendToggleLandingGear(AbstractVehicle vehicle) {
        ClientVehicleAction action = new ClientVehicleAction();
        action.vehicleEntityId = vehicle.getId();
        action.toggleLandingGear = true;
        PacketDistributor.sendToServer(action);
    }

    private static void sendToggleAirbrake(AbstractVehicle vehicle) {
        ClientVehicleAction action = new ClientVehicleAction();
        action.vehicleEntityId = vehicle.getId();
        action.toggleAirbrake = true;
        PacketDistributor.sendToServer(action);
    }

    private static void sendFlapStep(AbstractVehicle vehicle, int step) {
        ClientVehicleAction action = new ClientVehicleAction();
        action.vehicleEntityId = vehicle.getId();
        action.flapStep = step;
        PacketDistributor.sendToServer(action);
    }

    private static void sendToggleHoverMode(AbstractVehicle vehicle) {
        ClientVehicleAction action = new ClientVehicleAction();
        action.vehicleEntityId = vehicle.getId();
        action.toggleHoverMode = true;
        PacketDistributor.sendToServer(action);
    }

    private static void sendToggleAerobaticSmoke(AbstractVehicle vehicle) {
        ClientVehicleAction action = new ClientVehicleAction();
        action.vehicleEntityId = vehicle.getId();
        action.toggleAerobaticSmoke = true;
        AllConfigs.CommonConfig common = AllConfigs.common;
        action.aerobaticSmokeR = common.aerobaticSmokeR.get();
        action.aerobaticSmokeG = common.aerobaticSmokeG.get();
        action.aerobaticSmokeB = common.aerobaticSmokeB.get();
        PacketDistributor.sendToServer(action);
    }

}
