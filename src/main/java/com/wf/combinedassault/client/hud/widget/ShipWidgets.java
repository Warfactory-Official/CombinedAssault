package com.wf.combinedassault.client.hud.widget;

import com.wf.combinedassault.client.hud.CaDraw;
import com.wf.combinedassault.client.hud.HudCache;
import com.wf.combinedassault.client.hud.HudSubjects;
import com.wf.combinedassault.entity.vehicle.ShipVehicle;
import com.wf.combinedassault.naval.ShipHydrodynamics;
import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.SubjectRef;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

/**
 * Helm gauges beside the ship console: echo sounder (surface ships) and depth gauge (submarines).
 * Box origin = strip top-left; ground and heading drawn around it. Readings lag like the console.
 */
public final class ShipWidgets {
    static final int GAUGE_W = 16;
    static final int GAUGE_H = 60;
    static final int GAUGE_TOP = 11;
    static final int GAUGE_PAD = 26;
    static final int LABEL = 0x9966FF99;
    static final int SCALE = 0x8800CC44;
    static final int TRACK = 0x66003311;
    static final int TRACK_WARN = 0x77553300;
    static final int SHADE = 0x33000000;

    private ShipWidgets() {
    }

    abstract static class Gauge extends Widget {
        final TextBuffer text = new TextBuffer(24);
        int lagFor = -1;
        float lagged;

        @Override
        public void measure(UiEval ev) {
            width = GAUGE_W + GAUGE_PAD * 2;
            height = GAUGE_TOP + GAUGE_H + 29 + 5;
        }

        /** First-order lag with a constant in ticks; reset on another hull or a lost (NaN) reading. */
        float lag(UiEval ev, ShipVehicle ship, float target, float ticks) {
            float deltaTicks = (float) Math.min(4.0, Math.max(0.0, ev.dt() * 20));
            if (lagFor != ship.getId() || Float.isNaN(target) || Float.isNaN(lagged)) {
                lagFor = ship.getId();
                lagged = target;
            } else {
                lagged += (target - lagged) * (1.0f - (float) Math.exp(-deltaTicks / ticks));
            }
            return lagged;
        }

        void ground(Canvas canvas, float x, float y, float bottom, String key) {
            float left = x - GAUGE_PAD;
            float right = x + GAUGE_W + GAUGE_PAD;
            canvas.fill(left, y - 5, right, bottom + 29, SHADE);
            canvas.fill(right - 1, y - 5, right, bottom + 29, LABEL);
            canvas.textCentred(HudCache.translate(key), x + GAUGE_W / 2f, y, LABEL, true);
        }
    }

