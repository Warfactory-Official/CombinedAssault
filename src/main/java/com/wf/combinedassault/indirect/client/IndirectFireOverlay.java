package com.wf.combinedassault.indirect.client;

import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import com.wf.combinedassault.indirect.FireMission;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.client.render.util.Color;
import com.wf.combinedassault.indirect.BallisticSolver;
import com.wf.combinedassault.indirect.FireArc;
import com.wf.combinedassault.indirect.FireSolution;
import com.wf.combinedassault.indirect.GunLaying;
import com.wf.combinedassault.indirect.IndirectFire;
import com.wf.combinedassault.indirect.ReachBand;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.WeaponUnit;

import java.util.Locale;

/**
 * HUD only while the mount is off the sight (direct-sighted gunner needs none of this). Each figure drawn
 * against what it must be read against: elevation on a stop-marked quadrant, bearing on a tape carrying
 * the laid mark, range on a scale with the stops' window lit. Board height varies with what it has to show.
 */
@OnlyIn(Dist.CLIENT)
public class IndirectFireOverlay implements LayeredDraw.Layer {

    private static final int MARGIN = 6;
    private static final int PAD = 5;
    private static final int PANEL_W = 136;

    private static final int ROW_TITLE = 0;

    // quadrant: fan from just below horizon to vertical, pivot at its corner.
    private static final int QUAD_TOP = 13;
    private static final int QUAD_R = 34;
    private static final double QUAD_LOW = -10.0;
    private static final double QUAD_HIGH = 90.0;

    private static final int DATA_X = 46;
    private static final int ROW_ELEV = 15;
    private static final int ROW_GUN = 26;
    private static final int ROW_BRG = 37;

    private static final int ROW_STEP = 56;

    private static final int TAPE_TOP = 70;
    private static final int TAPE_H = 13;
    /** Zoom: how much compass fits in the tape window. */
    private static final int TAPE_PX_PER_DEGREE = 2;

    private static final int REACH_ROW = 88;
    private static final int REACH_BAR = 99;
    private static final int REACH_BAR_H = 5;
    private static final int REACH_LEGEND = 106;

    private static final int ROW_TARGET = 119;
    private static final int ROW_SOLUTION = 130;
    private static final int ROW_SPLASH = 143;

    private static final int ROW_H = 9;

    /** Legend stands this long after off-sight, then fades over the last {@link #LEGEND_FADE} of it. */
    private static final long LEGEND_MILLIS = 9000;
    private static final long LEGEND_FADE = 2000;
    /** Shorter stand time when only announcing a just-made change. */
    private static final long LEGEND_AGAIN = 3500;

    private static final int GREEN = Color.GREEN;
    private static final int AMBER = Color.AMMO_WARNING;
    /** Fixed labels; shouldn't compete with the figures beside them. */
    private static final int LABEL = 0x9966FF99;
    /** Scale marks + range ends. */
    private static final int SCALE = 0x8800CC44;
    /** Empty part of a bar; what's filled reads against it. */
    private static final int TRACK = 0x66003311;
    /** Usable part of a range. */
    private static final int BAND = 0xAA22AA55;
    /** Darker than the ship console's shade: that alpha left bindings unreadable over the gun's dark body. */
    private static final int SHADE = 0x55000000;

    // built once: a per-frame board should not rebuild/retranslate the same components every frame.
    private static final Component TITLE = Component.translatable("ui.indirect.title");
    private static final Component ELEVATION = Component.translatable("ui.indirect.elevation");
    private static final Component GUN = Component.translatable("ui.indirect.gun");
    private static final Component BEARING = Component.translatable("ui.indirect.bearing");
    private static final Component REACH = Component.translatable("ui.indirect.reach");
    private static final Component TARGET = Component.translatable("ui.indirect.target");

    /** Cached: costs a pair of searches, doesn't change between shots. */
    @Nullable
    private ReachBand band;
    @Nullable
    private RoundSpec bandSpec;
    private double bandLow;
    private double bandHigh;
    @Nullable
    private FireArc bandArc;

    @Nullable
    private RoundSpec rangeSpec;
    private double rangeElevation = Double.NaN;
    private double cachedRange;

