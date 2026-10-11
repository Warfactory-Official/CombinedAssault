package com.wf.combinedassault.tow.trial;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.tow.HitchInfo;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.tow.TowMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Same shape as the naval passage harness: a run is comparable only if setup is a state machine,
 * not a sleep. Ground cut -> pair settle onto wheels -> engine to power -> first row written. */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID)
public final class TowTrials {

    private enum Phase { BUILT, SETTLING, WARMING, RUNNING, DONE }

    /** Settle timeout, ticks. */
    private static final int SETTLE_LIMIT = 400;
    private static final double SETTLED = 0.02;

    /** Trailer ground-penetration allowed before the lap may start. Coupling closes on the drop-in,
     * leaving the trailer nose-up with its tail in the floor for ~10 ticks; that's setup settling,
     * not a result, so the clock waits for it. */
    private static final double SEATED = -0.1;

    /** Engine warmup timeout, ticks. */
    private static final int WARMUP_LIMIT = 200;

    /** Forceload margin outside the ring, chunks. */
    private static final int CHUNK_MARGIN = 1;

    private static Setup setup;

    /** Last trial's output, kept after it ends for a harness to poll. */
    private static final List<String> REPORT = new ArrayList<>();

    private TowTrials() {
    }

    /** Polled, not pushed: an RCON socket only hears its own command's reply; the trial outlives it. */
    public static List<String> report() {
        List<String> out = new ArrayList<>(REPORT);
        Setup active = setup;
        if (active != null) {
            out.add(String.format(Locale.ROOT, "running: %s for %d ticks",
                    active.phase.name().toLowerCase(Locale.ROOT), active.phaseTicks));
        }
        return out;
    }

    private static void say(String line) {
        REPORT.add(line);
        CombinedAssault.LOGGER.info("[tow trial] {}", line);
    }

    private static final class Setup {

        private final ServerLevel level;
        private final AbstractVehicle tractor;
        private final AbstractVehicle trailer;
        private final TowGround.Built course;
        private final TowDriver driver;
        private final double laps;
        private final List<ChunkPos> held;
        private final String name;

        private Phase phase = Phase.BUILT;
        private int phaseTicks;
        private TowTrialRun run;

        Setup(ServerLevel level, AbstractVehicle tractor, AbstractVehicle trailer,
              TowGround.Built course, TowDriver driver, double laps, List<ChunkPos> held,
              String name) {
            this.level = level;
            this.tractor = tractor;
            this.trailer = trailer;
            this.course = course;
            this.driver = driver;
            this.laps = laps;
            this.held = held;
            this.name = name;
        }

    }

    public static boolean running() {
        return setup != null;
    }

    /** Releases chunks; vehicles left where they stand. */
    public static void abandon() {
        if (setup == null) {
            return;
        }
        release(setup);
        setup = null;
    }

    /** @return error message, or null if started. */
    @Nullable
    public static String start(ServerLevel level, Vec3 centre, String courseName, int radius,
                               double laps, double speed, ResourceLocation tractorId,
                               @Nullable ResourceLocation trailerId, TowMode mode) {
        abandon();
        REPORT.clear();
        TowGround.Spec spec = TowGround.spec(courseName, radius);
        TowGround.Built course = TowGround.build(level, centre, spec);
        List<ChunkPos> held = hold(level, course);

        AbstractVehicle tractor = spawn(level, tractorId, course.start(), course.heading());
        if (tractor == null) {
            return "no such vehicle: " + tractorId;
        }
        if (trailerId == null) {
            // Control: solo lap (nothing on hook) to baseline against the tow's numbers.
            drive(level, tractor);
            setup = new Setup(level, tractor, tractor, course, new TowDriver(course, speed), laps,
                    held, String.format(Locale.ROOT, "%s-r%d-solo", spec.name(), radius));
            say(String.format(Locale.ROOT, "course %s cut at %.0f %.0f, radius %d: solo",
                    spec.name(), centre.x, centre.z, radius));
            return null;
        }
        HitchInfo hitch = pick(tractor, mode);
        if (hitch == null) {
            return tractorId + " has no hitch that takes a " + mode.lowerName();
        }
        AbstractVehicle trailer = spawn(level, trailerId, course.start(), course.heading());
        if (trailer == null) {
            return "no such vehicle: " + trailerId;
        }
        // Placed exactly at the coupling pose: first tick is a lap, not a recovery.
        Vec3 wanted = TowLinks.hitchPoint(tractor, hitch)
                .subtract(TowLinks.couplingPoint(trailer).subtract(trailer.position()));
        trailer.setPos(wanted.x, wanted.y, wanted.z);
        trailer.updateOBBs();
        if (!TowLinks.couple(tractor, trailer, hitch, mode)) {
            return "the pair would not couple on " + hitch.id;
        }

        drive(level, tractor);

        setup = new Setup(level, tractor, trailer, course, new TowDriver(course, speed), laps, held,
                String.format(Locale.ROOT, "%s-r%d-%s", spec.name(), radius, mode.lowerName()));
        say(String.format(Locale.ROOT,
                "course %s cut at %.0f %.0f, radius %d: %s", spec.name(), centre.x, centre.z, radius,
                String.join(" ", spec.sectors().stream().map(TowGround.Surface::lower).toList())));
        return null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        Setup active = setup;
        if (active == null) {
            return;
        }
        if (active.tractor.isRemoved() || active.trailer.isRemoved()) {
            AbstractVehicle gone = active.tractor.isRemoved() ? active.tractor : active.trailer;
            say(String.format(Locale.ROOT,
                    "trial abandoned: the %s went away at %.1f %.1f %.1f, %s, health %.0f",
                    gone == active.tractor ? "tractor" : "trailer",
                    gone.getX(), gone.getY(), gone.getZ(),
                    gone.getRemovalReason() == null ? "no reason" : gone.getRemovalReason().name(),
                    gone.getHealth()));
            if (active.run != null) {
                Path dump = active.run.dump("-abandoned");
                say(dump == null ? "the log could not be written" : "log: " + dump);
            }
            abandon();
            return;
        }
        active.phaseTicks++;
        switch (active.phase) {
            case BUILT -> {
                if (active.phaseTicks > 5) {
                    active.phase = Phase.SETTLING;
                    active.phaseTicks = 0;
                }
            }
            case SETTLING -> {
                if (seated(active) || active.phaseTicks > SETTLE_LIMIT) {
                    say("settled after " + active.phaseTicks + " ticks");
                    active.phase = Phase.WARMING;
                    active.phaseTicks = 0;
                }
            }
            case WARMING -> {
                // Re-check seated() here: falling takes ~20 ticks, engine ~100 to full power; an
                // on-the-way-in-only check would pass before anything settled.
                if ((active.tractor.getPower() >= 99 && seated(active)) || active.phaseTicks > WARMUP_LIMIT) {
                    active.run = new TowTrialRun(active.level, active.tractor, active.trailer,
                            active.course, active.driver, active.laps, active.name);
                    say("under way at " + active.tractor.getPower() + " power");
                    active.phase = Phase.RUNNING;
                    active.phaseTicks = 0;
                }
            }
            case RUNNING -> {
                active.run.tick();
                if (active.run.outcome() != TowTrialRun.Outcome.RUNNING) {
                    for (String line : active.run.verdict()) {
                        say(line);
                    }
                    Path dump = active.run.dump("");
                    say(dump == null ? "the log could not be written" : "log: " + dump);
                    say(active.run.clean() ? "clean" : "NOT CLEAN");
                    active.phase = Phase.DONE;
                }
            }
            case DONE -> abandon();
        }
    }

