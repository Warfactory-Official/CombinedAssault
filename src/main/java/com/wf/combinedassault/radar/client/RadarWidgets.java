package com.wf.combinedassault.radar.client;

import com.wf.combinedassault.hud.CaDraw;
import com.wf.combinedassault.hud.HudSubjects;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.radar.RadarUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.radar.WarningReceiver;
import com.wf.combinedassault.vehicle.pojo.WarnType;
import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.missile.api.MissileEntity;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.SubjectRef;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;

import java.util.List;
import java.util.Map;

/** Radar PPI scopes and the radar warning receiver; drawn about the box origin, size 0. */
public final class RadarWidgets {
    private static final int SECTOR = 0x4433FF33;
    private static final int DIM = 0x33000000;

    private RadarWidgets() {
    }

    abstract static class Plain extends Widget {
        final TextBuffer text = new TextBuffer(32);

        @Override
        public void measure(UiEval ev) {
        }

        /** Text on a circle of radius r at a bearing in degrees (0 = up, positive = anticlockwise), centred, 0.8x. */
        void bearingText(Canvas canvas, float cx, float cy, float r, float degrees) {
            float rad = -(float) Math.toRadians(degrees + 90);
            canvas.push().translate(cx + (float) Math.cos(rad) * r, cy + (float) Math.sin(rad) * r).scale(0.8f);
            canvas.textCentred(text, 0, 0, CaDraw.GREEN, true);
            canvas.pop();
        }
    }

    /** One scope per powered, visible radar of the station: sector, sweep, contacts with heading tails. */
    public record RadarScopes() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<WeaponUnit> station = binder.subject(HudSubjects.STATION);
            return new Plain() {
                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    WeaponUnit unit = station.get(ev);
                    if (unit == null) {
                        return;
                    }
                    List<RadarUnit> radars = unit.getRadarUnits();
                    if (radars.isEmpty()) {
                        return;
                    }
                    Canvas canvas = ev.canvas;
                    int count = radars.size();
                    float radius = count == 1 ? 50.0f : 50.0f / count / 0.6f;
                    float cx = x - (count - 1) * 32;
                    for (int i = 0; i < count; i++) {
                        RadarUnit radar = radars.get(i);
                        if (radar.isUiHide() || !radar.isOn()) {
                            continue;
                        }
                        double range = radar.getMaxScanDistance();
                        double min = radar.getYRotMin();
                        double max = radar.getYRotMax();
                        if (max - min >= 360) {
                            min = 0;
                            max = 360;
                        } else {
                            text.clear().append(min, 1).append('°');
                            bearingText(canvas, cx - 8, y, radius + 8, (float) -min);
                            text.clear().append(max, 1).append('°');
                            bearingText(canvas, cx + 8, y, radius + 8, (float) -max);
                        }
                        // canvas angles: 0 = +x clockwise; radar bearing 0 = up, increasing anticlockwise.
                        float start = (float) Math.toRadians(-max - 90);
                        canvas.sector(cx, y, radius, start, (float) Math.toRadians(max - min), SECTOR);
                        text.clear().append(range, 1).append(" m");
                        bearingText(canvas, cx, y, radius + 8, 0);
                        if (radar.getLockedEntity() == null) {
                            double sweep = Math.toRadians(Mth.lerp(ev.partialTick, radar.yRotO, radar.getYRot()) - 90);
                            canvas.line(cx, y, cx + (float) Math.cos(sweep) * radius, y + (float) Math.sin(sweep) * radius,
                                    0.4f, CaDraw.GREEN);
                        }
                        Entity locked = radar.getLockedEntity();
                        Vec3 origin = radar.worldRadarPosition();
                        for (Map.Entry<Integer, RadarUnit.DetectedObject> entry : radar.getDetectedEntities().entrySet()) {
                            RadarUnit.DetectedObject object = entry.getValue();
                            Vec3 local = radar.worldVecToLocalVec(object.detectedPosition.subtract(origin));
                            double length = local.length();
                            float scale = length == 0 ? 0 : (float) (-(length / range * radius) / length);
                            float tx = cx + (float) local.x * scale;
                            float ty = y + (float) local.z * scale;
                            target(canvas, tx, ty, object, radar);
                            if (locked != null && locked == object.entity) {
                                canvas.dashed(cx, y, tx, ty, 0.5f, 3, 1, CaDraw.GREEN);
                            }
                        }
                        cx -= (float) (radius * Math.sin(max) * 1.3f);
                    }
                }

