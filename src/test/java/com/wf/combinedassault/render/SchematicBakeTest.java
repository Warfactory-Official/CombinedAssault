package com.wf.combinedassault.render;

import com.wf.combinedassault.render.gem.SchematicBake;
import com.wf.gemrender.gltf.GltfPaletteLayout;
import com.wf.gemrender.gltf.GltfPose;
import com.wf.gemrender.gltf.MeshGeometry;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.skin.VertexSkinning;
import com.wf.gemrender.texture.SpriteUv;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicBakeTest {

    private static final int STRIDE = NodeTable.TRS_STRIDE;

    /** Node 0 "hull" at origin, node 1 "turret" child of hull offset (0, 1, 2). */
    private static GltfPaletteLayout layout() {
        float[] trs = new float[2 * STRIDE];
        trs[NodeTable.ROTATION + 3] = 1;
        trs[NodeTable.SCALE] = trs[NodeTable.SCALE + 1] = trs[NodeTable.SCALE + 2] = 1;
        trs[STRIDE + 1] = 1;
        trs[STRIDE + 2] = 2;
        trs[STRIDE + NodeTable.ROTATION + 3] = 1;
        trs[STRIDE + NodeTable.SCALE] = trs[STRIDE + NodeTable.SCALE + 1] = trs[STRIDE + NodeTable.SCALE + 2] = 1;
        return GltfPaletteLayout.ofNodes(NodeTable.ofNodes(new String[]{"hull", "turret"}, new int[]{-1, 0}, trs));
    }

    /** Upward quad {@code [x0,x1] x [z0,z1]} at height {@code y}, in node-local space of {@code slot}. */
    private static MeshGeometry quad(float x0, float x1, float z0, float z1, float y, int slot) {
        float[] pos = {x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1};
        float[] nrm = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        return MeshGeometry.of(pos, nrm, new float[8], new int[]{0, 2, 1, 0, 3, 2}, VertexSkinning.rigid(4, slot),
                SpriteUv.IDENTITY, 0);
    }

    private static Matrix4f[] rest(GltfPaletteLayout layout) {
        Matrix4f[] palette = new Matrix4f[layout.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = new Matrix4f();
        }
        GltfPose.evaluate(layout, null, 0.0f, palette);
        return palette;
    }

    @Test
    void childBoneGetsOwnSpriteInItsLocalFrame() {
        GltfPaletteLayout layout = layout();
        SchematicBake.Result result = SchematicBake.bake(
                List.of(quad(-2, 2, -4, 4, 0, 0), quad(-1, 1, -1, 1, 1, 1)), layout, rest(layout), List.of("turret"));
        assertNotNull(result);
        assertEquals(2, result.sprites().size());
        assertEquals(SchematicBake.HULL, result.sprites().get(0).bone());
        SchematicBake.Sprite turret = result.sprites().get(1);
        assertEquals("turret", turret.bone());
        assertTrue(turret.minX() < -1 && turret.maxX() > 1, "turret extent is its own local +-1: " + turret);
        assertTrue(turret.minZ() < -1 && turret.maxZ() > 1);
    }

    @Test
    void silhouetteIsOpaqueInsideAndOutlinedOutside() {
        GltfPaletteLayout layout = layout();
        SchematicBake.Result result = SchematicBake.bake(
                List.of(quad(-2, 2, -4, 4, 0, 0)), layout, rest(layout), List.of());
        SchematicBake.Sprite hull = result.sprites().get(0);
        int centre = result.argb()[(hull.y() + hull.h() / 2) * result.width() + hull.x() + hull.w() / 2];
        assertEquals(0xFF, centre >>> 24);
        assertTrue((centre & 0xFF) < 0xFF, "interior shaded, not outline");
        int corner = result.argb()[hull.y() * result.width() + hull.x()];
        assertEquals(0, corner >>> 24, "padding corner stays empty");
        int edge = result.argb()[(hull.y() + hull.h() / 2) * result.width() + hull.x() + 1];
        assertEquals(0xFFFFFFFF, edge, "outline ring just outside the silhouette");
    }

    @Test
    void missingBoneAndEmptyGeometry() {
        GltfPaletteLayout layout = layout();
        SchematicBake.Result result = SchematicBake.bake(
                List.of(quad(-1, 1, -1, 1, 0, 0)), layout, rest(layout), List.of("absent"));
        assertEquals(1, result.sprites().size());
        assertEquals(null, SchematicBake.bake(List.of(), layout, rest(layout), List.of()));
    }
}
