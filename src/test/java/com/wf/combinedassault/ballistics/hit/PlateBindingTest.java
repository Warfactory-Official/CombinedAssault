package com.wf.combinedassault.ballistics.hit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wf.wflib.armor.api.hull.HullProfile;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlateBindingTest {

    private static final String RHA = "{\"layers\":[{\"mm\":50,\"mat\":\"rha\"}]}";

    private static HullProfile profile(String plates) {
        return new Gson().fromJson("{\"plates\":" + plates + "}", HullProfile.class);
    }

    /** Unit right triangle facing {@code normal-ish}: +Z rotated by {@code tilt}. */
    private static float[] triangle(Quaternionf tilt, float x) {
        float[] out = new float[9];
        float[][] corners = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}};
        Vector3f v = new Vector3f();
        for (int i = 0; i < 3; i++) {
            tilt.transform(v.set(corners[i][0], corners[i][1], corners[i][2])).add(x, 0, 0);
            out[i * 3] = v.x;
            out[i * 3 + 1] = v.y;
            out[i * 3 + 2] = v.z;
        }
        return out;
    }

    private static float[] concat(float[]... parts) {
        int n = 0;
        for (float[] p : parts) {
            n += p.length;
        }
        float[] out = new float[n];
        int at = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    /** One node, rotation {@code frame}, triangles bound by {@code plates} (index + 1, 0 = unbound). */
    private static HitMesh mesh(Quaternionf frame, float[] triangles, short[] plates, String... ids) {
        TriangleBvh bvh = new TriangleBvh(triangles, plates, new byte[plates.length]);
        HitMesh.Node node = new HitMesh.Node("hull", -1, false, 0, "", "", "", "", 0, Float.NaN, frame, new Vector3f(), bvh, null,
                null);
        return new HitMesh("test", List.of(node), List.of(ids));
    }

    private static Quaternionf tiltUp(float degrees) {
        return new Quaternionf().rotationX((float) Math.toRadians(-degrees));
    }

    @Test
    void declaredAndBoundPlatesBindAndCompile() {
        HitMesh mesh = mesh(new Quaternionf(), triangle(new Quaternionf(), 0), new short[]{1}, "ufp");
        HullProfile armor = profile("{\"ufp\":{\"facing\":\"front\",\"slope\":60,\"layers\":[{\"mm\":50,\"mat\":\"rha\"}]}}");
        PlateBinding.bind(mesh, armor);
        assertEquals(50f, armor.plates.get("ufp").nominal(false));
    }

    @Test
    void meshIdWithoutEntryIsALoadError() {
        HitMesh mesh = mesh(new Quaternionf(), triangle(new Quaternionf(), 0), new short[]{1}, "ufp");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PlateBinding.bind(mesh, profile("{\"other\":" + RHA + "}")));
        assertTrue(e.getMessage().contains("ufp"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> PlateBinding.bind(mesh, null));
    }

    @Test
    void entryWithoutFacesIsALoadError() {
        HitMesh mesh = mesh(new Quaternionf(), triangle(new Quaternionf(), 0), new short[]{0}, "ufp");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PlateBinding.bind(mesh, profile("{\"ufp\":" + RHA + "}")));
        assertTrue(e.getMessage().contains("ufp"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> PlateBinding.bind(null, profile("{\"ufp\":" + RHA + "}")));
    }

    @Test
    void plateFreeVehiclesBindWithoutAProfile() {
        PlateBinding.bind(mesh(new Quaternionf(), triangle(new Quaternionf(), 0), new short[]{0}), null);
        PlateBinding.bind(null, null);
    }

    @Test
    void missingNormalIsFittedFromTheFaces() {
        float[] tris = concat(triangle(tiltUp(30), 0), triangle(tiltUp(30), 3));
        HitMesh mesh = mesh(new Quaternionf(), tris, new short[]{1, 1}, "ufp");
        HullProfile armor = profile("{\"ufp\":" + RHA + "}");
        PlateBinding.bind(mesh, armor);
        assertEquals(0f, Float.parseFloat(armor.plates.get("ufp").facing), 0.06f);
        assertEquals(30f, armor.plates.get("ufp").slope, 1.0e-3f);
    }

    @Test
    void fitUsesTheNodeFrameInTheModelFrame() {
        Quaternionf yaw = new Quaternionf().rotationY((float) Math.toRadians(90));
        HitMesh mesh = mesh(yaw, triangle(new Quaternionf(), 0), new short[]{1}, "p");
        HullProfile armor = profile("{\"p\":" + RHA + "}");
        PlateBinding.bind(mesh, armor);
        assertEquals(270f, Float.parseFloat(armor.plates.get("p").facing), 0.06f);
        assertEquals(0f, armor.plates.get("p").slope, 1.0e-3f);
    }

    @Test
    void horizontalFacesFitTopAndBottom() {
        Quaternionf up = new Quaternionf().rotationX((float) Math.toRadians(-90));
        HitMesh mesh = mesh(new Quaternionf(), triangle(up, 0), new short[]{1}, "roof");
        HullProfile armor = profile("{\"roof\":" + RHA + "}");
        PlateBinding.bind(mesh, armor);
        assertEquals("top", armor.plates.get("roof").facing);
    }

    @Test
    void opposedFacesRefuseTheFit() {
        Quaternionf back = new Quaternionf().rotationY((float) Math.PI);
        float[] tris = concat(triangle(new Quaternionf(), 0), triangle(back, 3));
        HitMesh mesh = mesh(new Quaternionf(), tris, new short[]{1, 1}, "skirt");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PlateBinding.bind(mesh, profile("{\"skirt\":" + RHA + "}")));
        assertTrue(e.getMessage().contains("skirt") && e.getMessage().contains("facing"), e.getMessage());
    }

    @Test
    void curvedAndDeclaredPlatesAreNotFitted() {
        float[] tris = concat(triangle(tiltUp(30), 0), triangle(new Quaternionf(), 3));
        HitMesh mesh = mesh(new Quaternionf(), tris, new short[]{1, 2}, "mantlet", "ufp");
        HullProfile armor = profile("{\"mantlet\":{\"curved\":true,\"layers\":[{\"mm\":300,\"mat\":\"cast\"}]},"
                + "\"ufp\":{\"facing\":\"front\",\"slope\":10,\"layers\":[{\"mm\":50,\"mat\":\"rha\"}]}}");
        PlateBinding.bind(mesh, armor);
        assertNull(armor.plates.get("mantlet").facing);
        assertEquals("front", armor.plates.get("ufp").facing);
    }

    @Test
    void invalidDeclarationNamesThePlate() {
        HitMesh mesh = mesh(new Quaternionf(), triangle(new Quaternionf(), 0), new short[]{1}, "ufp");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PlateBinding.bind(mesh, profile("{\"ufp\":{\"slope\":40,\"layers\":[{\"mm\":50,\"mat\":\"rha\"}]}}")));
        assertTrue(e.getMessage().contains("ufp"), e.getMessage());
    }

    @Test
    void materialTokenIsTheLastWordOfTheName() {
        assertEquals("ufp", plate("armor:ufp"));
        assertEquals("turret_cheek", plate("paint_armor:turret_cheek"));
        assertEquals("ufp", plate("hull armor:ufp.001"));
        assertNull(plate("armor_80"));
        assertNull(plate("noarmor:ufp"));
        assertNull(plate("armor:ufp rubber"));
    }

    private static String plate(String material) {
        GltfReader.Node node = new GltfReader.Node("hull", -1, new Matrix4f(), new Matrix4f(),
                triangle(new Quaternionf(), 0), new int[]{0}, new int[]{0}, "body", "", new JsonObject());
        GltfReader.Model model = new GltfReader.Model(List.of(node), List.of(material), List.of(), List.of(),
                new java.util.BitSet());
        ModelRig rig = ModelRig.of("test", model.nodes());
        HitMesh mesh = ModelDataManager.hitMesh("test", model, rig, java.util.Arrays.asList((String) null));
        return mesh.plateId(mesh.nodes().get(0).mesh().plate(0));
    }
}
