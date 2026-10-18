package com.wf.combinedassault.vehicle.part;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.client.resource.ClientAssetsManager;
import com.wf.combinedassault.client.resource.vehicle.TrackConfig;
import com.wf.combinedassault.client.resource.vehicle.TrackedVehicleDisplay;
import com.wf.combinedassault.compat.gemrender.GemModels;
import com.wf.combinedassault.custom.part.data.TrackUnitData;
import com.wf.combinedassault.custom.part.data.TrackUnitPojo;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.TrackedVehicle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;

public class TrackUnit extends SuspensionUnit<TrackUnitData> {

    private static final double MAX_FRAME_SECONDS = 0.25;
    private static final double CURVE_SAMPLES_PER_METER = 16.0;
    private final List<TrackPath> tracks;
    private long lastRenderNanos;
    /** Per road wheel, {@link #probeTravel}; keyed by game tick + partial tick (vehicle render, model drive). */
    private double[] wheelTravel = new double[0];
    private long travelTick = Long.MIN_VALUE;
    private float travelPartial = Float.NaN;

    public TrackUnit(int index, AbstractVehicle vehicle, TrackUnitData data) {
        super(index, vehicle, data);
        this.tracks = data.getTracks().stream().map(TrackPath::new).toList();
        List<TrackUnitPojo.RoadWheel> wheels = data.getRoadWheels();
        for (int i = 0; i < wheels.size(); i++) {
            tracks.get(wheels.get(i).track()).addWheel(wheels.get(i).center(), i);
        }
        tracks.forEach(TrackPath::bindWheels);
    }

    @Override
    public boolean isActive() {
        return super.isActive() && !partCubeOBBs.isEmpty();
    }

    /** Road wheels => one probe under each, track-box bottom. */
    @Override
    protected Vec3[] buildSupportOffsets() {
        Vec3[] grid = super.buildSupportOffsets();
        List<TrackUnitPojo.RoadWheel> wheels = data.getRoadWheels();
        if (wheels.isEmpty()) {
            return grid;
        }
        if (structureGroup == null) {
            throw new IllegalStateException("track " + id + " has road wheels but no part frame");
        }
        double bottom = Arrays.stream(grid).mapToDouble(Vec3::y).min().orElseThrow();
        Quaternionf inverse = structureGroup.parent == null ? new Quaternionf()
                : new Quaternionf(structureGroup.parent.globalTransform().rotation()).invert();
        Vec3[] out = new Vec3[wheels.size()];
        for (int i = 0; i < out.length; i++) {
            Vector3f local = inverse.transform(wheels.get(i).center().subtract(pivotOffset).toVector3f());
            out[i] = new Vec3(local.x, bottom, local.z);
        }
        return out;
    }

    @OnlyIn(Dist.CLIENT)
    private double[] wheelTravel(float partialTick) {
        long tick = vehicle.level().getGameTime();
        if (tick != travelTick || partialTick != travelPartial) {
            travelTick = tick;
            travelPartial = partialTick;
            if (wheelTravel.length != supportCount()) {
                wheelTravel = new double[supportCount()];
            }
            probeTravel(partialTick, interpolatedCompression(partialTick), wheelTravel);
        }
        return wheelTravel;
    }

    /** Parent-frame +y, vehicle frame. */
    private Vec3 travelAxis() {
        return structureGroup == null || structureGroup.parent == null ? new Vec3(0, 1, 0)
                : new Vec3(structureGroup.parent.globalTransform().rotation().transform(new Vector3f(0, 1, 0)));
    }

