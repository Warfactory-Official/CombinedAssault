package net.minecraft.util.math;
public class MathHelper {
    public static float sin(float v) { return (float) Math.sin(v); }
    public static float cos(float v) { return (float) Math.cos(v); }
    public static float sqrt(float v) { return (float) Math.sqrt(v); }
    public static float sqrt(double v) { return (float) Math.sqrt(v); }
    public static int ceil(float v) { return (int) Math.ceil(v); }
    public static int floor(double v) { return (int) Math.floor(v); }
    public static float abs(float v) { return Math.abs(v); }
    public static double atan2(double a, double b) { return Math.atan2(a, b); }
    public static float wrapDegrees(float v) { v %= 360; if (v >= 180) v -= 360; if (v < -180) v += 360; return v; }
    public static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }
    public static float clamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
}