                private void target(Canvas canvas, float tx, float ty, RadarUnit.DetectedObject object, RadarUnit radar) {
                    canvas.fill(tx - 1, ty - 1, tx + 1, ty + 1, CaDraw.GREEN);
                    if (!(object.entity instanceof MissileEntity)) {
                        canvas.fill(tx - 3, ty - 2, tx - 2, ty + 2, CaDraw.GREEN);
                        canvas.fill(tx + 2, ty - 2, tx + 3, ty + 2, CaDraw.GREEN);
                        Team team = object.entity.getTeam();
                        if (team != null && team.isAlliedTo(LocalVehiclePlayer.instance().getPlayer().getTeam())) {
                            canvas.fill(tx - 3, ty - 4, tx + 3, ty - 3, CaDraw.GREEN);
                        }
                    }
                    Vec3 velocity = object.entity.getDeltaMovement();
                    double speed = velocity.length();
                    if (speed != 0) {
                        Vec3 tail = radar.worldVecToLocalVec(velocity.scale(-5 / speed));
                        canvas.line(tx, ty, tx + (float) tail.x, ty + (float) tail.z, 0.8f, CaDraw.GREEN);
                    }
                }
            };
        }
    }

    /**
     * Radar warning receiver: rings, a bearing line per lock/launch, emitter labels at their bearing
     * (search returns fade over 1 s).
     */
    public record Rwr() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<AbstractVehicle> vehicleRef = binder.subject(HudSubjects.VEHICLE);
            return new Plain() {
                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    AbstractVehicle vehicle = vehicleRef.get(ev);
                    WarningReceiver receiver = vehicle == null ? null : vehicle.warningReceiver;
                    if (receiver == null) {
                        return;
                    }
                    Canvas canvas = ev.canvas;
                    canvas.push().translate(x, y - 40).scale(0.8f);
                    canvas.textCentred("RWR", 0, 0, CaDraw.GREEN, true);
                    canvas.pop();
                    CaDraw.circle(canvas, x, y, 3, 0.1f, CaDraw.GREEN);
                    CaDraw.circle(canvas, x, y, 25, 0.01f, CaDraw.GREEN);
                    CaDraw.circle(canvas, x, y, 20, 0.01f, CaDraw.GREEN);
                    canvas.disc(x, y, 25, DIM);
                    if (receiver.targets.isEmpty()) {
                        return;
                    }
                    LocalVehiclePlayer local = LocalVehiclePlayer.instance();
                    for (Map.Entry<Integer, WarningReceiver.WarnTarget> entry : receiver.targets.entrySet()) {
                        Entity entity = local.getPlayer().level().getEntity(entry.getKey());
                        if (entity == null) {
                            LocalVehiclePlayer.ServerEntity remote = local.serverEntities.get(entry.getKey());
                            if (remote == null || remote.entity == null) {
                                continue;
                            }
                            entity = remote.entity;
                        }
                        WarningReceiver.WarnTarget target = entry.getValue();
                        double distance = entity.position().distanceTo(vehicle.position());
                        double scale = Math.min(1, distance / 512);
                        Vec3 direction = vehicle.relativeRotDirection(entity.position().subtract(vehicle.position()), true);
                        double length = direction.length();
                        if (length == 0) {
                            continue;
                        }
                        float dx = (float) (direction.x / length);
                        float dz = (float) (direction.z / length);
                        if (target.warnType() == WarnType.RADAR_LOCK || target.warnType() == WarnType.MISSILE_LAUNCH) {
                            float reach = (float) (scale * -25);
                            canvas.dashed(x, y, x + dx * reach, y + dz * reach, 0.8f, 3, 1, CaDraw.GREEN);
                        }
                        int colour = CaDraw.GREEN;
                        if (target.warnType() == WarnType.RADAR_SEARCH) {
                            float age = (float) (System.currentTimeMillis() - target.receivedTime());
                            colour = CaDraw.withAlpha(CaDraw.GREEN, Math.max(4 / 255f, 1.0f - Math.min(1000f, age) / 1000f));
                        }
                        float reach = (float) (scale * 28);
                        canvas.push().translate(x + dx * reach, y + dz * reach).scale(0.6f);
                        String info = target.info();
                        canvas.textCentred(info == null || info.isEmpty() ? "?" : info, 0, 0, colour, true);
                        canvas.pop();
                    }
                }
            };
        }
    }
}