    private long legendSince;
    private boolean standing;
    /** Degrees-rung vs blocks-rung: crossing over changes what the 4 laying keys do. */
    private boolean correcting;

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (minecraft.options.hideGui || local == null || !local.onVehicle()) {
            standDown();
            return;
        }
        WeaponUnit unit = local.getWeaponUnit();
        if (unit == null || !unit.indirectFire().isIndirect()) {
            standDown();
            return;
        }
        if (!standing) {
            standing = true;
            correcting = unit.indirectFire().step().inBlocks();
            legendSince = Util.getMillis();
        }
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        double now = minecraft.level == null ? 0 : minecraft.level.getGameTime() + partialTick;
        graphics.drawManaged(() -> renderBoard(graphics, minecraft.font, unit, partialTick, now));
    }

    private void standDown() {
        standing = false;
        ShotClock.clear();
    }

    private void renderBoard(GuiGraphics graphics, Font font, WeaponUnit unit, float partialTick, double now) {
        GunLaying laying = unit.indirectFire();
        int x = MARGIN + PAD;
        int y = MARGIN + PAD;
        int right = x + PANEL_W;

        // interpolated, not tick, rotation: a needle built from the tick value would stand still then jump.
        Vec2 pointing = unit.worldRot(unit.getViewXRot(partialTick), unit.getViewYRot(partialTick));
        double gunElevation = -pointing.x;
        double gunBearing = pointing.y;
        double laidElevation = laying.elevation();
        double laidBearing = laying.azimuth();

        // gunner's sign (up = positive) flips xRot: ceiling is the smaller xRot.
        double stopLow = -unit.getXRotMax();
        double stopHigh = -unit.getXRotMin();
        boolean withinStops = laidElevation >= stopLow - 1.0E-4 && laidElevation <= stopHigh + 1.0E-4;
        double error = laying.error(gunElevation, gunBearing);
        int stopColour = withinStops ? GREEN : AMBER;
        int gunColour = !withinStops ? AMBER : error > IndirectFire.LAID_TOLERANCE ? GREEN : LABEL;

        // crossing degrees<->blocks changes what the 4 laying keys do: re-show the legend briefly.
        if (correcting != laying.step().inBlocks()) {
            correcting = laying.step().inBlocks();
            legendSince = Util.getMillis() - (LEGEND_MILLIS - LEGEND_AGAIN);
        }

        RoundSpec spec = IndirectFire.specOf(unit);
        FireSolution solution = laying.solution();
        Vec3 target = laying.target();
        int inFlight = ShotClock.prune(now);

        // in-flight rounds show even behind a fire mission that isn't there.
        boolean mission = solution != null && target != null;
        int splashRow = mission ? ROW_SPLASH : ROW_TARGET;
        int bottom = spec == null ? ROW_STEP + ROW_H
                : inFlight > 0 ? splashRow + ROW_H
                : mission ? ROW_SOLUTION + ROW_H
                : REACH_LEGEND + ROW_H;
        graphics.fill(x - PAD, y - PAD, right + PAD, y + bottom, SHADE);
        graphics.fill(x - PAD, y - PAD, x - PAD + 1, y + bottom, LABEL);

        graphics.drawString(font, TITLE, x, y + ROW_TITLE, LABEL, false);
        Component laid = Component.translatable("ui.indirect.laying." + laying.mode().name().toLowerCase(Locale.ROOT));
        graphics.drawString(font, IndirectFire.selfGuided(unit) ? laid : laid.copy().append(" ").append(
                        Component.translatable("ui.indirect.mode",
                                Component.translatable("ui.indirect.arc." + laying.arc().id()))),
                x + font.width(TITLE) + 5, y + ROW_TITLE, GREEN);
        state(graphics, font, right, y + ROW_TITLE, withinStops, error);

        quadrant(graphics, x + 2, y + QUAD_TOP + QUAD_R, stopLow, stopHigh,
                gunElevation, laidElevation, stopColour);

        label(graphics, font, x + DATA_X, y + ROW_ELEV, ELEVATION);
        rightAligned(graphics, font, right, y + ROW_ELEV, degrees(laidElevation), stopColour);
        label(graphics, font, x + DATA_X, y + ROW_GUN, GUN);
        rightAligned(graphics, font, right, y + ROW_GUN, degrees(gunElevation), gunColour);
        label(graphics, font, x + DATA_X, y + ROW_BRG, BEARING);
        rightAligned(graphics, font, right, y + ROW_BRG, bearing(laidBearing), GREEN);

        stepPips(graphics, font, right, y + ROW_STEP, laying, spec != null);
        // tape mark always green: no traverse stops exist (unlike elevation, which can hit a stop).
        bearingTape(graphics, font, x, y + TAPE_TOP, PANEL_W, compass(gunBearing), compass(laidBearing),
                GREEN);

        int legendY = y + bottom + 4;
        FireMission ordered = unit.fireMission();
        if (ordered != null) {
            graphics.drawString(font, Component.translatable("ui.indirect.fire_mission",
                    ordered.round() == Items.AIR ? Component.empty() : ordered.round().getDescription(),
                    ordered.remaining()), x, legendY, AMBER, true);
            legendY += ROW_H + 1;
        }
        if (spec == null) {
            legend(graphics, font, x, legendY, laying.step().inBlocks());
            return;
        }
        // clamped to the mount's stops: the scale answers where the round actually lands, not what was asked for.
        reach(graphics, font, x, y, right, spec, laying,
                Mth.clamp(laidElevation, stopLow, stopHigh), withinStops, stopLow, stopHigh);

        if (mission) {
            label(graphics, font, x, y + ROW_TARGET, TARGET);
            rightAligned(graphics, font, right, y + ROW_TARGET,
                    Mth.floor(target.x) + " " + Mth.floor(target.y) + " " + Mth.floor(target.z), GREEN);
            mission(graphics, font, x, y + ROW_SOLUTION, right, solution);
        }
        if (inFlight > 0) {
            splash(graphics, font, x, y + splashRow, right, now, inFlight);
        }
        legend(graphics, font, x, legendY, laying.step().inBlocks());
    }

    /** Laid angle drawn as a mark outside the rim, not a second needle: order and barrel never overlap. */
    private static void quadrant(GuiGraphics graphics, int cx, int cy,
                                 double stopLow, double stopHigh, double gun, double laid, int laidColour) {
        for (double mark = QUAD_LOW; mark <= QUAD_HIGH; mark += 5) {
            dot(graphics, cx, cy, mark, QUAD_R, 1, SCALE);
        }
        for (int mark = 0; mark <= 90; mark += 15) {
            ray(graphics, cx, cy, mark, QUAD_R - 3, QUAD_R, 1, mark % 45 == 0 ? LABEL : SCALE);
        }
        double low = Math.max(stopLow, QUAD_LOW);
        double high = Math.min(stopHigh, QUAD_HIGH);
        if (high > low) {
            rim(graphics, cx, cy, low, high, QUAD_R + 3, 3, BAND);
        }
        ray(graphics, cx, cy, Mth.clamp(gun, QUAD_LOW, QUAD_HIGH), 3, QUAD_R - 2, 2, GREEN);
        ray(graphics, cx, cy, Mth.clamp(laid, QUAD_LOW, QUAD_HIGH), QUAD_R + 1, QUAD_R + 6, 2, laidColour);
    }

    private static void rim(GuiGraphics graphics, int cx, int cy,
                            double from, double to, double radius, int size, int colour) {
        int steps = Math.max(1, (int) Math.ceil(Math.toRadians(to - from) * radius / (size * 0.7)));
        for (int i = 0; i <= steps; i++) {
            dot(graphics, cx, cy, from + (to - from) * i / steps, radius, size, colour);
        }
    }

    private static void ray(GuiGraphics graphics, int cx, int cy,
                            double degrees, double from, double to, int size, int colour) {
        int steps = Math.max(1, (int) Math.ceil((to - from) / (size * 0.7)));
        for (int i = 0; i <= steps; i++) {
            dot(graphics, cx, cy, degrees, from + (to - from) * i / steps, size, colour);
        }
    }

    private static void dot(GuiGraphics graphics, int cx, int cy,
                            double degrees, double radius, int size, int colour) {
        double radians = Math.toRadians(degrees);
        int px = (int) Math.round(cx + Math.cos(radians) * radius);
        int py = (int) Math.round(cy - Math.sin(radians) * radius);
        graphics.fill(px, py, px + size, py + size, colour);
    }

    /**
     * Tape moves with the gun; lubber line is the barrel. Laid mark slides to centre as traverse finishes,
     * so direction and completion read without arithmetic on two numbers.
     */
    private static void bearingTape(GuiGraphics graphics, Font font, int x, int y, int width,
                                    double gun, double laid, int markColour) {
        int middle = x + width / 2;
        int bottom = y + TAPE_H;
        graphics.fill(x, y, x + width, bottom, TRACK);

        double span = width / 2.0 / TAPE_PX_PER_DEGREE + 5;
        int first = Mth.floor((gun - span) / 5.0) * 5;
        for (int mark = first; mark <= gun + span; mark += 5) {
            int at = middle + (int) Math.round((mark - gun) * TAPE_PX_PER_DEGREE);
            if (at < x || at >= x + width) {
                continue;
            }
            int degrees = Math.floorMod(mark, 360);
            String legend = cardinal(degrees);
            if (legend == null && degrees % 10 == 0) {
                legend = String.format(Locale.ROOT, "%02d", degrees / 10);
            }
            if (legend == null) {
                graphics.fill(at, y + 1, at + 1, y + 4, SCALE);
                continue;
            }
            graphics.fill(at, y + 1, at + 1, y + 6, LABEL);
            int half = font.width(legend) / 2;
            if (at - half >= x && at + half <= x + width) {
                graphics.drawString(font, legend, at - half, y + 6, LABEL, false);
            }
        }

        double offset = Mth.wrapDegrees(laid - gun);
        int at = middle + (int) Math.round(offset * TAPE_PX_PER_DEGREE);
        if (at >= x + 2 && at <= x + width - 2) {
            for (int row = 0; row < 4; row++) {
                graphics.fill(at - 3 + row, y - 4 + row, at + 4 - row, y - 3 + row, markColour);
            }
            graphics.fill(at, y, at + 1, bottom, (markColour & 0x00FFFFFF) | 0x77000000);
        } else {
            int middleRow = y + TAPE_H / 2;
            for (int row = 0; row < 5; row++) {
                int px = offset < 0 ? x + 1 + row : x + width - 2 - row;
                graphics.fill(px, middleRow - row, px + 1, middleRow + row + 1, markColour);
            }
            String togo = String.valueOf(Math.round(Math.abs(offset)));
            int togoX = offset < 0 ? x + 8 : x + width - 8 - font.width(togo);
            graphics.drawString(font, togo, togoX, y + 3, markColour, false);
        }

        graphics.fill(middle, y - 5, middle + 1, y + 4, GREEN);
        graphics.fill(middle - 2, y - 5, middle + 3, y - 4, GREEN);
    }

    @Nullable
    private static String cardinal(int degrees) {
        return switch (degrees) {
            case 0 -> "N";
            case 90 -> "E";
            case 180 -> "S";
            case 270 -> "W";
            default -> null;
        };
    }

    /** Shipped 81mm bipod floor (55 deg) gives up ~1/4 of the tube's reach; near end costs a minimum range too. */
    private void reach(GuiGraphics graphics, Font font, int x, int y, int right, RoundSpec spec,
                       GunLaying laying, double firingElevation, boolean withinStops,
                       double stopLow, double stopHigh) {
        ReachBand window = band(spec, stopLow, stopHigh, laying.arc());
        double laid = laidRange(spec, firingElevation);
        int width = right - x;

        label(graphics, font, x, y + REACH_ROW, REACH);
        rightAligned(graphics, font, right, y + REACH_ROW,
                Component.translatable("ui.indirect.metres", (int) Math.round(laid)),
                window.covered() && withinStops ? GREEN : AMBER);

        int top = y + REACH_BAR;
        int barBottom = top + REACH_BAR_H;
        graphics.fill(x, top, right, barBottom, TRACK);
        for (int quarter = 1; quarter < 4; quarter++) {
            int at = x + quarter * width / 4;
            graphics.fill(at, top, at + 1, barBottom, SCALE);
        }
        if (!window.covered()) {
            graphics.drawString(font, Component.translatable("ui.indirect.arc_blocked",
                            Component.translatable("ui.indirect.arc." + laying.arc().id())),
                    x, y + REACH_LEGEND, AMBER, false);
            return;
        }

        double scale = window.maxRange() <= 0 ? 0 : width / window.maxRange();
        int near = x + (int) Math.round(Math.min(window.nearest(), window.maxRange()) * scale);
        int far = x + (int) Math.round(Math.min(window.farthest(), window.maxRange()) * scale);
        graphics.fill(near, top, Math.max(far, near + 1), barBottom, 0x99117733);
        int mark = Mth.clamp(x + (int) Math.round(laid * scale), x, right - 1);
        graphics.fill(mark, top - 2, mark + 1, barBottom + 2, GREEN);

        // labels under their bar positions; dropped when too crowded to tell apart.
        String nearest = String.valueOf((int) Math.round(window.nearest()));
        String farthest = String.valueOf((int) Math.round(window.farthest()));
        String reach = String.valueOf((int) Math.round(window.maxRange()));
        int nearX = Math.max(x, near - font.width(nearest) / 2);
        int farX = Math.min(right - font.width(farthest), far - font.width(farthest) / 2);
        graphics.drawString(font, nearest, nearX, y + REACH_LEGEND, SCALE, false);
        if (farX > nearX + font.width(nearest) + 3) {
            graphics.drawString(font, farthest, farX, y + REACH_LEGEND, SCALE, false);
        }
        int reachX = right - font.width(reach);
        if (reachX > farX + font.width(farthest) + 3) {
            graphics.drawString(font, reach, reachX, y + REACH_LEGEND, LABEL, false);
        }
    }

    /** Flight time + apex height only: range is on the scale above, drawn against mount coverage. */
    private static void mission(GuiGraphics graphics, Font font, int x, int y, int right,
                                FireSolution solution) {
        graphics.drawString(font, Component.translatable("ui.indirect.flight",
                String.format(Locale.ROOT, "%.1f", solution.flightSeconds())), x, y, GREEN, false);
        rightAligned(graphics, font, right, y,
                Component.translatable("ui.indirect.apex", (int) Math.round(solution.apexHeight())), LABEL);
    }

    /** Rounds in flight: the one thing on the board the gun can't change now. */
    private static void splash(GuiGraphics graphics, Font font, int x, int y, int right,
                               double now, int inFlight) {
        double left = ShotClock.nextDue(now);
        boolean landed = left <= 0;
        boolean flash = (Util.getMillis() / 200) % 2 == 0;
        int colour = landed ? (flash ? Color.WHITE : AMBER) : AMBER;
        Component text = landed
                ? Component.translatable("ui.indirect.splash_now")
                : Component.translatable("ui.indirect.splash", String.format(Locale.ROOT, "%.1f", left / 20.0));
        graphics.fill(x - 2, y - 2, right + 2, y + ROW_H + 1, 0x33000000);
        graphics.drawString(font, text, x, y, colour);
        if (inFlight > 1) {
            rightAligned(graphics, font, right, y,
                    Component.translatable("ui.indirect.in_flight", inFlight), LABEL);
        }
    }

    /** Laid / laying / stopped: the one "can I fire now" answer, so the one thing placed in a corner. */
    private static void state(GuiGraphics graphics, Font font, int right, int y,
                              boolean withinStops, double error) {
        String key = !withinStops ? "ui.indirect.state.stop"
                : error <= IndirectFire.LAID_TOLERANCE ? "ui.indirect.state.laid"
                : "ui.indirect.state.laying";
        int colour = !withinStops ? AMBER : error <= IndirectFire.LAID_TOLERANCE ? GREEN : LABEL;
        Component text = Component.translatable(key);
        int x = right - font.width(text);
        graphics.fill(x - 3, y - 2, right + 2, y + ROW_H + 1, (colour & 0x00FFFFFF) | 0x22000000);
        graphics.drawString(font, text, x, y, colour);
    }

    /** Blocks rungs shown spent (unusable) when the gun has no spec the solver understands. */
    private static void stepPips(GuiGraphics graphics, Font font, int right, int y,
                                 GunLaying laying, boolean canCorrect) {
        int at = right;
        for (int i = GunLaying.STEPS.length - 1; i >= 0; i--) {
            GunLaying.Step rung = GunLaying.STEPS[i];
            boolean live = i == laying.stepIndex();
            boolean usable = canCorrect || !rung.inBlocks();
            String text = trim(rung.size()) + (rung.inBlocks() ? "m" : "\u00B0");
            int width = font.width(text) + 6;
            at -= width;
            graphics.fill(at, y - 2, at + width - 2, y + ROW_H, live ? 0x66007733 : TRACK);
            graphics.drawString(font, text, at + 3, y, !usable ? SCALE : live ? GREEN : LABEL, false);
            at -= 2;
        }
    }

    /** Read off live key mappings, not hardcoded: a rebound key still shows correctly. */
    private void legend(GuiGraphics graphics, Font font, int x, int y, boolean inBlocks) {
        long shown = Util.getMillis() - legendSince;
        if (shown > LEGEND_MILLIS) {
            return;
        }
        float fade = shown < LEGEND_MILLIS - LEGEND_FADE ? 1.0f
                : (LEGEND_MILLIS - shown) / (float) LEGEND_FADE;
        int colour = (((int) (0x99 * fade)) << 24) | 0x66FF99;
        String first = key(IndirectFireKeys.ELEVATION_UP) + "/" + key(IndirectFireKeys.ELEVATION_DOWN) + " "
                + Component.translatable(inBlocks ? "ui.indirect.keys.correct"
                        : "ui.indirect.keys.elevate").getString() + "   "
                + key(IndirectFireKeys.TRAVERSE_LEFT) + " " + key(IndirectFireKeys.TRAVERSE_RIGHT) + " "
                + Component.translatable(inBlocks ? "ui.indirect.keys.shift"
                        : "ui.indirect.keys.traverse").getString();
        String second = key(IndirectFireKeys.CYCLE_STEP) + " "
                + Component.translatable("ui.indirect.keys.step").getString() + "   "
                + key(IndirectFireKeys.TOGGLE_ARC) + " "
                + Component.translatable("ui.indirect.keys.arc").getString() + "   "
                + key(IndirectFireKeys.TOGGLE_INDIRECT) + " "
                + Component.translatable("ui.indirect.keys.direct").getString();
        int width = Math.max(font.width(first), font.width(second));
        graphics.fill(x - PAD, y - 2, x + width + PAD, y + 20, (((int) (0x33 * fade)) << 24));
        graphics.drawString(font, first, x, y, colour, false);
        graphics.drawString(font, second, x, y + 10, colour, false);
    }

    private static String key(KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }

    private ReachBand band(RoundSpec spec, double lowest, double highest, FireArc arc) {
        if (band == null || !spec.equals(bandSpec) || lowest != bandLow || highest != bandHigh
                || arc != bandArc) {
            bandSpec = spec;
            bandLow = lowest;
            bandHigh = highest;
            bandArc = arc;
            band = ReachBand.of(spec, lowest, highest, arc);
        }
        return band;
    }

    /** Where the round lands on level ground at the figure the gun is laid to. One search, per adjustment. */
    private double laidRange(RoundSpec spec, double elevation) {
        if (!spec.equals(rangeSpec) || elevation != rangeElevation) {
            rangeSpec = spec;
            rangeElevation = elevation;
            cachedRange = BallisticSolver.groundRange(spec, elevation);
        }
        return cachedRange;
    }

    private static void label(GuiGraphics graphics, Font font, int x, int y, Component text) {
        graphics.drawString(font, text, x, y, LABEL, false);
    }

    private static void rightAligned(GuiGraphics graphics, Font font, int right, int y,
                                     Component text, int colour) {
        graphics.drawString(font, text, right - font.width(text), y, colour);
    }

    private static void rightAligned(GuiGraphics graphics, Font font, int right, int y,
                                     String text, int colour) {
        graphics.drawString(font, text, right - font.width(text), y, colour);
    }

    private static String degrees(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Bearings are shown the way the ship's compass shows a course, so north is 000 on both. */
    private static String bearing(double azimuth) {
        return String.format(Locale.ROOT, "%03d", Math.round(compass(azimuth)) % 360);
    }

    private static double compass(double azimuth) {
        return Mth.positiveModulo(azimuth + 180.0, 360.0);
    }

    private static String trim(double step) {
        return step >= 1 ? String.valueOf((int) step) : String.format(Locale.ROOT, "%.1f", step).substring(1);
    }

}