    private static void drive(ServerLevel level, AbstractVehicle tractor) {
        // ArmorStand, not FakePlayer: FakePlayer can't mount an AbstractVehicle; control unit
        // only needs a living entity as operator.
        ArmorStand driver = new ArmorStand(EntityType.ARMOR_STAND, level);
        driver.setPos(tractor.position());
        driver.setInvisible(true);
        driver.setNoGravity(true);
        level.addFreshEntity(driver);
        tractor.controlUnit.setOperator(driver);
        tractor.addEnergy(tractor.energyInfo.capacityUnits());
        tractor.toggleEngine(true);
    }

    private static boolean seated(Setup active) {
        return active.tractor.onGround() && active.trailer.onGround()
                && active.tractor.getDeltaMovement().lengthSqr() < SETTLED * SETTLED
                && TowGround.clearance(active.level, active.trailer) >= SEATED
                && TowGround.clearance(active.level, active.tractor) >= SEATED;
    }

    @Nullable
    private static HitchInfo pick(AbstractVehicle tractor, TowMode mode) {
        for (HitchInfo hitch : tractor.towing.hitches) {
            if (hitch.supports(mode)) {
                return hitch;
            }
        }
        return null;
    }

    @Nullable
    private static AbstractVehicle spawn(ServerLevel level, ResourceLocation id, Vec3 at, float yaw) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            return null;
        }
        double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(at.x), (int) Math.floor(at.z));
        AbstractVehicle vehicle = data.get().construct(level, new Vec3(at.x, y + 0.5, at.z), 0, yaw);
        vehicle.setDisplayId(id);
        if (!level.addFreshEntity(vehicle)) {
            return null;
        }
        vehicle.updateOBBs();
        return vehicle;
    }

    /** Ring-shaped hold only; a bounding square would exceed the forceload ceiling. */
    private static List<ChunkPos> hold(ServerLevel level, TowGround.Built course) {
        List<ChunkPos> held = new ArrayList<>();
        int reach = course.spec().radius() + course.spec().halfWidth();
        BlockPos centre = BlockPos.containing(course.centre());
        for (int x = (centre.getX() - reach) >> 4; x <= (centre.getX() + reach) >> 4; x++) {
            for (int z = (centre.getZ() - reach) >> 4; z <= (centre.getZ() + reach) >> 4; z++) {
                double dx = (x * 16 + 8) - course.centre().x;
                double dz = (z * 16 + 8) - course.centre().z;
                double offset = Math.abs(Math.hypot(dx, dz) - course.spec().radius());
                if (offset <= course.spec().halfWidth() + 16 * CHUNK_MARGIN) {
                    level.setChunkForced(x, z, true);
                    held.add(new ChunkPos(x, z));
                }
            }
        }
        return held;
    }

    private static void release(Setup active) {
        for (ChunkPos chunk : active.held) {
            active.level.setChunkForced(chunk.x, chunk.z, false);
        }
    }

}
