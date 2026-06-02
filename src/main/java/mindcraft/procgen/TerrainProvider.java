package mindcraft.procgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

/** Turns world block coordinates into elevation and biome lookups via a tile cache. */
public final class TerrainProvider {

    private static final Logger LOG = LoggerFactory.getLogger(TerrainProvider.class);

    public static final int GRID_SIZE        = 512;
    public static final int TILE_BLOCKS      = 2048;
    public static final int MC_SEA_LEVEL_Y   = 63;
    public static final int SAFE_MAX_SURFACE_Y = 250;
    public static final int MIN_Y            = -64;
    public static final int MAX_Y            = 1968;

    private static final float METRES_PER_BLOCK = 5.0f;
    private static final int   MAX_CACHE_TILES  = 32;

    private static volatile TerrainProvider INSTANCE;

    private final long worldSeed;
    private final WorldSpec spec;

    private final Object cacheLock = new Object();
    private final Map<Long, TerrainPipeline.Tile> tileCache = new LinkedHashMap<>(16, 0.75f, true);
    private final ConcurrentHashMap<Long, FutureTask<TerrainPipeline.Tile>> pending = new ConcurrentHashMap<>();

    private TerrainProvider(long worldSeed, WorldSpec spec) {
        this.worldSeed = worldSeed;
        this.spec = spec;
        LOG.info("TerrainProvider init: spec='{}'", spec.name);
    }

    public static synchronized void init(long worldSeed, WorldSpec spec) {
        INSTANCE = new TerrainProvider(worldSeed, spec);
    }

    public static TerrainProvider getInstance() {
        TerrainProvider p = INSTANCE;
        if (p != null) return p;
        synchronized (TerrainProvider.class) {
            if (INSTANCE == null) {
                LOG.warn("TerrainProvider accessed before init; using default spec");
                INSTANCE = new TerrainProvider(0L, SpecRegistry.loadOrDefault(SpecRegistry.defaultSpecName()));
            }
            return INSTANCE;
        }
    }

    public WorldSpec getSpec() { return spec; }

    private TerrainPipeline.Tile getTile(int tileX, int tileZ) {
        long key = (((long) tileX) << 32) | (tileZ & 0xFFFFFFFFL);
        synchronized (cacheLock) {
            TerrainPipeline.Tile tile = tileCache.get(key);
            if (tile != null) return tile;
        }
        FutureTask<TerrainPipeline.Tile> task = pending.computeIfAbsent(key, k -> new FutureTask<>(
                () -> TerrainPipeline.generate(spec, worldSeed, tileX, tileZ, TILE_BLOCKS, GRID_SIZE)));
        task.run();
        try {
            TerrainPipeline.Tile tile = task.get();
            synchronized (cacheLock) {
                tileCache.put(key, tile);
                while (tileCache.size() > MAX_CACHE_TILES) {
                    tileCache.remove(tileCache.entrySet().iterator().next().getKey());
                }
            }
            pending.remove(key);
            return tile;
        } catch (Exception e) {
            pending.remove(key);
            throw new RuntimeException("Failed to generate tile (" + tileX + "," + tileZ + ")", e);
        }
    }

    public float sampleElev(int blockX, int blockZ) {
        int tileX = Math.floorDiv(blockX, TILE_BLOCKS);
        int tileZ = Math.floorDiv(blockZ, TILE_BLOCKS);
        TerrainPipeline.Tile tile = getTile(tileX, tileZ);
        float gx = (float)(blockX - tileX * TILE_BLOCKS) / TILE_BLOCKS * (tile.gridSize - 1);
        float gz = (float)(blockZ - tileZ * TILE_BLOCKS) / TILE_BLOCKS * (tile.gridSize - 1);
        return tile.sampleElevBilinear(gx, gz);
    }

    public String sampleBiomeName(int blockX, int blockZ) {
        int tileX = Math.floorDiv(blockX, TILE_BLOCKS);
        int tileZ = Math.floorDiv(blockZ, TILE_BLOCKS);
        TerrainPipeline.Tile tile = getTile(tileX, tileZ);
        float gx = (float)(blockX - tileX * TILE_BLOCKS) / TILE_BLOCKS * (tile.gridSize - 1);
        float gz = (float)(blockZ - tileZ * TILE_BLOCKS) / TILE_BLOCKS * (tile.gridSize - 1);
        TerrainPipeline.RegistryBiomeIndex idx = tile.sampleBiomeNearest(gx, gz);
        return idx != null ? tile.biomesInUse[idx.idx] : "plains";
    }

    public int surfaceY(int blockX, int blockZ) {
        return elevToY(sampleElev(blockX, blockZ));
    }

    public int elevToY(float elev) {
        float span = spec.metresHi - spec.metresLo;
        float metresFromSea = (elev - spec.seaLevel) * span;
        int y;
        if (metresFromSea >= 0f) {
            float blocks = metresFromSea / METRES_PER_BLOCK;
            float peakBlocks = ((1f - spec.seaLevel) * span) / METRES_PER_BLOCK;
            float maxLand = SAFE_MAX_SURFACE_Y - MC_SEA_LEVEL_Y;
            if (peakBlocks > maxLand) blocks *= maxLand / peakBlocks;
            y = MC_SEA_LEVEL_Y + (int) Math.floor(blocks);
        } else {
            float depth = -metresFromSea;
            float compressed = (float) Math.sqrt(depth + 10f) - (float) Math.sqrt(10f);
            y = MC_SEA_LEVEL_Y - (int) Math.ceil(compressed);
        }
        if (y < MIN_Y + 5) y = MIN_Y + 5;
        if (y > SAFE_MAX_SURFACE_Y) y = SAFE_MAX_SURFACE_Y;
        return y;
    }
}
