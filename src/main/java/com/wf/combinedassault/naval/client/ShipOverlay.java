package com.wf.combinedassault.naval.client;

import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import com.wf.combinedassault.client.render.util.Color;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.ShipVehicle;
import com.wf.combinedassault.naval.ShipHydrodynamics;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;

import java.util.Locale;

/**
 * Bridge console: engine telegraph, rudder angle, log, course tape, echo sounder (surface) or depth
 * gauge (submarine).
 *
 * <p>Synced hull values arrive in steps (entity update interval, not frame) => a needle built from one
 * stands still then jumps. Each value lagged, tau in ticks not frames, so instruments settle at the
 * same rate regardless of frame rate; the number beside a needle is the lagged value too, not a
 * disagreeing raw reading.
 */
public class ShipOverlay implements LayeredDraw.Layer {

    /** Block/tick to knots: 20 ticks/s * 1.9438 kn per block/s. */
    private static final double KNOTS_PER_BLOCK_PER_TICK = 20.0 * 1.9438;

    /** Panel offset from screen edge. */
    private static final int MARGIN = 6;

    private static final int PANEL_W = 116;
    private static final int PANEL_H = 97;

    // Each row: label, value, instrument, in that order.
    private static final int ROW_ENGINE = 0;
    private static final int BAR_ENGINE = 12;
    private static final int ROW_SPEED = 24;
    private static final int BAR_SPEED = 35;
    private static final int ROW_RUDDER = 44;
    private static final int BAR_RUDDER = 56;
    private static final int ROW_COURSE = 69;
    private static final int TAPE_TOP = 81;
    private static final int TAPE_H = 14;

    /** Px/degree on the course tape; sets how much compass fits the window. */
    private static final int TAPE_PX_PER_DEGREE = 2;

    private static final int GAUGE_W = 16;
    private static final int GAUGE_H = 60;
    private static final int GAUGE_TOP = 11;
    /** Side room for lettering, wider than the gauge strip. */
    private static final int GAUGE_PAD = 26;

    private static final int GREEN = Color.GREEN;
    private static final int AMBER = Color.AMMO_WARNING;
    private static final int RED = Color.RED;
    /** Fixed lettering colour; muted so it doesn't compete with values beside it. */
    private static final int LABEL = 0x9966FF99;
    /** Scale marks and the ends of a range. */
    private static final int SCALE = 0x8800CC44;
    /** Empty bar fill; gives the filled length something to read against. */
    private static final int TRACK = 0x66003311;
    /** The part of a range a submarine is past its rating in. */
    private static final int TRACK_WARN = 0x77553300;
    /** Enough of a ground for green lettering to survive sunlit water. */
    private static final int SHADE = 0x33000000;

    private static final String[] AHEAD = {
            "ui.combinedassault.helm.ahead.dead_slow", "ui.combinedassault.helm.ahead.slow",
            "ui.combinedassault.helm.ahead.half", "ui.combinedassault.helm.ahead.full"};
    private static final String[] ASTERN = {
            "ui.combinedassault.helm.astern.dead_slow", "ui.combinedassault.helm.astern.slow",
            "ui.combinedassault.helm.astern.half", "ui.combinedassault.helm.astern.full"};

    /** Hull id the lagged readings below belong to; boarding another hull must not sweep a needle. */
    private int readingsFor = -1;
    private float telegraph;
    private float rudder;
    private float knots;
    private float clearance;
    private float depth;

