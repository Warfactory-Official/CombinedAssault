package com.wf.combinedassault.vehicle.screen;

import com.wf.combinedassault.hud.HudSubjects;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.pojo.WarnType;
import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.SubjectRef;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;

/** Aircraft display widgets; each fills its declared {@code w} x {@code h}. */
public final class MfdWidgets {
    static final int FG = 0xFF52F06A;
    static final int DIM = 0xFF1F7A30;
    static final int FRIEND = 0xFF46C8FF;
    static final int HOSTILE = 0xFFFF4A3A;
    static final int UNKNOWN = 0xFFFFD24A;
    static final int AMBER = 0xFFFFB02E;
    static final int SKY = 0xFF1E5AA8;
    static final int GROUND = 0xFF6B4423;
    /** Range ladder, blocks; smallest holding the farthest track (+15 %) wins. */
    private static final int[] RANGES = {1000, 2000, 4000, 8000, 16000};
    private static final String[] CARDINAL = {"N", "E", "S", "W"};
    private static final int[] ROLL_TICKS = {-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60};

    private MfdWidgets() {
    }

    abstract static class Sized extends Widget {
        final float w;
        final float h;
        final TextBuffer text = new TextBuffer(16);

        Sized(float w, float h) {
            this.w = w;
            this.h = h;
        }

        @Override
        public void measure(UiEval ev) {
            width = w;
            height = h;
        }

        void label(Canvas canvas, CharSequence s, float cx, float cy, float scale, int argb) {
            canvas.push().translate(cx, cy).scale(scale);
            canvas.textCentred(s, 0, -4, argb, false);
            canvas.pop();
        }
    }

