package com.wf.combinedassault.vehicle.sightline;

import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;
import com.wf.combinedassault.vehicle.crew.CrewRole;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.RotatableUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.part.data.OpticSpec;
import com.wf.combinedassault.weapon.part.data.WeaponUnitData;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Near-occluder audit of every first-person camera of a vehicle: rays through a {@link #W} x {@link #H} grid of the
 * real frustum (16:9, per zoom level) against {@link SightMesh} as posed; a ray is obstructed by a drawn surface
 * within {@link #REACH} of the eye. Sweep: own station yaw x pitch (or the ride frame's station), each switchable
 * flipped alone from spawn state, the seat's {@code hatch} held open; other stations at spawn. Worst pose reported.
 */
public final class Sightlines {

    /** MC near plane, m. */
    public static final float NEAR = 0.05f;
    public static final float REACH = 2.0f;
    public static final int W = 64;
    public static final int H = 36;
    /** MC default FOV option (vertical), deg. */
    public static final float FOV = 70;
    /** Centre box half-extent, NDC: box = 10% of the frame. */
    public static final float CENTRE = (float) Math.sqrt(0.1);
    /** Horizon band, +- deg elevation. */
    public static final float BAND = 10;
    /** Rider eye above a station pivot without a {@code view} socket (player eye height). */
    private static final float EYE = 1.62f;
    private static final float YAW_STEP = 30;
    /** Bore from the station's pitch frame meets its own vehicle within this, m => pose dropped (gun in the hull). */
    public static final float SELF = 4;

    /** {@code limit}: share of the role's region obstructed at most; SIGHT also needs a clear centre. */
    public enum Role {
        SIGHT(0.05f), DRIVER(0.05f), CREW(0.15f), PASSENGER(1);

        public final float limit;

        Role(float limit) {
            this.limit = limit;
        }
    }

    /** @param hideable subtree drawn wholly within {@link ViewHide#LIMIT} of the rest eye, base model */
    public record Occluder(String node, float share, boolean hideable) {
    }

    /**
     * @param eye    rest pose, model frame
     * @param centre SIGHT: obstructed centre rays
     * @param frac    obstructed share: SIGHT outside the centre, DRIVER horizon band, else whole frame
     * @param selfAim poses dropped: bore into the own vehicle ({@link #SELF})
     */
    public record Result(String part, String camera, Role role, Vector3f eye, int centre, float frac, boolean pass,
                         String pose, int selfAim, List<Occluder> occluders) {

        public String line() {
            StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%s %s %s %s centre %d frac %.3f @ %s",
                    part, camera, role, pass ? "ok" : "FAIL", centre, frac, pose));
            if (selfAim > 0) {
                out.append(" selfaim ").append(selfAim);
            }
            for (Occluder o : occluders) {
                out.append(String.format(Locale.ROOT, " | %s %.3f%s", o.node, o.share, o.hideable ? " hideable" : ""));
            }
            return out.toString();
        }
    }

    private record Cam(PartUnit<?> seat, String kind, Role role, @Nullable RotatableUnit<?> station, float[] zooms,
                       Supplier<Vec3> eye, Supplier<Quaternionf> look) {
    }

    private final AbstractVehicle vehicle;
    private final VehicleHits hits;
    private final SightMesh mesh;
    private final float[] depth = new float[W * H];
    private final int[] who = new int[W * H];
    private final float[] dir = new float[W * H * 3];
    private final float[] ndc = new float[W * H * 2];
    private float fovBuilt = -1;
    private float tanV;
    private float tanH;

    public Sightlines(AbstractVehicle vehicle, SightMesh mesh) {
        this.vehicle = vehicle;
        this.hits = vehicle.hits();
        this.mesh = mesh;
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                ndc[(j * W + i) * 2] = 2 * (i + 0.5f) / W - 1;
                ndc[(j * W + i) * 2 + 1] = 1 - 2 * (j + 0.5f) / H;
            }
        }
    }

    public List<Result> audit() {
        vehicle.updateOBBs();
        List<Result> out = new ArrayList<>();
        for (Cam cam : cameras()) {
            out.add(audit(cam));
        }
        return out;
    }

    private List<Cam> cameras() {
        List<Cam> out = new ArrayList<>();
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            PartUnit<?> part = seat.partUnit;
            boolean drives = CrewRules.roles(seat).contains(CrewRole.DRIVER);
            if (part instanceof WeaponUnit unit) {
                boolean sighted = unit.getOpticalSightType() != WeaponUnitData.OpticalSightType.NONE;
                boolean traverses = (unit.yRotMax > unit.yRotMin && unit.yRotSpeed > 0
                        || unit.xRotMax > unit.xRotMin && unit.xRotSpeed > 0) && !unit.indirectFire().isIndirect();
                if (!(vehicle.uav && sighted)) {
                    // LocalVehiclePlayer.gunLook: aircraft pilot's head stays in the airframe.
                    boolean gunLook = traverses && !(drives && cockpit());
                    // crt: the feed is the view; head sits in the interior.
                    Role role = unit.getOpticalSightType() == WeaponUnitData.OpticalSightType.CRT ? Role.PASSENGER
                            : drives ? Role.DRIVER : Role.CREW;
                    out.add(new Cam(part, "view", role, traverses ? unit : null, new float[]{1},
                            () -> unit.ownerViewPosition(false, EYE, 1.0F),
                            () -> gunLook ? barrel(unit) : vehicle.rotYXZ()));
                }
                if (sighted) {
                    out.add(sight(part, unit));
                }
                continue;
            }
            RotatableUnit<?> ride = rideStation(part);
            boolean periscope = part.getData().getPeriscopeOffset() != null;
            // Periscope seat: buttoned head in the interior; the periscope is its view.
            Role role = periscope ? Role.PASSENGER : drives ? Role.DRIVER
                    : part.getData().getHatch() != null ? Role.CREW : Role.PASSENGER;
            out.add(new Cam(part, "view", role, ride, new float[]{1}, () -> part.worldOwnerViewPosition(1.0F),
                    () -> part.rideRotation(1.0F)));
            if (periscope) {
                out.add(new Cam(part, "periscope", drives ? Role.DRIVER : Role.SIGHT, ride, new float[]{1},
                        () -> part.worldPeriscopePosition(1.0F), () -> part.rideRotation(1.0F)));
            }
            if (part.sensorUnit() != null) {
                out.add(sight(part, part.sensorUnit()));
            }
        }
        return out;
    }

    /** SCOPE camera; optic PiP: picture origin {@code objective} ahead, the optic's magnifications. */
    private Cam sight(PartUnit<?> seat, WeaponUnit unit) {
        OpticSpec optic = unit.adsCapable() && unit.lensNode() != null ? unit.optic() : null;
        float[] zooms;
        if (optic != null) {
            zooms = new float[optic.zoom().size()];
            for (int i = 0; i < zooms.length; i++) {
                zooms[i] = optic.zoom().get(i);
            }
        } else {
            List<Float> steps = new ArrayList<>();
            float max = unit.getZoomMax();
            for (float z = Math.max(1, unit.getData().getZoomMin()); ; z = Math.min(z * 2, max)) {
                steps.add(z);
                if (z >= max) {
                    break;
                }
            }
            zooms = new float[steps.size()];
            for (int i = 0; i < zooms.length; i++) {
                zooms[i] = steps.get(i);
            }
        }
        float objective = optic != null ? optic.objective() : 0;
        return new Cam(seat, "sight", Role.SIGHT, unit, zooms, () -> {
            Vec3 eye = unit.scopeEye(EYE);
            Vector3f ahead = barrel(unit).transform(new Vector3f(0, 0, objective));
            return eye.add(ahead.x, ahead.y, ahead.z);
        }, () -> barrel(unit));
    }

    private boolean cockpit() {
        return vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle && !vehicle.uav;
    }

    /** Camera along the bore ({@code LocalVehiclePlayer.cameraAimWith}, zeroing aside). */
    private static Quaternionf barrel(WeaponUnit unit) {
        Vec2 rot = unit.worldRot(unit.getXRot() + unit.xBarrelSelfRot, unit.getYRot() + unit.yBarrelSelfRot);
        return new Quaternionf().rotateY((float) Math.toRadians(-rot.y)).rotateX((float) Math.toRadians(rot.x))
                .rotateZ((float) Math.toRadians(unit.worldZRot()));
    }

    /** Station turning the seat's ride frame (or an ancestor of it), else null. */
    @Nullable
    private RotatableUnit<?> rideStation(PartUnit<?> seat) {
        for (VehicleCubeGroup group = seat.rideGroup(); group != null; group = group.parent) {
            for (PartUnit<?> part : vehicle.getPartUnits()) {
                if (part instanceof RotatableUnit<?> unit && part.getStructureGroup() == group) {
                    return unit;
                }
            }
        }
        return null;
    }

    private Result audit(Cam cam) {
        List<SwitchableUnit<?>> switches = new ArrayList<>();
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof SwitchableUnit<?> s) {
                switches.add(s);
            }
        }
        boolean[] spawn = new boolean[switches.size()];
        for (int i = 0; i < spawn.length; i++) {
            spawn[i] = switches.get(i).isOn();
        }
        String hatch = cam.seat.getData().getHatch();
        RotatableUnit<?> station = cam.station;
        float x0 = station == null ? 0 : station.getXRot();
        float y0 = station == null ? 0 : station.getYRot();
        boolean[] hidden = hidden(cam.seat);

        Vector3f eye = rest(cam, switches, spawn, hatch);
        Worst worst = new Worst(cam.role);
        for (int flip = -1; flip < switches.size(); flip++) {
            if (flip >= 0 && switches.get(flip).getId().equals(hatch)) {
                continue;
            }
            String open = state(switches, spawn, hatch, flip);
            for (float yaw : station == null || station.yRotSpeed <= 0 ? new float[]{y0} : yaws(station)) {
                for (float pitch : station == null || station.xRotSpeed <= 0 ? new float[]{x0} : pitches(station)) {
                    if (station != null) {
                        station.setXRot(pitch);
                        station.setYRot(yaw);
                        station.updateRot();
                        vehicle.updateOBBs();
                    }
                    Vec3 at = cam.eye.get();
                    Quaternionf look = cam.look.get();
                    if (station != null && intoHull(station, look)) {
                        worst.selfAim++;
                        continue;
                    }
                    for (float zoom : cam.zooms) {
                        float fov = (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(FOV / 2)) / zoom));
                        trace(at, look, fov, hidden);
                        worst.offer(this, look, String.format(Locale.ROOT, "yaw %.0f pitch %.0f%s zoom %s", yaw, pitch,
                                open, zoom));
                    }
                }
            }
        }
        if (station != null) {
            station.setXRot(x0);
            station.setYRot(y0);
            station.updateRot();
        }
        for (int i = 0; i < spawn.length; i++) {
            switches.get(i).setOn(spawn[i]);
        }
        vehicle.settleClips();

        List<Occluder> occluders = new ArrayList<>();
        for (int node = 0; node < worst.who.length; node++) {
            if (worst.who[node] > 0) {
                boolean hideable = mesh.kitOf().get(node) == null && mesh.reach(node, eye) <= ViewHide.LIMIT;
                occluders.add(new Occluder(mesh.names().get(node), (float) worst.who[node] / worst.region, hideable));
            }
        }
        occluders.sort(Comparator.comparingDouble(o -> -o.share));
        boolean pass = switch (cam.role) {
            case SIGHT -> worst.centre == 0 && worst.frac <= cam.role.limit;
            case PASSENGER -> true;
            default -> worst.frac <= cam.role.limit;
        };
        return new Result(cam.seat.getId(), cam.kind, cam.role, eye, worst.centre, worst.frac, pass, worst.pose,
                worst.selfAim, List.copyOf(occluders));
    }

    /** {@code station}'s bore ({@code look} from its pitch frame) meets another part's / the hull's drawn surface. */
    private boolean intoHull(RotatableUnit<?> station, Quaternionf look) {
        int frame = mesh.bore(station.getId());
        if (frame < 0) {
            return false;
        }
        Vec3 from = hits.origin(frame);
        Vector3f d = look.transform(new Vector3f(0, 0, SELF));
        Vec3 to = from.add(d.x, d.y, d.z);
        Matrix4f pose = new Matrix4f();
        Vector3f o = new Vector3f();
        Vector3f dir = new Vector3f();
        float[] tris;
        for (int node = 0; node < mesh.names().size(); node++) {
            if ((tris = mesh.tris()[node]) == null || !hits.live(node) || hits.part(node) == station) {
                continue;
            }
            hits.pose(node, from, pose).invert();
            pose.transformPosition(o.set(0, 0, 0));
            pose.transformDirection(dir.set((float) (to.x - from.x), (float) (to.y - from.y), (float) (to.z - from.z)));
            for (int t = 0; t < tris.length; t += 9) {
                if (segment(tris, t, o, dir)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Moller-Trumbore, both faces, {@code o + s dir}, 0 < s < 1. */
    private static boolean segment(float[] v, int t, Vector3f o, Vector3f d) {
        float e1x = v[t + 3] - v[t], e1y = v[t + 4] - v[t + 1], e1z = v[t + 5] - v[t + 2];
        float e2x = v[t + 6] - v[t], e2y = v[t + 7] - v[t + 1], e2z = v[t + 8] - v[t + 2];
        float hx = d.y * e2z - d.z * e2y, hy = d.z * e2x - d.x * e2z, hz = d.x * e2y - d.y * e2x;
        float det = e1x * hx + e1y * hy + e1z * hz;
        if (Math.abs(det) < 1.0e-12f) {
            return false;
        }
        float inv = 1 / det;
        float sx = o.x - v[t], sy = o.y - v[t + 1], sz = o.z - v[t + 2];
        float u = (sx * hx + sy * hy + sz * hz) * inv;
        if (u < 0 || u > 1) {
            return false;
        }
        float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        float w = (d.x * qx + d.y * qy + d.z * qz) * inv;
        if (w < 0 || u + w > 1) {
            return false;
        }
        float s = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return s > 0 && s < 1;
    }

    /** Eye at spawn pose (seat hatch open), model frame. */
    private Vector3f rest(Cam cam, List<SwitchableUnit<?>> switches, boolean[] spawn, @Nullable String hatch) {
        state(switches, spawn, hatch, -1);
        Vec3 world = cam.eye.get().subtract(vehicle.position());
        return new Quaternionf(vehicle.rotYXZ()).conjugate().transform(world.toVector3f());
    }

    /** Spawn states, seat hatch on, switch {@code flip} inverted; settled. Returns its label. */
    private String state(List<SwitchableUnit<?>> switches, boolean[] spawn, @Nullable String hatch, int flip) {
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < spawn.length; i++) {
            SwitchableUnit<?> s = switches.get(i);
            boolean on = s.getId().equals(hatch) || (i == flip) != spawn[i];
            s.setOn(on);
            if (on) {
                label.append(label.isEmpty() ? " open " : ",").append(s.getId());
            }
        }
        vehicle.settleClips();
        return label.toString();
    }

    private boolean[] hidden(PartUnit<?> seat) {
        boolean[] out = new boolean[mesh.names().size()];
        for (String name : seat.getData().getViewHide()) {
            int root = mesh.node(name);
            for (int i = 0; i < out.length; i++) {
                out[i] |= mesh.under(i, root);
            }
        }
        return out;
    }

    private static float[] yaws(RotatableUnit<?> s) {
        float lo = s.yRotMin - s.ySelfRot;
        float hi = s.yRotMax - s.ySelfRot;
        if (!(hi - lo < 360)) {
            float[] out = new float[(int) (360 / YAW_STEP)];
            for (int i = 0; i < out.length; i++) {
                out[i] = i * YAW_STEP - 180 + YAW_STEP;
            }
            return out;
        }
        return span(lo, hi, YAW_STEP);
    }

    private static float[] pitches(RotatableUnit<?> s) {
        float lo = s.xRotMin - s.xSelfRot;
        float hi = s.xRotMax - s.xSelfRot;
        return lo < 0 && hi > 0 ? new float[]{lo, 0, hi} : span(lo, hi, Float.MAX_VALUE);
    }

    private static float[] span(float lo, float hi, float step) {
        int n = hi > lo ? (int) Math.ceil((hi - lo) / step) : 0;
        float[] out = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            out[i] = n == 0 ? lo : lo + (hi - lo) * i / n;
        }
        return out;
    }

    private void rays(float fov) {
        if (fov == fovBuilt) {
            return;
        }
        fovBuilt = fov;
        tanV = (float) Math.tan(Math.toRadians(fov / 2));
        tanH = tanV * 16 / 9;
        for (int p = 0; p < W * H; p++) {
            // Camera local: +z ahead, +y up, +x left (screen right = -x).
            float x = -ndc[p * 2] * tanH, y = ndc[p * 2 + 1] * tanV;
            float l = (float) Math.sqrt(x * x + y * y + 1);
            dir[p * 3] = x / l;
            dir[p * 3 + 1] = y / l;
            dir[p * 3 + 2] = 1 / l;
        }
    }

    /** Fills {@link #depth} (< {@link #REACH} = obstructed) and {@link #who}. */
    private void trace(Vec3 eye, Quaternionf look, float fov, boolean[] hidden) {
        rays(fov);
        java.util.Arrays.fill(depth, REACH);
        java.util.Arrays.fill(who, -1);
        Quaternionf inverse = new Quaternionf(look).conjugate();
        Matrix4f pose = new Matrix4f();
        Matrix4f toCam = new Matrix4f();
        Vector3f c = new Vector3f();
        float[] v = new float[9];
        float[] px = new float[8];
        int nodes = mesh.names().size();
        for (int node = 0; node < nodes; node++) {
            float[] tris = mesh.tris()[node];
            if (tris == null || hidden[node] || !hits.live(node)) {
                continue;
            }
            float[] sphere = mesh.sphere()[node];
            toCam.rotation(inverse).mul(hits.pose(node, eye, pose));
            int last = sphere.length - 4;
            if (toCam.transformPosition(c.set(sphere[last], sphere[last + 1], sphere[last + 2])).length()
                    - sphere[last + 3] > REACH) {
                continue;
            }
            boolean[] culled = mesh.culled()[node];
            for (int t = 0; t < culled.length; t++) {
                if (toCam.transformPosition(c.set(sphere[t * 4], sphere[t * 4 + 1], sphere[t * 4 + 2])).length()
                        - sphere[t * 4 + 3] > REACH) {
                    continue;
                }
                for (int k = 0; k < 3; k++) {
                    toCam.transformPosition(c.set(tris[t * 9 + k * 3], tris[t * 9 + k * 3 + 1], tris[t * 9 + k * 3 + 2]));
                    v[k * 3] = c.x;
                    v[k * 3 + 1] = c.y;
                    v[k * 3 + 2] = c.z;
                }
                if (v[2] < NEAR && v[5] < NEAR && v[8] < NEAR) {
                    continue;
                }
                float e1x = v[3] - v[0], e1y = v[4] - v[1], e1z = v[5] - v[2];
                float e2x = v[6] - v[0], e2y = v[7] - v[1], e2z = v[8] - v[2];
                if (culled[t]) {
                    float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
                    if (nx * v[0] + ny * v[1] + nz * v[2] >= 0) {
                        continue; // back face from the eye
                    }
                }
                int corners = clip(v, px);
                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int k = 0; k < corners; k++) {
                    minX = Math.min(minX, px[k * 2]);
                    maxX = Math.max(maxX, px[k * 2]);
                    minY = Math.min(minY, px[k * 2 + 1]);
                    maxY = Math.max(maxY, px[k * 2 + 1]);
                }
                int i0 = Math.max(0, (int) Math.floor(minX)), i1 = Math.min(W - 1, (int) Math.ceil(maxX));
                int j0 = Math.max(0, (int) Math.floor(minY)), j1 = Math.min(H - 1, (int) Math.ceil(maxY));
                // Moller-Trumbore from the origin: s = -a, q = s x e1 fixed per triangle.
                float sx = -v[0], sy = -v[1], sz = -v[2];
                float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
                float e2q = e2x * qx + e2y * qy + e2z * qz;
                for (int j = j0; j <= j1; j++) {
                    for (int i = i0; i <= i1; i++) {
                        int p = j * W + i;
                        float dx = dir[p * 3], dy = dir[p * 3 + 1], dz = dir[p * 3 + 2];
                        float hx = dy * e2z - dz * e2y, hy = dz * e2x - dx * e2z, hz = dx * e2y - dy * e2x;
                        float det = e1x * hx + e1y * hy + e1z * hz;
                        if (Math.abs(det) < 1.0e-12f) {
                            continue;
                        }
                        float inv = 1 / det;
                        float u = (sx * hx + sy * hy + sz * hz) * inv;
                        if (u < 0 || u > 1) {
                            continue;
                        }
                        float w = (dx * qx + dy * qy + dz * qz) * inv;
                        if (w < 0 || u + w > 1) {
                            continue;
                        }
                        float d = e2q * inv;
                        if (d > 0 && d < depth[p] && d * dz >= NEAR) {
                            depth[p] = d;
                            who[p] = node;
                        }
                    }
                }
            }
        }
    }

    /** Camera-local triangle clipped to z >= {@link #NEAR}, corners -> pixel coords into {@code out}; corner count. */
    private int clip(float[] v, float[] out) {
        int n = 0;
        for (int k = 0; k < 3; k++) {
            int a = k * 3, b = (k + 1) % 3 * 3;
            boolean ina = v[a + 2] >= NEAR, inb = v[b + 2] >= NEAR;
            if (ina) {
                n = pixel(v[a], v[a + 1], v[a + 2], out, n);
            }
            if (ina != inb) {
                float s = (NEAR - v[a + 2]) / (v[b + 2] - v[a + 2]);
                n = pixel(v[a] + s * (v[b] - v[a]), v[a + 1] + s * (v[b + 1] - v[a + 1]), NEAR, out, n);
            }
        }
        return n;
    }

    private int pixel(float x, float y, float z, float[] out, int n) {
        out[n * 2] = (-x / z / tanH + 1) / 2 * W;
        out[n * 2 + 1] = (1 - y / z / tanV) / 2 * H;
        return n + 1;
    }

    /** Worst pose so far for one role: region counts read off {@link #depth}. */
    private static final class Worst {
        private final Role role;
        double badness = -1;
        int centre;
        float frac;
        int region;
        int selfAim;
        String pose = "";
        int[] who = new int[0];

        Worst(Role role) {
            this.role = role;
        }

        void offer(Sightlines s, Quaternionf look, String pose) {
            int centre = 0, inner = 0, hits = 0, total = 0;
            boolean[] in = new boolean[W * H];
            Vector3f d = new Vector3f();
            for (int p = 0; p < W * H; p++) {
                boolean blocked = s.depth[p] < REACH;
                boolean region;
                if (role == Role.SIGHT) {
                    boolean mid = Math.abs(s.ndc[p * 2]) <= CENTRE && Math.abs(s.ndc[p * 2 + 1]) <= CENTRE;
                    if (mid) {
                        inner++;
                        centre += blocked ? 1 : 0;
                    }
                    region = !mid;
                } else if (role == Role.DRIVER) {
                    look.transform(d.set(s.dir[p * 3], s.dir[p * 3 + 1], s.dir[p * 3 + 2]));
                    region = Math.abs(Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d.y))))) <= BAND;
                } else {
                    region = true;
                }
                in[p] = region || role == Role.SIGHT;
                if (region) {
                    total++;
                    hits += blocked ? 1 : 0;
                }
            }
            float frac = total == 0 ? 0 : (float) hits / total;
            double badness = role == Role.SIGHT && centre > 0 ? 1 + (double) centre / inner : frac;
            if (badness <= this.badness) {
                return;
            }
            this.badness = badness;
            this.centre = centre;
            this.frac = frac;
            this.pose = pose;
            this.who = new int[s.mesh.names().size()];
            this.region = 0;
            for (int p = 0; p < W * H; p++) {
                if (in[p]) {
                    region++;
                    if (s.who[p] >= 0) {
                        who[s.who[p]]++;
                    }
                }
            }
        }
    }
}