    /** Hull hung from the waterline to its draft, seabed rising beneath; threshold mark at minimum clearance. */
    public record Sounder() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<ShipVehicle> shipRef = binder.subject(HudSubjects.SHIP);
            return new Gauge() {
                @Override
                public void draw(UiEval ev, float bx, float by, float w, float h) {
                    ShipVehicle ship = shipRef.get(ev);
                    if (ship == null || ship.isSubmarine()) {
                        return;
                    }
                    float x = bx + GAUGE_PAD;
                    float y = by + 5;
                    float clearance = lag(ev, ship, ship.keelClearance(), 5.0f);
                    Canvas canvas = ev.canvas;
                    float top = y + GAUGE_TOP;
                    float bottom = top + GAUGE_H;
                    ground(canvas, x, y, bottom, "ui.combinedassault.helm.sounder");
                    canvas.fill(x, top, x + GAUGE_W, bottom, TRACK);
                    ShipHydrodynamics.Params params = ship.params();
                    double draft = Math.max(1.0, params.draft);
                    double range = Math.max(8.0, params.minKeelClearance * 4.0);
                    double perBlock = GAUGE_H / (draft + range);
                    float keel = top + Math.round(draft * perBlock);
                    canvas.fill(x, top, x + GAUGE_W, top + 1, SCALE);
                    canvas.fill(x + 3, top, x + GAUGE_W - 3, keel, LABEL);
                    float limit = top + Math.round((draft + params.minKeelClearance) * perBlock);
                    canvas.fill(x - 3, limit, x + GAUGE_W + 3, limit + 1, CaDraw.AMBER);
                    boolean known = !Float.isNaN(clearance);
                    int colour = !ship.afloat() || ship.aground() ? CaDraw.RED : ship.shallow() ? CaDraw.AMBER : CaDraw.GREEN;
                    if (known) {
                        double shown = Math.min(Math.max(clearance, 0), range);
                        float seabed = top + Math.round((draft + shown) * perBlock);
                        canvas.fill(x, seabed, x + GAUGE_W, seabed + 1, colour);
                        int hatch = (colour & 0x00FFFFFF) | 0x55000000;
                        for (float line = seabed + 3; line < bottom; line += 4) {
                            canvas.fill(x, line, x + GAUGE_W, Math.min(line + 1, bottom), hatch);
                        }
                        if (clearance > range) {
                            canvas.fill(x + GAUGE_W / 2f - 2, bottom + 2, x + GAUGE_W / 2f + 3, bottom + 3, SCALE);
                        }
                        text.clear().append(Math.max(0, clearance), 1);
                    } else {
                        text.clear().append(HudCache.translate("ui.combinedassault.helm.unknown"));
                    }
                    canvas.textCentred(text, x + GAUGE_W / 2f, bottom + 7, known ? colour : SCALE, true);
                }
            };
        }
    }

    /** Whole dive envelope: rated band, crush line, 10-block marks, ordered depth tab, depth needle. */
    public record DepthGauge() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            SubjectRef<ShipVehicle> shipRef = binder.subject(HudSubjects.SHIP);
            return new Gauge() {
                @Override
                public void draw(UiEval ev, float bx, float by, float w, float h) {
                    ShipVehicle ship = shipRef.get(ev);
                    if (ship == null || !ship.isSubmarine()) {
                        return;
                    }
                    float x = bx + GAUGE_PAD;
                    float y = by + 5;
                    float depth = lag(ev, ship, ship.depth(), 3.0f);
                    Canvas canvas = ev.canvas;
                    float top = y + GAUGE_TOP;
                    float bottom = top + GAUGE_H;
                    ground(canvas, x, y, bottom, "ui.combinedassault.helm.depth");
                    ShipHydrodynamics.Params params = ship.params();
                    double crush = Math.max(1.0, params.crushDepth);
                    double perBlock = GAUGE_H / crush;
                    float rated = top + Math.round(Math.min(params.maxDepth, crush) * perBlock);
                    canvas.fill(x, top, x + GAUGE_W, rated, TRACK);
                    canvas.fill(x, rated, x + GAUGE_W, bottom, TRACK_WARN);
                    canvas.fill(x, top, x + GAUGE_W, top + 1, SCALE);
                    canvas.fill(x - 3, rated, x + GAUGE_W + 3, rated + 1, CaDraw.AMBER);
                    canvas.fill(x - 3, bottom, x + GAUGE_W + 3, bottom + 1, CaDraw.RED);
                    for (int mark = 10; mark < crush; mark += 10) {
                        float at = top + Math.round(mark * perBlock);
                        canvas.fill(x, at, x + 4, at + 1, SCALE);
                    }
                    int colour = depth > params.crushDepth ? CaDraw.RED : depth > params.maxDepth ? CaDraw.AMBER : CaDraw.GREEN;
                    float ordered = top + Math.round(Math.max(0, Math.min(1, ship.depthSetting() / crush)) * GAUGE_H);
                    canvas.fill(x + GAUGE_W, ordered - 1, x + GAUGE_W + 4, ordered + 2, LABEL);
                    float at = top + Math.round(Math.max(0, Math.min(1, depth / crush)) * GAUGE_H);
                    canvas.fill(x - 4, at - 1, x + GAUGE_W, at + 2, colour);
                    text.clear().append(Math.max(0, depth), 1);
                    canvas.textCentred(text, x + GAUGE_W / 2f, bottom + 7, colour, true);
                    String setFormat = HudCache.translate("ui.combinedassault.helm.ordered");
                    int slot = setFormat.indexOf("%s");
                    text.clear();
                    if (slot >= 0) {
                        text.append(setFormat, 0, slot).append(ship.depthSetting(), 0).append(setFormat, slot + 2, setFormat.length());
                    } else {
                        text.append(setFormat);
                    }
                    canvas.textCentred(text, x + GAUGE_W / 2f, bottom + 18, LABEL, true);
                }
            };
        }
    }
}
