package com.wf.combinedassault.entity.vehicle;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.vehicle.rigid.ContactBuffer;
import com.wf.combinedassault.vehicle.rigid.ConvexContacts;
import com.wf.combinedassault.vehicle.rigid.NarrowNative;
import com.wf.combinedassault.vehicle.rigid.Polytope;
import com.wf.combinedassault.vehicle.rigid.RigidSolver;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every server vehicle that ticked, stepped once per level tick (REDACTED): capture, islands of touching
 * hulls, one {@link RigidSolver} solve + integration per island on the pool, flush in id order. Tick thread except
 * island tasks.
 */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID)
public final class RigidStep {

    static final boolean DEBUG = Boolean.getBoolean("combinedassault.rigid.debug");
    private static final Map<ServerLevel, RigidStep> LEVELS = new WeakHashMap<>();
    private static volatile ExecutorService pool;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** Pair's last contacts: anchor in the lower id's hull frame, world normal, impulses (n, t1, t2). */
    private static final class PairCache {
        float[] points = new float[0];
        int count;
        long used;
    }

    private static final class Scratch {
        final RigidSolver solver = new RigidSolver(Integer.getInteger("combinedassault.rigid.substeps", RigidSolver.SUBSTEPS), Integer.getInteger("combinedassault.rigid.iterations", 1));
        final ConvexContacts narrow = new ConvexContacts();
        @Nullable final NarrowNative nativeNarrow = NarrowNative.available() ? new NarrowNative() : null;
        final ContactBuffer buffer = new ContactBuffer();
    }

    private static final class Island {
        final List<VehicleStep> members = new ArrayList<>();
        /** Member index pairs. */
        final List<int[]> pairs = new ArrayList<>();
        final List<PairCache> caches = new ArrayList<>();
        boolean live;
        int contacts;
        Future<?> future;
    }

    /** Bench counters, tick thread; off unless {@link #stats}. */
    public static boolean stats;
    private static long statNanos, statTicks, statIslands, statBodies, statContacts;

    /** Per stepped tick since the last drain: step us, islands, bodies, contacts. */
    public static String drainStats() {
        long ticks = Math.max(1, statTicks);
        String line = String.format(java.util.Locale.ROOT, "step %.0f us islands %.1f bodies %.1f contacts %.1f",
                statNanos / 1000.0 / ticks, (double) statIslands / ticks, (double) statBodies / ticks, (double) statContacts / ticks);
        statNanos = statTicks = statIslands = statBodies = statContacts = 0;
        return line;
    }

    private final List<AbstractVehicle> registered = new ArrayList<>();
    private final Long2ObjectOpenHashMap<PairCache> pairCaches = new Long2ObjectOpenHashMap<>();
    private long tick;

    private RigidStep() {}

    /** Entity tick: steps at this level's Post. */
    static void register(AbstractVehicle vehicle) {
        LEVELS.computeIfAbsent((ServerLevel) vehicle.level(), level -> new RigidStep()).registered.add(vehicle);
    }

    static boolean registered(AbstractVehicle vehicle) {
        RigidStep step = LEVELS.get((ServerLevel) vehicle.level());
        return step != null && step.registered.contains(vehicle);
    }

