package com.wf.combinedassault.compat.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LevelExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import com.wf.combinedassault.api.collision.CollisionProvider;
import com.wf.combinedassault.api.collision.CollisionProviders;
import com.wf.combinedassault.api.collision.OrientedBoxes;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.collision.ChunkCollisionCache;

import java.util.ArrayList;
import java.util.List;

public class SableCompat {

    private static final String MOD_ID = "sable";
    private static boolean IS_LOADED = false;

    public static void init() {
        IS_LOADED = ModList.get().isLoaded(MOD_ID);
        if (IS_LOADED) {
            SubLevelRadarManager.init();
            // Guarded: avoids classloading when Sable absent.
            CollisionProviders.register(new SableCollisionProvider());
        }
    }

    public static boolean isLoaded() {
        return IS_LOADED;
    }

    public static Vec3 transformInverse(Level level, Vec3 checkPos, Vec3 worldPos) {
        return SableImplementation.transformInverse(level, checkPos, worldPos);
    }

    public static Vec3 transform(Level level, Vec3 localPos) {
        return SableImplementation.transform(level, localPos);
    }

}

/** Sable sub-level blocks as oriented boxes; rides the vehicle with the sub-level that bore it. */
class SableCollisionProvider implements CollisionProvider {

    @Nullable
    @Override
    public Session begin(AbstractVehicle vehicle, AABB hullBounds) {
        Level level = vehicle.level();
        BoundingBox3d bounds = new BoundingBox3d(
                hullBounds.minX, hullBounds.minY, hullBounds.minZ,
                hullBounds.maxX, hullBounds.maxY, hullBounds.maxZ);
        List<SableSession.Region> regions = new ArrayList<>();
        ChunkCollisionCache cache = ChunkCollisionCache.of(level);
        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, bounds)) {
            // Sub-level blocks live in host level at plot coords; inverse-transform hull bound to query via ordinary block cache.
            AABB localBounds = transformBounds(subLevel.logicalPose(), hullBounds, true);
            // begin() on tick thread only: sole place chunk data may be read. Session afterwards reads snapshots only.
            cache.prepare(level, localBounds);
            regions.add(new SableSession.Region(subLevel, localBounds));
        }
        if (regions.isEmpty()) {
            return null;
        }
        return new SableSession(vehicle, level, regions);
    }

    private static final class SableSession implements Session {

        record Region(SubLevel subLevel, AABB localBounds) {}

        private final AbstractVehicle vehicle;
        private final Level level;
        private final List<Region> regions;
        /** Per collected box: its region. */
        private final List<SubLevel> owners = new ArrayList<>();
        private SubLevel trackedSubLevel;

        private SableSession(AbstractVehicle vehicle, Level level, List<Region> regions) {
            this.vehicle = vehicle;
            this.level = level;
            this.regions = regions;
        }

        /** Local block boxes through each sub-level pose: centre mapped, orientation = pose's, extents x scale. */
        @Override
        public void collectBoxes(AABB bounds, OrientedBoxes out) {
            ChunkCollisionCache cache = ChunkCollisionCache.of(level);
            List<AABB> localBoxes = new ArrayList<>();
            Vector3d centre = new Vector3d();
            Quaternionf rotation = new Quaternionf();
            owners.clear();
            for (Region region : regions) {
                localBoxes.clear();
                cache.collectBoxes(region.localBounds(), localBoxes);
                Pose3dc pose = region.subLevel().logicalPose();
                Quaterniondc orientation = pose.orientation();
                rotation.set((float) orientation.x(), (float) orientation.y(), (float) orientation.z(), (float) orientation.w());
                Vector3dc scale = pose.scale();
                for (int i = 0, size = localBoxes.size(); i < size; i++) {
                    AABB box = localBoxes.get(i);
                    if (!transformBounds(pose, box, false).intersects(bounds)) {
                        continue;
                    }
                    pose.transformPosition(centre.set((box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5,
                            (box.minZ + box.maxZ) * 0.5));
                    out.add(centre.x, centre.y, centre.z, rotation, (float) (box.getXsize() * 0.5 * scale.x()),
                            (float) (box.getYsize() * 0.5 * scale.y()), (float) (box.getZsize() * 0.5 * scale.z()));
                    owners.add(region.subLevel());
                }
            }
        }

        @Override
        public void touched(int box) {
            if (trackedSubLevel == null) {
                trackedSubLevel = owners.get(box);
            }
        }

        @Override
        public void end() {
            if (trackedSubLevel != null) {
                SableImplementation.rideSubLevel(vehicle, trackedSubLevel);
            }
        }

    }

    /** AABB of box's 8 corners mapped through pose; {@code inverse} selects direction. */
    private static AABB transformBounds(Pose3dc pose, AABB box, boolean inverse) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 point = new Vec3(
                    (corner & 1) == 0 ? box.minX : box.maxX,
                    (corner & 2) == 0 ? box.minY : box.maxY,
                    (corner & 4) == 0 ? box.minZ : box.maxZ);
            Vec3 mapped = inverse ? pose.transformPositionInverse(point) : pose.transformPosition(point);
            minX = Math.min(minX, mapped.x);
            minY = Math.min(minY, mapped.y);
            minZ = Math.min(minZ, mapped.z);
            maxX = Math.max(maxX, mapped.x);
            maxY = Math.max(maxY, mapped.y);
            maxZ = Math.max(maxZ, mapped.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

}

class SableImplementation {

    protected static void rideSubLevel(AbstractVehicle vehicle, SubLevel trackedSubLevel) {
            Quaterniondc lastOrientation = trackedSubLevel.lastPose().orientation();
            Quaterniondc currentOrientation = trackedSubLevel.logicalPose().orientation();
            Quaterniond relativeOrientation = new Quaterniond();
            currentOrientation.div(lastOrientation, relativeOrientation);
            // Small-angle yaw-only extraction: y/w ~ tan(theta/2) ~ theta/2; exact atan2 skipped.
            final double angleDiff = 2 * relativeOrientation.y / relativeOrientation.w;
            final float delta = (float) Math.toDegrees(angleDiff);
            vehicle.setYRot(vehicle.getYRot() - delta);
            LivingEntityMovementExtension livingExt = (LivingEntityMovementExtension) vehicle;
            Vec3 velocity = JOMLConversion.toMojang(livingExt.sable$getInheritedVelocity());
            EntityMovementExtension entityMovementExtension = (EntityMovementExtension) vehicle;
            SubLevelEntityCollision.CollisionInfo collisionInfo = SubLevelEntityCollision.collide(vehicle, Vec3.ZERO, velocity, ((LevelExtension) vehicle.level()).sable$getJOMLSink());
            collisionInfo.preTrackingSubLevel = entityMovementExtension.sable$getTrackingSubLevel();
            collisionInfo.preDeltaMovement = vehicle.getDeltaMovement();
            if (collisionInfo.trackingSubLevel != null) {
                if (collisionInfo.verticalCollisionBelow) {
                    entityMovementExtension.sable$setTrackingSubLevel(collisionInfo.trackingSubLevel);
                }
            } else {
                entityMovementExtension.sable$setTrackingSubLevel(null);
            }

            if (collisionInfo.inheritedMotion != null && collisionInfo.inheritedMotion.lengthSqr() > 1.0E-12) {
                vehicle.setPos(vehicle.position().add(collisionInfo.inheritedMotion));
                livingExt.sable$getInheritedVelocity()
                        .set(collisionInfo.inheritedMotion.x, collisionInfo.inheritedMotion.y, collisionInfo.inheritedMotion.z);
            }
            Vec3 vec31 = vehicle.trackingPosition();
            VecDeltaCodec positionCodec = new VecDeltaCodec();
            positionCodec.setBase(vehicle.trackingPosition());
            ClientboundMoveEntityPacket.PosRot packet = new ClientboundMoveEntityPacket.PosRot(vehicle.getId(),
                    (short) ((int) positionCodec.encodeX(vec31)),
                    (short) ((int) positionCodec.encodeY(vec31)),
                    (short) ((int) positionCodec.encodeZ(vec31)),
                    (byte) 0, (byte) 0, vehicle.onGround());
            ((ServerLevel) vehicle.level()).getChunkSource().broadcast(vehicle, packet);
    }

    protected static Vec3 transformInverse(Level level, Vec3 checkPos, Vec3 worldPos) {
        SubLevel subLevel = Sable.HELPER.getContaining(level, checkPos);
        if (subLevel != null) {
            return subLevel.logicalPose().transformPositionInverse(worldPos);
        }
        BoundingBox3d queryBounds = new BoundingBox3d(
                checkPos.x - 0.001, checkPos.y - 0.001, checkPos.z - 0.001,
                checkPos.x + 0.001, checkPos.y + 0.001, checkPos.z + 0.001
        );
        for (SubLevel querySubLevel : Sable.HELPER.getAllIntersecting(level, queryBounds)) {
            return querySubLevel.logicalPose().transformPositionInverse(worldPos);
        }
        return worldPos;
    }

    protected static Vec3 transform(Level level, Vec3 localPos) {
        SubLevel subLevel = Sable.HELPER.getContaining(level, localPos);
        if (subLevel != null) {
            return subLevel.logicalPose().transformPosition(localPos);
        }
        return localPos;
    }

}
