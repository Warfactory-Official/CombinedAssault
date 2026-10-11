package com.wf.combinedassault.naval.trial;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Vehicle-tick cost breakdown, sampled only while a trial runs; off, a probe is a read + branch.
 *
 * <p>Counters are the level's, not one hull's: anything aboard is a vehicle too, and its cost is
 * part of what is measured. Stages cover ~1/3 of a carrier's tick (rest unprobed); they rank cost,
 * not partition it, since e.g. a sea-probe slowdown and a rider-path slowdown look identical from
 * outside. Probe call sites: {@code AbstractVehicle}, {@code ShipVehicle}, {@code VehicleParenting}.
 *
 * <p>{@link Stage#PHYSICS}: on the tick thread iff no {@code CollisionProvider} is registered
 * (Create, Sable each register one) => {@code tryLaunchAsyncPhysics} offloads to a worker instead,
 * and this stage becomes only the submit+wait; the solve itself is then collected at the level
 * barrier, outside every probe here.
 */
public final class TrialProfile {

    public enum Stage {
        /** Sea probe + hydrodynamic step; nested inside {@link #MOVE} (don't sum both). */
        SEA,
        PHYSICS,
        /** Hull box rebuild + deck snapshot publish. */
        OBB,
        /** Rider lookup + carry + settle. */
        RIDERS,
        /** Parked-vehicle harness + deck carry. */
        HARNESS,
        /** Rider-vs-hull move clip; charged to the rider's own tick, not the hull's. */
        CLIP,
        /** {@code Entity.tick} + position/rotation bookkeeping. */
        BASE,
        /** Resupply, cargo mass, pending mounts; pre-solve. */
        UPKEEP,
        /** Travel planning: prepares every section the swept hull covers. */
        PLAN,
        /** Nested in {@link #PLAN}: chunk lookup per section column. */
        CHUNKS,
        /** Nested in {@link #PLAN}: the section sweep itself, excludes pose capture. */
        PREPARE,
        /** Deck link + swept broadphase + in-reach gate. */
        GATE,
        /** Lists what the hull carries when the move is sliced. */
        CARRIED,
        /** Sliced-move solve + write-back. */
        TRAVEL,
        /** Push on hull-bounds overlap: scope = the whole hull volume. */
        PUSH,
        /** Helm + requested forces, up to the solve handoff. */
        MOVE,
        /** Post-solve: move event, righting, part entities, decorations. */
        PARTS
    }

    /** What the tick's work ran over, counted not timed: distinguishes a cost that follows the hull
     *  from one that follows the terrain under it. */
    public enum Work {
        /** Sections covered by hull bounds (incl. snapshot): the solve's broadphase. */
        SECTIONS,
        /** Collision boxes those sections held. */
        BOXES,
        /** Hull sample points carried into contact resolution. */
        POINTS,
        /** Travel substeps; each a full sweep. */
        SUBSTEPS,
        /** Sections whose snapshot was built, not read from cache. */
        BUILDS
    }

    private static final Stage[] STAGES = Stage.values();
    private static final Work[] WORKS = Work.values();

    /** Sentinel: probe opened while off. */
    private static final long IDLE = Long.MIN_VALUE;

    private static volatile boolean enabled;
    private static final AtomicLongArray NANOS = new AtomicLongArray(STAGES.length);
    private static final AtomicLongArray CALLS = new AtomicLongArray(STAGES.length);
    private static final AtomicLongArray WORK = new AtomicLongArray(WORKS.length);

    private TrialProfile() {
    }

    public static void setEnabled(boolean on) {
        if (on) {
            for (int i = 0; i < STAGES.length; i++) {
                NANOS.set(i, 0);
                CALLS.set(i, 0);
            }
            for (int i = 0; i < WORKS.length; i++) {
                WORK.set(i, 0);
            }
        }
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** @return nanoTime, or {@link #IDLE} when off */
    public static long start() {
        return enabled ? System.nanoTime() : IDLE;
    }

    public static void stop(Stage stage, long opened) {
        if (opened == IDLE) {
            return;
        }
        int i = stage.ordinal();
        NANOS.addAndGet(i, System.nanoTime() - opened);
        CALLS.incrementAndGet(i);
    }

    public static void count(Work work, long amount) {
        if (enabled) {
            WORK.addAndGet(work.ordinal(), amount);
        }
    }

    /** @param work sized {@link #workCount()}, indexed by {@link Work#ordinal()} */
    public static void drainWork(long[] work) {
        for (int i = 0; i < WORKS.length; i++) {
            work[i] = WORK.getAndSet(i, 0);
        }
    }

    public static int workCount() {
        return WORKS.length;
    }

    /**
     * @param nanos sized {@link #stageCount()}, indexed by {@link Stage#ordinal()}
     * @param calls same sizing/indexing as {@code nanos}
     */
    public static void drain(long[] nanos, long[] calls) {
        for (int i = 0; i < STAGES.length; i++) {
            nanos[i] = NANOS.getAndSet(i, 0);
            calls[i] = CALLS.getAndSet(i, 0);
        }
    }

    public static int stageCount() {
        return STAGES.length;
    }

}
