package com.wf.combinedassault.naval.trial;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.ShipVehicle;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One trial, server-wide. Setup is a state machine, not a sleep: hull settles to its waterline,
 * plant reaches full power, only then does row 1 get written. Passages must start alike to compare.
 */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID)
public final class Trials {

    private enum Phase { SPAWNED, BUILT, SETTLING, WARMING, RUNNING, DONE }

    private static final Comparator<Unit> UNIT_COMPARATOR = (a, b) -> 0;
    private static final TicketType<Unit> TRIAL_TICKET =
            TicketType.create("combinedassault_trial", UNIT_COMPARATOR, 200);
    /** Renewal cadence; must stay well under the ticket's own 200-tick life. */
    private static final int TICKET_INTERVAL = 20;
    private static final int TICKET_MARGIN = 2;

    private static final int SPAWN_TICKS = 20;
    private static final double CLEAR_RADIUS = 1000.0;
    private static final int SETTLE_LIMIT = 400;
    private static final double SETTLED_HEAVE = 0.01;
    private static final int WARMUP_LIMIT = 300;

    private static Setup setup;

    private Trials() {
    }

    private static final class Setup {

        private final ServerLevel level;
        private final ShipVehicle ship;
        private final String courseName;
        private final Vec3 origin;
        private final float heading;
        private final int crewCount;
        private final ResourceLocation parkedId;
        private final int parkedCount;
        private final TrialCrew crew = new TrialCrew();

        private Phase phase = Phase.SPAWNED;
        private int phaseTicks;
        private TrialCourse.Built course;
        private TrialRun run;
        private String note = "";

        Setup(ServerLevel level, ShipVehicle ship, String courseName, Vec3 origin, float heading,
              int crewCount, @Nullable ResourceLocation parkedId, int parkedCount) {
            this.level = level;
            this.ship = ship;
            this.courseName = courseName;
            this.origin = origin;
            this.heading = heading;
            this.crewCount = crewCount;
            this.parkedId = parkedId;
            this.parkedCount = parkedCount;
        }
    }

    public static boolean busy() {
        return setup != null && setup.phase != Phase.DONE;
    }

    public static void begin(ServerLevel level, ShipVehicle ship, String courseName, Vec3 origin,
                             float heading, int crew, @Nullable ResourceLocation parkedId, int parked) {
        setup = new Setup(level, ship, courseName, origin, heading, crew, parkedId, parked);
    }

    @Nullable
    public static TrialRun run() {
        return setup == null ? null : setup.run;
    }

    public static String status() {
        if (setup == null) {
            return "no trial";
        }
        if (setup.run == null) {
            return String.format(Locale.ROOT, "%s, %d ticks in%s",
                    setup.phase.name().toLowerCase(Locale.ROOT), setup.phaseTicks,
                    setup.note.isEmpty() ? "" : ": " + setup.note);
        }
        TrialRun run = setup.run;
        return String.format(Locale.ROOT,
                "%s tick %d on %s, keel %.2f, margin %.1f, tick cost %d us",
                run.outcome(), run.ticks(), run.pilot().legName(), setup.ship.keelClearance(),
                run.pilot().margin(), setup.ship.lastTickNanos() / 1000);
    }

    public static List<String> stop() {
        if (setup == null) {
            return List.of("no trial");
        }
        Setup ending = setup;
        setup = null;
        TrialProfile.setEnabled(false);
        if (ending.run == null) {
            clear(ending);
            return List.of("trial abandoned in " + ending.phase.name().toLowerCase(Locale.ROOT));
        }
        ending.run.abandon();
        List<String> report = report(ending);
        clear(ending);
        return report;
    }

    private static List<String> report(Setup ending) {
        List<String> lines = new ArrayList<>();
        lines.add(ending.course.describe());
        lines.addAll(ending.run.report());
        Path csv = ending.run.dump(ending.ship.getDisplayId().getPath());
        lines.add(csv == null ? "  rows could not be written" : "  rows: " + csv);
        return lines;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Setup current = setup;
        if (current == null || current.phase == Phase.DONE) {
            return;
        }
        current.phaseTicks++;
        hold(current);
        if (current.phase != Phase.RUNNING && current.phase != Phase.WARMING) {
            // Repeated, not one-shot: a leftover hull from an earlier run only "arrives" (its chunk
            // starts ticking) after the course is cut, so clearing runs until muster puts vehicles aboard.
            clearStarters(current);
        }
        if (current.ship.isRemoved()) {
            current.note = "the hull was removed";
            current.phase = Phase.DONE;
            return;
        }
        switch (current.phase) {
            case SPAWNED -> spawned(current);
            case BUILT -> built(current);
            case SETTLING -> settling(current);
            case WARMING -> warming(current);
            case RUNNING -> running(current);
            default -> { }
        }
    }

