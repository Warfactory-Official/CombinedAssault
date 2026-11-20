package com.wf.combinedassault.vehicle.util;

import com.wf.combinedassault.ballistics.addon.AddonArmorLayer;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.dynamics.structure.VehicleCubeGroup;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.combinedassault.props.part.DecorationUnit;
import com.wf.combinedassault.render.model.VehicleModelInstance;
import com.wf.combinedassault.vehicle.compat.sable.SableCompat;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.wflib.geom.api.OBB;
import com.wf.wflib.geom.api.OBBEntity;
import com.wf.wflib.grenade.api.SightBlocker;
import com.wf.wflib.missile.api.SeekerDecoy;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.entity.PartEntity;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.joml.*;

import java.lang.Math;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class VectorUtil {

    private static double fov = 70;
    private static Matrix4f modelViewMatrix = new Matrix4f();
    private static Matrix4f projectionMatrix = new Matrix4f();

    public static void captureMatrices(Matrix4f modelView, Matrix4f projection) {
        modelViewMatrix = modelView;
        projectionMatrix = projection;
    }

    // Ported from Minecraft-Ping-Wheel (LukenSkyne), MathUtils.java#L15.
    public static Vec3 worldToScreen(Vec3 pos) {
        if (pos == null) {
            return Vec3.ZERO;
        }
        var mc = Minecraft.getInstance();
        var window = mc.getWindow();
        var camera = mc.gameRenderer.getMainCamera();
        var worldPosRel = new Vector4f(camera.getPosition().reverse().add(pos).toVector3f(), 1f);
        worldPosRel.mul(modelViewMatrix);
        worldPosRel.mul(projectionMatrix);
        var depth = worldPosRel.w;
        if (depth != 0) {
            worldPosRel.div(depth);
        }
        return new Vec3(
                window.getGuiScaledWidth() * (0.5f + worldPosRel.x * 0.5f),
                window.getGuiScaledHeight() * (0.5f - worldPosRel.y * 0.5f),
                depth);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void captureFov(ViewportEvent.ComputeFov event) {
        if (event.usedConfiguredFov()) {
            fov = event.getFOV();
        }
    }

    public static Vec3 hitPosition(Entity shooter, Vec3 start, Vec3 end) {
        Level level = shooter.level();
        ClipContext blockContext = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter);
        BlockHitResult blockHitResult = level.clip(blockContext);
        Vec3 blockHitPos = blockHitResult.getLocation();
        if (blockHitResult.getType() != BlockHitResult.Type.MISS && SableCompat.isLoaded()) {
            blockHitPos = SableCompat.transform(shooter.level(), blockHitPos);
        }
        double blockDistance = blockHitPos.distanceTo(start);
        EntityHitResult entityHit = hitEntity(shooter, start, end);
        Vec3 hitPoint = blockHitPos;
        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            if (entity instanceof SightBlocker || entity instanceof SeekerDecoy) {
                return hitPoint;
            }
            AABB targetBox = entityHit.getEntity().getBoundingBox();
            Optional<Vec3> intersectOptional = targetBox.clip(start, end);
            if (intersectOptional.isPresent()) {
                Vec3 intersectPos = intersectOptional.get();
                double entityDistance = intersectPos.distanceTo(start);
                if (entityDistance < blockDistance) {
                    hitPoint = intersectPos;
                }
            }
        }
        return hitPoint;
    }

    public static EntityHitResult hitEntity(Entity shooter, Vec3 start, Vec3 end) {
        Vec3 direction = end.subtract(start);
        AABB aabb = shooter.getBoundingBox().expandTowards(direction).inflate(1.0);
        Level level = shooter.level();
        Predicate<Entity> filter = entity ->
                (entity.isPickable() || entity instanceof SightBlocker || entity instanceof SeekerDecoy)
                        && !entity.isSpectator()
                        && entity != shooter
                        && entity != shooter.getVehicle()
                        && !(shooter.getVehicle() != null && shooter.getVehicle() == entity.getVehicle())
                        && !shooter.getPassengers().contains(entity);
        double d0 = Double.MAX_VALUE;
        Entity entity = null;
        for (Entity hitEntity : level.getEntities(shooter, aabb, filter)) {
            AABB boundingBox = hitEntity.getBoundingBox().inflate(0.3);
            Vec3 hitPosition = null;
            if (boundingBox.contains(start)) {
                hitPosition = start;
            } else {
                Optional<Vec3> optional = boundingBox.clip(start, end);
                if (optional.isPresent()) {
                    hitPosition = optional.get();
                }
            }
            if (hitPosition != null) {
                double d1 = start.distanceToSqr(hitPosition);
                if (d1 < d0) {
                    entity = hitEntity;
                    d0 = d1;
                }
            }
        }
        if (entity instanceof OBBEntity) {
            EntityHitResult result = null;
            Vec3 closestHitPos = VectorUtil.closestHitObbPosition(entity, start, end);
            if (closestHitPos != null) {
                result = new EntityHitResult(entity, closestHitPos);
            }
            return result;
        }
        return entity == null ? null : new EntityHitResult(entity instanceof PartEntity<?> partEntity ? partEntity.getParent() : entity);
    }

    public static Pair<Entity, Vec3> hitObbPosition(Entity shooter, Vec3 start, Vec3 end) {
        Level level = shooter.level();
        ClipContext blockContext = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter);
        BlockHitResult blockHitResult = level.clip(blockContext);
        EntityHitResult entityHitResult = VectorUtil.hitEntity(shooter, start, end);
        if (entityHitResult != null) {
            Vec3 obbPos = closestHitObbPosition(entityHitResult.getEntity(), start, end);
            if (obbPos != null) {
                if (blockHitResult.getLocation().distanceTo(start) > obbPos.distanceTo(start)) {
                    return Pair.of(entityHitResult.getEntity(), obbPos);
                }
            }
        }
        return null;
    }

    /** Hull or, nearer, installed ERA/slat: what a round or the crosshair meets first. */
    public static Vec3 closestHitObbPosition(Entity entity, Vec3 start, Vec3 end) {
        Vec3 hull = closestHullHit(entity, start, end);
        if (entity instanceof AbstractVehicle vehicle && vehicle.addonArmor().anyInstalled()) {
            AddonArmorLayer.Hit addon = vehicle.addonArmor().first(vehicle, start, end, true);
            if (addon != null && (hull == null
                    || addon.point().distanceTo(start) < hull.distanceTo(start) + AddonArmorLayer.SKIN_INSET)) {
                return addon.point();
            }
        }
        return hull;
    }

    public static Vec3 closestHullHit(Entity entity, Vec3 start, Vec3 end) {
        if (entity instanceof AbstractVehicle vehicle && vehicle.hits() != null) {
            VehicleHits.Hit hit = vehicle.hits().surface(start, end);
            return hit == null ? null : hit.point();
        }
        if (entity instanceof OBBEntity obbEntity) {
            OBB.SegmentHit hitOBB = OBB.first(obbEntity.getOBBs(), start, end);
            if (hitOBB != null) {
                return hitOBB.point();
            }
        }
        return null;
    }

    public static PartUnit<?> hitPartUnit(Entity entity, Vec3 start, Vec3 end) {
        return VectorUtil.hitPartUnit(entity, start, end, true);
    }

    /** Server part pick for a vehicle vanilla let {@code player} touch: reach + box extent (nearest surface still wins). */
    @Nullable
    public static PartUnit<?> pickPartUnit(Player player, Entity vehicle) {
        AABB box = vehicle.getBoundingBox();
        double length = player.entityInteractionRange() + Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
        Vec3 eye = player.getEyePosition();
        return pickPartUnit(vehicle, eye, eye.add(player.getLookAngle().scale(length)));
    }

    /**
     * What a player points at: nearest drawn surface, its part or null (hull). {@link #hitPartUnit} skips the
     * hull => a click threading a gap in the near side opens the far door.
     */
    public static PartUnit<?> pickPartUnit(Entity entity, Vec3 start, Vec3 end) {
        if (entity instanceof AbstractVehicle vehicle && vehicle.hits() != null
                && !(vehicle.level().isClientSide() && hitDecoration(vehicle, start, end) != null)) {
            VehicleHits hits = vehicle.hits();
            VehicleHits.Hit hit = hits.trace(start, end, i -> hits.live(i) && !hits.model().nodes().get(i).mesh().module());
            return hit == null ? null : hits.part(hit.node());
        }
        return hitPartUnit(entity, start, end);
    }

    private static DecorationUnit hitDecoration(AbstractVehicle vehicle, Vec3 start, Vec3 end) {
        double minDistance = Double.MAX_VALUE;
        DecorationUnit closest = null;
        for (DecorationUnit decorationUnit : vehicle.getDecorationUnits().values()) {
            OBB.SegmentHit hitOBB = OBB.first(decorationUnit.getOBBs(), start, end);
            if (hitOBB != null && hitOBB.distance() < minDistance) {
                minDistance = hitOBB.distance();
                closest = decorationUnit;
            }
        }
        return closest;
    }

    public static PartUnit<?> hitPartUnit(Entity entity, Vec3 start, Vec3 end, boolean withDestroyed) {
        if (entity instanceof AbstractVehicle vehicle) {
            double minDistance = Double.MAX_VALUE;
            PartUnit<?> closestPartUnit = null;
            List<PartUnit<?>> partUnits = new ArrayList<>(vehicle.getPartUnits());
            if (vehicle.level().isClientSide()) {
                DecorationUnit decoration = hitDecoration(vehicle, start, end);
                if (decoration != null) {
                    return decoration;
                }
            }
            VehicleHits hits = vehicle.hits();
            if (hits != null) {
                // Nearest part-owned surface; the hull in front does not shield it (as with part boxes).
                VehicleHits.Hit hit = hits.trace(start, end, i -> {
                    PartUnit<?> part = hits.part(i);
                    return part != null && hits.live(i) && !hits.model().nodes().get(i).mesh().module()
                            && (withDestroyed || !part.isDestroyed());
                });
                return hit == null ? null : hits.part(hit.node());
            }
            for (PartUnit<?> partUnit : partUnits) {
                if (!withDestroyed && partUnit.isDestroyed()) {
                    continue;
                }
                OBB.SegmentHit hitOBB = OBB.first(partUnit.getOBBs(), start, end);
                if (hitOBB != null && hitOBB.distance() < minDistance) {
                    minDistance = hitOBB.distance();
                    closestPartUnit = partUnit;
                }
            }
            return closestPartUnit;
        }
        return null;
    }

    /** @param attachmentBoneIndex display-model bone index; -1 = none or not resolved (server) */
    public record HitBone(String boneName, int attachmentBoneIndex, Vec3 offset, Quaternionf rotation, Vec3 position) {}

    /** Nearest drawn surface of the hit mesh, in its frame's bone; no hit mesh => {@link #hitHull}. Client. */
    public static HitBone hitBone(AbstractVehicle vehicle, Vec3 start, Vec3 end, Vec3 forward) {
        VehicleHits hits = vehicle.hits();
        if (hits == null) {
            return hitHull(vehicle, start, end, forward);
        }
        VehicleHits.Hit hit = hits.surface(start, end);
        if (hit == null) {
            return null;
        }
        VehicleCubeGroup group = hits.groups()[hits.model().nodes().get(hit.node()).group()];
        HitBone bone = inGroup(vehicle, group, hit.point(), hit.normal(), forward);
        VehicleModelInstance instance = vehicle.getVehicleModelInstance();
        int index = instance == null || bone.boneName().isEmpty() ? -1 : instance.boneIndex(bone.boneName());
        return new HitBone(bone.boneName(), index, bone.offset(), bone.rotation(), bone.position());
    }

    /**
     * {@link #hitBone} from collision cubes: both sides. Cube on unnamed bones => nearest named ancestor's frame;
     * part-posed, so mid-clip bones sit where the server thinks.
     *
     * @param forward the attachment's own facing, rotated onto the surface normal in the returned rotation
     */
    public static HitBone hitHull(AbstractVehicle vehicle, Vec3 start, Vec3 end, Vec3 forward) {
        VehicleCubeOBB hitCube = null;
        Vec3 hitPos = null;
        double minDistance = Double.MAX_VALUE;
        Vector3f from = start.toVector3f();
        Vector3f to = end.toVector3f();
        for (VehicleCubeOBB cube : hullCubes(vehicle)) {
            Vector3f clipped = cube.obb().clip(from, to).orElse(null);
            if (clipped == null) {
                continue;
            }
            Vec3 point = new Vec3(clipped);
            double distance = point.distanceTo(start);
            if (distance < minDistance) {
                minDistance = distance;
                hitCube = cube;
                hitPos = point;
            }
        }
        if (hitCube == null) {
            return null;
        }
        VehicleCubeGroup group = hitCube.group == null ? null : hitCube.group.namedAncestor();
        return inGroup(vehicle, group, hitPos, hitCube.obb().faceNormal(hitPos), forward);
    }

    /** World point + outward world normal -> {@code group}'s frame (null => vehicle frame, name ""). */
    private static HitBone inGroup(AbstractVehicle vehicle, @Nullable VehicleCubeGroup group, Vec3 hitPos,
                                   Vector3f worldNormal, Vec3 forward) {
        VehicleCubeGroup.GlobalTransform transform = group == null
                ? new VehicleCubeGroup.GlobalTransform(Vec3.ZERO, new Quaternionf())
                : group.globalTransform();
        Quaternionf vehicleRotation = vehicle.rotYXZ();
        Quaternionf inverseVehicle = vehicleRotation.conjugate(new Quaternionf());
        Quaternionf inverseGroup = transform.rotation().conjugate(new Quaternionf());

        // Undoing VehicleCubeOBB.update step for step: world -> vehicle-local -> bone-local.
        Vector3f offset = hitPos.subtract(vehicle.position()).subtract(vehicle.centerOffset).toVector3f();
        inverseVehicle.transform(offset);
        offset.add(vehicle.centerOffset.toVector3f());
        offset.sub(transform.offset().toVector3f());
        inverseGroup.transform(offset);

        Vector3f normal = new Quaternionf(vehicleRotation).mul(transform.rotation()).conjugate().transform(new Vector3f(worldNormal));
        Quaternionf rotation = new Quaternionf().rotationTo(forward.toVector3f(), normal);
        return new HitBone(group == null ? "" : group.name, -1, new Vec3(offset), rotation, hitPos);
    }

    /** Body cubes plus every part still attached; the same set the hull collides and takes damage with. */
    public static List<VehicleCubeOBB> hullCubes(AbstractVehicle vehicle) {
        List<VehicleCubeOBB> cubes = new ArrayList<>(vehicle.getVehicleCubeOBBs());
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit.isDetached() || partUnit.isDestroyed()) {
                continue;
            }
            cubes.addAll(partUnit.getPartCubeOBBs());
        }
        return cubes;
    }

    public static Vec3 relativeRotPos(Entity entity, Vec3 worldPos, boolean reverse) {
        return relativeRotDirection(entity, worldPos.subtract(entity.position()), reverse).add(entity.position());
    }

    public static Vec3 relativeRotDirection(Entity entity, Vec3 worldDirection, boolean reverse) {
        Quaternionf q = new Quaternionf();
        q.rotateY(org.joml.Math.toRadians(-entity.getYRot()))
                .rotateX(org.joml.Math.toRadians(entity.getXRot()));
        Matrix3f axisRollMat = new Matrix3f();
        q.get(axisRollMat);
        if (reverse) {
            axisRollMat = axisRollMat.transpose();
        }
        Vector3f d = axisRollMat.transform(new Vector3f((float) worldDirection.x(), (float) worldDirection.y(), (float) worldDirection.z()));
        return new Vec3(d.x, d.y, d.z);
    }

    public static Vec3 rotToVec(float pXRot, float pYRot) {
        float f = pXRot * ((float)Math.PI / 180F);
        float f1 = -pYRot * ((float)Math.PI / 180F);
        float f2 = Mth.cos(f1);
        float f3 = Mth.sin(f1);
        float f4 = Mth.cos(f);
        float f5 = Mth.sin(f);
        return new Vec3(f3 * f4, -f5, f2 * f4);
    }

    public static Vec2 vecToRot(Vec3 vec) {
        float pitch = (float) Math.toDegrees(Math.atan2(-vec.y, Math.sqrt(vec.x * vec.x + vec.z * vec.z)));
        float yaw = (float) Math.toDegrees(-Math.atan2(vec.x, vec.z));
        return new Vec2(pitch, yaw);
    }

    public static double angleBetween(Vec3 a, Vec3 b) {
        double dot = a.dot(b);
        double magA = a.length();
        double magB = b.length();
        if (magA == 0 || magB == 0) return 0;
        double cos = dot / (magA * magB);
        // clamp: fp rounding can push cos outside [-1,1] -> acos NaN.
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return Math.acos(cos);
    }

    public static Vec3 project(Vec3 a, Vec3 b) {
        double dot = a.dot(b);
        double len2 = b.lengthSqr();
        if (len2 == 0) return Vec3.ZERO;
        return b.scale(dot / len2);
    }

    public static List<Vector2f> convexHull(List<Vector2f> points) {
        if (points.size() <= 1) return new ArrayList<>(points);

        List<Vector2f> sorted = new ArrayList<>(points);
        sorted.sort((a, b) -> {
            if (a.x == b.x) return Float.compare(a.y, b.y);
            return Float.compare(a.x, b.x);
        });

        List<Vector2f> lower = new ArrayList<>();
        for (Vector2f p : sorted) {
            while (lower.size() >= 2 &&
                    cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0) {
                lower.remove(lower.size() - 1);
            }
            lower.add(p);
        }

        List<Vector2f> upper = new ArrayList<>();
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Vector2f p = sorted.get(i);
            while (upper.size() >= 2 &&
                    cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0) {
                upper.remove(upper.size() - 1);
            }
            upper.add(p);
        }

        // Lower/upper chains share both endpoints; drop each chain's last before joining.
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);

        return lower;
    }

    public static float cross(Vector2f p1, Vector2f p2, Vector2f p3) {
        return (p2.x - p1.x) * (p3.y - p1.y) - (p2.y - p1.y) * (p3.x - p1.x);
    }

    private static float cross(float x1, float y1, float x2, float y2, float x3, float y3) {
        return (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1);
    }

    /** Distance from point to line segment; primitive version. */
    public static float pointToSegmentDist(float px, float py,
                                           float ax, float ay, float bx, float by) {
        float abX = bx - ax;
        float abY = by - ay;
        float t = ((px - ax) * abX + (py - ay) * abY) / (abX * abX + abY * abY);
        t = Math.clamp(t, 0, 1);
        float dx = px - (ax + t * abX);
        float dy = py - (ay + t * abY);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static float pointToSegmentDist(Vector2f p, Vector2f a, Vector2f b) {
        Vector2f ab = new Vector2f(b).sub(a);
        Vector2f ap = new Vector2f(p).sub(a);
        float t = ap.dot(ab) / ab.lengthSquared();
        t = Math.max(0, Math.min(1, t));
        Vector2f proj = new Vector2f(a).fma(t, ab);
        return p.distance(proj);
    }

}
