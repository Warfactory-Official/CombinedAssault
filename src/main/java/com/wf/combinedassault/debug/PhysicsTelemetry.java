package com.wf.combinedassault.debug;

import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.physics.api.PhysicsSettings;
import com.wf.wflib.physics.api.PhysicsStep;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Locale;

/**
 * On-screen physics profile, integrated server only (PhysicsStep + tick counters are JVM statics).
 * Costs nothing while off: PhysicsStep stage timers are gated on its stats flag.
 */
public final class PhysicsTelemetry {

    private static final int WINDOW = 40;
    private static final int CENSUS_EVERY = 20;

    private static boolean on;
    private static volatile Report report = Report.EMPTY;

    private static final double[] tickMs = new double[WINDOW];
    private static final double[] stepUs = new double[WINDOW];
    private static final double[][] stageUs = new double[PhysicsStep.STAGES.length][WINDOW];
    private static int cursor;
    private static int filled;
    private static long tickStart;
    private static int islands, bodies, contacts;
    private static boolean async;
    /** lastTick() holds the last stepped tick: resting vehicles step on a heartbeat, the rest would re-read it. */
    private static PhysicsStep.Tick lastSeen;
    private static int vehicles, resting, towed;

    private static double frameMs;
    private static long lastFrame;

    private PhysicsTelemetry() {
    }

    private record Report(double tickAvg, double tickMax, double stepAvg, double stepMax, double[] stageAvg, double[] stageMax,
                          int islands, int bodies, int contacts, boolean async, int vehicles, int resting, int towed,
                          boolean live) {
        static final Report EMPTY = new Report(0, 0, 0, 0, new double[PhysicsStep.STAGES.length],
                new double[PhysicsStep.STAGES.length], 0, 0, 0, false, 0, 0, 0, false);
    }

    public static boolean enabled() {
        return on;
    }

    public static void set(boolean enable) {
        on = enable;
        PhysicsStep.setStats(enable);
        cursor = filled = 0;
        report = Report.EMPTY;
    }

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        if (on) {
            tickStart = System.nanoTime();
        }
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (!on) {
            return;
        }
        tickMs[cursor] = (System.nanoTime() - tickStart) / 1.0e6;
        PhysicsStep.Tick tick = PhysicsStep.lastTick();
        boolean stepped = tick != null && tick != lastSeen && tick.totalNanos() > 0;
        lastSeen = tick;
        stepUs[cursor] = stepped ? tick.totalNanos() / 1000.0 : 0;
        for (int i = 0; i < stageUs.length; i++) {
            stageUs[i][cursor] = stepped ? tick.stageNanos()[i] / 1000.0 : 0;
        }
        if (stepped) {
            islands = tick.islands();
            bodies = tick.bodies();
            contacts = tick.contacts();
            async = tick.async();
        }
        cursor = (cursor + 1) % WINDOW;
        filled = Math.min(filled + 1, WINDOW);
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % CENSUS_EVERY == 0) {
            census(server);
        }
        report = summarise();
    }

    private static void census(MinecraftServer server) {
        int total = 0, still = 0, tow = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof AbstractVehicle vehicle) {
                    total++;
                    still += vehicle.resting() ? 1 : 0;
                }
            }
            tow += TowLinks.towedIn(level).size();
        }
        vehicles = total;
        resting = still;
        towed = tow;
    }

    private static Report summarise() {
        int n = filled;
        double tickSum = 0, tickMax = 0, stepSum = 0, stepMax = 0;
        double[] stageSum = new double[stageUs.length], stageMax = new double[stageUs.length];
        for (int k = 0; k < n; k++) {
            tickSum += tickMs[k];
            tickMax = Math.max(tickMax, tickMs[k]);
            stepSum += stepUs[k];
            stepMax = Math.max(stepMax, stepUs[k]);
            for (int i = 0; i < stageUs.length; i++) {
                stageSum[i] += stageUs[i][k];
                stageMax[i] = Math.max(stageMax[i], stageUs[i][k]);
            }
        }
        for (int i = 0; i < stageSum.length; i++) {
            stageSum[i] /= Math.max(n, 1);
        }
        return new Report(tickSum / Math.max(n, 1), tickMax, stepSum / Math.max(n, 1), stepMax, stageSum, stageMax,
                islands, bodies, contacts, async, vehicles, resting, towed, true);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!on) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrame != 0) {
            frameMs += ((now - lastFrame) / 1.0e6 - frameMs) * 0.05;
        }
        lastFrame = now;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        Report r = report;
        PhysicsSettings settings = PhysicsSettings.current();
        int y = 40;
        int white = 0xFFFFFFFF;
        graphics.drawString(mc.font, String.format(Locale.ROOT, "PHYSICS  frame %.1f ms (%d fps)", frameMs, mc.getFps()), 4, y, white);
        y += 10;
        if (!mc.hasSingleplayerServer() || !r.live()) {
            graphics.drawString(mc.font, mc.hasSingleplayerServer() ? "waiting for a stepped tick" : "integrated server only", 4, y, 0xFFFFAA00);
            return;
        }
        String[] lines = new String[PhysicsStep.STAGES.length + 5];
        int l = 0;
        lines[l++] = String.format(Locale.ROOT, "server tick %.2f avg / %.2f max ms (budget 50)", r.tickAvg(), r.tickMax());
        lines[l++] = String.format(Locale.ROOT, "step %.0f avg / %.0f max us  [%s, %d threads, %s narrowphase]", r.stepAvg(), r.stepMax(),
                r.async() ? "async" : "sync", settings.threads(), settings.nativeNarrowphase() ? "native" : "java");
        for (int i = 0; i < PhysicsStep.STAGES.length; i++) {
            lines[l++] = String.format(Locale.ROOT, "  %-9s %7.0f avg %7.0f max us  %4.1f%%", PhysicsStep.STAGES[i], r.stageAvg()[i],
                    r.stageMax()[i], r.stepAvg() > 0 ? 100 * r.stageAvg()[i] / r.stepAvg() : 0);
        }
        lines[l++] = String.format(Locale.ROOT, "last step: %d islands, %d bodies, %d contacts", r.islands(), r.bodies(), r.contacts());
        lines[l++] = String.format(Locale.ROOT, "vehicles %d (%d resting), towed %d", r.vehicles(), r.resting(), r.towed());
        for (String line : lines) {
            graphics.drawString(mc.font, line, 4, y, white);
            y += 10;
        }
    }
}