    /** Chunk ticket on the hull: course-cut walks thousands of chunks and would else evict (and remove) it. */
    private static void hold(Setup current) {
        if (current.phaseTicks % TICKET_INTERVAL != 1) {
            return;
        }
        int radius = TICKET_MARGIN;
        double[] hull = TrialCourse.hullSize(current.ship);
        if (hull != null) {
            radius += (int) Math.ceil(Math.max(hull[0], hull[1]) / 32.0);
        }
        current.level.getChunkSource().addRegionTicket(TRIAL_TICKET,
                new ChunkPos(BlockPos.containing(current.ship.getX(), current.ship.getY(),
                        current.ship.getZ())), radius, Unit.INSTANCE);
    }

    private static void spawned(Setup current) {
        if (current.phaseTicks < SPAWN_TICKS || current.ship.getMainCubeOBB() == null) {
            return;
        }
        double[] hull = TrialCourse.hullSize(current.ship);
        if (hull == null) {
            return;
        }
        current.course = TrialCourse.build(current.level, current.courseName,
                TrialCourse.byName(current.courseName), current.origin, current.heading,
                hull[0], hull[1], current.ship.params().draft,
                current.ship.params().minKeelClearance);
        if (current.course == null) {
            current.note = "the course could not be cut: no water at the start, or it is too large";
            current.phase = Phase.DONE;
            return;
        }
        current.note = current.course.describe();
        current.phase = Phase.BUILT;
        current.phaseTicks = 0;
    }

    /**
     * Clears litter from a run that never cleaned up after itself: two hulls pressing on each other
     * read as aground, wasting the whole trial on the last one's leftovers.
     */
    private static void clearStarters(Setup current) {
        AABB around = AABB.ofSize(current.ship.position(), CLEAR_RADIUS * 2, CLEAR_RADIUS * 2, CLEAR_RADIUS * 2);
        for (Entity entity : current.level.getEntities(current.ship, around,
                other -> other instanceof AbstractVehicle)) {
            entity.discard();
        }
    }

    private static void built(Setup current) {
        // +1.0 Y: clears whatever carve() just wrote at the surface.
        current.ship.moveTo(current.course.start().x, current.course.surfaceY() + 1.0,
                current.course.start().z, current.heading, 0);
        current.ship.setDeltaMovement(Vec3.ZERO);
        current.phase = Phase.SETTLING;
        current.phaseTicks = 0;
    }

    private static void settling(Setup current) {
        boolean still = current.ship.afloat()
                && Math.abs(current.ship.getDeltaMovement().y) < SETTLED_HEAVE;
        if (!still && current.phaseTicks < SETTLE_LIMIT) {
            return;
        }
        if (!current.crew.muster(current.level, current.ship, current.crewCount,
                current.parkedCount, current.parkedId) && current.crewCount > 0) {
            current.note = "the hull has no deck to muster a crew on";
        }
        current.ship.setEnergy(current.ship.energyInfo.energyCapacity);
        current.ship.toggleEngine(true);
        current.phase = Phase.WARMING;
        current.phaseTicks = 0;
    }

    private static void warming(Setup current) {
        if (current.ship.getPower() < 100 && current.phaseTicks < WARMUP_LIMIT) {
            return;
        }
        current.run = new TrialRun(current.level, current.ship, current.course,
                new TrialPilot(current.course.marks(), current.course.hullLength(),
                        current.course.beam()), current.crew);
        current.phase = Phase.RUNNING;
        current.phaseTicks = 0;
    }

    private static void running(Setup current) {
        current.run.tick();
        if (current.run.outcome() != TrialRun.Outcome.RUNNING) {
            current.phase = Phase.DONE;
        }
    }

    public static List<String> finished() {
        if (setup == null) {
            return List.of("no trial");
        }
        if (setup.run == null) {
            return List.of(setup.phase.name().toLowerCase(Locale.ROOT) + ": " + setup.note);
        }
        if (setup.run.outcome() == TrialRun.Outcome.RUNNING) {
            return List.of("still running, tick " + setup.run.ticks());
        }
        Setup ending = setup;
        setup = null;
        List<String> report = report(ending);
        clear(ending);
        return report;
    }

    /** Removes hull + everything aboard: left behind, it is the litter the next run's clearStarters finds. */
    private static void clear(Setup ending) {
        ending.crew.dismiss();
        if (!ending.ship.isRemoved()) {
            ending.ship.discard();
        }
    }

}
