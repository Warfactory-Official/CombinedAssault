package com.wf.combinedassault.vehicle.collision;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import com.wf.wflib.util.OBB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Vehicle-vehicle contact island: box-box manifolds (reference face clip, or edge-edge closest points) per body pair,
 * then sequential impulses over every contact of the island at once, linear + angular + Coulomb friction, and a
 * linear split-impulse push out of penetration. Tick thread; one instance reused. Positions relative to
 * {@link #origin} in float.
 */
public final class HullContact {

    /** Per pair. */
    public static final int MAX_POINTS = 16;
    private static final int ITERATIONS = 16;
    /** Face axis kept unless the edge overlap is this much shallower (relative, absolute): no flicker on flush faces. */
    private static final float EDGE_RELATIVE = 0.95f;
    private static final float EDGE_ABSOLUTE = 0.01f;
    /**
     * Split impulse: share of (depth - slop) pushed out per tick, as displacement, never velocity. Velocity bias
     * FORBIDDEN: kept as speed => kicks 1-4 b/t out of deep piles ({@code PitBench}). Rest overlap ~ G / PUSH + SLOP.
     */
    private static final float PUSH = 0.5f;
    private static final float SLOP = 0.02f;
    private static final float MAX_PUSH = 0.25f;
    private static final float RESTITUTION = 0.05f;
    /** ~2 G: resting body closes ~G (prepaid), not 0. */
    private static final float G_BOUND = 0.05f;
    private static final float FRICTION = 0.5f;
    /**
     * |n.y| > this: linear only; angular response on a stacked hull just rocks it (hiace off a hiace). All
     * contacts angular FORBIDDEN: COM inside the patch tipped 35-39 deg, PitBench launched a Merkava 227 b/t
     * ({@code StackBench}). Gap: no tipping off another hull.
     */
    private static final float STACKING_NORMAL = 0.7f;
    /** Grounded body, normal this far into the ground: the ground takes the torque too. */
    private static final float PINNED_NORMAL = 0.5f;

    /** One body of the island, velocities per tick. Caller fills, solve writes dv/dw/push. */
    public static final class Body {
        public float inverseMass;
        public final Matrix3f inverseInertia = new Matrix3f();
        /** COM relative to origin. */
        public final Vector3f com = new Vector3f();
        /** Next tick's gravity included. */
        public final Vector3f velocity = new Vector3f();
        public final Vector3f spin = new Vector3f();
        /**
         * Grounded: the ground takes the downward part of an impulse, never the horizontal. Held (whole impulse
         * dropped, infinite mass) FORBIDDEN: a grounded humvee at 1.2 b/t wedged under an M1A2 lifted it and its
         * stack 1.7 b/t ({@code PitBench}).
         */
        public boolean grounded;
        /**
         * World blocks: static; contacts only bound the solve's own increments (dv, dw, push) into the world. Base
         * velocity and existing overlap stay {@code PhysicsEngine}'s. Absent => wall-pinned humvee shoved up 3.5
         * blocks by a tank ({@code StackBench} sandwich); push pass only => still 3.4.
         */
        public boolean world;
        public final Vector3f dv = new Vector3f();
        public final Vector3f dw = new Vector3f();
        /** Displacement out of penetration, blocks; not velocity. */
        public final Vector3f push = new Vector3f();

        /**
         * Impulse {@code amount * direction}; grounded + (friction or downward direction): vertical dv zeroed. Decided
         * on direction, as {@link #response}; on the signed impulse a backing-off iteration moved a grounded body
         * => gain ~ mass ratio per iteration ({@code PitBench}: 2.5e5 b/t).
         */
        void apply(Vector3f direction, float amount, Vector3f arm, float sign, boolean spins, boolean friction,
                   Vector3f scratch) {
            linear(dv, direction, amount, sign, friction);
            if (spins && (friction || !pinned(direction, sign))) {
                arm.cross(direction, scratch).mul(sign * amount);
                dw.add(inverseInertia.transform(scratch));
            }
        }

        void shift(Vector3f direction, float amount, float sign) {
            linear(push, direction, amount, sign, false);
        }

        private void linear(Vector3f into, Vector3f direction, float amount, float sign, boolean friction) {
            boolean flat = grounded && (friction || sign * direction.y < 0);
            float scale = sign * amount * inverseMass;
            into.add(direction.x * scale, flat ? 0 : direction.y * scale, direction.z * scale);
        }

        private boolean pinned(Vector3f direction, float sign) {
            return grounded && sign * direction.y < -PINNED_NORMAL * direction.length();
        }

        /**
         * v + w x r with the solve's increments; !spins => v only, as {@link #response}. Spin read by a linear-only
         * contact is never paid back: a 1.4 t launcher tumbling 0.13 rad/t lifted a 65 t hull 0.3 b/t per tick.
         */
        Vector3f pointVelocity(Vector3f arm, boolean spins, Vector3f dest) {
            if (!spins) {
                return dest.set(velocity).add(dv);
            }
            dest.set(spin).add(dw).cross(arm);
            return dest.add(velocity).add(dv);
        }

        /** As {@link #pointVelocity}, increments only. */
        Vector3f pointIncrement(Vector3f arm, boolean spins, Vector3f dest) {
            if (!spins) {
                return dest.set(dv);
            }
            return dest.set(dw).cross(arm).add(dv);
        }

        /** n . M^-1 n + (I^-1 (r x n) x r) . n, impulse sign * n on this body. */
        float response(Vector3f arm, Vector3f n, float sign, boolean spins, boolean friction, Vector3f scratch) {
            float linear = inverseMass * (grounded && (friction || sign * n.y < 0) ? n.x * n.x + n.z * n.z : 1);
            if (!spins || !friction && pinned(n, sign)) {
                return linear;
            }
            arm.cross(n, scratch);
            inverseInertia.transform(scratch);
            scratch.cross(arm);
            return linear + scratch.dot(n);
        }
    }

    public final double[] origin = new double[3];
    private final List<Body> bodies = new ArrayList<>();
    private int bodyCount;
    private int capacity = MAX_POINTS;
    private float[] px = new float[capacity], py = new float[capacity], pz = new float[capacity];
    private float[] nx = new float[capacity], ny = new float[capacity], nz = new float[capacity];
    private float[] depths = new float[capacity];
    private float[] normalImpulse = new float[capacity];
    private float[] tangentImpulse = new float[capacity];
    private float[] targets = new float[capacity];
    private boolean[] spinning = new boolean[capacity];
    /** Contact with a {@link Body#world} body. */
    private boolean[] guard = new boolean[capacity];
    private int[] bodyA = new int[capacity], bodyB = new int[capacity];
    private int count;
    /** Current pair: first point, bodies. */
    private int pairStart, pairA, pairB;

    private final Vector3f[] axesA = {new Vector3f(), new Vector3f(), new Vector3f()};
    private final Vector3f[] axesB = {new Vector3f(), new Vector3f(), new Vector3f()};
    private final float[] extentA = new float[3], extentB = new float[3];
    private final Vector3f d = new Vector3f(), axis = new Vector3f(), normal = new Vector3f();
    private final Vector3f t0 = new Vector3f(), t1 = new Vector3f(), t2 = new Vector3f(), t3 = new Vector3f();
    private final Vector3f va = new Vector3f(), vb = new Vector3f(), tangent = new Vector3f();
    private final float[] polygon = new float[48], clipped = new float[48];
    private final HullPieces singleA = new HullPieces(), singleB = new HullPieces();

    /** New island: no bodies, no contacts. */
    public void reset(double x, double y, double z) {
        origin[0] = x;
        origin[1] = y;
        origin[2] = z;
        count = 0;
        bodyCount = 0;
        pairStart = 0;
        pairA = -1;
        pairB = -1;
    }

    /** Next body, zeroed outputs; index = bodies added before it. */
    public Body addBody() {
        if (bodyCount == bodies.size()) {
            bodies.add(new Body());
        }
        Body body = bodies.get(bodyCount++);
        body.world = false;
        body.dv.zero();
        body.dw.zero();
        body.push.zero();
        return body;
    }

    public Body body(int i) {
        return bodies.get(i);
    }

    /** Following {@link #collide} calls: contacts between bodies a and b, at most {@link #MAX_POINTS}. */
    public void pair(int a, int b) {
        closePair();
        pairStart = count;
        pairA = a;
        pairB = b;
    }

    /** Closes the current pair; returns its first contact index (pair = [first, count)). */
    public int endPair() {
        closePair();
        int first = pairStart;
        pairStart = count;
        return first;
    }

    /** Contact for the current pair, as {@link #collide} would add it. */
    public void addContact(float x, float y, float z, float normalX, float normalY, float normalZ, float depth) {
        normal.set(normalX, normalY, normalZ);
        add(x, y, z, depth);
    }

    /**
     * Drops points opposing the pair's depth-weighted normal. Deep overlap => piece SAT axes disagree (+n and -n
     * in one pair); both kept => body pinched out at 4.7 b/t ({@code PitBench}).
     */
    private void closePair() {
        float sx = 0, sy = 0, sz = 0;
        for (int k = pairStart; k < count; k++) {
            sx += nx[k] * depths[k];
            sy += ny[k] * depths[k];
            sz += nz[k] * depths[k];
        }
        int kept = pairStart;
        for (int k = pairStart; k < count; k++) {
            if (nx[k] * sx + ny[k] * sy + nz[k] * sz < 0) {
                continue;
            }
            px[kept] = px[k];
            py[kept] = py[k];
            pz[kept] = pz[k];
            nx[kept] = nx[k];
            ny[kept] = ny[k];
            nz[kept] = nz[k];
            depths[kept] = depths[k];
            bodyA[kept] = bodyA[k];
            bodyB[kept] = bodyB[k];
            kept++;
        }
        count = kept;
    }

    public int count() {
        return count;
    }

    public float depth(int i) {
        return depths[i];
    }

    public Vector3f point(int i, Vector3f dest) {
        return dest.set(px[i], py[i], pz[i]);
    }

    public Vector3f normal(int i, Vector3f dest) {
        return dest.set(nx[i], ny[i], nz[i]);
    }

    /**
     * Adds a vs b contacts, normal a -> b.
     * @return overlap depth; <= 0 separated (nothing added)
     */
    public float collide(OBB a, OBB b) {
        singleA.load(a);
        singleB.load(b);
        return collide(singleA, 0, singleB, 0);
    }

    /** As {@link #collide(OBB, OBB)}, piece i of a vs piece j of b. */
    public float collide(HullPieces a, int i0, HullPieces b, int j0) {
        for (int i = 0; i < 3; i++) {
            axesA[i].set(a.axis(i0, i, 0), a.axis(i0, i, 1), a.axis(i0, i, 2));
            axesB[i].set(b.axis(j0, i, 0), b.axis(j0, i, 1), b.axis(j0, i, 2));
            extentA[i] = a.extent(i0, i);
            extentB[i] = b.extent(j0, i);
        }
        double acx = a.centre(i0, 0), acy = a.centre(i0, 1), acz = a.centre(i0, 2);
        d.set((float) (b.centre(j0, 0) - acx), (float) (b.centre(j0, 1) - acy), (float) (b.centre(j0, 2) - acz));
        float ax = (float) (acx - origin[0]), ay = (float) (acy - origin[1]), az = (float) (acz - origin[2]);

        float bestFace = Float.MAX_VALUE;
        int faceAxis = -1;
        for (int i = 0; i < 6; i++) {
            Vector3f n = i < 3 ? axesA[i] : axesB[i - 3];
            float overlap = radius(n, axesA, extentA) + radius(n, axesB, extentB) - Math.abs(d.dot(n));
            if (overlap <= 0) {
                return overlap;
            }
            if (overlap < bestFace) {
                bestFace = overlap;
                faceAxis = i;
            }
        }
        float bestEdge = Float.MAX_VALUE;
        int edgeI = -1, edgeJ = -1;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                axesA[i].cross(axesB[j], axis);
                float length = axis.length();
                if (length < 1.0e-4f) {
                    continue;
                }
                axis.div(length);
                float overlap = radius(axis, axesA, extentA) + radius(axis, axesB, extentB) - Math.abs(d.dot(axis));
                if (overlap <= 0) {
                    return overlap;
                }
                if (overlap < bestEdge) {
                    bestEdge = overlap;
                    edgeI = i;
                    edgeJ = j;
                }
            }
        }
        if (edgeI >= 0 && bestEdge < EDGE_RELATIVE * bestFace - EDGE_ABSOLUTE) {
            axesA[edgeI].cross(axesB[edgeJ], normal).normalize();
            if (normal.dot(d) < 0) {
                normal.negate();
            }
            edgeContact(ax, ay, az, edgeI, edgeJ, bestEdge);
            return bestEdge;
        }
        boolean referenceA = faceAxis < 3;
        Vector3f[] refAxes = referenceA ? axesA : axesB;
        Vector3f[] incAxes = referenceA ? axesB : axesA;
        float[] refExtent = referenceA ? extentA : extentB;
        float[] incExtent = referenceA ? extentB : extentA;
        int refAxis = faceAxis % 3;
        Vector3f refNormal = t0.set(refAxes[refAxis]);
        float toward = referenceA ? d.dot(refNormal) : -d.dot(refNormal);
        if (toward < 0) {
            refNormal.negate();
        }
        normal.set(refNormal);
        if (!referenceA) {
            normal.negate();
        }
        float rcx = referenceA ? ax : ax + d.x, rcy = referenceA ? ay : ay + d.y, rcz = referenceA ? az : az + d.z;
        float icx = referenceA ? ax + d.x : ax, icy = referenceA ? ay + d.y : ay, icz = referenceA ? az + d.z : az;
        float fcx = rcx + refNormal.x * refExtent[refAxis];
        float fcy = rcy + refNormal.y * refExtent[refAxis];
        float fcz = rcz + refNormal.z * refExtent[refAxis];
        // Incident axis: most aligned (either sign) with refNormal; signed below.
        int incAxis = 0;
        float most = -1;
        for (int k = 0; k < 3; k++) {
            float along = Math.abs(incAxes[k].dot(refNormal));
            if (along > most) {
                most = along;
                incAxis = k;
            }
        }
        float sign = incAxes[incAxis].dot(refNormal) > 0 ? -1 : 1;
        int u = (incAxis + 1) % 3, v = (incAxis + 2) % 3;
        float ccx = icx + incAxes[incAxis].x * sign * incExtent[incAxis];
        float ccy = icy + incAxes[incAxis].y * sign * incExtent[incAxis];
        float ccz = icz + incAxes[incAxis].z * sign * incExtent[incAxis];
        int n = 0;
        for (int corner = 0; corner < 4; corner++) {
            float su = corner == 0 || corner == 3 ? 1 : -1;
            float sv = corner < 2 ? 1 : -1;
            polygon[n * 3] = ccx + incAxes[u].x * su * incExtent[u] + incAxes[v].x * sv * incExtent[v];
            polygon[n * 3 + 1] = ccy + incAxes[u].y * su * incExtent[u] + incAxes[v].y * sv * incExtent[v];
            polygon[n * 3 + 2] = ccz + incAxes[u].z * su * incExtent[u] + incAxes[v].z * sv * incExtent[v];
            n++;
        }
        int ru = (refAxis + 1) % 3, rv = (refAxis + 2) % 3;
        n = clip(n, refAxes[ru], fcx, fcy, fcz, refExtent[ru]);
        n = clip(n, refAxes[rv], fcx, fcy, fcz, refExtent[rv]);
        for (int k = 0; k < n; k++) {
            float qx = polygon[k * 3], qy = polygon[k * 3 + 1], qz = polygon[k * 3 + 2];
            float separation = (qx - fcx) * refNormal.x + (qy - fcy) * refNormal.y + (qz - fcz) * refNormal.z;
            if (separation > 0) {
                continue;
            }
            // Contact point: midway between incident point and its projection on the reference face.
            add(qx - refNormal.x * separation * 0.5f, qy - refNormal.y * separation * 0.5f,
                    qz - refNormal.z * separation * 0.5f, -separation);
        }
        return bestFace;
    }

    /** Keeps |(p - c) . axis| <= extent: two half-plane passes over polygon. */
    private int clip(int n, Vector3f axis, float cx, float cy, float cz, float extent) {
        n = clipHalf(n, axis, cx, cy, cz, extent, 1);
        return clipHalf(n, axis, cx, cy, cz, extent, -1);
    }

    private int clipHalf(int n, Vector3f axis, float cx, float cy, float cz, float extent, float side) {
        int out = 0;
        for (int k = 0; k < n; k++) {
            int next = (k + 1) % n;
            float ax = polygon[k * 3], ay = polygon[k * 3 + 1], az = polygon[k * 3 + 2];
            float bx = polygon[next * 3], by = polygon[next * 3 + 1], bz = polygon[next * 3 + 2];
            float da = side * ((ax - cx) * axis.x + (ay - cy) * axis.y + (az - cz) * axis.z) - extent;
            float db = side * ((bx - cx) * axis.x + (by - cy) * axis.y + (bz - cz) * axis.z) - extent;
            if (da <= 0) {
                clipped[out * 3] = ax;
                clipped[out * 3 + 1] = ay;
                clipped[out * 3 + 2] = az;
                out++;
            }
            if ((da < 0) != (db < 0) && da != db) {
                float t = da / (da - db);
                clipped[out * 3] = ax + (bx - ax) * t;
                clipped[out * 3 + 1] = ay + (by - ay) * t;
                clipped[out * 3 + 2] = az + (bz - az) * t;
                out++;
            }
        }
        System.arraycopy(clipped, 0, polygon, 0, out * 3);
        return out;
    }

    private void edgeContact(float ax, float ay, float az, int i, int j, float depth) {
        // Support point of A toward +normal, of B toward -normal, along their respective off-edge axes.
        Vector3f pa = t0.set(ax, ay, az), pb = t1.set(ax + d.x, ay + d.y, az + d.z);
        for (int k = 0; k < 3; k++) {
            if (k != i) {
                pa.fma(Math.signum(normal.dot(axesA[k])) * extentA[k], axesA[k]);
            }
            if (k != j) {
                pb.fma(-Math.signum(normal.dot(axesB[k])) * extentB[k], axesB[k]);
            }
        }
        Vector3f ea = axesA[i], eb = axesB[j];
        Vector3f w = t2.set(pa).sub(pb);
        float b = ea.dot(eb), dd = ea.dot(w), e = eb.dot(w);
        float denominator = 1 - b * b;
        float s = denominator > 1.0e-6f ? (b * e - dd) / denominator : 0;
        s = Math.max(-extentA[i], Math.min(extentA[i], s));
        float t = Math.max(-extentB[j], Math.min(extentB[j], b * s + e));
        pa.fma(s, ea);
        pb.fma(t, eb);
        add((pa.x + pb.x) * 0.5f, (pa.y + pb.y) * 0.5f, (pa.z + pb.z) * 0.5f, depth);
    }

    private void add(float x, float y, float z, float depth) {
        int slot = count;
        if (count - pairStart == MAX_POINTS) {
            slot = pairStart;
            for (int k = pairStart + 1; k < count; k++) {
                if (depths[k] < depths[slot]) {
                    slot = k;
                }
            }
            if (depths[slot] >= depth) {
                return;
            }
        } else {
            if (count == capacity) {
                grow();
            }
            count++;
        }
        px[slot] = x;
        py[slot] = y;
        pz[slot] = z;
        nx[slot] = normal.x;
        ny[slot] = normal.y;
        nz[slot] = normal.z;
        depths[slot] = depth;
        bodyA[slot] = pairA;
        bodyB[slot] = pairB;
    }

    private void grow() {
        capacity *= 2;
        px = Arrays.copyOf(px, capacity);
        py = Arrays.copyOf(py, capacity);
        pz = Arrays.copyOf(pz, capacity);
        nx = Arrays.copyOf(nx, capacity);
        ny = Arrays.copyOf(ny, capacity);
        nz = Arrays.copyOf(nz, capacity);
        depths = Arrays.copyOf(depths, capacity);
        normalImpulse = new float[capacity];
        tangentImpulse = new float[capacity];
        targets = new float[capacity];
        spinning = new boolean[capacity];
        guard = new boolean[capacity];
        bodyA = Arrays.copyOf(bodyA, capacity);
        bodyB = Arrays.copyOf(bodyB, capacity);
    }

    private static float radius(Vector3f axis, Vector3f[] axes, float[] extents) {
        return Math.abs(axis.dot(axes[0])) * extents[0] + Math.abs(axis.dot(axes[1])) * extents[1]
                + Math.abs(axis.dot(axes[2])) * extents[2];
    }

    /**
     * Impulses along each point's normal (a -> b) and against slip, then pushes out of depth; every contact of the
     * island per iteration => support passes through a stack (pairwise: 65 t hull sank through a 2 t one at 0.6 b/t).
     */
    public void solve() {
        closePair();
        pairStart = count;
        Vector3f n = t0, ra = t1, rb = t2, scratch = t3;
        float fastest = 0;
        for (int k = 0; k < count; k++) {
            Body a = bodies.get(bodyA[k]), b = bodies.get(bodyB[k]);
            n.set(nx[k], ny[k], nz[k]);
            ra.set(px[k], py[k], pz[k]).sub(a.com);
            rb.set(px[k], py[k], pz[k]).sub(b.com);
            spinning[k] = Math.abs(n.y) <= STACKING_NORMAL;
            guard[k] = a.world || b.world;
            float closing = b.pointVelocity(rb, spinning[k], vb).sub(a.pointVelocity(ra, spinning[k], va)).dot(n);
            targets[k] = guard[k] ? 0 : Math.max(0, -RESTITUTION * closing);
            if (!guard[k]) {
                fastest = Math.max(fastest, -closing);
            }
            normalImpulse[k] = 0;
            tangentImpulse[k] = 0;
        }
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            for (int k = 0; k < count; k++) {
                Body a = bodies.get(bodyA[k]), b = bodies.get(bodyB[k]);
                n.set(nx[k], ny[k], nz[k]);
                ra.set(px[k], py[k], pz[k]).sub(a.com);
                rb.set(px[k], py[k], pz[k]).sub(b.com);
                boolean spins = spinning[k];
                float response = a.response(ra, n, -1, spins, false, scratch) + b.response(rb, n, 1, spins, false, scratch);
                if (response <= 1.0e-12f) {
                    continue;
                }
                Vector3f relative = guard[k] ? b.pointIncrement(rb, spins, vb).sub(a.pointIncrement(ra, spins, va))
                        : b.pointVelocity(rb, spins, vb).sub(a.pointVelocity(ra, spins, va));
                float next = Math.max(0, normalImpulse[k] + (targets[k] - relative.dot(n)) / response);
                float delta = next - normalImpulse[k];
                normalImpulse[k] = next;
                b.apply(n, delta, rb, 1, spins, false, scratch);
                a.apply(n, delta, ra, -1, spins, false, scratch);
                if (guard[k]) {
                    continue;
                }

                relative = b.pointVelocity(rb, spins, vb).sub(a.pointVelocity(ra, spins, va));
                tangent.set(relative).fma(-relative.dot(n), n);
                float slip = tangent.length();
                if (slip < 1.0e-6f) {
                    continue;
                }
                tangent.div(slip);
                float tangentResponse = a.response(ra, tangent, 1, spins, true, scratch) + b.response(rb, tangent, -1, spins, true, scratch);
                if (tangentResponse <= 1.0e-12f) {
                    continue;
                }
                float limit = FRICTION * normalImpulse[k];
                // Accumulated along the current slip direction (scalar): approximate, direction drifts per pass.
                float nextTangent = Math.min(limit, tangentImpulse[k] + slip / tangentResponse);
                float tangentDelta = nextTangent - tangentImpulse[k];
                tangentImpulse[k] = nextTangent;
                b.apply(tangent, -tangentDelta, rb, 1, spins, true, scratch);
                a.apply(tangent, -tangentDelta, ra, -1, spins, true, scratch);
            }
        }
        // Infeasible (wedged between world and deep hull rows) => impulses unbounded: Centauro 6.4 b/t ({@code
        // PitBench}). No contact returns more than it closed.
        float maxDv = fastest * (1 + RESTITUTION) + G_BOUND;
        for (int i = 0; i < bodyCount; i++) {
            Vector3f dv = bodies.get(i).dv;
            float length = dv.length();
            if (length > maxDv) {
                dv.mul(maxDv / length);
            }
        }
        for (int k = 0; k < count; k++) {
            targets[k] = guard[k] ? 0 : Math.min(MAX_PUSH, PUSH * Math.max(0, depths[k] - SLOP));
            normalImpulse[k] = 0;
        }
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            for (int k = 0; k < count; k++) {
                if (targets[k] == 0 && !guard[k]) {
                    continue;
                }
                Body a = bodies.get(bodyA[k]), b = bodies.get(bodyB[k]);
                n.set(nx[k], ny[k], nz[k]);
                float response = a.response(ra, n, -1, false, false, scratch) + b.response(rb, n, 1, false, false, scratch);
                if (response <= 1.0e-12f) {
                    continue;
                }
                float separating = scratch.set(b.push).sub(a.push).dot(n);
                float next = Math.max(0, normalImpulse[k] + (targets[k] - separating) / response);
                float delta = next - normalImpulse[k];
                normalImpulse[k] = next;
                b.shift(n, delta, 1);
                a.shift(n, delta, -1);
            }
        }
        // Infeasible loop (hull sunk through another, sandwiched) => accumulated push unbounded: 13 blocks (PitBench).
        for (int i = 0; i < bodyCount; i++) {
            Vector3f push = bodies.get(i).push;
            float length = push.length();
            if (length > MAX_PUSH) {
                push.mul(MAX_PUSH / length);
            }
        }
    }
}