    /** HIGHEST: tow, rail and other Post subscribers see the stepped poses. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            RigidStep step = LEVELS.get(level);
            if (step != null) {
                step.step();
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LEVELS.remove(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
        ExecutorService running = pool;
        pool = null;
        if (running != null) {
            running.shutdown();
            try {
                if (!running.awaitTermination(5, TimeUnit.SECONDS)) {
                    running.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                running.shutdownNow();
            }
        }
    }

    private void step() {
        if (registered.isEmpty()) {
            evict();
            return;
        }
        long startedAt = stats ? System.nanoTime() : 0;
        List<VehicleStep> steps = new ArrayList<>(registered.size());
        registered.sort(Comparator.comparingInt(AbstractVehicle::getId));
        boolean async = AllConfigs.Cached.asyncVehiclePhysics;
        for (int i = 0, size = registered.size(); i < size; i++) {
            AbstractVehicle vehicle = registered.get(i);
            if (vehicle.isRemoved() || vehicle.getMainCubeOBB() == null) {
                continue;
            }
            VehicleStep step = vehicle.step;
            step.woken = false;
            step.capture(async);
            steps.add(step);
        }
        registered.clear();
        // Towed before its tractor ticked (or a sleeping tractor): coupling rows need both bodies.
        for (int i = 0; i < steps.size(); i++) {
            VehicleStep step = steps.get(i);
            AbstractVehicle tractor = step.tow == null ? null : step.tow.tractor();
            if (tractor != null) {
                wake(tractor, steps, async);
            }
            for (AbstractVehicle towed : TowLinks.towedBy(step.vehicle)) {
                wake(towed, steps, async);
            }
        }
        for (int i = 0, captured = steps.size(); i < captured; i++) {
            List<AbstractVehicle> near = steps.get(i).vehicle.level().getEntitiesOfClass(AbstractVehicle.class,
                    steps.get(i).gather);
            for (AbstractVehicle other : near) {
                if (!other.step.active && other.wakeForStep()) {
                    other.step.woken = true;
                    other.step.capture(async);
                    steps.add(other.step);
                }
            }
        }
        List<Island> islands = islands(steps);
        for (Island island : islands) {
            if (!island.live && async) {
                try {
                    island.future = pool().submit(() -> solve(island));
                } catch (RejectedExecutionException shuttingDown) {
                    island.future = null;
                }
            }
        }
        for (Island island : islands) {
            if (island.future == null) {
                solve(island);
                continue;
            }
            try {
                island.future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                hold(island, interrupted);
            } catch (ExecutionException failed) {
                hold(island, failed.getCause());
            }
        }
        // Carriers before riders: a rider's deck carry reads its carrier's flushed snapshot.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0, size = steps.size(); i < size; i++) {
                VehicleStep step = steps.get(i);
                AbstractVehicle carrier = step.vehicle.carrierLink().carrier();
                boolean rider = carrier != null && carrier.step.active;
                if (rider == (pass == 1) && !step.vehicle.isRemoved()) {
                    step.vehicle.completeStep(step);
                }
            }
        }
        for (VehicleStep step : steps) {
            step.active = false;
        }
        evict();
        if (stats) {
            statNanos += System.nanoTime() - startedAt;
            statTicks++;
            statIslands += islands.size();
            statBodies += steps.size();
            for (Island island : islands) {
                statContacts += island.contacts;
            }
        }
    }

    private static void wake(AbstractVehicle vehicle, List<VehicleStep> steps, boolean async) {
        if (!vehicle.step.active && vehicle.wakeForStep()) {
            vehicle.step.woken = true;
            vehicle.step.capture(async);
            steps.add(vehicle.step);
        }
    }

    private static void hold(Island island, Throwable cause) {
        CombinedAssault.LOGGER.error("Rigid step failed for {}; held this tick", island.members.get(0).vehicle, cause);
        for (VehicleStep member : island.members) {
            member.hold();
        }
    }

    /** Client tick thread: one predicted vehicle, no neighbours, live world (REDACTED). */
    static void predict(VehicleStep step) {
        Island island = new Island();
        island.members.add(step);
        island.live = true;
        try {
            solve(island);
        } catch (RuntimeException failed) {
            CombinedAssault.LOGGER.error("Predicted step failed for {}; held", step.vehicle, failed);
            step.hold();
        }
        step.active = false;
    }

