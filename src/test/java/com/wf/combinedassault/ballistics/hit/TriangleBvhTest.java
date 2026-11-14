package com.wf.combinedassault.ballistics.hit;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriangleBvhTest {

    /** Quad at {@code z}, two triangles spanning -1..1 in x and y. */
    private static void quad(float[] out, int quad, float z) {
        float[] q = {-1, -1, z, 1, -1, z, 1, 1, z, -1, -1, z, 1, 1, z, -1, 1, z};
        System.arraycopy(q, 0, out, quad * 18, 18);
    }

    /** Sheets at z = 0, 1, 2, ... n - 1, shuffled into the BVH by its median split. */
    private static TriangleBvh sheets(int n) {
        float[] tris = new float[n * 18];
        for (int i = 0; i < n; i++) {
            quad(tris, i, (i * 7) % n);
        }
        return new TriangleBvh(tris, new short[n * 2], new byte[n * 2]);
    }

    @Test
    void everySheetAlongTheLineAscending() {
        TriangleBvh bvh = sheets(10);
        float[] ts = new float[16];
        int[] tris = new int[16];
        int n = bvh.traceAll(0.1f, 0.2f, -5, 0, 0, 1, 100, new int[TriangleBvh.STACK], ts, tris);
        assertEquals(10, n);
        for (int i = 0; i < n; i++) {
            assertEquals(5 + i, ts[i], 1e-4, "sheet " + i);
        }
    }

    @Test
    void theNearestFitWhenThereAreMoreThanRoom() {
        TriangleBvh bvh = sheets(10);
        float[] ts = new float[4];
        int[] tris = new int[4];
        int n = bvh.traceAll(0.1f, 0.2f, -5, 0, 0, 1, 100, new int[TriangleBvh.STACK], ts, tris);
        assertEquals(4, n);
        for (int i = 0; i < n; i++) {
            assertEquals(5 + i, ts[i], 1e-4, "sheet " + i);
        }
    }

    @Test
    void limitAndDirectionBoundTheQuery() {
        TriangleBvh bvh = sheets(10);
        float[] ts = new float[16];
        int[] tris = new int[16];
        assertEquals(3, bvh.traceAll(0.1f, 0.2f, -5, 0, 0, 1, 7.5f, new int[TriangleBvh.STACK], ts, tris));
        assertEquals(0, bvh.traceAll(0.1f, 0.2f, -5, 0, 0, -1, 100, new int[TriangleBvh.STACK], ts, tris), "behind the origin");
        assertEquals(0, bvh.traceAll(3f, 0.2f, -5, 0, 0, 1, 100, new int[TriangleBvh.STACK], ts, tris), "off the sheets");
    }

    @Test
    void firstOfAllIsTheNearestHit() {
        TriangleBvh bvh = sheets(16);
        float[] ts = new float[16];
        int[] tris = new int[16];
        float[] one = new float[1];
        int hit = bvh.trace(0.1f, 0.2f, 20, 0, 0, -1, 100, one);
        int n = bvh.traceAll(0.1f, 0.2f, 20, 0, 0, -1, 100, new int[TriangleBvh.STACK], ts, tris);
        assertEquals(16, n);
        assertEquals(one[0], ts[0], 1e-6);
        assertEquals(hit, tris[0]);
    }

    @Test
    void theQueryAllocatesNothing() {
        TriangleBvh bvh = sheets(12);
        float[] ts = new float[16];
        int[] tris = new int[16];
        int[] stack = new int[TriangleBvh.STACK];
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        int sink = 0;
        for (int i = 0; i < 20000; i++) {
            sink += bvh.traceAll(0.1f, 0.2f, -5, 0, 0, 1, 100, stack, ts, tris);
        }
        long before = mx.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 20000; i++) {
            sink += bvh.traceAll(0.1f, 0.2f, -5, 0, 0, 1, 100, stack, ts, tris);
        }
        long bytes = mx.getThreadAllocatedBytes(thread) - before;
        assertTrue(sink > 0);
        assertTrue(bytes < 4096, bytes + " bytes over 20000 queries");
    }
}
