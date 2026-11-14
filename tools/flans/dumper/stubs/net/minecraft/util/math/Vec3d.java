package net.minecraft.util.math;
public class Vec3d {
    public static final Vec3d ZERO = new Vec3d(0, 0, 0);
    public final double x, y, z;
    public Vec3d(double x, double y, double z) { this.x = x == -0.0D ? 0.0D : x; this.y = y == -0.0D ? 0.0D : y; this.z = z == -0.0D ? 0.0D : z; }
    public Vec3d subtract(Vec3d o) { return new Vec3d(x - o.x, y - o.y, z - o.z); }
    public Vec3d subtract(double a, double b, double c) { return new Vec3d(x - a, y - b, z - c); }
    public Vec3d add(Vec3d o) { return new Vec3d(x + o.x, y + o.y, z + o.z); }
    public Vec3d add(double a, double b, double c) { return new Vec3d(x + a, y + b, z + c); }
    public Vec3d scale(double s) { return new Vec3d(x * s, y * s, z * s); }
    public Vec3d crossProduct(Vec3d v) { return new Vec3d(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x); }
    public double dotProduct(Vec3d v) { return x * v.x + y * v.y + z * v.z; }
    public double length() { return Math.sqrt(x * x + y * y + z * z); }
    public double lengthVector() { return length(); }
    public Vec3d normalize() { double d = length(); return d < 1.0E-4D ? ZERO : new Vec3d(x / d, y / d, z / d); }
    public Vec3d rotatePitch(float a) { float c = (float) Math.cos(a), s = (float) Math.sin(a); return new Vec3d(x, y * c + z * s, z * c - y * s); }
    public Vec3d rotateYaw(float a) { float c = (float) Math.cos(a), s = (float) Math.sin(a); return new Vec3d(x * c + z * s, y, z * c - x * s); }
    public double distanceTo(Vec3d v) { return subtract(v).length(); }
}
