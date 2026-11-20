package com.wf.combinedassault.hud;

import org.joml.*;

import javax.annotation.Nonnull;
import java.lang.Math;

/** Ported from TACZ Guns. */
public class MathUtil {

    public static final float[] QUATERNION_ONE = {0, 0, 0, 1};
    public static final Quaternionf IDENTITY_QUATERNION = new Quaternionf();

    public static double magnificationToFov(double magnification, double currentFov) {
        double currentTan = Math.tan(Math.toRadians(currentFov / 2));
        double newTan = currentTan / magnification;
        return Math.toDegrees(Math.atan(newTan)) * 2;
    }

    /** Euler -> quaternion, order roll(z) -> yaw(y) -> pitch(x). Returns {@code [x,y,z,w]}. */
    public static float[] toQuaternion(float pitch, float yaw, float roll) {
        double cy = Math.cos(roll * 0.5);
        double sy = Math.sin(roll * 0.5);
        double cp = Math.cos(yaw * 0.5);
        double sp = Math.sin(yaw * 0.5);
        double cr = Math.cos(pitch * 0.5);
        double sr = Math.sin(pitch * 0.5);
        return new float[]{
                (float) (cy * cp * sr - sy * sp * cr),
                (float) (sy * cp * sr + cy * sp * cr),
                (float) (sy * cp * cr - cy * sp * sr),
                (float) (cy * cp * cr + sy * sp * sr)
        };
    }

    /** Same convention as {@link #toQuaternion(float, float, float)}; writes into {@code quaternion}. */
    public static void toQuaternion(float pitch, float yaw, float roll, @Nonnull Quaternionf quaternion) {
        double cy = Math.cos(roll * 0.5);
        double sy = Math.sin(roll * 0.5);
        double cp = Math.cos(yaw * 0.5);
        double sp = Math.sin(yaw * 0.5);
        double cr = Math.cos(pitch * 0.5);
        double sr = Math.sin(pitch * 0.5);

        quaternion.set(
                (float) (cy * cp * sr - sy * sp * cr),
                (float) (sy * cp * sr + cy * sp * cr),
                (float) (sy * cp * cr - cy * sp * sr),
                (float) (cy * cp * cr + sy * sp * sr)
        );
    }

    public static float[] slerp(float[] from, float[] to, float alpha) {
        float ax = from[0];
        float ay = from[1];
        float az = from[2];
        float aw = from[3];
        float bx = to[0];
        float by = to[1];
        float bz = to[2];
        float bw = to[3];

        float dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0) {
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
            dot = -dot;
        }
        float epsilon = 1e-6f;
        float s0, s1;
        if ((1.0 - dot) > epsilon) {
            float omega = (float) Math.acos(dot);
            float invSinOmega = 1.0f / (float) Math.sin(omega);
            s0 = (float) Math.sin((1.0 - alpha) * omega) * invSinOmega;
            s1 = (float) Math.sin(alpha * omega) * invSinOmega;
        } else {
            s0 = 1.0f - alpha;
            s1 = alpha;
        }
        float[] result = new float[4];
        result[0] = s0 * ax + s1 * bx;
        result[1] = s0 * ay + s1 * by;
        result[2] = s0 * az + s1 * bz;
        result[3] = s0 * aw + s1 * bw;
        return result;
    }

    public static Quaternionf toQuaternion(float[] q) {
        return new Quaternionf(q[0], q[1], q[2], q[3]);
    }

    public static Quaternionf slerp(Quaternionf from, Quaternionf to, float alpha) {
        float ax = from.x();
        float ay = from.y();
        float az = from.z();
        float aw = from.w();
        float bx = to.x();
        float by = to.y();
        float bz = to.z();
        float bw = to.w();

        float dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0) {
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
            dot = -dot;
        }
        float epsilon = 1e-6f;
        float s0, s1;
        if ((1.0 - dot) > epsilon) {
            float omega = (float) Math.acos(dot);
            float invSinOmega = 1.0f / (float) Math.sin(omega);
            s0 = (float) Math.sin((1.0 - alpha) * omega) * invSinOmega;
            s1 = (float) Math.sin(alpha * omega) * invSinOmega;
        } else {
            s0 = 1.0f - alpha;
            s1 = alpha;
        }
        float rx = s0 * ax + s1 * bx;
        float ry = s0 * ay + s1 * by;
        float rz = s0 * az + s1 * bz;
        float rw = s0 * aw + s1 * bw;
        return new Quaternionf(rx, ry, rz, rw);
    }

    public static float[] solveEquations(float[][] coefficients, float[] constants) {
        int n = constants.length;
        for (int pivot = 0; pivot < n - 1; pivot++) {
            for (int row = pivot + 1; row < n; row++) {
                float factor = coefficients[row][pivot] / coefficients[pivot][pivot];
                for (int col = pivot; col < n; col++) {
                    coefficients[row][col] -= coefficients[pivot][col] * factor;
                }
                constants[row] -= constants[pivot] * factor;
            }
        }
        float[] solution = new float[n];
        for (int i = n - 1; i >= 0; i--) {
            float sum = 0.0f;
            for (int j = i + 1; j < n; j++) {
                sum += coefficients[i][j] * solution[j];
            }
            solution[i] = (constants[i] - sum) / coefficients[i][i];
        }
        return solution;
    }

    /** Solves {@code A*C = B} for {@code C} (not {@code C*A = B}). */
    public static float[] getRelativeQuaternion(float[] qa, float[] qb) {
        float[][] coefficients = {
                {qa[3], -qa[2], qa[1], qa[0]},
                {qa[2], qa[3], -qa[0], qa[1]},
                {-qa[1], qa[0], qa[3], qa[2]},
                {-qa[0], -qa[1], -qa[2], qa[3]},
        };
        float[] constants = {qb[0], qb[1], qb[2], qb[3]};
        return solveEquations(coefficients, constants);
    }

    /** @see #getRelativeQuaternion(float[], float[]) */
    public static Quaternionf getRelativeQuaternion(Quaternionf qa, Quaternionf qb) {
        float[][] coefficients = {
                {qa.w(), -qa.z(), qa.y(), qa.x()},
                {qa.z(), qa.w(), -qa.x(), qa.y()},
                {-qa.y(), qa.x(), qa.w(), qa.z()},
                {-qa.x(), -qa.y(), -qa.z(), qa.w()},
        };
        float[] constants = {qb.x(), qb.y(), qb.z(), qb.w()};
        float[] result = solveEquations(coefficients, constants);
        return new Quaternionf(result[0], result[1], result[2], result[3]);
    }

}
