package com.wf.combinedassault.hud.widget;

import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.combinedassault.hud.CaDraw;
import com.wf.combinedassault.hud.HudCache;
import com.wf.combinedassault.hud.HudSubjects;
import com.wf.combinedassault.render.gem.SchematicBake;
import com.wf.combinedassault.render.gem.SchematicSprites;
import com.wf.combinedassault.render.model.VehicleModelInstance;
import com.wf.combinedassault.render.util.GuiHelper;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.RotatableUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.weapon.VehicleMissile;
import com.wf.combinedassault.weapon.client.StationAds;
import com.wf.combinedassault.weapon.data.VehicleMissileWeaponData;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.part.data.WeaponUnitData;
import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.api.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scope-view instruments of a weapon station; GUI coordinates, size 0. */
public final class ScopeWidgets {
    private ScopeWidgets() {
    }

    abstract static class Plain extends Widget {
        @Override
        public void measure(UiEval ev) {
        }
    }

    /**
     * Sight at the projected impact point: CRT reticle (bars, square, range, zoom, STAB, focus lock),
     * else a 1 px dot unless the station looks through an optical scope; reload rings either way.
     */
    public record ScopeSight(String color) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<WeaponUnit> station = binder.subject(HudSubjects.STATION);
            SubjectRef<LocalVehiclePlayer> localRef = binder.subject(HudSubjects.LOCAL);
            ColorProp colour = binder.color(color);
            return new Plain() {
                private final float[] at = new float[2];
                private final float[] atO = new float[2];
                private final TextBuffer text = new TextBuffer(24);

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    WeaponUnit unit = station.get(ev);
                    LocalVehiclePlayer local = localRef.get(ev);
                    if (unit == null || local == null) {
                        return;
                    }
                    float px;
                    float py;
                    if (local.lensSight() || local.spotting()) {
                        // Lens = gimbal, spotter = sight line: boresight is the screen centre; the station may have no turn groups.
                        px = ev.width / 2;
                        py = ev.height / 2;
                    } else if (ScreenProjection.project(unit.weaponHitPos, at)
                            && ScreenProjection.project(unit.weaponHitPosO, atO)) {
                        px = ev.fromHost(Mth.lerp(ev.partialTick, atO[0], at[0]));
                        py = ev.fromHost(Mth.lerp(ev.partialTick, atO[1], at[1]));
                    } else {
                        return;
                    }
                    int argb = colour.get(ev);
                    Canvas canvas = ev.canvas;
                    WeaponUnitData.OpticalSightType sight = unit.getOpticalSightType();
                    if (sight == WeaponUnitData.OpticalSightType.CRT) {
                        CaDraw.square(canvas, px - 0.5f, py - 0.5f, 5, argb);
                        canvas.fill(px - 0.5f, py - 32, px + 0.5f, py - 8, argb);
                        canvas.fill(px - 0.5f, py + 8, px + 0.5f, py + 32, argb);
                        canvas.fill(px - 32, py - 0.5f, px - 8, py + 0.5f, argb);
                        canvas.fill(px + 8, py - 0.5f, px + 32, py + 0.5f, argb);
                        text.clear();
                        if (local.outOfRangeFinding) {
                            text.append('>');
                        }
                        text.append((long) local.aimLocationDistance).append(" m");
                        canvas.textCentred(text, px, py + 40, argb, true);
                        if (unit.zeroRange() > 0) {
                            text.clear().append("Z ").append((long) unit.zeroRange()).append(" m");
                            canvas.textCentred(text, px, py + 52, argb, true);
                        }
                        if (unit.rangefinder() != null && unit.rangeReadingLive()) {
                            text.clear().append("LRF ");
                            if (unit.rangeReading > 0) {
                                text.append((long) unit.rangeReading).append(" m");
                            } else {
                                text.append("----");
                            }
                            canvas.textCentred(text, px, py + 64, argb, true);
                        }
                        text.clear().append('x').append(unit.getZoom(), 1);
                        canvas.text(text, px + 25, py + 16, argb, true);
                        if (unit.withStabilizer()) {
                            canvas.text(HudCache.translate("ui.stabilizer_on"), px + 25, py + 28, argb, true);
                        }
                        if (unit.withFocusLocker() && unit.getFocusLockPos() != null) {
                            canvas.push().translate(px, py - 0.5f).rotate((float) (Math.PI / 4));
                            CaDraw.crossHollow(canvas, 0, 0, 40, 8, 1, CaDraw.WHITE);
                            canvas.pop();
                            canvas.text(HudCache.translate("ui.focus_lock"), px + 25, py + 40, argb, true);
                        }
                    } else if (sight != WeaponUnitData.OpticalSightType.OPTICAL_SCOPE && !StationAds.aiming(unit)) {
                        canvas.frame(px - 1, py - 1, px + 1, py + 1, 1, argb);
                    }
                    AimWidgets.reloadArcs(canvas, unit, px, py);
                }
            };
        }
    }

    /** Full-screen procedural scope (vignette, reticle, haze) of {@code optical_scope} stations. */
    public record OpticalScope() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<WeaponUnit> station = binder.subject(HudSubjects.STATION);
            return new Plain() {
                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    WeaponUnit unit = station.get(ev);
                    if (unit == null || ev.graphics == null
                            || unit.getOpticalSightType() != WeaponUnitData.OpticalSightType.OPTICAL_SCOPE
                            || StationAds.aiming(unit)) {
                        return;
                    }
                    ev.canvas.suspend();
                    GuiHelper.drawOpticalScope(ev.graphics, ev.graphics.guiWidth(), ev.graphics.guiHeight());
                    ev.canvas.resume();
                }
            };
        }
    }

    /**
     * Traverse/elevation limits as a box with the gun's position cross (half scale), a second box for
     * a missile's launch envelope. Offset (0, 140) from the box origin in unscaled units.
     */
    public record EngagementEnvelope(String color) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<WeaponUnit> station = binder.subject(HudSubjects.STATION);
            ColorProp colour = binder.color(color);
            return new Plain() {
                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    WeaponUnit unit = station.get(ev);
                    AbstractVehicleWeapon<?> weapon = unit == null ? null : unit.currentWeapon();
                    if (weapon == null) {
                        return;
                    }
                    float range = unit.getYRotMax() - unit.getYRotMin();
                    if (range <= 0 || range >= 360 || unit.yRotSpeed == 0) {
                        return;
                    }
                    int argb = colour.get(ev);
                    Canvas canvas = ev.canvas;
                    canvas.push().translate(x, y).scale(0.5f);
                    float baseY = 140;
                    CaDraw.rectByCorner(canvas, (int) (unit.getYRotMin() - unit.ySelfRot), (int) (unit.getYRotMax() - unit.ySelfRot),
                            (int) (baseY + unit.getXRotMin() - unit.xSelfRot), (int) (baseY + unit.getXRotMax() - unit.xSelfRot), argb);
                    if (weapon instanceof VehicleMissile missile) {
                        VehicleMissileWeaponData data = missile.getData();
                        CaDraw.rectByCorner(canvas, (int) (data.getYRotMin() - unit.ySelfRot), (int) (data.getYRotMax() - unit.ySelfRot),
                                (int) (baseY + Math.max(data.getXRotMin(), unit.getXRotMin()) - unit.xSelfRot),
                                (int) (baseY + Math.min(data.getXRotMax(), unit.getXRotMax()) - unit.xSelfRot), argb);
                    }
                    int gx = (int) Mth.lerp(ev.partialTick, unit.yRotO, unit.getYRot());
                    int gy = (int) (baseY + (int) Mth.lerp(ev.partialTick, unit.xRotO, unit.getXRot()));
                    canvas.fill(gx, gy - 8, gx + 1, gy - 2, argb);
                    canvas.fill(gx, gy + 3, gx + 1, gy + 9, argb);
                    canvas.fill(gx - 8, gy, gx - 2, gy + 1, argb);
                    canvas.fill(gx + 3, gy, gx + 9, gy + 1, argb);
                    canvas.pop();
                }
            };
        }
    }

    /**
     * Top-down hull schematic rotated to the operator's traverse, one baked sprite per part bone
     * ({@link SchematicSprites}); own station blue, damage steps yellow/orange/red, destroyed red. Boxes while the
     * model has no sprites. Scaled so any hull is {@code length} units long (default 50); {@code hullUp} keeps the hull's nose up instead of following the traverse.
     */
    public record VehicleSchematic(float length, boolean hullUp) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<AbstractVehicle> vehicleRef = binder.subject(HudSubjects.VEHICLE);
            return new Plain() {
                private final Vector3f axisX = new Vector3f();
                private final Vector3f axisZ = new Vector3f();
                private final Vector3f forward = new Vector3f();
                private final Quaternionf rotation = new Quaternionf();
                private final Quaternionf slerped = new Quaternionf();
                private final Vector3f origin = new Vector3f();
                private final Vector3f along = new Vector3f();
                private AbstractVehicle bound;
                private SchematicSprites.Sheet sheet;
                private final Map<String, PartUnit<?>> boneParts = new LinkedHashMap<>();
                private SchematicSprites.Sheet resolved;
                private PartUnit<?>[] spriteParts = new PartUnit<?>[0];
                private int[] spriteBones = new int[0];

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    AbstractVehicle vehicle = vehicleRef.get(ev);
                    if (vehicle == null) {
                        return;
                    }
                    float pt = ev.partialTick;
                    Canvas canvas = ev.canvas;
                    float scale = (length > 0 ? length : 50.0f) / 10.0f / (float) Math.max(4.0, vehicle.getStructureLength());
                    Player player = LocalVehiclePlayer.instance().getPlayer();
                    PartUnit<?> own = vehicle.getOwnOperatorUnit(player);
                    float zRot = !hullUp && own instanceof RotatableUnit<?> rotatable ? turretYaw(rotatable, vehicle, pt) : 0;
                    canvas.push().translate(x, y).scale(scale).rotate((float) Math.toRadians(180 - zRot));
                    if (sprites(canvas, vehicle, player, pt)) {
                        canvas.pop();
                        return;
                    }
                    double vx = Mth.lerp(pt, vehicle.xo, vehicle.getX());
                    double vy = Mth.lerp(pt, vehicle.yo, vehicle.getY());
                    double vz = Mth.lerp(pt, vehicle.zo, vehicle.getZ());
                    rotation.identity()
                            .rotateY((float) Math.toRadians(-vehicle.getViewYRot(pt)))
                            .rotateX((float) Math.toRadians(vehicle.getViewXRot(pt)))
                            .rotateZ((float) Math.toRadians(vehicle.getViewZRot(pt)));
                    rotation.transform(axisX.set(1, 0, 0));
                    rotation.transform(axisZ.set(0, 0, 1));
                    List<VehicleCubeOBB> hull = vehicle.getVehicleCubeOBBs();
                    for (int i = 0; i < hull.size(); i++) {
                        box(canvas, hull.get(i), vx, vy, vz, 0, pt, CaDraw.WHITE);
                    }
                    List<PartUnit<?>> parts = vehicle.getPartUnits();
                    for (int p = 0; p < parts.size(); p++) {
                        PartUnit<?> part = parts.get(p);
                        if (part instanceof WeaponUnit weapon && !weapon.isSeat()) {
                            continue;
                        }
                        int colour = part.getOwner() == player ? CaDraw.BLUE : CaDraw.WHITE;
                        List<VehicleCubeOBB> boxes = part.getPartCubeOBBs();
                        for (int i = 0; i < boxes.size(); i++) {
                            VehicleCubeOBB obb = boxes.get(i);
                            box(canvas, obb, vx, vy, vz, boxYaw(obb, vehicle, pt), pt, part.isDestroyed() ? CaDraw.RED : colour);
                        }
                        if (part instanceof WeaponUnit weapon) {
                            List<WeaponUnit> subs = weapon.getSubWeaponUnits();
                            for (int s = 0; s < subs.size(); s++) {
                                List<VehicleCubeOBB> subBoxes = subs.get(s).getPartCubeOBBs();
                                for (int i = 0; i < subBoxes.size(); i++) {
                                    VehicleCubeOBB obb = subBoxes.get(i);
                                    box(canvas, obb, vx, vy, vz, boxYaw(obb, vehicle, pt), pt, colour);
                                }
                            }
                        }
                    }
                    canvas.pop();
                }

                /** @return false: model has no sprites, draw boxes. */
                private boolean sprites(Canvas canvas, AbstractVehicle vehicle, Player player, float pt) {
                    if (vehicle != bound || !sheet.valid()) {
                        bind(vehicle);
                    }
                    SchematicSprites.Sheet.State state = sheet.state();
                    if (state == SchematicSprites.Sheet.State.FAILED) {
                        return false;
                    }
                    VehicleModelInstance instance = vehicle.getVehicleModelInstance();
                    if (state == SchematicSprites.Sheet.State.PENDING || !instance.posed()) {
                        return true;
                    }
                    SchematicBake.Result result = sheet.result();
                    if (resolved != sheet) {
                        resolve(instance, result);
                    }
                    canvas.push().scale(10);
                    for (int i = 0; i < spriteParts.length; i++) {
                        SchematicBake.Sprite sprite = result.sprites().get(i);
                        float angle = 0;
                        float ox = 0;
                        float oz = 0;
                        if (spriteBones[i] >= 0) {
                            // TODO: globalTransform allocates a Matrix4f per call; paid per sprite per frame while scoped.
                            Matrix4f bone = instance.globalTransform(spriteBones[i]);
                            bone.transformPosition(0, 0, 0, origin);
                            bone.transformDirection(1, 0, 0, along);
                            ox = origin.x;
                            oz = origin.z;
                            angle = (float) Math.atan2(along.z, along.x);
                        }
                        canvas.push().translate(ox - result.centreX(), oz - result.centreZ()).rotate(angle);
                        float u = 1.0f / result.width();
                        float v = 1.0f / result.height();
                        canvas.sprite(sheet.texture(), sprite.minX(), sprite.minZ(), sprite.maxX(), sprite.maxZ(),
                                sprite.x() * u, sprite.y() * v, (sprite.x() + sprite.w()) * u,
                                (sprite.y() + sprite.h()) * v, tint(vehicle, spriteParts[i], player));
                        canvas.pop();
                    }
                    canvas.pop();
                    return true;
                }

                private void bind(AbstractVehicle vehicle) {
                    bound = vehicle;
                    boneParts.clear();
                    List<PartUnit<?>> parts = vehicle.getPartUnits();
                    for (int p = 0; p < parts.size(); p++) {
                        PartUnit<?> part = parts.get(p);
                        claim(part);
                        if (part instanceof WeaponUnit weapon) {
                            List<WeaponUnit> subs = weapon.getSubWeaponUnits();
                            for (int s = 0; s < subs.size(); s++) {
                                claim(subs.get(s));
                            }
                        }
                    }
                    sheet = SchematicSprites.request(vehicle.getVehicleModelInstance(), new ArrayList<>(boneParts.keySet()));
                    resolved = null;
                }

                private void claim(PartUnit<?> part) {
                    String bone = part.getRenderBoneName();
                    if (bone != null && !bone.isBlank()) {
                        boneParts.putIfAbsent(bone, part);
                    }
                }

                private void resolve(VehicleModelInstance instance, SchematicBake.Result result) {
                    int count = result.sprites().size();
                    spriteParts = new PartUnit<?>[count];
                    spriteBones = new int[count];
                    for (int i = 0; i < count; i++) {
                        String bone = result.sprites().get(i).bone();
                        spriteParts[i] = boneParts.get(bone);
                        spriteBones[i] = bone.isEmpty() ? -1 : instance.boneIndex(bone);
                    }
                    resolved = sheet;
                }

                private int tint(AbstractVehicle vehicle, PartUnit<?> part, Player player) {
                    if (part == null) {
                        return CaDraw.WHITE;
                    }
                    if (part.isDestroyed()) {
                        return CaDraw.RED;
                    }
                    float max = part.getMaxHealth();
                    ComponentState state = ComponentState.of(max <= 0 ? 1 : Math.max(0, part.getHealth() / max));
                    return switch (state) {
                        case PRISTINE -> part.getOwner() == player ? CaDraw.BLUE : CaDraw.WHITE;
                        case WORN -> 0xFFE8D060;
                        case DAMAGED -> 0xFFE08830;
                        case BROKEN -> 0xFFB82820;
                    };
                }

                private void box(Canvas canvas, VehicleCubeOBB obb, double vx, double vy, double vz, float yaw, float pt, int argb) {
                    double ox = Mth.lerp(pt, obb.positionO.x, obb.position.x) - vx;
                    double oy = Mth.lerp(pt, obb.positionO.y, obb.position.y) - vy;
                    double oz = Mth.lerp(pt, obb.positionO.z, obb.position.z) - vz;
                    float bx = (float) ((ox * axisX.x() + oy * axisX.y() + oz * axisX.z()) * 10.0);
                    float bz = (float) ((ox * axisZ.x() + oy * axisZ.y() + oz * axisZ.z()) * 10.0);
                    int hw = (int) (obb.width * 5.0);
                    int hd = (int) (obb.depth * 5.0);
                    canvas.push().translate(bx, bz).rotate((float) Math.toRadians(yaw));
                    CaDraw.rectByCorner(canvas, -hw, hw, -hd, hd, argb);
                    canvas.pop();
                }

                // TODO: RotatableUnit.worldRot allocates (Vec3, Vec2, Quaternionf); paid per frame while scoped.
                private float turretYaw(RotatableUnit<?> unit, AbstractVehicle vehicle, float pt) {
                    float now = Mth.wrapDegrees(unit.worldRot().y - vehicle.getYRot());
                    float before = Mth.wrapDegrees(unit.worldRot(unit.xRotO, unit.yRotO).y - vehicle.getYRot());
                    float diff = Mth.wrapDegrees(now - before);
                    return Mth.lerp(pt, now - diff, now);
                }

                private float boxYaw(VehicleCubeOBB obb, AbstractVehicle vehicle, float pt) {
                    if (obb.rotationO == null || obb.rotation == null) {
                        return 0;
                    }
                    slerped.set(obb.rotationO).slerp(obb.rotation, pt).transform(forward.set(0, 0, 1));
                    float worldYaw = (float) Math.toDegrees(-Math.atan2(forward.x, forward.z));
                    return Mth.wrapDegrees(worldYaw - vehicle.getViewYRot(pt));
                }
            };
        }
    }
}
