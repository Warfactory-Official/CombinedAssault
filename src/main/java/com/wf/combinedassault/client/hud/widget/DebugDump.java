package com.wf.combinedassault.client.hud.widget;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.command.sub.DebugCommand;
import com.wf.combinedassault.custom.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@code /combinedassault debug}: server-side vehicle and weapon state, singleplayer only. Allocates; debug. */
public record DebugDump() implements WidgetDef {
    private static final int MAX_DEPTH = 16;
    private static final int LINE_H = 10;
    private static final int COL1_X = 2;
    private static final int COL2_X = 2;
    private static final int COL3_X = 240;

    @Override
    public Widget create(Binder binder) {
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                dump(ev.canvas);
            }
        };
    }

    static void dump(Canvas canvas) {
        if (!LocalVehiclePlayer.instance.onVehicle() || !DebugCommand.DEBUG) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.hasSingleplayerServer()) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            return;
        }
        UUID uuid = mc.player.getUUID();
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(uuid);
        Entity entity = serverPlayer.level().getEntity(LocalVehiclePlayer.instance.vehicle.getId());
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return;
        }
        canvas.push().scale(0.5f);
        try {
            WeaponUnit weaponUnit = LocalVehiclePlayer.instance.getWeaponUnit();
            int y = 2;
            canvas.text("-- vehicle --", COL1_X, y, 0xDD999999, true); y += LINE_H;
            canvas.text("vehicleId: " + vehicle.getVehicleId(), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("displayId: " + vehicle.getDisplayId(), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            Vec3 vel = vehicle.getDeltaMovement();
            canvas.text("velocity: " + String.format("%.2f, %.2f, %.2f", vel.x, vel.y, vel.z), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("speed: " + String.format("%.2f", vel.length()), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("xRot: " + String.format("%.2f", vehicle.getXRot()) + "  yRot: " + String.format("%.2f", vehicle.getYRot()) + "  zRot: " + String.format("%.2f", vehicle.getZRot()), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("engineSpeed: " + String.format("%.2f", vehicle.getEngineSpeed()), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("power: " + String.format("%.2f", vehicle.getPower()), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("energy: " + String.format("%.2f", vehicle.getEnergy()), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("mass (kg): " + String.format("%.2f", vehicle.physicsEngine.mass), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("curbWeight (kg): " + String.format("%.2f", vehicle.curbWeight), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("engineOn: " + vehicle.isEngineOn(), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("destroyed: " + vehicle.isDestroyed(), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            canvas.text("onGround: " + vehicle.onGround(), COL1_X, y, 0xDDFFFFFF, true); y += LINE_H;
            y += LINE_H;
            canvas.text("-- weapon data --", COL2_X, y, 0xDD999999, true); y += LINE_H;
            if (weaponUnit != null) {
                var currentWeapon = weaponUnit.getCurrentWeapon();
                if (currentWeapon.isPresent()) {
                    BaseVehicleWeaponData data = currentWeapon.get().getData();
                    for (String line : getGetterLines(data, 0)) {
                        canvas.text(line, COL2_X, y, 0xDDFFFFFF, true);
                        y += LINE_H;
                    }
                } else {
                    canvas.text("no weapon selected", COL2_X, y, 0xDDFFFFFF, true);
                }
            } else {
                canvas.text("no weaponUnit", COL2_X, y, 0xDDFFFFFF, true);
            }
            y = 2;
            canvas.text("-- weapon unit --", COL3_X, y, 0xDD999999, true); y += LINE_H;
            if (weaponUnit == null) {
                canvas.text("null", COL3_X, y, 0xDDFFFFFF, true);
            } else {
                canvas.text("name: " + weaponUnit.getName().getString(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("currentWeaponIndex: " + (weaponUnit.getCurrentWeaponIndex() + 1) + " / " + weaponUnit.weapons.size(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("currentSecondaryWeaponIndex: " + (weaponUnit.getCurrentSecondaryWeaponIndex() + 1)  + " / " + weaponUnit.secondaryWeapons.size(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("ammoCapacity: " + weaponUnit.getAmmoCapacity(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("firingMode: " + weaponUnit.getData().getFiringMode(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("withStabilizer: " + weaponUnit.getData().withStabilizer(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("withFocusLocker: " + weaponUnit.getData().withFocusLocker(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("withThermalImager: " + weaponUnit.getData().withThermalImager(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("zoom: " + String.format("%.1f", weaponUnit.getZoom()), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("zoomRange: " + String.format("%.1f", weaponUnit.getData().getZoomMin()) + " ~ " + String.format("%.1f", weaponUnit.getData().getZoomMax()), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("opticalSightType: " + weaponUnit.opticalSightType, COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("fireControlSensorType: " + weaponUnit.getData().getFireControlSensorType(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("crosshairStyle: " + weaponUnit.crosshairStyle, COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("seeker: " + weaponUnit.isSeekerOn(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                y += 2;
                canvas.text("weapons: " + weaponUnit.weapons.size(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("secondaryWeapons: " + weaponUnit.secondaryWeapons.size(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("independentWeapons: " + weaponUnit.independentWeapons.size(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("indexedWeapons: " + weaponUnit.indexedWeapons.size(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
                canvas.text("weaponBayUnits: " + weaponUnit.weaponBayUnits.size(), COL3_X, y, 0xDDFFFFFF, true); y += LINE_H;
            }
        } finally {
            canvas.pop();
        }
    }

    private static List<String> getGetterLines(Object obj, int depth) {
        List<String> lines = new ArrayList<>();
        String prefix = depth > 0 ? "  ".repeat(depth) : "";
        for (Class<?> clazz = obj.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                SerializedName annotation = field.getAnnotation(SerializedName.class);
                if (annotation == null) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    String name = annotation.value();
                    if (value instanceof Ingredient) {
                        continue;
                    }
                    if (value == null) {
                        lines.add(prefix + name + ": null");
                    } else if (isSimple(value) || depth >= MAX_DEPTH) {
                        lines.add(prefix + name + ": " + value);
                    } else {
                        lines.add(prefix + name + ":");
                        lines.addAll(getGetterLines(value, depth + 1));
                    }
                } catch (Exception e) {
                    lines.add(prefix + annotation.value() + ": <err>");
                }
            }
        }
        return lines;
    }

    private static boolean isSimple(Object value) {
        return value instanceof String
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof Enum<?>
            || value instanceof net.minecraft.resources.ResourceLocation
            || value.getClass().isEnum();
    }

}