    /** Heading-up plan: compass rose, range rings, radar / data-link tracks, RWR emitters on the outer ring. */
    public record Situation(float w, float h) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<MfdView> mfd = binder.subject(MfdView.SUBJECT);
            return new Sized(w, h) {
                @Override
                public void draw(UiEval ev, float x, float y, float bw, float bh) {
                    MfdView view = mfd.get(ev);
                    if (view == null) {
                        return;
                    }
                    Canvas c = ev.canvas;
                    AbstractVehicle vehicle = view.vehicle();
                    float cx = x + w / 2;
                    float r = Math.min(w * 0.46f, (h - 13) / 2);
                    float cy = y + 12 + (h - 13) / 2;
                    double heading = view.heading(ev.partialTick);
                    int range = RANGES[RANGES.length - 1];
                    for (int candidate : RANGES) {
                        if (candidate >= view.farthest * 1.15) {
                            range = candidate;
                            break;
                        }
                    }
                    float scale = r / range;
                    c.pushClip(x, y, x + w, y + h);
                    c.ring(cx, cy, r, 0.7f, FG);
                    c.ring(cx, cy, r / 2, 0.4f, DIM);
                    for (int k = 0; k < 36; k++) {
                        double a = Math.toRadians(k * 10 - heading);
                        float s = (float) Math.sin(a);
                        float co = (float) -Math.cos(a);
                        float len = k % 3 == 0 ? 3.5f : 1.8f;
                        c.line(cx + s * r, cy + co * r, cx + s * (r - len), cy + co * (r - len), 0.6f, FG);
                        if (k % 9 == 0) {
                            label(c, CARDINAL[k / 9], cx + s * (r - 9), cy + co * (r - 9), 0.8f, FG);
                        }
                    }
                    // Heading box.
                    c.fill(cx - 12, y + 1, cx + 12, y + 11, 0xFF000000);
                    c.frame(cx - 12, y + 1, cx + 12, y + 11, 0.7f, FG);
                    text.clear().append(Math.round(heading) % 360, 3, '0', false);
                    label(c, text, cx, y + 6, 0.85f, FG);
                    text.clear().append(range / 1000.0, range < 2000 ? 1 : 0).append(" km");
                    c.push().translate(x + 2, y + h - 8).scale(0.8f);
                    c.text(text, 0, 0, FG, false);
                    c.pop();
                    // Own ship.
                    c.line(cx, cy - 4, cx, cy + 4, 0.8f, FG);
                    c.line(cx - 4, cy, cx + 4, cy, 0.8f, FG);
                    c.line(cx - 1.5f, cy + 3.5f, cx + 1.5f, cy + 3.5f, 0.8f, FG);
                    double hr = Math.toRadians(heading);
                    double sin = Math.sin(hr);
                    double cos = Math.cos(hr);
                    for (int i = 0; i < view.tracks; i++) {
                        MfdView.Track t = view.track(i);
                        double dx = t.x - vehicle.getX();
                        double dn = -(t.z - vehicle.getZ());
                        // Rotate world (east, north) into (right, ahead).
                        float px = cx + (float) ((dx * cos - dn * sin) * scale);
                        float py = cy - (float) ((dx * sin + dn * cos) * scale);
                        int col = switch (t.kind) {
                            case FRIEND -> FRIEND;
                            case HOSTILE, MISSILE -> HOSTILE;
                            case UNKNOWN -> UNKNOWN;
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
                            c.line(px, py, px + (float) ((ve * cos - vn * sin) * 6), py - (float) ((ve * sin + vn * cos) * 6), 0.5f, col);
                        }
                        if (t.locked) {
                            c.frame(px - 4.5f, py - 4.5f, px + 4.5f, py + 4.5f, 0.6f, AMBER);
                            c.dashed(cx, cy, px, py, 0.5f, 3, 2, AMBER);
                        }
                    }
                    long now = System.currentTimeMillis();
                    boolean blink = (now / 250 & 1) == 0;
                    for (int i = 0; i < view.emitters; i++) {
                        MfdView.Emitter e = view.emitter(i);
                        double a = Math.toRadians(e.bearing);
                        float s = (float) Math.sin(a);
                        float co = (float) -Math.cos(a);
                        float er = r * 0.82f;
                        int col = switch (e.type) {
                            case MISSILE_LAUNCH -> blink ? HOSTILE : 0xFF801810;
                            case RADAR_LOCK -> AMBER;
                            default -> withAlpha(UNKNOWN, Math.max(0.15f, 1 - (now - e.received) / 1000f));
                        };
                        if (e.type != WarnType.RADAR_SEARCH) {
                            c.dashed(cx + s * er * 0.2f, cy + co * er * 0.2f, cx + s * (er - 5), cy + co * (er - 5), 0.6f, 3, 2, col);
                        }
                        c.frame(cx + s * er - 5, cy + co * er - 5, cx + s * er + 5, cy + co * er + 5, 0.6f, col);
                        label(c, e.label, cx + s * er, cy + co * er, 0.75f, col);
                    }
                    c.popClip();
                }
            };
        }

        private static int withAlpha(int argb, float alpha) {
            return ((int) (alpha * 255) << 24) | (argb & 0xFFFFFF);
        }
    }

    /** Attitude indicator: horizon by pitch / roll, 10-degree ladder, roll scale. */
    public record Adi(float w, float h) implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<AbstractVehicle> vehicleRef = binder.subject(HudSubjects.VEHICLE);
            return new Sized(w, h) {
                @Override
                public void draw(UiEval ev, float x, float y, float bw, float bh) {
                    AbstractVehicle vehicle = vehicleRef.get(ev);
                    if (vehicle == null) {
                        return;
                    }
                    Canvas c = ev.canvas;
                    float cx = x + w / 2;
                    float cy = y + h / 2;
                    float perDegree = h / 50;
                    float pitch = -vehicle.getViewXRot(ev.partialTick);
                    float roll = vehicle.getViewZRot(ev.partialTick);
                    float span = (w + h) * 2;
                    c.pushClip(x, y, x + w, y + h);
                    c.push().translate(cx, cy).rotate((float) Math.toRadians(-roll)).translate(0, pitch * perDegree);
                    c.fill(-span, -span, span, 0, SKY);
                    c.fill(-span, 0, span, span, GROUND);
                    c.line(-span, 0, span, 0, 0.8f, 0xFFFFFFFF);
                    for (int d = -80; d <= 80; d += 10) {
                        if (d == 0) {
                            continue;
                        }
                        float ly = -d * perDegree;
                        float half = d % 20 == 0 ? w * 0.18f : w * 0.1f;
                        if (d > 0) {
                            c.line(-half, ly, half, ly, 0.6f, 0xFFFFFFFF);
                        } else {
                            c.dashed(-half, ly, half, ly, 0.6f, 2, 1.5f, 0xFFFFFFFF);
                        }
                        if (d % 20 == 0) {
                            text.clear().append(Math.abs(d));
                            label(c, text, half + 7, ly, 0.6f, 0xFFFFFFFF);
                        }
                    }
                    c.pop();
                    // Roll scale + pointer.
                    float rr = Math.min(w, h) * 0.42f;
                    for (int a : ROLL_TICKS) {
                        double t = Math.toRadians(a - 90);
                        float len = a % 30 == 0 ? 3 : 1.6f;
                        c.line(cx + (float) Math.cos(t) * rr, cy + (float) Math.sin(t) * rr,
                                cx + (float) Math.cos(t) * (rr + len), cy + (float) Math.sin(t) * (rr + len), 0.6f, 0xFFFFFFFF);
                    }
                    double p = Math.toRadians(-roll - 90);
                    float tx = cx + (float) Math.cos(p) * (rr - 1);
                    float ty = cy + (float) Math.sin(p) * (rr - 1);
                    c.triangle(tx, ty, tx - 2, ty + 3.5f, tx + 2, ty + 3.5f, AMBER);
                    // Fixed aircraft symbol.
                    c.line(cx - w * 0.22f, cy, cx - w * 0.07f, cy, 1.2f, AMBER);
                    c.line(cx + w * 0.07f, cy, cx + w * 0.22f, cy, 1.2f, AMBER);
                    c.fill(cx - 1, cy - 1, cx + 1, cy + 1, AMBER);
                    c.popClip();
                }
            };
        }
    }
}