    /**
     * ~60 fills + ~12 strings; unbatched, each ends its own batch: 114us/frame vs 75us in one managed
     * batch. Course-tape scissor still breaks the batch there (enabling a scissor flushes a managed
     * batch itself).
     */
    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (minecraft.options.hideGui || local == null || !local.onVehicle()) {
            readingsFor = -1;
            return;
        }
        AbstractVehicle vehicle = local.vehicle;
        if (!(vehicle instanceof ShipVehicle ship) || !local.getPlayer().equals(ship.getDriver())) {
            readingsFor = -1;
            return;
        }
        // Post-pause/load frame = many ticks; don't let it sweep a needle.
        track(ship, Mth.clamp(deltaTracker.getRealtimeDeltaTicks(), 0.0f, 4.0f));
        graphics.drawManaged(() -> renderHelm(graphics, minecraft.font, ship,
                deltaTracker.getGameTimeDeltaPartialTick(true)));
    }

    private void renderHelm(GuiGraphics graphics, Font font, ShipVehicle ship, float partialTick) {
        // Top corners, clear of the bow: bottom-left = crew list, bottom-right = weapon card + radar.
        int gaugeX = graphics.guiWidth() - MARGIN - GAUGE_PAD - GAUGE_W;
        renderConsole(graphics, font, MARGIN + 5, MARGIN + 5, ship, partialTick);
        if (ship.isSubmarine()) {
            renderDepthGauge(graphics, font, gaugeX, MARGIN + 5, ship);
        } else {
            renderSounder(graphics, font, gaugeX, MARGIN + 5, ship);
        }
        // Alerts centred: a warning in a corner goes unseen.
        renderWarnings(graphics, font, graphics.guiWidth() / 2, graphics.guiHeight() / 2, ship);
    }

    // ------------------------------------------------------------------ the console

    private void renderConsole(GuiGraphics graphics, Font font, int x, int y,
                               ShipVehicle ship, float partialTick) {
        int right = x + PANEL_W;
        graphics.fill(x - 5, y - 5, right + 5, y + PANEL_H, SHADE);
        graphics.fill(x - 5, y - 5, x - 4, y + PANEL_H, LABEL);

        ShipHydrodynamics.Params params = ship.params();
        boolean submerged = ship.isSubmarine() && ship.depth() > 0.5f;
        double topSpeed = submerged ? params.maxSpeedSubmerged : params.maxSpeedForward;

        // Engine order + the lever reading it.
        label(graphics, font, x, y + ROW_ENGINE, "ui.combinedassault.helm.engine");
        Component order = order(telegraph);
        int orderColour = telegraph < -0.05f ? AMBER : GREEN;
        graphics.drawString(font, order, right - font.width(order), y + ROW_ENGINE, orderColour);
        bipolar(graphics, x, y + BAR_ENGINE, PANEL_W, 7, telegraph, orderColour);

        // Speed made good vs hull's max, ordered speed on the same scale.
        label(graphics, font, x, y + ROW_SPEED, "ui.combinedassault.helm.speed");
        Component log = Component.translatable("ui.combinedassault.helm.knots",
                String.format(Locale.ROOT, "%.1f", knots));
        graphics.drawString(font, log, right - font.width(log), y + ROW_SPEED, GREEN);
        float maxKnots = (float) (topSpeed * KNOTS_PER_BLOCK_PER_TICK);
        float ordered = (float) (Math.abs(telegraph)
                * (telegraph < 0 ? params.maxSpeedBackward : topSpeed) * KNOTS_PER_BLOCK_PER_TICK);
        unipolar(graphics, x, y + BAR_SPEED, PANEL_W, 4,
                maxKnots <= 0 ? 0 : knots / maxKnots, maxKnots <= 0 ? 0 : ordered / maxKnots);

        // Rudder: the one control whose sign must read instantly.
        label(graphics, font, x, y + ROW_RUDDER, "ui.combinedassault.helm.rudder");
        int wheel = Math.round(rudder * 100);
        Component helm = wheel > 0
                ? Component.translatable("ui.combinedassault.helm.starboard", wheel)
                : wheel < 0
                ? Component.translatable("ui.combinedassault.helm.port", -wheel)
                : Component.translatable("ui.combinedassault.helm.midships");
        graphics.drawString(font, helm, right - font.width(helm), y + ROW_RUDDER, GREEN);
        bipolar(graphics, x, y + BAR_RUDDER, PANEL_W, 7, rudder, GREEN);

        // Tape not a number: a number can't show a swing starting.
        float course = Mth.positiveModulo(
                Mth.rotLerp(partialTick, ship.yRotO, ship.getYRot()) + 180.0f, 360.0f);
        label(graphics, font, x, y + ROW_COURSE, "ui.combinedassault.helm.course");
        String bearing = String.format(Locale.ROOT, "%03d", Math.round(course) % 360);
        graphics.drawString(font, bearing, right - font.width(bearing), y + ROW_COURSE, GREEN);
        courseTape(graphics, font, x, y + TAPE_TOP, PANEL_W, course);
    }

    private static void label(GuiGraphics graphics, Font font, int x, int y, String key) {
        graphics.drawString(font, Component.translatable(key), x, y, LABEL, false);
    }

    /** Bidirectional bar from a middle mark; for a lever whose sign is half its reading. */
    private static void bipolar(GuiGraphics graphics, int x, int y, int width, int height,
                                float value, int colour) {
        int middle = x + width / 2;
        graphics.fill(x, y, x + width, y + height, TRACK);
        for (int quarter = 1; quarter < 4; quarter++) {
            int offset = quarter * (width / 2) / 4;
            graphics.fill(middle + offset, y, middle + offset + 1, y + height, SCALE);
            graphics.fill(middle - offset - 1, y, middle - offset, y + height, SCALE);
        }
        int end = middle + Math.round(Mth.clamp(value, -1, 1) * (width / 2f));
        if (end != middle) {
            graphics.fill(Math.min(middle, end), y + 1, Math.max(middle, end), y + height - 1, colour);
        }
        // Middle mark drawn over the fill: lever always reads against a fixed zero.
        graphics.fill(middle - 1, y - 2, middle + 1, y + height + 2, GREEN);
    }

    /**
     * One-way bar; ordered value marked on the same scale. Hull takes ~1 min to work up, so the gap
     * between reading and order is the answer to whether it's still gathering way.
     */
    private static void unipolar(GuiGraphics graphics, int x, int y, int width, int height,
                                 float fraction, float ordered) {
        graphics.fill(x, y, x + width, y + height, TRACK);
        for (int quarter = 1; quarter < 4; quarter++) {
            int at = x + quarter * width / 4;
            graphics.fill(at, y, at + 1, y + height, SCALE);
        }
        int end = x + Math.round(Mth.clamp(fraction, 0, 1) * width);
        if (end > x) {
            graphics.fill(x, y, end, y + height, GREEN);
        }
        int mark = Math.min(x + Math.round(Mth.clamp(ordered, 0, 1) * width), x + width - 1);
        graphics.fill(mark, y - 2, mark + 1, y + height + 2, LABEL);
    }

    /** The compass laid out flat under a fixed lubber line, running the way the bow swings. */
    private static void courseTape(GuiGraphics graphics, Font font, int x, int y, int width, float course) {
        int middle = x + width / 2;
        int bottom = y + TAPE_H;
        graphics.fill(x, y, x + width, bottom, TRACK);
        int span = width / 2 / TAPE_PX_PER_DEGREE + 5;
        graphics.enableScissor(x, y, x + width, bottom);
        int first = Mth.floor((course - span) / 5.0f) * 5;
        for (int mark = first; mark <= course + span; mark += 5) {
            int at = middle + Math.round((mark - course) * TAPE_PX_PER_DEGREE);
            int degrees = Math.floorMod(mark, 360);
            String legend = cardinal(degrees);
            if (legend == null && degrees % 10 == 0) {
                legend = String.format(Locale.ROOT, "%02d", degrees / 10);
            }
            if (legend == null) {
                graphics.fill(at, y + 1, at + 1, y + 4, SCALE);
            } else {
                graphics.fill(at, y + 1, at + 1, y + 6, LABEL);
                graphics.drawString(font, legend, at - font.width(legend) / 2, y + 6, LABEL, false);
            }
        }
        graphics.disableScissor();
        // Lubber line drawn only to the ticks: full-height would strike through the legend being read.
        graphics.fill(middle, y - 4, middle + 1, y + 5, GREEN);
        graphics.fill(middle - 2, y - 4, middle + 3, y - 3, GREEN);
    }

    private static String cardinal(int degrees) {
        return switch (degrees) {
            case 0 -> "N";
            case 90 -> "E";
            case 180 -> "S";
            case 270 -> "W";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ what is under the hull

    /**
     * Echo sounder: hull drawn to its draft, seabed rising under it to scale. Keel clearance is the
     * number that decides a passage, hardest to read as digits alone; drawn, the closing gap is the
     * warning.
     */
    private void renderSounder(GuiGraphics graphics, Font font, int x, int y, ShipVehicle ship) {
        int top = y + GAUGE_TOP;
        int bottom = top + GAUGE_H;
        gaugeGround(graphics, font, x, y, bottom, "ui.combinedassault.helm.sounder");
        graphics.fill(x, top, x + GAUGE_W, bottom, TRACK);

        ShipHydrodynamics.Params params = ship.params();
        double draft = Math.max(1.0, params.draft);
        // Scale centres the clearance threshold on the strip.
        double range = Math.max(8.0, params.minKeelClearance * 4.0);
        double perBlock = GAUGE_H / (draft + range);

        // Waterline mark; hull drawn hanging from it.
        int keel = top + (int) Math.round(draft * perBlock);
        graphics.fill(x, top, x + GAUGE_W, top + 1, SCALE);
        graphics.fill(x + 3, top, x + GAUGE_W - 3, keel, LABEL);

        // Shallow-warning threshold mark; a closing reading is seen approaching it.
        int limit = top + (int) Math.round((draft + params.minKeelClearance) * perBlock);
        graphics.fill(x - 3, limit, x + GAUGE_W + 3, limit + 1, AMBER);

        boolean known = !Float.isNaN(clearance);
        int colour = !ship.afloat() || ship.aground() ? RED : ship.shallow() ? AMBER : GREEN;
        if (known) {
            double shown = Math.min(Math.max(clearance, 0), range);
            int seabed = top + (int) Math.round((draft + shown) * perBlock);
            graphics.fill(x, seabed, x + GAUGE_W, seabed + 1, colour);
            int ground = (colour & 0x00FFFFFF) | 0x55000000;
            for (int line = seabed + 3; line < bottom; line += 4) {
                graphics.fill(x, line, x + GAUGE_W, Math.min(line + 1, bottom), ground);
            }
            if (clearance > range) {
                // Past scale bottom: digits are the real reading, the bar only a floor.
                graphics.fill(x + GAUGE_W / 2 - 2, bottom + 2, x + GAUGE_W / 2 + 3, bottom + 3, SCALE);
            }
        }
        String reading = known ? String.format(Locale.ROOT, "%.1f", Math.max(0, clearance))
                : Component.translatable("ui.combinedassault.helm.unknown").getString();
        graphics.drawCenteredString(font, reading, x + GAUGE_W / 2, bottom + 7, known ? colour : SCALE);
    }

    /**
     * Depth gauge: whole dive envelope at once, ordered depth marked on it. A diving boat has one
     * number and two limits that are worthless as digits on the tick they're passed.
     */
    private void renderDepthGauge(GuiGraphics graphics, Font font, int x, int y, ShipVehicle ship) {
        int top = y + GAUGE_TOP;
        int bottom = top + GAUGE_H;
        gaugeGround(graphics, font, x, y, bottom, "ui.combinedassault.helm.depth");

        ShipHydrodynamics.Params params = ship.params();
        double crush = Math.max(1.0, params.crushDepth);
        double perBlock = GAUGE_H / crush;
        int rated = top + (int) Math.round(Math.min(params.maxDepth, crush) * perBlock);
        // Clear band to rated depth; marked band from there to hull's actual range.
        graphics.fill(x, top, x + GAUGE_W, rated, TRACK);
        graphics.fill(x, rated, x + GAUGE_W, bottom, TRACK_WARN);
        graphics.fill(x, top, x + GAUGE_W, top + 1, SCALE);
        graphics.fill(x - 3, rated, x + GAUGE_W + 3, rated + 1, AMBER);
        graphics.fill(x - 3, bottom, x + GAUGE_W + 3, bottom + 1, RED);
        for (int mark = 10; mark < crush; mark += 10) {
            int at = top + (int) Math.round(mark * perBlock);
            graphics.fill(x, at, x + 4, at + 1, SCALE);
        }

        int colour = depth > params.crushDepth ? RED : depth > params.maxDepth ? AMBER : GREEN;
        int ordered = top + (int) Math.round(Mth.clamp(ship.depthSetting() / crush, 0, 1) * GAUGE_H);
        graphics.fill(x + GAUGE_W, ordered - 1, x + GAUGE_W + 4, ordered + 2, LABEL);
        int at = top + (int) Math.round(Mth.clamp(depth / crush, 0, 1) * GAUGE_H);
        graphics.fill(x - 4, at - 1, x + GAUGE_W, at + 2, colour);

        graphics.drawCenteredString(font, String.format(Locale.ROOT, "%.1f", Math.max(0, depth)),
                x + GAUGE_W / 2, bottom + 7, colour);
        graphics.drawCenteredString(font, Component.translatable("ui.combinedassault.helm.ordered",
                        String.format(Locale.ROOT, "%.0f", ship.depthSetting())),
                x + GAUGE_W / 2, bottom + 18, LABEL);
    }

    /** The ground and the heading the two gauges share, sized around the lettering rather than the strip. */
    private static void gaugeGround(GuiGraphics graphics, Font font, int x, int y, int bottom, String key) {
        int left = x - GAUGE_PAD;
        int right = x + GAUGE_W + GAUGE_PAD;
        graphics.fill(left, y - 5, right, bottom + 29, SHADE);
        graphics.fill(right - 1, y - 5, right, bottom + 29, LABEL);
        graphics.drawCenteredString(font, Component.translatable(key), x + GAUGE_W / 2, y, LABEL);
    }

    // ------------------------------------------------------------------ what is going wrong

    /** The two or three things worth taking the helmsman's eye off the bow for, and nothing else. */
    private void renderWarnings(GuiGraphics graphics, Font font, int centerX, int centerY, ShipVehicle ship) {
        boolean flash = (Util.getMillis() / 400) % 2 == 0;
        int y = centerY + 46;
        if (!ship.afloat()) {
            y = warn(graphics, font, centerX, y, "ui.combinedassault.helm.beached", RED, flash);
        } else if (ship.aground()) {
            y = warn(graphics, font, centerX, y, "ui.combinedassault.helm.aground", RED, flash);
        } else if (ship.shallow()) {
            y = warn(graphics, font, centerX, y, "ui.combinedassault.helm.shallow", AMBER, true);
        }
        if (ship.isSubmarine() && ship.depth() > ship.params().crushDepth) {
            y = warn(graphics, font, centerX, y, "ui.combinedassault.helm.crush", RED, flash);
        }
        if (!ship.hasPower()) {
            warn(graphics, font, centerX, y, "ui.combinedassault.helm.no_power", AMBER, true);
        }
    }

    private static int warn(GuiGraphics graphics, Font font, int centerX, int y,
                            String key, int colour, boolean shown) {
        if (shown) {
            Component text = Component.translatable(key);
            int half = font.width(text) / 2;
            graphics.fill(centerX - half - 3, y - 2, centerX + half + 3, y + 10, SHADE);
            graphics.drawString(font, text, centerX - half, y, colour);
        }
        return y + 13;
    }

    // ------------------------------------------------------------------ lagged readings

    private void track(ShipVehicle ship, float deltaTicks) {
        float log = (float) (Math.abs(ship.surge()) * KNOTS_PER_BLOCK_PER_TICK);
        if (readingsFor != ship.getId()) {
            readingsFor = ship.getId();
            telegraph = ship.throttle();
            rudder = ship.rudder();
            knots = log;
            clearance = ship.keelClearance();
            depth = ship.depth();
            return;
        }
        telegraph = approach(telegraph, ship.throttle(), deltaTicks, 2.0f);
        rudder = approach(rudder, ship.rudder(), deltaTicks, 2.0f);
        knots = approach(knots, log, deltaTicks, 4.0f);
        depth = approach(depth, ship.depth(), deltaTicks, 3.0f);
        float keel = ship.keelClearance();
        // Lost bottom reads as lost, not a lagged slide off the last reading.
        clearance = Float.isNaN(keel) || Float.isNaN(clearance) ? keel
                : approach(clearance, keel, deltaTicks, 5.0f);
    }

    /** One frame of a first-order lag whose constant is in ticks, so the frame rate does not set it. */
    private static float approach(float current, float target, float deltaTicks, float ticks) {
        return current + (target - current) * (1.0f - (float) Math.exp(-deltaTicks / ticks));
    }

    private static Component order(float throttle) {
        float magnitude = Math.abs(throttle);
        int step = magnitude > 0.875f ? 3 : magnitude > 0.625f ? 2 : magnitude > 0.375f ? 1
                : magnitude > 0.05f ? 0 : -1;
        if (step < 0) {
            return Component.translatable("ui.combinedassault.helm.stop");
        }
        return Component.translatable(throttle < 0 ? ASTERN[step] : AHEAD[step]);
    }

}
