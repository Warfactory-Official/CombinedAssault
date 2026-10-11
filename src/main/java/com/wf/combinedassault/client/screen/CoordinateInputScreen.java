package com.wf.combinedassault.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.VehicleMissile;

import java.util.Locale;
import java.util.function.Consumer;

public class CoordinateInputScreen extends Screen {

    /** Preview of a fixed-profile round's climb/dive legs, blocks. */
    private static final double PRESET_ASCENT = 64;
    private static final double PRESET_DIVE = 128;
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 230;
    private static final int INPUT_WIDTH = 250;
    private static final int INPUT_HEIGHT = 28;
    private final Vec3 initialPosition;
    private Vec3 aimPosition;
    private final Consumer<Vec3> onConfirm;
    private EditBox xInput;
    private EditBox yInput;
    private EditBox zInput;
    private Button confirmButton;

    public CoordinateInputScreen(Vec3 initialPosition, Consumer<Vec3> onConfirm) {
        super(Component.translatable("screen.coordinate_input.title"));
        this.initialPosition = initialPosition;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        int inputX = left + 76;

        xInput = addCoordinateInput(inputX, top + 48, "screen.coordinate_input.x", initialPosition.x);
        yInput = addCoordinateInput(inputX, top + 84, "screen.coordinate_input.y", initialPosition.y);
        zInput = addCoordinateInput(inputX, top + 120, "screen.coordinate_input.z", initialPosition.z);

        confirmButton = addRenderableWidget(Button.builder(Component.translatable("screen.coordinate_input.confirm"), button -> confirm())
                .bounds(left + 32, top + 174, 140, 28)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 188, top + 174, 140, 28)
                .build());

        updateInputState();
        setFocused(null);
    }

    private EditBox addCoordinateInput(int x, int y, String narrationKey, double initialValue) {
        EditBox input = new EditBox(font, x, y, INPUT_WIDTH, INPUT_HEIGHT, Component.translatable(narrationKey));
        input.setMaxLength(32);
        input.setValue(String.format(Locale.ROOT, "%.2f", initialValue));
        input.setResponder(value -> updateInputState());
        return addRenderableWidget(input);
    }

    private void updateInputState() {
        boolean xValid = isValidCoordinate(xInput.getValue());
        boolean yValid = isValidCoordinate(yInput.getValue());
        boolean zValid = isValidCoordinate(zInput.getValue());
        xInput.setTextColor(xValid ? 0xE0E0E0 : 0xFF5555);
        yInput.setTextColor(yValid ? 0xE0E0E0 : 0xFF5555);
        zInput.setTextColor(zValid ? 0xE0E0E0 : 0xFF5555);
        boolean allValid = xValid && yValid && zValid;
        if (confirmButton != null) {
            confirmButton.active = allValid;
        }
        if (allValid) {
            LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
            if (instance.onVehicle()
                    && instance.seat.partUnit instanceof WeaponUnit weaponUnit
                    && weaponUnit.getYRotSpeed() > 0 && weaponUnit.getXRotSpeed() > 0) {
                Vec3 targetPosition = new Vec3(
                        Double.parseDouble(xInput.getValue()),
                        Double.parseDouble(yInput.getValue()),
                        Double.parseDouble(zInput.getValue())
                );
                aimPosition = targetPosition;
                var currentWeapon = weaponUnit.getCurrentWeapon().orElse(null);
                if (currentWeapon instanceof VehicleMissile vehicleMissile && vehicleMissile.presetFlight()) {
                    aimPosition = calculatePresetAscentEnd(vehicleMissile, targetPosition);
                }
                if (weaponUnit.indirectFire().isIndirect()) {
                    // Indirect: coordinate = fire mission target, not an aim point.
                    com.wf.combinedassault.indirect.IndirectFire.solveTo(weaponUnit, targetPosition);
                } else {
                    weaponUnit.aim(aimPosition);
                }
            }
        }
    }

    private Vec3 calculatePresetAscentEnd(VehicleMissile vehicleMissile, Vec3 targetPosition) {
        Vec3 launchPosition = vehicleMissile.getWeaponUnit().aimContext().from;
        Vec3 horizontalToTarget = new Vec3(
                targetPosition.x - launchPosition.x,
                0,
                targetPosition.z - launchPosition.z
        );
        double horizontalDistance = horizontalToTarget.length();
        Vec3 forward = horizontalDistance > 1.0E-6
                ? horizontalToTarget.scale(1 / horizontalDistance)
                : Vec3.ZERO;
        double ascentDistance = PRESET_ASCENT;
        double diveDistance = PRESET_DIVE;
        double transitionDistance = ascentDistance + diveDistance;
        if (transitionDistance > horizontalDistance && transitionDistance > 1.0E-6) {
            ascentDistance *= horizontalDistance / transitionDistance;
        }
        return new Vec3(
                launchPosition.x + forward.x * ascentDistance,
                com.wf.combinedassault.compat.wflib.Munitions.missile(vehicleMissile.currentRound()).altitudeParam(),
                launchPosition.z + forward.z * ascentDistance
        );
    }

    private boolean isValidCoordinate(String value) {
        try {
            return Double.isFinite(Double.parseDouble(value));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void confirm() {
        if (!confirmButton.active) {
            return;
        }
        Vec3 position = new Vec3(
                Double.parseDouble(xInput.getValue()),
                Double.parseDouble(yInput.getValue()),
                Double.parseDouble(zInput.getValue()));
        onClose();
        if (aimPosition != null) {
            LocalVehiclePlayer instance = LocalVehiclePlayer.instance;
            instance.thirdPersonCameraAimAt(aimPosition, instance.vehicle);
        }
        onConfirm.accept(position);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && confirmButton.active) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        guiGraphics.drawCenteredString(font, title, width / 2, top + 16, 0xFFFFFF);
        guiGraphics.drawString(font, Component.literal("X"), left + 42, top + 57, 0xE0E0E0);
        guiGraphics.drawString(font, Component.literal("Y"), left + 42, top + 93, 0xE0E0E0);
        guiGraphics.drawString(font, Component.literal("Z"), left + 42, top + 129, 0xE0E0E0);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xD0101010);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

}
