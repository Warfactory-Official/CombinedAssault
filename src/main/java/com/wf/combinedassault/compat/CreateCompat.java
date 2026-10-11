package com.wf.combinedassault.compat;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.foundation.collision.CollisionList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import com.wf.combinedassault.api.collision.CollisionProvider;
import com.wf.combinedassault.api.collision.CollisionProviders;
import com.wf.combinedassault.api.collision.OrientedBoxes;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;

public class CreateCompat {

    private static final String MOD_ID = "create";
    private static boolean IS_LOADED = false;

    public static void init() {
        IS_LOADED = ModList.get().isLoaded(MOD_ID);
        if (IS_LOADED) {
            // Guard avoids classloading Create classes when absent.
            CollisionProviders.register(new CreateCollisionProvider());
        }
    }

    public static boolean isLoaded() {
        return IS_LOADED;
    }

}

class CreateCollisionProvider implements CollisionProvider {

    @Nullable
    @Override
    public Session begin(AbstractVehicle vehicle, AABB hullBounds) {
        Level level = vehicle.level();
        List<Contraption> contraptions = new ArrayList<>();
        for (Entity entity : level.getEntities(vehicle, hullBounds)) {
            if (entity instanceof AbstractContraptionEntity contraptionEntity) {
                CollisionList simplifiedEntityColliders =
                        contraptionEntity.getContraption().getSimplifiedEntityColliders();
                if (simplifiedEntityColliders != null) {
                    contraptions.add(new Contraption(contraptionEntity, simplifiedEntityColliders));
                }
            }
        }
        return contraptions.isEmpty() ? null : new CreateSession(contraptions);
    }

    record Contraption(AbstractContraptionEntity entity, CollisionList colliders) {}

    record CreateSession(List<Contraption> contraptions) implements Session {

        @Override
        public void collectBoxes(AABB bounds, OrientedBoxes out) {
            Vec3 zero = new Vec3(0, 0, 0);
            Matrix3f basis = new Matrix3f();
            Quaternionf rotation = new Quaternionf();
            for (Contraption contraption : contraptions) {
                AbstractContraptionEntity entity = contraption.entity();
                // Affine: subtract origin from unit-axis transforms => rotation basis, 4 calls not 8.
                Vec3 origin = entity.toGlobalVector(zero, 1.0F);
                Vec3 axisX = entity.toGlobalVector(new Vec3(1, 0, 0), 1.0F).subtract(origin);
                Vec3 axisY = entity.toGlobalVector(new Vec3(0, 1, 0), 1.0F).subtract(origin);
                Vec3 axisZ = entity.toGlobalVector(new Vec3(0, 0, 1), 1.0F).subtract(origin);
                basis.set((float) axisX.x, (float) axisX.y, (float) axisX.z, (float) axisY.x, (float) axisY.y, (float) axisY.z,
                        (float) axisZ.x, (float) axisZ.y, (float) axisZ.z);
                basis.getNormalizedRotation(rotation);

                CollisionList colliders = contraption.colliders();
                for (int i = 0; i < colliders.size; i++) {
                    double cx = colliders.centerX[i];
                    double cy = colliders.centerY[i];
                    double cz = colliders.centerZ[i];
                    double ex = colliders.extentsX[i];
                    double ey = colliders.extentsY[i];
                    double ez = colliders.extentsZ[i];
                    double centreX = origin.x + axisX.x * cx + axisY.x * cy + axisZ.x * cz;
                    double centreY = origin.y + axisX.y * cx + axisY.y * cy + axisZ.y * cz;
                    double centreZ = origin.z + axisX.z * cx + axisY.z * cy + axisZ.z * cz;
                    double spanX = Math.abs(axisX.x) * ex + Math.abs(axisY.x) * ey + Math.abs(axisZ.x) * ez;
                    double spanY = Math.abs(axisX.y) * ex + Math.abs(axisY.y) * ey + Math.abs(axisZ.y) * ez;
                    double spanZ = Math.abs(axisX.z) * ex + Math.abs(axisY.z) * ey + Math.abs(axisZ.z) * ez;
                    if (centreX + spanX <= bounds.minX || centreX - spanX >= bounds.maxX
                            || centreY + spanY <= bounds.minY || centreY - spanY >= bounds.maxY
                            || centreZ + spanZ <= bounds.minZ || centreZ - spanZ >= bounds.maxZ) {
                        continue;
                    }
                    out.add(centreX, centreY, centreZ, rotation, (float) ex, (float) ey, (float) ez);
                }
            }
        }

    }

}
