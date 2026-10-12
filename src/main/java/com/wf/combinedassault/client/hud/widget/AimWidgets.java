package com.wf.combinedassault.client.hud.widget;

import com.wf.combinedassault.client.hud.AimPoint;
import com.wf.combinedassault.client.hud.CaDraw;
import com.wf.combinedassault.client.hud.HudSubjects;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.wflib.round.RocketEntity;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.RadarUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.ScreenProjection;
import com.wf.wflib.ui.SubjectRef;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;

import java.util.List;

import static com.wf.combinedassault.all.AllKeys.FREE_CAMERA;

/** Screen-space aiming marks drawn at projected world points; size 0. */
public final class AimWidgets {
    private AimWidgets() {
    }

    abstract static class Mark extends Widget {
        final float[] at = new float[2];
        final float[] atO = new float[2];

        @Override
        public void measure(UiEval ev) {
        }

        /** Projected and lerped, layout units; false when either sample is behind the camera. */
        boolean project(UiEval ev, Vec3 now, Vec3 before) {
            if (!ScreenProjection.project(now, at) || !ScreenProjection.project(before, atO)) {
                return false;
            }
            at[0] = ev.fromHost(Mth.lerp(ev.partialTick, atO[0], at[0]));
            at[1] = ev.fromHost(Mth.lerp(ev.partialTick, atO[1], at[1]));
            return true;
        }

        /** Layout units; false behind the camera. */
        boolean project(UiEval ev, double x, double y, double z) {
            if (!ScreenProjection.project(x, y, z, at)) {
                return false;
            }
            at[0] = ev.fromHost(at[0]);
            at[1] = ev.fromHost(at[1]);
            return true;
        }

        boolean project(UiEval ev, Vec3 point) {
            return point != null && project(ev, point.x, point.y, point.z);
        }
    }

    /** Reload progress rings around the aim point: primary 7, secondary 5.6, first independent 4.4. */
    static void reloadArcs(Canvas canvas, WeaponUnit unit, float x, float y) {
        reloadArc(canvas, unit.currentWeapon(), x, y, 7f, 1.2f);
        reloadArc(canvas, unit.currentSecondaryWeapon(), x, y, 5.6f, 1f);
        if (!unit.independentWeapons.isEmpty()) {
            reloadArc(canvas, unit.independentWeapons.get(0), x, y, 4.4f, 0.8f);
        }
    }

    /** Weapons without reload data (torpedo tubes, launchers fed elsewhere) draw nothing. */
    static void reloadArc(Canvas canvas, AbstractVehicleWeapon<?> weapon, float x, float y, float r, float thickness) {
        if (weapon == null || weapon.getData().getReload() == null) {
            return;
        }
        int reload = weapon.getReloadTime();
        int max = weapon.getData().getReload().getTime();
        if (reload > 0 && max > 0) {
            float done = 1f - (float) reload / max;
            CaDraw.arc(canvas, x, y, r, thickness, 0f, 1f, CaDraw.RELOAD_ARC_BG);
            CaDraw.arc(canvas, x, y, r, thickness, 0f, done, CaDraw.RELOAD_ARC);
        }
    }