    /** Swept bounds meeting + hull contact allowed => same island. */
    private List<Island> islands(List<VehicleStep> steps) {
        int n = steps.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VehicleStep towed = steps.get(i);
            if (towed.tow != null && towed.tow.tractor().step.active) {
                int j = steps.indexOf(towed.tow.tractor().step);
                int ra = root(parent, i), rb = root(parent, j);
                parent[Math.max(ra, rb)] = Math.min(ra, rb);
            }
        }
        for (int i = 0; i < n; i++) {
            VehicleStep a = steps.get(i);
            for (int j = i + 1; j < n; j++) {
                VehicleStep b = steps.get(j);
                double reach = a.margin + b.margin;
                if (!a.gather.inflate(reach).intersects(b.gather) || !a.vehicle.hullContacts(b.vehicle)) {
                    continue;
                }
                pairs.add(new int[]{i, j});
                int ra = root(parent, i), rb = root(parent, j);
                parent[Math.max(ra, rb)] = Math.min(ra, rb);
            }
        }
        List<Island> islands = new ArrayList<>();
        int[] islandOf = new int[n];
        int[] slot = new int[n];
        for (int i = 0; i < n; i++) {
            int r = root(parent, i);
            if (r == i) {
                islandOf[i] = islands.size();
                islands.add(new Island());
            } else {
                islandOf[i] = islandOf[r];
            }
            Island island = islands.get(islandOf[i]);
            slot[i] = island.members.size();
            island.members.add(steps.get(i));
            island.live |= steps.get(i).live;
        }
        for (int[] pair : pairs) {
            Island island = islands.get(islandOf[pair[0]]);
            island.pairs.add(new int[]{slot[pair[0]], slot[pair[1]]});
            PairCache cache = pairCaches.computeIfAbsent(key(steps.get(pair[0]).vehicle, steps.get(pair[1]).vehicle),
                    k -> new PairCache());
            cache.used = tick;
            island.caches.add(cache);
        }
        return islands;
    }

    /** Island task. */
    private static void solve(Island island) {
        Scratch scratch = SCRATCH.get();
        RigidSolver solver = scratch.solver;
        VehicleStep first = island.members.get(0);
        solver.reset(first.comX, first.comY, first.comZ);
        solver.addBody();
        for (VehicleStep member : island.members) {
            member.addBody(solver);
            if (!island.pairs.isEmpty()) {
                member.poseShapes(solver.origin);
            }
        }
        for (VehicleStep member : island.members) {
            member.worldContacts(solver, scratch.narrow, AllConfigs.Cached.nativeVehiclePhysics ? scratch.nativeNarrow : null,
                    scratch.buffer);
        }
        int[] pairFrom = new int[island.pairs.size()];
        for (int p = 0, size = island.pairs.size(); p < size; p++) {
            int[] pair = island.pairs.get(p);
            pairFrom[p] = solver.contactCount();
            hullContacts(solver, scratch, island.members.get(pair[0]), island.members.get(pair[1]), island.caches.get(p));
        }
        island.contacts = solver.contactCount();
        for (VehicleStep member : island.members) {
            member.towJoint = -1;
            if (member.tow != null && member.tow.tractor().step.active) {
                member.towJoint = solver.jointCount();
                member.towJoint(solver, member.tow.tractor().step);
            }
        }
        solver.solve();
        for (VehicleStep member : island.members) {
            if (member.towJoint >= 0) {
                RigidSolver.Joint joint = solver.joint(member.towJoint);
                member.tow.store(joint);
                if (DEBUG) {
                    CombinedAssault.LOGGER.info(String.format(java.util.Locale.ROOT, "[towjoint] %d t%d J %.4f,%.4f,%.4f yaw %.4f axle %.4f",
                            member.vehicle.getId(), member.vehicle.tickCount, joint.impulse.x, joint.impulse.y, joint.impulse.z,
                            joint.yawImpulse, joint.axleImpulse));
                }
            }
        }
        for (int p = 0, size = island.pairs.size(); p < size; p++) {
            int to = p + 1 < size ? pairFrom[p + 1] : solver.contactCount();
            int[] pair = island.pairs.get(p);
            store(solver, island.members.get(pair[0]), island.members.get(pair[1]), island.caches.get(p), pairFrom[p], to);
        }
        for (VehicleStep member : island.members) {
            member.finish(solver);
        }
    }

    /** a = lower id (sorted); normal a -> b. */
    private static void hullContacts(RigidSolver solver, Scratch scratch, VehicleStep a, VehicleStep b, PairCache cache) {
        ContactBuffer buffer = scratch.buffer;
        buffer.clear();
        float margin = Math.max(a.margin, b.margin);
        for (int i = 0; i < a.hullShapeCount; i++) {
            Polytope shapeA = a.hullShapes.get(i);
            for (int j = 0; j < b.hullShapeCount; j++) {
                Polytope shapeB = b.hullShapes.get(j);
                if (shapeA.boundsMeet(shapeB, margin)) {
                    // Per piece pair: one reduction over the pair kept 4 points whose deepest flipped between pieces
                    // each tick => humvee on humvee rocked period 2, 7 cm deep (offline harness); per pair 8 mm, still.
                    int from = buffer.size();
                    scratch.narrow.collide(shapeA, shapeB, margin, buffer);
                    buffer.reduce(from);
                }
            }
        }
        opposing(buffer);
        Vector3f local = new Vector3f();
        for (int k = 0, size = buffer.size(); k < size; k++) {
            RigidSolver.Contact c = solver.addContact(a.body, b.body, buffer.x(k), buffer.y(k), buffer.z(k),
                    buffer.normalX(k), buffer.normalY(k), buffer.normalZ(k), buffer.separation(k));
            c.friction = VehicleStep.FRICTION;
            c.restitution = VehicleStep.HULL_RESTITUTION;
            c.debugTagA = buffer.tagA(k);
            c.debugTagB = buffer.tagB(k);
            a.hullRotation.transformInverse(local.set(c.anchorA));
            int best = -1;
            float bestDistance = 0.15f * 0.15f;
            for (int m = 0; m < cache.count; m++) {
                int o = m * 9;
                float dx = cache.points[o] - local.x, dy = cache.points[o + 1] - local.y, dz = cache.points[o + 2] - local.z;
                float distance = dx * dx + dy * dy + dz * dz;
                if (distance < bestDistance && cache.points[o + 3] * c.normal.x + cache.points[o + 4] * c.normal.y
                        + cache.points[o + 5] * c.normal.z > 0.95f) {
                    bestDistance = distance;
                    best = m;
                }
            }
            if (best >= 0) {
                c.normalImpulse = cache.points[best * 9 + 6];
                c.tangentImpulse1 = cache.points[best * 9 + 7];
                c.tangentImpulse2 = cache.points[best * 9 + 8];
            }
        }
    }

    /**
     * Drops points against the pair's depth-weighted normal. Deep overlap => piece SAT axes disagree (+n and -n in
     * one pair); kept => tank thrown up 0.76 b/t by a humvee 1.1 inside it.
     */
    private static void opposing(ContactBuffer buffer) {
        float sx = 0, sy = 0, sz = 0;
        for (int k = 0, size = buffer.size(); k < size; k++) {
            float depth = Math.max(0, -buffer.separation(k));
            sx += buffer.normalX(k) * depth;
            sy += buffer.normalY(k) * depth;
            sz += buffer.normalZ(k) * depth;
        }
        if (sx == 0 && sy == 0 && sz == 0) {
            return;
        }
        float x = sx, y = sy, z = sz;
        buffer.retain(k -> buffer.normalX(k) * x + buffer.normalY(k) * y + buffer.normalZ(k) * z >= 0);
    }

    private static void store(RigidSolver solver, VehicleStep a, VehicleStep b, PairCache cache, int from, int to) {
        int count = to - from;
        if (cache.points.length < count * 9) {
            cache.points = new float[Math.max(count * 9, cache.points.length * 2)];
        }
        Vector3f local = new Vector3f();
        boolean touching = false;
        for (int k = 0; k < count; k++) {
            RigidSolver.Contact c = solver.contact(from + k);
            a.hullRotation.transformInverse(local.set(c.anchorA));
            int o = k * 9;
            cache.points[o] = local.x;
            cache.points[o + 1] = local.y;
            cache.points[o + 2] = local.z;
            cache.points[o + 3] = c.normal.x;
            cache.points[o + 4] = c.normal.y;
            cache.points[o + 5] = c.normal.z;
            cache.points[o + 6] = c.normalImpulse;
            cache.points[o + 7] = c.tangentImpulse1;
            cache.points[o + 8] = c.tangentImpulse2;
            touching |= c.totalNormalImpulse > 0;
            // Supported by the other hull counts as ground.
            if (c.totalNormalImpulse > 0) {
                if (c.normal.y > VehicleStep.SUPPORT_NORMAL) {
                    b.grounded = true;
                } else if (c.normal.y < -VehicleStep.SUPPORT_NORMAL) {
                    a.grounded = true;
                }
            }
        }
        cache.count = count;
        if (DEBUG) {
            StringBuilder line = new StringBuilder(String.format(java.util.Locale.ROOT, "[rigid] pair %d-%d n=%d", a.vehicle.getId(), b.vehicle.getId(), count));
            for (int k = 0; k < count; k++) {
                RigidSolver.Contact c = solver.contact(from + k);
                line.append(String.format(java.util.Locale.ROOT, " (%d/%d n %.2f %.2f %.2f s0 %.3f J %.3f m %.1f rA %.2f %.2f %.2f rB %.2f %.2f %.2f)", c.debugTagA, c.debugTagB, c.normal.x, c.normal.y, c.normal.z, c.debugSeparation(), c.totalNormalImpulse, c.debugMass(), c.anchorA.x, c.anchorA.y, c.anchorA.z, c.anchorB.x, c.anchorB.y, c.anchorB.z));
            }
            for (int i = 0; i < a.hullShapeCount; i++) {
                com.wf.wflib.util.OBB o = a.vehicle.getOBBs().get(i);
                line.append(String.format(java.util.Locale.ROOT, " A%d[c %.2f %.2f %.2f e %.2f %.2f %.2f]", i, o.centerX() - a.comX, o.centerY() - a.comY, o.centerZ() - a.comZ, o.extents().x, o.extents().y, o.extents().z));
            }
            for (int i = 0; i < b.hullShapeCount; i++) {
                com.wf.wflib.util.OBB o = b.vehicle.getOBBs().get(i);
                line.append(String.format(java.util.Locale.ROOT, " B%d[c %.2f %.2f %.2f e %.2f %.2f %.2f]", i, o.centerX() - b.comX, o.centerY() - b.comY, o.centerZ() - b.comZ, o.extents().x, o.extents().y, o.extents().z));
            }
            line.append(" invI_b ").append(solver.body(b.body).inverseInertia).append(" invM_b ").append(solver.body(b.body).inverseMass);
            for (int k = 0; k < 0; k++) {
            }
            CombinedAssault.LOGGER.info(line.toString());
        }
        if (touching) {
            a.touched.add(b.vehicle);
            b.touched.add(a.vehicle);
        }
    }

    /** Pair unseen this tick => gone. */
    private void evict() {
        ObjectIterator<Long2ObjectMap.Entry<PairCache>> entries = pairCaches.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().used < tick) {
                entries.remove();
            }
        }
        tick++;
    }

    private static long key(AbstractVehicle a, AbstractVehicle b) {
        int low = Math.min(a.getId(), b.getId()), high = Math.max(a.getId(), b.getId());
        return (long) low << 32 | high & 0xFFFFFFFFL;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static ExecutorService pool() {
        ExecutorService current = pool;
        if (current == null) {
            synchronized (RigidStep.class) {
                current = pool;
                if (current == null) {
                    AtomicInteger index = new AtomicInteger();
                    int configured = AllConfigs.Cached.physicsThreads;
                    int size = configured > 0 ? configured : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
                    current = Executors.newFixedThreadPool(size, task -> {
                        Thread thread = new Thread(task, "combinedassault-vehicle-physics-" + index.getAndIncrement());
                        thread.setDaemon(true);
                        thread.setPriority(Thread.NORM_PRIORITY - 1);
                        return thread;
                    });
                    pool = current;
                }
            }
        }
        return current;
    }
}