    /** Road wheel node -> model-space travel off its rest. */
    @OnlyIn(Dist.CLIENT)
    public void roadWheelTravel(float partialTick, BiConsumer<String, Vec3> sink) {
        List<TrackUnitPojo.RoadWheel> wheels = data.getRoadWheels();
        if (wheels.isEmpty()) {
            return;
        }
        double[] travel = wheelTravel(partialTick);
        Vec3 axis = travelAxis();
        for (int i = 0; i < wheels.size(); i++) {
            sink.accept(wheels.get(i).node(), axis.scale(travel[i]));
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick) {
        if (isDetached() || tracks.isEmpty()) {
            return;
        }
        TrackedVehicleDisplay display = getTrackedVehicleDisplay();
        if (display == null) {
            return;
        }
        TrackConfig config = display.getTrackConfig();
        if (config == null) {
            return;
        }
        boolean wheeled = !data.getRoadWheels().isEmpty();
        if (wheeled) {
            double[] travel = wheelTravel(partialTick);
            Vec3 axis = travelAxis();
            for (TrackPath track : tracks) {
                track.deform(axis, travel);
            }
        }
        for (TrackPath track : tracks) {
            track.setModuleLength(config.moduleLength);
        }
        advanceAnimation();
        int modelLight = vehicle.isDestroyed() ? 64 : packedLight;
        poseStack.pushPose();
        try {
            if (!wheeled) {
                Vec3 offset = getVisualOffset(partialTick);
                poseStack.translate(offset.x, offset.y, offset.z);
            }
            for (TrackPath track : tracks) {
                if (track.isValid()) {
                    renderTrack(track, config.model, poseStack, modelLight);
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    @OnlyIn(Dist.CLIENT)
    private TrackedVehicleDisplay getTrackedVehicleDisplay() {
        var displayOptional = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId());
        if (displayOptional.isPresent() && displayOptional.get() instanceof TrackedVehicleDisplay trackedVehicleDisplay) {
            return trackedVehicleDisplay;
        }
        return null;
    }

    public OptionalDouble getFirstLeftTrackLateralOffset() {
        return firstTrackLateralOffset(true);
    }

    public OptionalDouble getFirstRightTrackLateralOffset() {
        return firstTrackLateralOffset(false);
    }

    private OptionalDouble firstTrackLateralOffset(boolean left) {
        return tracks.stream()
                .filter(TrackPath::isValid)
                .mapToDouble(TrackPath::lateralOffset)
                .filter(offset -> left ? offset > 0 : offset < 0)
                .findFirst();
    }

    @OnlyIn(Dist.CLIENT)
    private void advanceAnimation() {
        long now = System.nanoTime();
        if (lastRenderNanos == 0) {
            lastRenderNanos = now;
            return;
        }
        double deltaSeconds = Math.min((now - lastRenderNanos) / 1_000_000_000.0, MAX_FRAME_SECONDS);
        lastRenderNanos = now;
        if (!(vehicle instanceof TrackedVehicle trackedVehicle)) {
            return;
        }
        double forwardSpeed = trackedVehicle.getForwardSpeed() * 20.0;
        double turnSpeed = trackedVehicle.getTurnSpeed();
        for (TrackPath track : tracks) {
            if (track.isValid()) {
                track.advance((forwardSpeed + turnSpeed * track.lateralOffset()) * deltaSeconds);
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void renderTrack(TrackPath track, ResourceLocation model, PoseStack poseStack, int packedLight) {
        Vector3f position = new Vector3f();
        Vector3f tangent = new Vector3f();
        Vector3f inward = new Vector3f();
        for (int index = 0; index < track.linkCount(); index++) {
            track.sample(track.phase() + index * track.spacing(), position, tangent, inward);
            poseStack.pushPose();
            poseStack.translate(position.x, position.y, position.z);
            poseStack.mulPose(trackRotation(tangent, inward));
            boolean drawn = GemModels.draw(model, poseStack, packedLight);
            poseStack.popPose();
            if (!drawn) {
                return;
            }
        }
    }

    private static Quaternionf trackRotation(Vector3f tangent, Vector3f up) {
        Vector3f forward = new Vector3f(tangent).negate();
        Vector3f right = new Vector3f(up).cross(forward).normalize();
        Matrix3f rotation = new Matrix3f();
        rotation.setColumn(0, right);
        rotation.setColumn(1, up);
        rotation.setColumn(2, forward);
        return new Quaternionf().setFromNormalized(rotation);
    }

    /**
     * Closed Hermite loop through the outline, xyz per sample (last = first). Lower run below the road-wheel axles
     * follows the wheels: travel lerped along z between wheels, faded to 0 toward the loop's z ends and up to axle
     * height. Link count fixed at the rest length.
     */
    private static final class TrackPath {

        private final double[] base;
        private final double[] points;
        private final double[] distance;
        private final int count;
        private final double baseLength;
        private double length;
        private final double centerX, centerY, centerZ;
        private final double lateralOffset;
        private int linkCount;
        private double spacing;
        private double phase;
        private final List<double[]> wheels = new ArrayList<>();
        /** Sorted by z: wheel z, probe index. */
        private double[] wheelZ = new double[0];
        private int[] wheelProbe = new int[0];
        private double lowY, axleY, minZ, maxZ;

        private TrackPath(List<Vec3> outline) {
            List<Vec3> keys = new ArrayList<>();
            for (Vec3 point : outline) {
                if (keys.isEmpty() || point.distanceToSqr(keys.get(keys.size() - 1)) > 1.0E-12) {
                    keys.add(point);
                }
            }
            if (keys.size() > 1 && keys.get(0).distanceToSqr(keys.get(keys.size() - 1)) <= 1.0E-12) {
                keys.remove(keys.size() - 1);
            }
            this.base = smooth(keys);
            this.count = base.length / 3;
            this.points = base.clone();
            this.distance = new double[count];
            measure();
            this.baseLength = length;
            double weightedX = 0, weightedY = 0, weightedZ = 0;
            for (int i = 1; i < count; i++) {
                double segment = distance[i] - distance[i - 1];
                weightedX += (base[i * 3 - 3] + base[i * 3]) * 0.5 * segment;
                weightedY += (base[i * 3 - 2] + base[i * 3 + 1]) * 0.5 * segment;
                weightedZ += (base[i * 3 - 1] + base[i * 3 + 2]) * 0.5 * segment;
            }
            centerX = length > 0 ? weightedX / length : 0;
            centerY = length > 0 ? weightedY / length : 0;
            centerZ = length > 0 ? weightedZ / length : 0;
            lateralOffset = centerX;
        }

        /** Cyclic: closing edge included, tangents wrap. */
        private static double[] smooth(List<Vec3> keys) {
            int n = keys.size();
            if (n < 3) {
                double[] out = new double[n * 3];
                for (int i = 0; i < n; i++) {
                    out[i * 3] = keys.get(i).x;
                    out[i * 3 + 1] = keys.get(i).y;
                    out[i * 3 + 2] = keys.get(i).z;
                }
                return out;
            }
            Vec3[] tangents = new Vec3[n];
            for (int i = 0; i < n; i++) {
                Vec3 prev = keys.get((i - 1 + n) % n), at = keys.get(i), next = keys.get((i + 1) % n);
                Vec3 direction = next.subtract(prev);
                if (direction.lengthSqr() <= 1.0E-12) {
                    direction = next.subtract(at);
                }
                tangents[i] = direction.normalize().scale(Math.min(at.distanceTo(prev), next.distanceTo(at)));
            }
            List<Vec3> out = new ArrayList<>();
            out.add(keys.get(0));
            for (int i = 0; i < n; i++) {
                Vec3 start = keys.get(i), end = keys.get((i + 1) % n);
                int steps = Math.max(4, (int) Math.ceil(start.distanceTo(end) * CURVE_SAMPLES_PER_METER));
                for (int step = 1; step <= steps; step++) {
                    out.add(hermite(start, end, tangents[i], tangents[(i + 1) % n], (double) step / steps));
                }
            }
            double[] flat = new double[out.size() * 3];
            for (int i = 0; i < out.size(); i++) {
                flat[i * 3] = out.get(i).x;
                flat[i * 3 + 1] = out.get(i).y;
                flat[i * 3 + 2] = out.get(i).z;
            }
            return flat;
        }

        private static Vec3 hermite(Vec3 start, Vec3 end, Vec3 startTangent, Vec3 endTangent, double t) {
            double t2 = t * t, t3 = t2 * t;
            return start.scale(2 * t3 - 3 * t2 + 1)
                    .add(startTangent.scale(t3 - 2 * t2 + t))
                    .add(end.scale(-2 * t3 + 3 * t2))
                    .add(endTangent.scale(t3 - t2));
        }

        private void addWheel(Vec3 center, int probe) {
            wheels.add(new double[]{center.z, center.y, probe});
        }

        private void bindWheels() {
            if (wheels.isEmpty() || count == 0) {
                return;
            }
            wheels.sort((a, b) -> Double.compare(a[0], b[0]));
            wheelZ = new double[wheels.size()];
            wheelProbe = new int[wheels.size()];
            axleY = 0;
            for (int i = 0; i < wheels.size(); i++) {
                wheelZ[i] = wheels.get(i)[0];
                wheelProbe[i] = (int) wheels.get(i)[2];
                axleY += wheels.get(i)[1] / wheels.size();
            }
            lowY = minZ = Double.POSITIVE_INFINITY;
            maxZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < count; i++) {
                lowY = Math.min(lowY, base[i * 3 + 1]);
                minZ = Math.min(minZ, base[i * 3 + 2]);
                maxZ = Math.max(maxZ, base[i * 3 + 2]);
            }
        }

        /** {@code travel} by probe index, along {@code axis}. */
        private void deform(Vec3 axis, double[] travel) {
            if (wheelZ.length == 0 || axleY <= lowY) {
                return;
            }
            int last = wheelZ.length - 1;
            for (int i = 0; i < count; i++) {
                int at = i * 3;
                double y = base[at + 1], z = base[at + 2];
                double height = Mth.clamp((axleY - y) / (axleY - lowY), 0, 1);
                double d;
                if (height == 0) {
                    d = 0;
                } else if (z <= wheelZ[0]) {
                    d = travel[wheelProbe[0]] * (wheelZ[0] > minZ ? Mth.clamp((z - minZ) / (wheelZ[0] - minZ), 0, 1) : 1);
                } else if (z >= wheelZ[last]) {
                    d = travel[wheelProbe[last]]
                            * (maxZ > wheelZ[last] ? Mth.clamp((maxZ - z) / (maxZ - wheelZ[last]), 0, 1) : 1);
                } else {
                    int k = 0;
                    while (wheelZ[k + 1] < z) {
                        k++;
                    }
                    double t = (z - wheelZ[k]) / (wheelZ[k + 1] - wheelZ[k]);
                    d = Mth.lerp(t, travel[wheelProbe[k]], travel[wheelProbe[k + 1]]);
                }
                d *= height;
                points[at] = base[at] + axis.x * d;
                points[at + 1] = y + axis.y * d;
                points[at + 2] = z + axis.z * d;
            }
            measure();
        }

        private void measure() {
            length = 0;
            for (int i = 1; i < count; i++) {
                double dx = points[i * 3] - points[i * 3 - 3];
                double dy = points[i * 3 + 1] - points[i * 3 - 2];
                double dz = points[i * 3 + 2] - points[i * 3 - 1];
                length += Math.sqrt(dx * dx + dy * dy + dz * dz);
                distance[i] = length;
            }
            spacing = linkCount > 0 ? length / linkCount : spacing;
        }

        private boolean isValid() {
            return count >= 2 && baseLength > 0;
        }

        private void sample(double at, Vector3f position, Vector3f tangent, Vector3f inward) {
            positionAt(at, position);
            Vector3f previous = positionAt(at - spacing * 0.5, new Vector3f());
            positionAt(at + spacing * 0.5, tangent).sub(previous);
            if (tangent.lengthSquared() <= 1.0E-8f) {
                tangentAt(at, tangent);
            }
            tangent.normalize();
            inward.set((float) (centerX - position.x), (float) (centerY - position.y), (float) (centerZ - position.z));
            inward.sub(new Vector3f(tangent).mul(inward.dot(tangent)));
            if (inward.lengthSquared() <= 1.0E-8f) {
                Vector3f fallback = Math.abs(tangent.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
                inward.set(fallback).sub(new Vector3f(tangent).mul(fallback.dot(tangent)));
            }
            inward.normalize();
        }

        /** Segment index i: [i, i + 1]. */
        private int segmentAt(double remaining) {
            int lo = 0, hi = count - 2;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (distance[mid] <= remaining) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        }

        private Vector3f positionAt(double at, Vector3f out) {
            double remaining = positiveModulo(at, length);
            int i = segmentAt(remaining);
            double span = distance[i + 1] - distance[i];
            double t = span > 0 ? (remaining - distance[i]) / span : 0;
            int a = i * 3, b = a + 3;
            return out.set((float) Mth.lerp(t, points[a], points[b]), (float) Mth.lerp(t, points[a + 1], points[b + 1]),
                    (float) Mth.lerp(t, points[a + 2], points[b + 2]));
        }

        private void tangentAt(double at, Vector3f out) {
            int a = segmentAt(positiveModulo(at, length)) * 3, b = a + 3;
            out.set((float) (points[b] - points[a]), (float) (points[b + 1] - points[a + 1]),
                    (float) (points[b + 2] - points[a + 2]));
        }

        private void advance(double distance) {
            phase = positiveModulo(phase + distance, length);
        }

        private void setModuleLength(double moduleLength) {
            if (linkCount == 0) {
                linkCount = Math.max(1, (int) Math.round(baseLength / moduleLength));
            }
            spacing = length / linkCount;
        }

        private static double positiveModulo(double value, double modulus) {
            double result = value % modulus;
            return result < 0 ? result + modulus : result;
        }

        private int linkCount() {
            return linkCount;
        }

        private double spacing() {
            return spacing;
        }

        private double lateralOffset() {
            return lateralOffset;
        }

        private double phase() {
            return phase;
        }
    }
}
