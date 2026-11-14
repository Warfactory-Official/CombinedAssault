import com.flansmod.client.tmt.ModelRendererTurbo;
import com.flansmod.client.tmt.PositionTextureVertex;
import com.flansmod.client.tmt.PositionTransformVertex;
import com.flansmod.client.tmt.TexturedPolygon;
import com.flansmod.common.vector.Vector3f;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * args: outDir class... => outDir/<SimpleName>.json.
 * Vertex = Flan model space after ModelRendererTurbo.render(1, oldRotateOrder) transform (1/16-block units, y up after flipAll).
 */
public final class FlanDump {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        int failed = 0;
        for (int i = 1; i < args.length; i++) {
            try {
                Object model = Class.forName(args[i]).getDeclaredConstructor().newInstance();
                StringBuilder sb = new StringBuilder();
                dump(model, sb);
                Files.writeString(out.resolve(model.getClass().getSimpleName() + ".json"), sb, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                failed++;
                System.err.println("FAIL " + args[i] + ": " + t);
                t.printStackTrace(System.err);
            }
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void dump(Object model, StringBuilder sb) throws Exception {
        boolean oldOrder = false;
        sb.append("{\"class\":\"").append(model.getClass().getName()).append("\",\"groups\":{");
        boolean first = true;
        StringBuilder vectors = new StringBuilder();
        StringBuilder scalars = new StringBuilder();
        for (Class<?> c = model.getClass(); c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(model);
                if (f.getName().equals("oldRotateOrder")) oldOrder = (Boolean) v;
                if (v == null) continue;
                if (v instanceof Vector3f vec) {
                    vectors.append(vectors.isEmpty() ? "" : ",").append('"').append(f.getName()).append("\":")
                            .append(String.format(Locale.ROOT, "[%s,%s,%s]", vec.x, vec.y, vec.z));
                } else if (v instanceof Number || v instanceof Boolean) {
                    scalars.append(scalars.isEmpty() ? "" : ",").append('"').append(f.getName()).append("\":").append(v);
                }
            }
        }
        for (Class<?> c = model.getClass(); c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(model);
                if (v == null) continue;
                if (v instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        if (e.getValue() instanceof ModelRendererTurbo[][] arr) {
                            first = group(sb, first, f.getName() + ":" + e.getKey(), arr, oldOrder);
                        }
                    }
                } else if (v instanceof ModelRendererTurbo[][] arr) {
                    first = group(sb, first, f.getName(), arr, oldOrder);
                } else if (v instanceof ModelRendererTurbo[] arr) {
                    first = group(sb, first, f.getName(), new ModelRendererTurbo[][]{arr}, oldOrder);
                } else if (v instanceof ModelRendererTurbo m) {
                    first = group(sb, first, f.getName(), new ModelRendererTurbo[][]{{m}}, oldOrder);
                }
            }
        }
        sb.append("},\"vectors\":{").append(vectors).append("},\"scalars\":{").append(scalars).append("}}");
    }

    /** groups[name] = [[box, ...] per sub-array]; box = {"name","polys":[[[x,y,z,u,v],...],...]}. */
    private static boolean group(StringBuilder sb, boolean first, String name, ModelRendererTurbo[][] arr, boolean oldOrder) {
        int count = 0;
        for (ModelRendererTurbo[] a : arr) count += a == null ? 0 : a.length;
        if (count == 0) return first;
        sb.append(first ? "" : ",").append('"').append(name).append("\":[");
        for (int i = 0; i < arr.length; i++) {
            sb.append(i == 0 ? "" : ",").append('[');
            ModelRendererTurbo[] a = arr[i] == null ? new ModelRendererTurbo[0] : arr[i];
            boolean firstBox = true;
            for (int j = 0; j < a.length; j++) {
                ModelRendererTurbo m = a[j];
                if (m == null) continue;
                sb.append(firstBox ? "" : ",");
                firstBox = false;
                box(sb, m, j, oldOrder);
            }
            sb.append(']');
        }
        sb.append(']');
        return false;
    }

    private static void box(StringBuilder sb, ModelRendererTurbo m, int index, boolean oldOrder) {
        double[] r = rotation(m, oldOrder);
        sb.append("{\"i\":").append(index).append(",\"hidden\":").append(!m.showModel || m.isHidden)
                .append(",\"pivot\":").append(String.format(Locale.ROOT, "[%s,%s,%s]", m.rotationPointX, m.rotationPointY, m.rotationPointZ))
                .append(",\"polys\":[");
        boolean firstPoly = true;
        for (TexturedPolygon p : faces(m)) {
            if (p == null || p.vertexPositions == null || p.vertexPositions.length < 3) continue;
            sb.append(firstPoly ? "" : ",").append('[');
            firstPoly = false;
            for (int k = 0; k < p.vertexPositions.length; k++) {
                PositionTextureVertex v = p.vertexPositions[k];
                if (v instanceof PositionTransformVertex t) t.setTransformation();
                double x = v.vector3D.x, y = v.vector3D.y, z = v.vector3D.z;
                double wx = r[0] * x + r[1] * y + r[2] * z + m.rotationPointX;
                double wy = r[3] * x + r[4] * y + r[5] * z + m.rotationPointY;
                double wz = r[6] * x + r[7] * y + r[8] * z + m.rotationPointZ;
                sb.append(k == 0 ? "" : ",").append(String.format(Locale.ROOT, "[%.5f,%.5f,%.5f,%.6f,%.6f]",
                        wx, wy, wz, v.texturePositionX, v.texturePositionY));
            }
            sb.append(']');
        }
        sb.append("]}");
    }

    private static TexturedPolygon[] faces(ModelRendererTurbo m) {
        try {
            Field f = ModelRendererTurbo.class.getDeclaredField("faces");
            f.setAccessible(true);
            return (TexturedPolygon[]) f.get(m);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Row-major R = product of the GL rotate calls in ModelRendererTurbo.render(worldScale, oldRotateOrder). */
    private static double[] rotation(ModelRendererTurbo m, boolean oldOrder) {
        double[] r = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        if (!oldOrder && m.rotateAngleY != 0) r = mul(r, rot(1, m.rotateAngleY));
        if (m.rotateAngleZ != 0) r = mul(r, rot(2, (oldOrder ? -1 : 1) * m.rotateAngleZ));
        if (oldOrder && m.rotateAngleY != 0) r = mul(r, rot(1, -m.rotateAngleY));
        if (m.rotateAngleX != 0) r = mul(r, rot(0, m.rotateAngleX));
        return r;
    }

    private static double[] rot(int axis, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return switch (axis) {
            case 0 -> new double[]{1, 0, 0, 0, c, -s, 0, s, c};
            case 1 -> new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
            default -> new double[]{c, -s, 0, s, c, 0, 0, 0, 1};
        };
    }

    private static double[] mul(double[] a, double[] b) {
        double[] o = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return o;
    }
}
