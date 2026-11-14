package com.wf.combinedassault.radar.client;

import com.wf.combinedassault.hud.CaDraw;
import com.wf.combinedassault.hud.HudSubjects;
import com.wf.combinedassault.radar.RadarUnit;
import com.wf.combinedassault.radar.WarningReceiver;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.pojo.WarnType;
import com.wf.combinedassault.vehicle.screen.MfdView;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.recon.api.ContactClass;
import com.wf.wflib.recon.api.ScopeFrame;
import com.wf.wflib.recon.api.TrackQuality;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.missile.api.MissileEntity;
import com.wf.wflib.ui.api.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;

import java.util.List;
import java.util.Map;

/** Radar PPI scopes and the radar warning receiver; drawn about the box origin, size 0. */
public final class RadarWidgets {
    private static final int SECTOR = 0x44FFFFFF;
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
            canvas.textCentred(text, 0, 0, CaDraw.WHITE, true);
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
                                    0.4f, CaDraw.WHITE);
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
                                canvas.dashed(cx, y, tx, ty, 0.5f, 3, 1, CaDraw.WHITE);
                            }
                        }
                        cx -= (float) (radius * Math.sin(max) * 1.3f);
                    }
                }

                private void target(Canvas canvas, float tx, float ty, RadarUnit.DetectedObject object, RadarUnit radar) {
                    canvas.fill(tx - 1, ty - 1, tx + 1, ty + 1, CaDraw.WHITE);
                    if (!(object.entity instanceof MissileEntity)) {
                        canvas.fill(tx - 3, ty - 2, tx - 2, ty + 2, CaDraw.WHITE);
                        canvas.fill(tx + 2, ty - 2, tx + 3, ty + 2, CaDraw.WHITE);
                        Team team = object.entity.getTeam();
                        if (team != null && team.isAlliedTo(LocalVehiclePlayer.instance().getPlayer().getTeam())) {
                            canvas.fill(tx - 3, ty - 4, tx + 3, ty - 3, CaDraw.WHITE);
                        }
                    }
                    Vec3 velocity = object.entity.getDeltaMovement();
                    double speed = velocity.length();
                    if (speed != 0) {
                        Vec3 tail = radar.worldVecToLocalVec(velocity.scale(-5 / speed));
                        canvas.line(tx, ty, tx + (float) tail.x, ty + (float) tail.z, 0.8f, CaDraw.WHITE);
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
                    canvas.textCentred("RWR", 0, 0, CaDraw.WHITE, true);
                    canvas.pop();
                    CaDraw.circle(canvas, x, y, 3, 0.1f, CaDraw.WHITE);
                    CaDraw.circle(canvas, x, y, 25, 0.01f, CaDraw.WHITE);
                    CaDraw.circle(canvas, x, y, 20, 0.01f, CaDraw.WHITE);
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
                            canvas.dashed(x, y, x + dx * reach, y + dz * reach, 0.8f, 3, 1, CaDraw.WHITE);
                        }
                        int colour = CaDraw.WHITE;
                        if (target.warnType() == WarnType.RADAR_SEARCH) {
                            float age = (float) (System.currentTimeMillis() - target.receivedTime());
                            colour = CaDraw.withAlpha(CaDraw.WHITE, Math.max(4 / 255f, 1.0f - Math.min(1000f, age) / 1000f));
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

    /**
     * Expanded scope, heading up: graticule and range rings at the {@link RadarExpanded} range, every powered
     * radar's sector and fading sweep, tracks (radar, data link), lock line, RWR emitters on the rim. Drawn about
     * the box origin, size 0; the hull schematic is placed over the centre by the layout.
     * Sectors and sweeps are drawn from the nose, ignoring the radar's mount yaw.
     */
    public record FullScope() implements WidgetDef {
        private static final float R = 100;
        private static final String[] CARDINAL = {"N", "E", "S", "W"};
        private static final int GREEN = 0xFF6BFF7D;
        private static final int GRID = 0x802E9E4C;
        private static final int SEISMIC = 0xFFFFA640;
        private static final int CORROBORATED = 0xFFFFD24A;
        private static final int FADE_STEPS = 8;
        private static final float FADE_SPAN = (float) Math.toRadians(40);

        @Override
        public Widget create(Binder binder) {
            SubjectRef<AbstractVehicle> vehicleRef = binder.subject(HudSubjects.VEHICLE);
            return new Plain() {
                private MfdView view;

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    AbstractVehicle vehicle = vehicleRef.get(ev);
                    if (vehicle == null) {
                        return;
                    }
                    if (view == null || view.vehicle() != vehicle) {
                        view = new MfdView(vehicle);
                    }
                    view.refresh(ev.partialTick);
                    Canvas c = ev.canvas;
                    int range = RadarExpanded.range();
                    float scale = R / range;
                    double heading = view.heading(ev.partialTick);
                    c.disc(x, y, R, 0x55000000);
                    c.pushClip(x - R - 14, y - R - 14, x + R + 14, y + R + 24);
                    for (int i = 1; i <= 4; i++) {
                        c.ring(x, y, R * i / 4, i == 4 ? 0.7f : 0.4f, GRID);
                    }
                    for (int k = 0; k < 12; k++) {
                        double a = Math.toRadians(k * 30 - heading);
                        float s = (float) Math.sin(a);
                        float co = (float) -Math.cos(a);
                        c.line(x, y, x + s * R, y + co * R, 0.3f, GRID);
                        if (k % 3 == 0) {
                            c.push().translate(x + s * (R + 9), y + co * (R + 9)).scale(0.8f);
                            c.textCentred(CARDINAL[k / 3], 0, -4, GREEN, false);
                            c.pop();
                        }
                    }
                    scopes(c, vehicle, x, y, ev.partialTick);
                    ScopeFrame frame = RadarExpanded.frame();
                    if (frame != null) {
                        network(c, frame, vehicle, x, y, scale, heading);
                    }
                    double hr = Math.toRadians(heading);
                    double sin = Math.sin(hr);
                    double cos = Math.cos(hr);
                    for (int i = 0; i < view.tracks; i++) {
                        MfdView.Track t = view.track(i);
                        float px = x + (float) (((t.x - vehicle.getX()) * cos - -(t.z - vehicle.getZ()) * sin) * scale);
                        float py = y - (float) (((t.x - vehicle.getX()) * sin + -(t.z - vehicle.getZ()) * cos) * scale);
                        if ((px - x) * (px - x) + (py - y) * (py - y) > R * R) {
                            continue;
                        }
                        int col = switch (t.kind) {
                            case FRIEND -> 0xFF46B4FF;
                            case HOSTILE, MISSILE -> 0xFFFF5A3C;
                            case UNKNOWN -> GREEN;
                        };
                        switch (t.kind) {
                            case FRIEND -> c.ring(px, py, 2.4f, 0.7f, col);
                            case HOSTILE -> c.quad(px, py - 3, px + 3, py, px, py + 3, px - 3, py, col);
                            case UNKNOWN -> c.frame(px - 2.2f, py - 2.2f, px + 2.2f, py + 2.2f, 0.7f, col);
                            case MISSILE -> {
                                c.line(px - 2, py - 2, px + 2, py + 2, 0.7f, col);
                                c.line(px - 2, py + 2, px + 2, py - 2, 0.7f, col);
                            }
                        }
                        double speed = Math.hypot(t.vx, t.vz);
                        if (speed > 1e-3) {
                            double ve = t.vx / speed;
                            double vn = -t.vz / speed;
                            c.line(px, py, px + (float) ((ve * cos - vn * sin) * 7), py - (float) ((ve * sin + vn * cos) * 7), 0.5f, col);
                        }
                        if (t.locked) {
                            c.frame(px - 4.5f, py - 4.5f, px + 4.5f, py + 4.5f, 0.6f, CaDraw.AMBER);
                            c.dashed(x, y, px, py, 0.5f, 3, 2, CaDraw.AMBER);
                        }
                    }
                    long now = System.currentTimeMillis();
                    boolean blink = (now / 250 & 1) == 0;
                    for (int i = 0; i < view.emitters; i++) {
                        MfdView.Emitter e = view.emitter(i);
                        double a = Math.toRadians(e.bearing);
                        float s = (float) Math.sin(a);
                        float co = (float) -Math.cos(a);
                        float er = R * 0.9f;
                        int col = switch (e.type) {
                            case MISSILE_LAUNCH -> blink ? 0xFFFF5A3C : 0xFF801810;
                            case RADAR_LOCK -> CaDraw.AMBER;
                            default -> CaDraw.withAlpha(GREEN, Math.max(0.15f, 1 - (now - e.received) / 1000f));
                        };
                        if (e.type != WarnType.RADAR_SEARCH) {
                            c.dashed(x + s * er * 0.2f, y + co * er * 0.2f, x + s * (er - 6), y + co * (er - 6), 0.6f, 3, 2, col);
                        }
                        c.frame(x + s * er - 5, y + co * er - 5, x + s * er + 5, y + co * er + 5, 0.6f, col);
                        c.push().translate(x + s * er, y + co * er).scale(0.75f);
                        c.textCentred(e.label, 0, -4, col, false);
                        c.pop();
                    }
                    c.popClip();
                    text.clear().append("RADAR ").append(range, 1).append(" m");
                    c.push().translate(x, y + R + 18).scale(1.2f);
                    c.textCentred(text, 0, -4, GREEN, true);
                    c.pop();
                    ScopeFrame net = RadarExpanded.frame();
                    c.push().translate(x, y + R + 30).scale(0.8f);
                    if (net == null) {
                        c.textCentred("NO NET LINK", 0, -4, 0xFF808080, false);
                    } else {
                        text.clear().append("NET ").append(net.blips().size(), 1).append(" TRK ")
                                .append(net.events().size(), 1).append(" SEIS");
                        c.textCentred(text, 0, -4, GREEN, false);
                    }
                    c.pop();
                }


                /** Network contacts: size = track error, fade = age x quality, tag = bands holding it; seismic fixes amber. */
                private void network(Canvas c, ScopeFrame frame, AbstractVehicle vehicle, float x, float y, float scale,
                                     double heading) {
                    double hr = Math.toRadians(heading);
                    double sin = Math.sin(hr);
                    double cos = Math.cos(hr);
                    List<ScopeFrame.Event> events = frame.events();
                    for (int i = 0; i < events.size(); i++) {
                        ScopeFrame.Event e = events.get(i);
                        double dx = e.x() - vehicle.getX();
                        double dn = -(e.z() - vehicle.getZ());
                        float px = x + (float) ((dx * cos - dn * sin) * scale);
                        float py = y - (float) ((dx * sin + dn * cos) * scale);
                        if ((px - x) * (px - x) + (py - y) * (py - y) > R * R) {
                            continue;
                        }
                        float fresh = 1.0f - Math.min(1.0f, e.ageSeconds() / 300.0f);
                        int col = CaDraw.withAlpha(SEISMIC, 0.25f + 0.75f * fresh);
                        float r = Math.min(R * 0.5f, Math.max(2.5f, e.error() * scale));
                        CaDraw.circle(c, px, py, r, 0.4f, col);
                        c.line(px - 3, py - 3, px + 3, py + 3, 0.6f, col);
                        c.line(px - 3, py + 3, px + 3, py - 3, 0.6f, col);
                    }
                    List<ScopeFrame.Blip> blips = frame.blips();
                    for (int i = 0; i < blips.size(); i++) {
                        ScopeFrame.Blip b = blips.get(i);
                        double dx = b.x() - vehicle.getX();
                        double dn = -(b.z() - vehicle.getZ());
                        float px = x + (float) ((dx * cos - dn * sin) * scale);
                        float py = y - (float) ((dx * sin + dn * cos) * scale);
                        if ((px - x) * (px - x) + (py - y) * (py - y) > R * R) {
                            continue;
                        }
                        int base = switch (b.iffValue()) {
                            case FRIENDLY -> 0xFF46B4FF;
                            case HOSTILE -> 0xFFFF5A3C;
                            case UNKNOWN -> b.kindValue() == ContactClass.UNKNOWN ? GREEN : 0xFFFF5A3C;
                        };
                        float freshness = 1.0f - Math.min(1.0f, b.age() / 100.0f);
                        float weight = b.qualityValue() == TrackQuality.FIRM ? 1.0f
                                : b.qualityValue() == TrackQuality.CONFIRMED ? 0.75f : 0.45f;
                        float alpha = Math.max(0.15f, freshness * weight);
                        float r = Math.min(R * 0.3f, Math.max(1.5f, b.error() * scale));
                        c.disc(px, py, r, CaDraw.withAlpha(base, alpha * 0.35f));
                        if (r > 2.5f) {
                            CaDraw.circle(c, px, py, r, 0.4f, CaDraw.withAlpha(base, Math.min(1, alpha + 0.4f)));
                        }
                        c.disc(px, py, 1.2f, CaDraw.withAlpha(base, alpha));
                        if (b.corroborated()) {
                            c.frame(px - r - 1.5f, py - r - 1.5f, px + r + 1.5f, py + r + 1.5f, 0.5f, CORROBORATED);
                        }
                        if (b.count() > 1) {
                            text.clear().append('x').append(b.count(), 1);
                            c.push().translate(px + r + 2, py - 3).scale(0.6f);
                            c.text(text, 0, 0, base, false);
                            c.pop();
                        }
                    }
                }

                private void scopes(Canvas c, AbstractVehicle vehicle, float x, float y, float partialTick) {
                    List<PartUnit<?>> parts = vehicle.getPartUnits();
                    for (int p = 0; p < parts.size(); p++) {
                        if (!(parts.get(p) instanceof WeaponUnit weapon)) {
                            continue;
                        }
                        List<RadarUnit> radars = weapon.getRadarUnits();
                        for (int i = 0; i < radars.size(); i++) {
                            RadarUnit radar = radars.get(i);
                            if (!radar.isOn()) {
                                continue;
                            }
                            double min = radar.getYRotMin();
                            double max = radar.getYRotMax();
                            if (max - min >= 360) {
                                min = 0;
                                max = 360;
                            }
                            float reach = (float) Math.min(R, radar.getMaxScanDistance() * R / RadarExpanded.range());
                            c.sector(x, y, reach, (float) Math.toRadians(-max - 90), (float) Math.toRadians(max - min), 0x1AFFFFFF);
                            if (radar.getLockedEntity() != null) {
                                continue;
                            }
                            float sweep = (float) Math.toRadians(Mth.lerp(partialTick, radar.yRotO, radar.getYRot()) - 90);
                            for (int s = 0; s < FADE_STEPS; s++) {
                                float alpha = 0.35f * (1 - s / (float) FADE_STEPS);
                                c.sector(x, y, reach, sweep - FADE_SPAN * (s + 1) / FADE_STEPS, FADE_SPAN / FADE_STEPS,
                                        CaDraw.withAlpha(GREEN, alpha));
                            }
                            c.line(x, y, x + (float) Math.cos(sweep) * reach, y + (float) Math.sin(sweep) * reach, 0.6f, GREEN);
                        }
                    }
                }
            };
        }
    }
}
