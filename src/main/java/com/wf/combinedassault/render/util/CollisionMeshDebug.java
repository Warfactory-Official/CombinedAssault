package com.wf.combinedassault.render.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.wf.wflib.physics.api.ChunkCollisionCache;
import com.wf.wflib.physics.api.SectionCollision;

import java.util.ArrayList;
import java.util.List;

/** Draws the boxes physics actually queries, not a parallel reconstruction. */
@OnlyIn(Dist.CLIENT)
public final class CollisionMeshDebug {

    /** Follows real collision shapes; slabs draw at half block height. */
    public record MeshBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

    public record SectionMesh(long sectionKey, SectionCollision.Kind kind, List<MeshBox> boxes) {}

    private CollisionMeshDebug() {}

    public static List<SectionMesh> meshesAround(Level level, BlockPos around, int sectionRadius) {
        ChunkCollisionCache cache = ChunkCollisionCache.of(level);
        int centreX = SectionPos.blockToSectionCoord(around.getX());
        int centreY = SectionPos.blockToSectionCoord(around.getY());
        int centreZ = SectionPos.blockToSectionCoord(around.getZ());

        List<SectionMesh> meshes = new ArrayList<>();
        for (int x = centreX - sectionRadius; x <= centreX + sectionRadius; x++) {
            for (int y = centreY - sectionRadius; y <= centreY + sectionRadius; y++) {
                for (int z = centreZ - sectionRadius; z <= centreZ + sectionRadius; z++) {
                    long key = SectionPos.asLong(x, y, z);
                    SectionCollision snapshot = cache.snapshotAt(key);
                    if (snapshot == null || snapshot.isEmpty()) {
                        continue;
                    }
                    meshes.add(new SectionMesh(key, snapshot.kind(), boxesFor(key, snapshot)));
                }
            }
        }
        return meshes;
    }

    private static List<MeshBox> boxesFor(long sectionKey, SectionCollision snapshot) {
        int originX = SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey));
        int originY = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey));
        int originZ = SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey));
        long[] packedBoxes = snapshot.collisionBoxes();
        List<MeshBox> boxes = new ArrayList<>(packedBoxes.length);
        for (long packed : packedBoxes) {
            double x = originX + SectionCollision.boxMinX(packed);
            double y = originY + SectionCollision.boxMinY(packed);
            double z = originZ + SectionCollision.boxMinZ(packed);
            boxes.add(new MeshBox(x, y, z,
                    x + SectionCollision.boxSizeX(packed),
                    y + SectionCollision.boxSizeY(packed),
                    z + SectionCollision.boxSizeZ(packed)));
        }
        return boxes;
    }

    /** Kept for the overlay toggle; merged boxes are cached on snapshots. */
    public static void clear() {}

}
