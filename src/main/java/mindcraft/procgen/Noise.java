package mindcraft.procgen;

import java.util.HashMap;
import java.util.Map;

/**
 * Procedural noise functions ported from terrain_pipeline.py.
 *
 * All functions are evaluable at any continuous (x, y) coordinate, allowing both
 * tile-grid sampling and per-block sampling.
 *
 * Outputs of fbm/ridged/billow/domain_warp/domain_warp_ridged are ROUGHLY in [-1,1]
 * (or [0,1] for ridged). The pipeline normalises a sampled tile to exactly [0,1].
 */
public final class Noise {

    private Noise() {}

    // -------------------------------------------------------------------------
    // Permutation table cache (per seed)
    // -------------------------------------------------------------------------

    private static final class PermTable {
        final float[] gx;
        final float[] gy;
        final int[] perm;
        final int n;
        final int mask;

        PermTable(int seed) {
            int k = seed & 0xFFFF;
            n = 512;
            mask = n - 1;
            gx = new float[n];
            gy = new float[n];
            int[] base = new int[n];
            for (int i = 0; i < n; i++) base[i] = i;

            // Mirror numpy.random.RandomState behaviour deterministically with our own LCG.
            long state = (k == 0 ? 0x9E3779B97F4A7C15L : (long) k * 0x9E3779B97F4A7C15L);
            for (int i = 0; i < n; i++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                double u = ((state >>> 11) & ((1L << 53) - 1)) / (double)(1L << 53);
                double ang = u * 2.0 * Math.PI;
                gx[i] = (float) Math.cos(ang);
                gy[i] = (float) Math.sin(ang);
            }
            for (int i = n - 1; i > 0; i--) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                int j = (int) ((state >>> 1) % (long)(i + 1));
                int tmp = base[i];
                base[i] = base[j];
                base[j] = tmp;
            }
            perm = new int[n * 2];
            for (int i = 0; i < n; i++) {
                perm[i] = base[i];
                perm[i + n] = base[i];
            }
        }
    }

    private static final Map<Integer, PermTable> PERM_CACHE = new HashMap<>();

    private static synchronized PermTable getPerm(int seed) {
        int k = seed & 0xFFFF;
        PermTable t = PERM_CACHE.get(k);
        if (t == null) {
            t = new PermTable(k);
            PERM_CACHE.put(k, t);
        }
        return t;
    }

    // -------------------------------------------------------------------------
    // Gradient Perlin (single-frequency)
    // -------------------------------------------------------------------------

    /** Single-octave Perlin noise sample at (x, y). Output roughly in [-1, 1]. */
    public static float perlin(float x, float y, int seed) {
        PermTable t = getPerm(seed);
        int xi = ((int) Math.floor(x)) & t.mask;
        int yi = ((int) Math.floor(y)) & t.mask;
        float xf = x - (float) Math.floor(x);
        float yf = y - (float) Math.floor(y);
        int xi1 = (xi + 1) & t.mask;
        int yi1 = (yi + 1) & t.mask;

        float u = xf * xf * xf * (xf * (xf * 6f - 15f) + 10f);
        float v = yf * yf * yf * (yf * (yf * 6f - 15f) + 10f);

        int i00 = t.perm[t.perm[xi]  + yi];
        int i10 = t.perm[t.perm[xi1] + yi];
        int i01 = t.perm[t.perm[xi]  + yi1];
        int i11 = t.perm[t.perm[xi1] + yi1];

        float n00 = t.gx[i00] * xf       + t.gy[i00] * yf;
        float n10 = t.gx[i10] * (xf - 1) + t.gy[i10] * yf;
        float n01 = t.gx[i01] * xf       + t.gy[i01] * (yf - 1);
        float n11 = t.gx[i11] * (xf - 1) + t.gy[i11] * (yf - 1);

        float nx0 = n00 + u * (n10 - n00);
        float nx1 = n01 + u * (n11 - n01);
        return nx0 + v * (nx1 - nx0);
    }

    // -------------------------------------------------------------------------
    // fBm / ridged / billow
    // -------------------------------------------------------------------------

    public static float fbm(float x, float y, int octaves, float persistence, float lacunarity, int seed) {
        float out = 0f, amp = 1f, total = 0f, f = 1f;
        for (int i = 0; i < octaves; i++) {
            out   += amp * perlin(x * f, y * f, seed + i * 97);
            total += amp;
            amp   *= persistence;
            f     *= lacunarity;
        }
        return out / total;
    }

    public static float ridged(float x, float y, int octaves, float persistence, float lacunarity,
                                float sharpness, int seed) {
        float out = 0f, amp = 1f, total = 0f, f = 1f;
        for (int i = 0; i < octaves; i++) {
            float n = perlin(x * f, y * f, seed + i * 97);
            n = 1f - Math.abs(n);
            if (n < 0f) n = 0f;
            n = (float) Math.pow(n, sharpness);
            out   += amp * n;
            total += amp;
            amp   *= persistence;
            f     *= lacunarity;
        }
        return out / total;
    }

    public static float billow(float x, float y, int octaves, float persistence, float lacunarity, int seed) {
        float out = 0f, amp = 1f, total = 0f, f = 1f;
        for (int i = 0; i < octaves; i++) {
            float n = Math.abs(perlin(x * f, y * f, seed + i * 97)) * 2f - 1f;
            out   += amp * n;
            total += amp;
            amp   *= persistence;
            f     *= lacunarity;
        }
        return out / total;
    }

    public static float domainWarp(float x, float y, int octaves, float warp, int seed) {
        // freq=1 because callers pre-scale x, y. warp is multiplied by an
        // implicit base frequency of 1 to keep parity with the python pipeline
        // (where wx = fbm(...) * warp * freq and the inputs were already in
        // [0, freq] range — here x, y already live in the same space).
        float wx = fbm(x, y, octaves, 0.5f, 2.0f, seed + 200) * warp;
        float wy = fbm(x, y, octaves, 0.5f, 2.0f, seed + 400) * warp;
        return fbm(x + wx, y + wy, octaves, 0.5f, 2.0f, seed);
    }

    public static float domainWarpRidged(float x, float y, int octaves, float warp, float sharpness,
                                          float persistence, float lacunarity, int seed) {
        float wx = fbm(x, y, octaves, 0.5f, 2.0f, seed + 200) * warp;
        float wy = fbm(x, y, octaves, 0.5f, 2.0f, seed + 400) * warp;
        float out = 0f, amp = 1f, total = 0f, f = 1f;
        for (int i = 0; i < octaves; i++) {
            float n = perlin((x + wx) * f, (y + wy) * f, seed + i * 97);
            n = 1f - Math.abs(n);
            if (n < 0f) n = 0f;
            n = (float) Math.pow(n, sharpness);
            out   += amp * n;
            total += amp;
            amp   *= persistence;
            f     *= lacunarity;
        }
        return out / total;
    }

    // -------------------------------------------------------------------------
    // Cellular (Worley/Voronoi) noise
    // -------------------------------------------------------------------------

    private static int hash2(int ix, int iy, int seed) {
        int h = ix * 0x27d4eb2d ^ iy * 0x165667b1 ^ seed * 0x85ebca6b;
        h ^= h >>> 15; h *= 0x2c1b3c6d;
        h ^= h >>> 12; h *= 0x297a2d39;
        h ^= h >>> 15;
        return h;
    }

    public static float cellular(float x, float y, String mode, float jitter, int seed) {
        int ix0 = (int) Math.floor(x);
        int iy0 = (int) Math.floor(y);
        float fx = x - ix0;
        float fy = y - iy0;
        float f1 = 1e9f;
        float f2 = 1e9f;
        float j = Math.max(0f, Math.min(1f, jitter));
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = ix0 + dx;
                int cy = iy0 + dy;
                int h = hash2(cx, cy, seed);
                float rx = ((h & 0xFFFF) / 65536f);
                float ry = (((h >>> 16) & 0xFFFF) / 65536f);
                float px = dx + 0.5f + (rx - 0.5f) * j;
                float py = dy + 0.5f + (ry - 0.5f) * j;
                float ddx = px - fx, ddy = py - fy;
                float d = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                if (d < f1) { f2 = f1; f1 = d; }
                else if (d < f2) { f2 = d; }
            }
        }
        if ("f1".equals(mode)) return f1;
        if ("f1_inv".equals(mode)) return 1f - f1;
        return f2 - f1;
    }
}
