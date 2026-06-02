package mindcraft.procgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Produces (heightmap, biomeMap) tiles from a {@link WorldSpec}. */
public final class TerrainPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(TerrainPipeline.class);

    /** Result of generating one tile. */
    public static final class Tile {
        public final float[][] elev;
        public final RegistryBiomeIndex[][] biomeIdx;
        public final String[] biomesInUse;
        public final boolean[][] riverMask;
        public final int gridSize;
        public final int tileBlocks;

        public Tile(float[][] elev, RegistryBiomeIndex[][] biomeIdx, String[] biomesInUse,
                    boolean[][] riverMask, int gridSize, int tileBlocks) {
            this.elev = elev;
            this.biomeIdx = biomeIdx;
            this.biomesInUse = biomesInUse;
            this.riverMask = riverMask;
            this.gridSize = gridSize;
            this.tileBlocks = tileBlocks;
        }

        public float sampleElevBilinear(float gx, float gz) {
            int g = gridSize - 1;
            if (gx < 0) gx = 0; if (gx > g) gx = g;
            if (gz < 0) gz = 0; if (gz > g) gz = g;
            int x0 = (int) gx, z0 = (int) gz;
            int x1 = Math.min(x0 + 1, g);
            int z1 = Math.min(z0 + 1, g);
            float fx = gx - x0, fz = gz - z0;
            float v0 = elev[z0][x0] + fx * (elev[z0][x1] - elev[z0][x0]);
            float v1 = elev[z1][x0] + fx * (elev[z1][x1] - elev[z1][x0]);
            return v0 + fz * (v1 - v0);
        }

        public RegistryBiomeIndex sampleBiomeNearest(float gx, float gz) {
            int g = gridSize - 1;
            int x = Math.round(gx), z = Math.round(gz);
            if (x < 0) x = 0; if (x > g) x = g;
            if (z < 0) z = 0; if (z > g) z = g;
            return biomeIdx[z][x];
        }
    }

    public static final class RegistryBiomeIndex {
        public final int idx;
        public RegistryBiomeIndex(int idx) { this.idx = idx; }
    }

    private TerrainPipeline() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Top-level entry
    // ─────────────────────────────────────────────────────────────────────────

    public static Tile generate(WorldSpec spec, long worldSeed, int tileX, int tileZ,
                                int tileBlocks, int gridSize) {
        long t0 = System.currentTimeMillis();

        int seed = (int) ((worldSeed ^ (long) spec.seedOffset) & 0x7FFFFFFF);

        float[][] elev = buildBaseElevation(spec, seed, tileX, tileZ, gridSize);

        // CDF-normalise so elevation is a uniform terrain fraction in [0,1].
        ensureElevCdf(spec, seed);
        float[] cdf = spec.elevCdf;
        for (int z = 0; z < gridSize; z++)
            for (int x = 0; x < gridSize; x++)
                elev[z][x] = normaliseThroughCdf(cdf, elev[z][x]);

        boolean[][] riverMask = null;
        for (WorldSpec.SpatialFeature f : spec.spatialFeatures) {
            if (f.type == null) continue;
            switch (f.type) {
                case "sea_threshold" -> seaThreshold(elev, f.offset);
                case "stretch" -> {
                    if (f.to != null && f.to.length == 2) stretch(elev, f.to[0], f.to[1]);
                }
                case "mesa_step" -> {
                    float[][] jit = globalFbmGrid(8.0f, 3, 0.4f, 2.0f, seed + 700,
                                                  gridSize, tileX, tileZ);
                    int N = gridSize;
                    for (int z = 0; z < N; z++)
                        for (int x = 0; x < N; x++)
                            elev[z][x] = clamp01(
                                    (float) Math.floor(elev[z][x] * f.nSteps) / f.nSteps
                                    + jit[z][x] * f.jitter);
                }
                case "coastal_smooth" -> {
                    float sl = f.seaLevel != null ? f.seaLevel : spec.seaLevel;
                    coastalSmooth(elev, sl, f.width, f.strength);
                }
                case "dune_ridges" -> duneRidges(elev, f.directionDeg, f.spacing,
                                                  f.amplitude, seed + 50,
                                                  gridSize, tileX, tileZ);
                case "river" -> {
                    Object[] r = continuousRivers(elev, f, spec.seaLevel,
                                                  0f, 1f,
                                                  seed, tileX, tileZ, gridSize);
                    elev = (float[][]) r[0];
                    riverMask = (boolean[][]) r[1];
                }
                default -> LOG.warn("Unknown feature type {}", f.type);
            }
        }

        BiomeAssignment ba = resolveBiomes(elev, spec.biomeZones, riverMask, spec.riverBiome);

        long elapsed = System.currentTimeMillis() - t0;
        LOG.info("Generated tile ({},{}) in {} ms — {} unique biomes",
                tileX, tileZ, elapsed, ba.biomesInUse.length);
        return new Tile(elev, ba.biomeIdx, ba.biomesInUse, riverMask, gridSize, tileBlocks);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Base elevation + per-spec histogram normalisation
    // ─────────────────────────────────────────────────────────────────────────

    /** Raw noise (single profile or composited layers) + the elevation_range
     *  linear remap. This is the distribution the CDF is built from. */
    private static float[][] buildBaseElevation(WorldSpec spec, int seed,
                                                int tileX, int tileZ, int N) {
        float[][] elev;
        if (spec.noiseLayers != null && !spec.noiseLayers.isEmpty()) {
            elev = compositeLayers(spec.noiseLayers, seed, tileX, tileZ, N);
        } else if (spec.elevationProfile != null) {
            elev = makeNoise(spec.elevationProfile, seed, tileX, tileZ, N);
        } else {
            elev = constant(0.4f, N);
        }
        float lo = spec.elevationRangeLo, hi = spec.elevationRangeHi;
        if (lo != 0f || hi != 1f) {
            for (int z = 0; z < N; z++)
                for (int x = 0; x < N; x++)
                    elev[z][x] = clamp01(elev[z][x] * (hi - lo) + lo);
        }
        return elev;
    }

    /** Sample size for the per-spec elevation CDF (one representative patch). */
    private static final int CDF_SAMPLES = 200;   // 200×200 = 40k values

    /**
     * Lazily builds the spec's elevation CDF: a sorted sample of base-elevation
     * values taken from one representative off-origin patch. Stationary noise
     * means this single sample's distribution matches the whole infinite world,
     * so reusing it as a fixed monotonic remap keeps tiles seamlessly continuous.
     */
    private static void ensureElevCdf(WorldSpec spec, int seed) {
        if (spec.elevCdf != null) return;
        synchronized (spec) {
            if (spec.elevCdf != null) return;
            // Off-origin patch so we don't bias toward the (0,0) noise cell.
            float[][] sample = buildBaseElevation(spec, seed, 17, 23, CDF_SAMPLES);
            float[] flat = new float[CDF_SAMPLES * CDF_SAMPLES];
            int k = 0;
            for (float[] row : sample) for (float v : row) flat[k++] = v;
            Arrays.sort(flat);
            spec.elevCdf = flat;
        }
    }

    /**
     * Maps a raw base-elevation value to its quantile in [0,1] via the sorted
     * CDF sample (binary search + linear interpolation between neighbours).
     * Monotonic and deterministic ⇒ a continuous remap.
     */
    private static float normaliseThroughCdf(float[] sortedAsc, float e) {
        int n = sortedAsc.length;
        if (e <= sortedAsc[0])     return 0f;
        if (e >= sortedAsc[n - 1]) return 1f;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedAsc[mid] < e) lo = mid + 1; else hi = mid;
        }
        // sortedAsc[lo] >= e ; interpolate between lo-1 and lo for smoothness.
        float aVal = sortedAsc[lo - 1], bVal = sortedAsc[lo];
        float frac = (bVal > aVal) ? (e - aVal) / (bVal - aVal) : 0f;
        return ((lo - 1) + frac) / (n - 1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Noise layer construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds one normalised [0,1] elevation grid for a layer, sampling the
     * globally-continuous noise field at the coordinates for tile (tileX,tileZ).
     *
     * Uses fixed (mode-dependent) rescaling instead of per-tile min/max normalise
     * so that adjacent tiles always map to the same [0,1] range.
     */
    private static float[][] makeNoise(WorldSpec.NoiseLayer layer, int seed,
                                       int tileX, int tileZ, int N) {
        float[][] out = new float[N][N];
        float freq = layer.frequency;

        switch (layer.mode) {
            case "fbm" -> {
                fillFbm(out, freq, layer.octaves, layer.persistence, layer.lacunarity,
                        seed, tileX, tileZ);
                rescaleFbm(out);          // fixed: (v+1)/2 maps [-1,1] → [0,1]
            }
            case "ridged" -> {
                fillRidged(out, freq, layer.octaves, layer.persistence, layer.lacunarity,
                           layer.sharpness, seed, tileX, tileZ);
                // ridged output is already in [0,1] by construction — just clamp
                clipTo01(out);
            }
            case "billow" -> {
                fillBillow(out, freq, layer.octaves, layer.persistence, layer.lacunarity,
                           seed, tileX, tileZ);
                rescaleFbm(out);          // billow has same [-1,1] range as fbm
            }
            case "cellular" -> {
                fillCellular(out, freq, layer.cellMode, layer.cellJitter, seed, tileX, tileZ);
                clipTo01(out);            // f2-f1 peaks near 1.1; clip is safe
            }
            case "stepped" -> {
                fillFbm(out, freq, layer.octaves, layer.persistence, layer.lacunarity,
                        seed, tileX, tileZ);
                rescaleFbm(out);          // get consistent [0,1] before stepping
                float[][] jit = globalFbmGrid(8.0f, 3, 0.4f, 2.0f, seed + 700,
                                              N, tileX, tileZ);
                for (int z = 0; z < N; z++)
                    for (int x = 0; x < N; x++)
                        out[z][x] = clamp01(
                                (float) Math.floor(out[z][x] * layer.nSteps) / layer.nSteps
                                + jit[z][x] * layer.jitter);
            }
            case "domain_warp" -> {
                fillDomainWarp(out, freq, layer.octaves, layer.warpStrength, seed, tileX, tileZ);
                rescaleFbm(out);          // domain_warp wraps fbm → same range
            }
            case "domain_warp_ridged" -> {
                fillDomainWarpRidged(out, freq, layer.octaves, layer.warpStrength,
                                     layer.sharpness, layer.persistence, layer.lacunarity,
                                     seed, tileX, tileZ);
                clipTo01(out);            // domain_warp_ridged wraps ridged → [0,1]
            }
            default -> {
                LOG.warn("Unknown noise mode {}", layer.mode);
                fillFbm(out, freq, layer.octaves, layer.persistence, layer.lacunarity,
                        seed, tileX, tileZ);
                rescaleFbm(out);
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fill functions — all take (offU=tileX, offV=tileZ) in "tile units"
    // Global noise coord: worldX = (tileX + lx/(N-1)) * freq
    // ─────────────────────────────────────────────────────────────────────────

    private static void fillFbm(float[][] out, float freq, int oct, float p, float lac,
                                 int seed, int tileX, int tileZ) {
        int N = out.length;
        float invN = 1f / (N - 1);
        for (int z = 0; z < N; z++) {
            float yy = (tileZ + z * invN) * freq;
            for (int x = 0; x < N; x++) {
                float xx = (tileX + x * invN) * freq;
                out[z][x] = Noise.fbm(xx, yy, oct, p, lac, seed);
            }
        }
    }

    private static void fillRidged(float[][] out, float freq, int oct, float p, float lac,
                                    float sharp, int seed, int tileX, int tileZ) {
        int N = out.length;
        float invN = 1f / (N - 1);
        for (int z = 0; z < N; z++) {
            float yy = (tileZ + z * invN) * freq;
            for (int x = 0; x < N; x++) {
                float xx = (tileX + x * invN) * freq;
                out[z][x] = Noise.ridged(xx, yy, oct, p, lac, sharp, seed);
            }
        }
    }

    private static void fillBillow(float[][] out, float freq, int oct, float p, float lac,
                                    int seed, int tileX, int tileZ) {
        int N = out.length;
        float invN = 1f / (N - 1);
        for (int z = 0; z < N; z++) {
            float yy = (tileZ + z * invN) * freq;
            for (int x = 0; x < N; x++) {
                float xx = (tileX + x * invN) * freq;
                out[z][x] = Noise.billow(xx, yy, oct, p, lac, seed);
            }
        }
    }

    private static void fillCellular(float[][] out, float freq, String mode, float jitter,
                                      int seed, int tileX, int tileZ) {
        int N = out.length;
        float invN = 1f / (N - 1);
        for (int z = 0; z < N; z++) {
            float yy = (tileZ + z * invN) * freq;
            for (int x = 0; x < N; x++) {
                float xx = (tileX + x * invN) * freq;
                out[z][x] = Noise.cellular(xx, yy, mode, jitter, seed);
            }
        }
    }

    private static void fillDomainWarp(float[][] out, float freq, int oct, float warp,
                                        int seed, int tileX, int tileZ) {
        int N = out.length;
        float invN = 1f / (N - 1);
        float effWarp = warp * freq;
        for (int z = 0; z < N; z++) {
            float yy = (tileZ + z * invN) * freq;
            for (int x = 0; x < N; x++) {
                float xx = (tileX + x * invN) * freq;
                out[z][x] = Noise.domainWarp(xx, yy, oct, effWarp, seed);
            }
        }
    }

    private static void fillDomainWarpRidged(float[][] out, float freq, int oct, float warp,
                                              float sharp, float p, float lac,
                                              int seed, int tileX, int tileZ) {
        int N = out.length;
        float invN = 1f / (N - 1);
        float effWarp = warp * freq;
        for (int z = 0; z < N; z++) {
            float yy = (tileZ + z * invN) * freq;
            for (int x = 0; x < N; x++) {
                float xx = (tileX + x * invN) * freq;
                out[z][x] = Noise.domainWarpRidged(xx, yy, oct, effWarp, sharp, p, lac, seed);
            }
        }
    }

    private static float[][] globalFbmGrid(float freq, int oct, float p, float lac,
                                            int seed, int N, int tileX, int tileZ) {
        float[][] out = new float[N][N];
        fillFbm(out, freq, oct, p, lac, seed, tileX, tileZ);
        rescaleFbm(out);   // consistent [0,1] across tiles
        return out;
    }

    private static float[][] compositeLayers(List<WorldSpec.NoiseLayer> layers, int baseSeed,
                                              int tileX, int tileZ, int N) {
        float[][] accum  = new float[N][N];
        float[][] weight = new float[N][N];
        java.util.List<float[][][]> extras = new java.util.ArrayList<>();

        for (int li = 0; li < layers.size(); li++) {
            WorldSpec.NoiseLayer layer = layers.get(li);
            // Each layer uses a seed derived from the base seed + layer index,
            // keeping the seed tile-independent so layers are also continuous.
            int ls = baseSeed + li * 1009;
            float[][] e = makeNoise(layer, ls, tileX, tileZ, N);
            float wc = layer.weight;
            float[][] mask = layerMask(layer, ls, N, tileX, tileZ);
            float[][] wpix = new float[N][N];
            for (int z = 0; z < N; z++)
                for (int x = 0; x < N; x++)
                    wpix[z][x] = wc * (mask != null ? mask[z][x] : 1f);

            if ("add".equals(layer.blend)) {
                extras.add(new float[][][]{ e, wpix });
            } else {
                for (int z = 0; z < N; z++)
                    for (int x = 0; x < N; x++) {
                        accum[z][x]  += e[z][x] * wpix[z][x];
                        weight[z][x] += wpix[z][x];
                    }
            }
        }

        float[][] base = new float[N][N];
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++)
                base[z][x] = accum[z][x] / Math.max(weight[z][x], 1e-6f);

        for (float[][][] extra : extras) {
            float[][] e = extra[0]; float[][] w = extra[1];
            for (int z = 0; z < N; z++)
                for (int x = 0; x < N; x++)
                    base[z][x] = base[z][x] + e[z][x] * w[z][x];
        }
        // All input layers are already in [0,1] after fixed rescaling; the
        // weighted average stays in [0,1]. Just clamp any 'add' overflow.
        clipTo01(base);
        return base;
    }

    private static float[][] layerMask(WorldSpec.NoiseLayer layer, int seed,
                                        int N, int tileX, int tileZ) {
        if (layer.mask == null) return null;
        WorldSpec.LayerMask m = layer.mask;
        float[][] grid = globalFbmGrid(m.frequency, m.octaves, m.persistence, 2.0f,
                                       seed + 313, N, tileX, tileZ);
        // grid is already in [0,1] from globalFbmGrid
        float t  = m.threshold;
        float sf = Math.max(m.softness, 1e-3f);
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++) {
                float w = (grid[z][x] - (t - sf)) / (2.0f * sf);
                if (w < 0f) w = 0f; if (w > 1f) w = 1f;
                grid[z][x] = m.invert ? (1f - w) : w;
            }
        return grid;
    }

    private static void rescaleFbm(float[][] a) {
        int N = a.length;
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++) {
                float v = (a[z][x] + 1f) * 0.5f;
                a[z][x] = v < 0f ? 0f : (v > 1f ? 1f : v);
            }
    }

    private static void clipTo01(float[][] a) {
        int N = a.length;
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++) {
                if (a[z][x] < 0f) a[z][x] = 0f;
                if (a[z][x] > 1f) a[z][x] = 1f;
            }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static void seaThreshold(float[][] elev, float offset) {
        int N = elev.length;
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++) {
                float v = elev[z][x] + offset;
                elev[z][x] = v < 0f ? 0f : (v > 1f ? 1f : v);
            }
    }

    private static void stretch(float[][] elev, float toLo, float toHi) {
        int N = elev.length;
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++)
                elev[z][x] = clamp01(toLo + elev[z][x] * (toHi - toLo));
    }

    private static void coastalSmooth(float[][] elev, float seaLevel, float width, float strength) {
        int N = elev.length;
        float invW = 1f / Math.max(width, 1e-6f);
        for (int z = 0; z < N; z++)
            for (int x = 0; x < N; x++) {
                float delta = elev[z][x] - seaLevel;
                float pull = Math.max(0f, 1f - Math.abs(delta) * invW) * strength;
                elev[z][x] = elev[z][x] - delta * pull;
            }
    }

    private static void duneRidges(float[][] elev, float directionDeg, float spacing,
                                    float amplitude, int seed,
                                    int N, int tileX, int tileZ) {
        float theta = (float) Math.toRadians(directionDeg);
        float ux = (float) Math.cos(theta);
        float uy = (float) Math.sin(theta);
        float invSp = 1f / Math.max(spacing, 1e-4f);
        float[][] mask = globalFbmGrid(2.5f, 3, 0.5f, 2.0f, seed + 31, N, tileX, tileZ);
        float invN = 1f / (N - 1);
        for (int z = 0; z < N; z++) {
            float yy = tileZ + z * invN;
            for (int x = 0; x < N; x++) {
                float xx = tileX + x * invN;
                float proj = (ux * xx + uy * yy) * invSp;
                float r = (float) Math.sin(2.0 * Math.PI * proj) * 0.5f + 0.5f;
                r = r * r;
                float v = elev[z][x] + amplitude * (r - 0.5f) * mask[z][x];
                elev[z][x] = v < 0f ? 0f : (v > 1f ? 1f : v);
            }
        }
    }

    private static float[][] constant(float v, int N) {
        float[][] out = new float[N][N];
        for (float[] row : out) Arrays.fill(row, v);
        return out;
    }

    private static Object[] continuousRivers(float[][] elev, WorldSpec.SpatialFeature f,
                                              float seaLevel, float rangeLo, float rangeHi,
                                              int seed, int tileX, int tileZ, int N) {
        float invN   = 1f / (N - 1);
        int   rseed  = seed + 1300;
        float fr     = f.riverFreq;
        float warp   = f.riverWarp;
        int   oct    = Math.max(2, f.riverOctaves);
        float rWidth = Math.max(1e-4f, f.riverWidth);
        float bWidth = Math.max(1e-4f, f.bankWidth);
        float tribS  = clamp01(f.tributaryStrength);

        float bed    = clamp01(seaLevel - 0.035f);
        float span   = Math.max(1e-3f, rangeHi - rangeLo);
        float gateLo = rangeLo + 0.45f * span;
        float gateHi = rangeLo + 0.70f * span;

        float[][]   result    = new float[N][N];
        boolean[][] riverMask = new boolean[N][N];

        float hh = invN;

        for (int z = 0; z < N; z++) {
            float gz = tileZ + z * invN;
            for (int x = 0; x < N; x++) {
                float gx = tileX + x * invN;
                float e  = elev[z][x];

                // Distance to the meandering zero-contour, normalised by the
                // field gradient: dist ≈ |v| / |∇v|. This is the key to a
                // constant-width river — a bare |v| threshold balloons wherever
                // the noise is flat. Pure function of coords ⇒ still continuous.
                float dist1 = contourDist(gx, gz, fr,        hh, oct, warp, rseed);
                float dist;
                if (tribS > 1e-3f) {
                    // Tributaries: a higher-frequency contour network blended
                    // in based on tributary_strength. Distance is scaled by
                    // (2 - tribS) so weak tributaries appear thinner than
                    // the main channels.
                    float dist2 = contourDist(gx, gz, fr * 2.3f, hh, oct, warp, rseed + 777);
                    dist = Math.min(dist1, dist2 * (2f - tribS));
                } else {
                    dist = dist1;
                }

                // Channel profile: 1 inside the channel, smoothstep down to 0
                // at the bank edge. rWidth/bWidth are true tile-unit widths.
                float chan = 1f - smooth01(dist, rWidth, rWidth + bWidth);

                // Lowland gate: rivers only where terrain is low (valleys/plains),
                // fading out smoothly so they never gash through mountains.
                float gate = 1f - smooth01(e, gateLo, gateHi);

                float amount = chan * gate;            // 0..1 carve strength

                // Pull terrain down toward the river bed (never raises it, so
                // existing ocean/lakebed is untouched).
                float target = Math.min(e, bed);
                float newE   = e + (target - e) * amount;
                result[z][x] = clamp01(newE);

                // Biome river mask = the wet channel core only (thin line of
                // water in MC, not the full bank ramp).
                riverMask[z][x] = amount > 0.85f;
            }
        }
        return new Object[]{ result, riverMask };
    }

    /**
     * First-order distance (in tile units) from (gx,gz) to the zero-contour of
     * a domain-warped fbm field of frequency {@code freq}: |v| / |∇v|, with the
     * gradient estimated by central differences. Pure function of world coords,
     * so the resulting river network is continuous across all tile boundaries.
     */
    private static float contourDist(float gx, float gz, float freq, float h,
                                      int oct, float warp, int seed) {
        float v   = Noise.domainWarp( gx      * freq,  gz      * freq, oct, warp, seed);
        float vpx = Noise.domainWarp((gx + h) * freq,  gz      * freq, oct, warp, seed);
        float vmx = Noise.domainWarp((gx - h) * freq,  gz      * freq, oct, warp, seed);
        float vpz = Noise.domainWarp( gx      * freq, (gz + h) * freq, oct, warp, seed);
        float vmz = Noise.domainWarp( gx      * freq, (gz - h) * freq, oct, warp, seed);
        float dvx = (vpx - vmx) / (2f * h);
        float dvz = (vpz - vmz) / (2f * h);
        float grad = (float) Math.sqrt(dvx * dvx + dvz * dvz) + 1e-4f;
        return Math.abs(v) / grad;
    }

    /** Smoothstep ramp: 0 below `lo`, 1 above `hi`, smooth in between. */
    private static float smooth01(float v, float lo, float hi) {
        if (hi <= lo) return v >= hi ? 1f : 0f;
        float t = (v - lo) / (hi - lo);
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return t * t * (3f - 2f * t);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Biome assignment
    // ─────────────────────────────────────────────────────────────────────────

    public static final class BiomeAssignment {
        public final RegistryBiomeIndex[][] biomeIdx;
        public final String[] biomesInUse;
        BiomeAssignment(RegistryBiomeIndex[][] idx, String[] names) {
            this.biomeIdx = idx; this.biomesInUse = names;
        }
    }

    private static BiomeAssignment resolveBiomes(float[][] elev, List<WorldSpec.BiomeZone> zones,
                                                  boolean[][] riverMask, String riverBiome) {
        java.util.List<String> biomesInUse = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (zones.isEmpty()) { biomesInUse.add("plains"); seen.add("plains"); }
        else for (WorldSpec.BiomeZone z : zones) if (seen.add(z.biome)) biomesInUse.add(z.biome);
        if (riverMask != null && seen.add(riverBiome)) biomesInUse.add(riverBiome);

        Map<String, Integer> nameToIdx = new HashMap<>();
        for (int i = 0; i < biomesInUse.size(); i++) nameToIdx.put(biomesInUse.get(i), i);

        int N = elev.length;
        RegistryBiomeIndex[] pool = new RegistryBiomeIndex[biomesInUse.size()];
        for (int i = 0; i < pool.length; i++) pool[i] = new RegistryBiomeIndex(i);

        int fallback = nameToIdx.get(zones.isEmpty() ? "plains" : zones.get(zones.size()-1).biome);
        RegistryBiomeIndex[][] out = new RegistryBiomeIndex[N][N];
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                float e = elev[z][x];
                int idx = fallback;
                for (WorldSpec.BiomeZone bz : zones) {
                    if (e <= bz.elevMax) { idx = nameToIdx.get(bz.biome); break; }
                }
                out[z][x] = pool[idx];
            }
        }
        if (riverMask != null) {
            int rIdx = nameToIdx.get(riverBiome);
            for (int z = 0; z < N; z++)
                for (int x = 0; x < N; x++)
                    if (riverMask[z][x]) out[z][x] = pool[rIdx];
        }
        return new BiomeAssignment(out, biomesInUse.toArray(new String[0]));
    }
}