    /** Free-aim circle (half alpha, quarter while free-looking) and reload rings. Hidden in scope view. */
    public record AimReticle(String color) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<WeaponUnit> station = binder.subject(HudSubjects.STATION);
            ColorProp colour = binder.color(color);
            return new Mark() {
                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    WeaponUnit unit = station.get(ev);
                    if (unit == null || !AimPoint.shown()) {
                        return;
                    }
                    float ax = ev.fromHost((float) AimPoint.x(ev.partialTick));
                    float ay = ev.fromHost((float) AimPoint.y(ev.partialTick));
                    int argb = CaDraw.withAlpha(colour.get(ev), FREE_CAMERA.isDown() ? 0.25f : 0.5f);
                    CaDraw.circle(ev.canvas, ax, ay, 8, 0.05f, argb);
                    reloadArcs(ev.canvas, unit, ax, ay);
                }
            };
        }
    }

    /**
     * Impact point of the current weapon (and every full-salvo partner) in its {@code crosshair_style};
     * CCIP marks green. IR/RF seeker rings while the seeker is on.
     */
    public record ImpactMarkers(String color) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<WeaponUnit> station = binder.subject(HudSubjects.STATION);
            ColorProp colour = binder.color(color);
            return new Mark() {
                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    WeaponUnit unit = station.get(ev);
                    AbstractVehicleWeapon<?> weapon = unit == null ? null : unit.currentWeapon();
                    if (weapon == null) {
                        return;
                    }
                    int argb = colour.get(ev);
                    impact(ev, unit, weapon, argb);
                    if (unit.getFiringMode() == WeaponUnitData.FiringMode.FULL_SALVO) {
                        List<AbstractVehicleWeapon<?>> salvo = unit.fullSalvoWeapons;
                        for (int i = 0; i < salvo.size(); i++) {
                            impact(ev, unit, salvo.get(i), argb);
                        }
                    }
                    seeker(ev, unit, weapon, argb);
                }

                private void impact(UiEval ev, WeaponUnit unit, AbstractVehicleWeapon<?> weapon, int argb) {
                    WeaponUnit aiming = weapon.getWeaponUnit();
                    WeaponUnitData.CrosshairStyle style = aiming.crosshairStyle;
                    if (style == null) {
                        return;
                    }
                    WeaponUnitData.FireControlSensorType sensor = aiming.getFireControlSensorType();
                    if (aiming.isParentWeaponUnitAim()
                            || sensor == WeaponUnitData.FireControlSensorType.CCIP && aiming.currentWeapon() == null) {
                        aiming = unit;
                    }
                    if (aiming.weaponHitPos == null || aiming.weaponHitPosO == null
                            || !project(ev, aiming.weaponHitPos, aiming.weaponHitPosO)) {
                        return;
                    }
                    int markColour = sensor == WeaponUnitData.FireControlSensorType.CCIP ? CaDraw.GREEN : argb;
                    Canvas canvas = ev.canvas;
                    switch (style) {
                        case CIRCLE -> CaDraw.circle(canvas, at[0], at[1], 3, 0.05f, markColour);
                        case SQUARE -> CaDraw.square(canvas, at[0], at[1], 5, markColour);
                        case CROSS -> CaDraw.crossDiagonal(canvas, at[0], at[1], 8, 1, markColour);
                        case BIG_CROSS -> CaDraw.cross(canvas, at[0], at[1], 16, markColour);
                        default -> {
                        }
                    }
                }

                private void seeker(UiEval ev, WeaponUnit unit, AbstractVehicleWeapon<?> weapon, int argb) {
                    WeaponUnitData.FireControlSensorType sensor = unit.getFireControlSensorType();
                    boolean ir = sensor == WeaponUnitData.FireControlSensorType.IR;
                    boolean rf = sensor == WeaponUnitData.FireControlSensorType.RF;
                    if (!(ir || rf) || !unit.isSeekerOn()) {
                        return;
                    }
                    WeaponUnit current = weapon.getWeaponUnit();
                    if (current.weaponHitPos == null || current.weaponHitPosO == null
                            || !project(ev, current.weaponHitPos, current.weaponHitPosO)) {
                        return;
                    }
                    float alpha = 0.4f + (float) Math.sin((double) unit.getLockCoolingTick() / 5 * Math.PI) * 0.2f;
                    boolean locked = unit.getLockedEntity() != null;
                    int ring = CaDraw.withAlpha(argb & (locked ? 0x00FF0000 : 0x00FFFFFF), alpha);
                    Canvas canvas = ev.canvas;
                    if (!locked) {
                        if (ir) {
                            CaDraw.circle(canvas, at[0], at[1], 15, 0.03f, ring);
                        } else {
                            CaDraw.circle(canvas, at[0], at[1], 5, 0.05f, ring);
                            CaDraw.circle(canvas, at[0], at[1], 4, 0.06f, ring);
                        }
                    }
                    CaDraw.circle(canvas, at[0], at[1], 2.8f * current.getSeekerFov(), 0.01f, ring);
                }
            };
        }
    }

    /** Locked target (seeker ring / EO box) and radar contacts with range and closing rate. */
    public record LockMarkers() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<WeaponUnit> station = binder.subject(HudSubjects.STATION);
            SubjectRef<AbstractVehicle> vehicleRef = binder.subject(HudSubjects.VEHICLE);
            return new Mark() {
                private final TextBuffer text = new TextBuffer(24);

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    WeaponUnit unit = station.get(ev);
                    AbstractVehicle vehicle = vehicleRef.get(ev);
                    if (unit == null || vehicle == null) {
                        return;
                    }
                    Canvas canvas = ev.canvas;
                    WeaponUnitData.FireControlSensorType sensor = unit.getFireControlSensorType();
                    Entity locked = unit.getLockedEntity();
                    if (locked != null && projectCentre(ev, locked)) {
                        if (unit.isSeekerOn()) {
                            if (sensor == WeaponUnitData.FireControlSensorType.IR) {
                                CaDraw.circle(canvas, at[0], at[1], 15, 0.03f, CaDraw.RED);
                            } else if (sensor == WeaponUnitData.FireControlSensorType.RF) {
                                CaDraw.circle(canvas, at[0], at[1], 5, 0.05f, CaDraw.RED);
                                CaDraw.circle(canvas, at[0], at[1], 4, 0.06f, CaDraw.RED);
                            }
                        }
                        if (sensor == WeaponUnitData.FireControlSensorType.EO) {
                            CaDraw.square(canvas, at[0], at[1], 15, CaDraw.GREEN);
                        }
                    }
                    RadarUnit radar = unit.getMainRadarUnit();
                    if (radar == null) {
                        return;
                    }
                    Entity radarLock = radar.getLockedEntity();
                    if (sensor == WeaponUnitData.FireControlSensorType.RF && radarLock != null) {
                        RadarUnit.DetectedObject object = radar.getDetectedEntities().get(radarLock.getId());
                        if (object != null && project(ev, object.entity.getBoundingBox().getCenter())) {
                            CaDraw.square(canvas, at[0], at[1], 15, CaDraw.GREEN);
                            CaDraw.square(canvas, at[0], at[1], 10, CaDraw.GREEN);
                            allies(canvas, object, at[0], at[1]);
                            info(canvas, vehicle, object, at[0], at[1]);
                        }
                    }
                    List<RadarUnit> radars = unit.getRadarUnits();
                    for (int r = 0; r < radars.size(); r++) {
                        for (RadarUnit.DetectedObject object : radars.get(r).getDetectedEntities().values()) {
                            if (radarLock != null && object.entity.getId() == radarLock.getId()) {
                                continue;
                            }
                            if (!project(ev, object.detectedPosition)) {
                                continue;
                            }
                            if (object.entity instanceof Projectile || object.entity instanceof RocketEntity) {
                                CaDraw.squareCorners(canvas, at[0], at[1], 10, 3, CaDraw.GREEN);
                            } else {
                                CaDraw.squareCorners(canvas, at[0], at[1], 15, 5, CaDraw.GREEN);
                                allies(canvas, object, at[0], at[1]);
                                info(canvas, vehicle, object, at[0], at[1]);
                            }
                        }
                    }
                }

                private boolean projectCentre(UiEval ev, Entity entity) {
                    float partialTick = ev.partialTick;
                    AABB box = entity.getBoundingBox();
                    double cx = Mth.lerp(partialTick, entity.xo, entity.getX()) + (box.minX + box.maxX) * 0.5 - entity.getX();
                    double cy = Mth.lerp(partialTick, entity.yo, entity.getY()) + (box.minY + box.maxY) * 0.5 - entity.getY();
                    double cz = Mth.lerp(partialTick, entity.zo, entity.getZ()) + (box.minZ + box.maxZ) * 0.5 - entity.getZ();
                    return project(ev, cx, cy, cz);
                }

                private void allies(Canvas canvas, RadarUnit.DetectedObject object, float px, float py) {
                    Team team = object.entity.getTeam();
                    if (team != null && team.isAlliedTo(LocalVehiclePlayer.instance.getPlayer().getTeam())) {
                        Integer colour = team.getColor().getColor();
                        canvas.fill(px - 6, py - 11, px + 7, py - 10, colour == null ? CaDraw.GREEN : 0xFF000000 | colour);
                    }
                }

                private void info(Canvas canvas, AbstractVehicle vehicle, RadarUnit.DetectedObject object, float px, float py) {
                    Vec3 velocity = object.entity.getDeltaMovement();
                    CaDraw.square(canvas, px, py + 12, 4, CaDraw.GREEN);
                    double speed = velocity.length();
                    if (speed != 0) {
                        Vec3 direction = vehicle.relativeRotDirection(velocity.scale(-8 / speed), true);
                        canvas.line(px + 0.5f, py + 12, px + 0.5f + (float) direction.x, py + 12 + (float) direction.z, 1, CaDraw.GREEN);
                    }
                    boolean small = object.entity instanceof Projectile || object.entity instanceof RocketEntity;
                    float tx = px + (small ? 8 : 12);
                    float ty = py + (small ? 12 : -12);
                    canvas.push().translate(tx, ty).scale(0.8f);
                    Vec3 position = vehicle.position();
                    text.clear().append(object.detectedPosition.distanceTo(position), 2).append(" m");
                    canvas.text(text, 0, 0, CaDraw.GREEN, false);
                    Vec3 approach = velocity.subtract(vehicle.getDeltaMovement());
                    double dot = approach.x * (object.entity.getX() - position.x) + approach.y * (object.entity.getY() - position.y)
                            + approach.z * (object.entity.getZ() - position.z);
                    text.clear().append((dot > 0 ? -1 : 1) * approach.length() * 20, 2).append(" m/s");
                    canvas.text(text, 0, 12, CaDraw.GREEN, false);
                    canvas.pop();
                }
            };
        }
    }
}
