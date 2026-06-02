package mindcraft.procgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Java mirror of the WorldSpec JSON format documented in terrain_specs/_template.json.
 *
 * Parsed lazily from JSON into nested POJO-ish records. Unknown fields are ignored.
 * Documentation fields starting with __ are silently dropped.
 */
public final class WorldSpec {

    public String name;
    public String description = "";
    public int seedOffset = 0;

    /** Single-layer noise (mutually exclusive with noiseLayers). May be null. */
    public NoiseLayer elevationProfile;
    /** Multi-layer noise (mutually exclusive with elevationProfile). May be null. */
    public List<NoiseLayer> noiseLayers;

    public float elevationRangeLo = 0.0f;
    public float elevationRangeHi = 1.0f;

    public List<SpatialFeature> spatialFeatures = new ArrayList<>();

    public float seaLevel = 0.30f;
    public List<BiomeZone> biomeZones = new ArrayList<>();
    public String riverBiome = "river";

    public float metresLo = -300f;
    public float metresHi = 200f;

    /**
     * Sorted ascending sample of base-elevation values, built once by
     * {@code TerrainPipeline.ensureElevCdf}. Used to histogram-normalise
     * elevation so biome_zones / sea_level act as terrain fractions. Not parsed
     * from JSON; transient so it never serialises with the spec.
     */
    public transient float[] elevCdf;

    /**
     * One noise layer (used both for elevationProfile and entries of noiseLayers).
     */
    public static final class NoiseLayer {
        public String mode = "fbm";
        public float frequency = 4.0f;
        public int octaves = 7;
        public float persistence = 0.5f;
        public float lacunarity = 2.0f;
        public float sharpness = 2.5f;     // ridged / domain_warp_ridged only
        public float warpStrength = 0.45f; // domain_warp variants only
        public int   nSteps = 7;            // stepped only
        public float jitter = 0.022f;       // stepped only
        public String cellMode = "f2_minus_f1"; // cellular only
        public float cellJitter = 1.0f;     // cellular only
        public float weight = 1.0f;         // multi-layer only
        public String blend = "average";    // "average" | "add"
        public LayerMask mask;              // optional, multi-layer only
    }

    /** Spatial low-frequency mask for gating a layer's contribution. */
    public static final class LayerMask {
        public float frequency = 1.5f;
        public int   octaves = 4;
        public float persistence = 0.5f;
        public float threshold = 0.5f;
        public float softness = 0.05f;
        public boolean invert = false;
    }

    /** A single feature in the spatial_features pipeline. */
    public static final class SpatialFeature {
        public String type;
        // river (continuous analytic network — see TerrainPipeline.continuousRivers).
        // A river is the meandering zero-contour of a domain-warped noise field;
        // where it passes through *lowland* terrain the channel is carved down to
        // sea level so Minecraft fills it with water (the river biome is cosmetic).
        public Float riverElev;             // legacy (unused)
        public float bankDepth = 0.12f;     // legacy (unused — rivers carve to sea level)
        public float smoothSigma = 12.0f;   // legacy (unused)
        public float accumFrac = 0.004f;    // legacy (unused)
        public float riverFreq = 0.55f;     // tile-unit frequency of the meander network.
                                            // Lower = fewer, longer rivers. 0.4=sparse main rivers,
                                            // 0.7=several rivers, 1.0=dense network.
        public float riverWarp = 0.5f;      // domain-warp strength (meander wiggliness)
        public int   riverOctaves = 4;      // octaves of the warp field
        public float riverWidth = 0.0024f;  // channel half-width in TILE UNITS (~2-3 blocks)
        public float bankWidth = 0.018f;    // bank ramp width in tile units (gentle slope)
        public float tributaryStrength = 0f;// 0 = no tributaries (clean main channels),
                                            // 1 = full tributary network. Default off; mangrove
                                            // deltas / dense river worlds raise this.
        public float lowlandBand = 0.22f;   // legacy (unused — peak fade now uses the
                                            // spec's own elevation_range band)
        // sea_threshold
        public float offset = -0.46f;
        // mesa_step
        public int   nSteps = 6;
        public float jitter = 0.02f;
        // coastal_smooth (also uses sea_level / width / strength)
        public Float seaLevel;
        public float width = 0.08f;
        public float strength = 0.6f;
        // dune_ridges
        public float directionDeg = 270f;
        public float spacing = 0.04f;
        public float amplitude = 0.04f;
        // stretch
        public float[] to;
    }

    public static final class BiomeZone {
        public final float elevMax;
        public final String biome;
        public BiomeZone(float elevMax, String biome) {
            this.elevMax = elevMax;
            this.biome   = biome;
        }
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    public static WorldSpec fromJson(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        return fromJson(root);
    }

    public static WorldSpec fromJson(String json) {
        return fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    public static WorldSpec fromJson(JsonObject root) {
        WorldSpec s = new WorldSpec();
        s.name        = optString(root, "name", "Unnamed");
        s.description = optString(root, "description", "");
        s.seedOffset  = optInt(root, "seed_offset", 0);

        if (root.has("elevation_profile") && root.get("elevation_profile").isJsonObject()) {
            s.elevationProfile = parseLayer(root.getAsJsonObject("elevation_profile"));
        }
        if (root.has("noise_layers") && root.get("noise_layers").isJsonArray()) {
            s.noiseLayers = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("noise_layers")) {
                if (e.isJsonObject()) s.noiseLayers.add(parseLayer(e.getAsJsonObject()));
            }
        }

        if (root.has("elevation_range") && root.get("elevation_range").isJsonArray()) {
            JsonArray a = root.getAsJsonArray("elevation_range");
            if (a.size() >= 2) {
                s.elevationRangeLo = a.get(0).getAsFloat();
                s.elevationRangeHi = a.get(1).getAsFloat();
            }
        }

        if (root.has("spatial_features") && root.get("spatial_features").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("spatial_features")) {
                if (e.isJsonObject()) {
                    SpatialFeature f = parseFeature(e.getAsJsonObject());
                    if (f != null && f.type != null) s.spatialFeatures.add(f);
                }
            }
        }

        s.seaLevel  = optFloat(root, "sea_level", 0.30f);
        s.riverBiome = optString(root, "river_biome", "river");

        if (root.has("biome_zones") && root.get("biome_zones").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("biome_zones")) {
                if (e.isJsonObject()) {
                    JsonObject z = e.getAsJsonObject();
                    if (z.has("elev_max") && z.has("biome")) {
                        s.biomeZones.add(new BiomeZone(z.get("elev_max").getAsFloat(),
                                                       z.get("biome").getAsString()));
                    }
                }
            }
        }
        if (root.has("metres_range") && root.get("metres_range").isJsonArray()) {
            JsonArray a = root.getAsJsonArray("metres_range");
            if (a.size() >= 2) {
                s.metresLo = a.get(0).getAsFloat();
                s.metresHi = a.get(1).getAsFloat();
            }
        }

        // Sort biome zones ascending by elev_max so resolveBiome can do a linear scan.
        s.biomeZones.sort((a, b) -> Float.compare(a.elevMax, b.elevMax));
        return s;
    }

    /** Returns validation errors that would make this spec unsafe for world generation. */
    public List<String> validateForGeneration() {
        List<String> errors = new ArrayList<>();
        boolean hasSingle = elevationProfile != null;
        boolean hasLayers = noiseLayers != null && !noiseLayers.isEmpty();
        if (name == null || name.isBlank()) {
            errors.add("name is required");
        }
        if (hasSingle == hasLayers) {
            errors.add("use exactly one of elevation_profile or non-empty noise_layers");
        }
        if (hasSingle) validateLayer(elevationProfile, "elevation_profile", errors);
        if (hasLayers) {
            for (int i = 0; i < noiseLayers.size(); i++) {
                validateLayer(noiseLayers.get(i), "noise_layers[" + i + "]", errors);
            }
        }
        if (!Float.isFinite(elevationRangeLo) || !Float.isFinite(elevationRangeHi)
                || elevationRangeLo < 0f || elevationRangeHi > 1f || elevationRangeLo >= elevationRangeHi) {
            errors.add("elevation_range must be [lo, hi] with 0 <= lo < hi <= 1");
        }
        if (biomeZones.isEmpty()) {
            errors.add("biome_zones is required");
        }
        float previous = -1f;
        for (int i = 0; i < biomeZones.size(); i++) {
            BiomeZone zone = biomeZones.get(i);
            if (!Float.isFinite(zone.elevMax) || zone.elevMax <= previous || zone.elevMax > 1.0001f) {
                errors.add("biome_zones[" + i + "].elev_max must be sorted ascending in 0..1");
            }
            if (!BiomeRegistry.isKnown(zone.biome)) {
                errors.add("biome_zones[" + i + "].biome is not valid: " + zone.biome);
            }
            previous = zone.elevMax;
        }
        if (!biomeZones.isEmpty() && biomeZones.get(biomeZones.size() - 1).elevMax < 0.999f) {
            errors.add("last biome_zones entry must have elev_max 1.0");
        }
        if (riverBiome != null && !riverBiome.isBlank() && !BiomeRegistry.isKnown(riverBiome)) {
            errors.add("river_biome is not valid: " + riverBiome);
        }
        if (!Float.isFinite(metresLo) || !Float.isFinite(metresHi) || metresLo >= metresHi) {
            errors.add("metres_range must be [lo, hi] with lo < hi");
        }
        for (int i = 0; i < spatialFeatures.size(); i++) {
            String type = spatialFeatures.get(i).type;
            if (!isFeatureType(type)) {
                errors.add("spatial_features[" + i + "].type is not valid: " + type);
            }
        }
        return errors;
    }

    private static void validateLayer(NoiseLayer layer, String path, List<String> errors) {
        if (!isNoiseMode(layer.mode)) {
            errors.add(path + ".mode is not valid: " + layer.mode);
        }
        if (!Float.isFinite(layer.frequency) || layer.frequency <= 0f) {
            errors.add(path + ".frequency must be > 0");
        }
        if (layer.octaves < 1 || layer.octaves > 10) {
            errors.add(path + ".octaves must be between 1 and 10");
        }
        if (!Float.isFinite(layer.persistence) || layer.persistence < 0f || layer.persistence > 1f) {
            errors.add(path + ".persistence must be in 0..1");
        }
        if (!Float.isFinite(layer.lacunarity) || layer.lacunarity <= 0f) {
            errors.add(path + ".lacunarity must be > 0");
        }
    }

    private static boolean isNoiseMode(String mode) {
        return "fbm".equals(mode)
                || "ridged".equals(mode)
                || "billow".equals(mode)
                || "cellular".equals(mode)
                || "stepped".equals(mode)
                || "domain_warp".equals(mode)
                || "domain_warp_ridged".equals(mode);
    }

    private static boolean isFeatureType(String type) {
        return "river".equals(type)
                || "sea_threshold".equals(type)
                || "stretch".equals(type)
                || "coastal_smooth".equals(type)
                || "mesa_step".equals(type)
                || "dune_ridges".equals(type);
    }

    private static NoiseLayer parseLayer(JsonObject o) {
        NoiseLayer l = new NoiseLayer();
        l.mode         = optString(o, "mode", "fbm");
        l.frequency    = optFloat (o, "frequency", 4.0f);
        l.octaves      = optInt   (o, "octaves", 7);
        l.persistence  = optFloat (o, "persistence", 0.5f);
        l.lacunarity   = optFloat (o, "lacunarity", 2.0f);
        l.sharpness    = optFloat (o, "sharpness", 2.5f);
        l.warpStrength = optFloat (o, "warp_strength", 0.45f);
        l.nSteps       = optInt   (o, "n_steps", 7);
        l.jitter       = optFloat (o, "jitter", 0.022f);
        l.cellMode     = optString(o, "cell_mode", "f2_minus_f1");
        l.cellJitter   = optFloat (o, "cell_jitter", 1.0f);
        l.weight       = optFloat (o, "weight", 1.0f);
        l.blend        = optString(o, "blend", "average");
        if (o.has("mask") && o.get("mask").isJsonObject()) {
            JsonObject m = o.getAsJsonObject("mask");
            l.mask = new LayerMask();
            l.mask.frequency   = optFloat (m, "frequency", 1.5f);
            l.mask.octaves     = optInt   (m, "octaves", 4);
            l.mask.persistence = optFloat (m, "persistence", 0.5f);
            l.mask.threshold   = optFloat (m, "threshold", 0.5f);
            l.mask.softness    = optFloat (m, "softness", 0.05f);
            l.mask.invert      = optBool  (m, "invert", false);
        }
        return l;
    }

    private static SpatialFeature parseFeature(JsonObject o) {
        SpatialFeature f = new SpatialFeature();
        f.type = optString(o, "type", null);
        if (f.type == null) return null;
        f.bankDepth    = optFloat(o, "bank_depth",   0.12f);
        f.smoothSigma  = optFloat(o, "smooth_sigma", 12.0f);
        f.accumFrac    = optFloat(o, "accum_frac",   0.004f);
        f.riverFreq    = optFloat(o, "river_freq",    0.55f);
        f.riverWarp    = optFloat(o, "river_warp",    0.5f);
        f.riverOctaves = optInt  (o, "river_octaves", 4);
        f.riverWidth   = optFloat(o, "river_width",   0.0024f);
        f.bankWidth    = optFloat(o, "bank_width",    0.018f);
        f.tributaryStrength = optFloat(o, "tributary_strength", 0f);
        f.lowlandBand  = optFloat(o, "lowland_band",  0.22f);
        if (o.has("river_elev") && o.get("river_elev").isJsonPrimitive()) {
            f.riverElev = o.get("river_elev").getAsFloat();
        }
        f.offset       = optFloat(o, "offset",       -0.46f);
        f.nSteps       = optInt  (o, "n_steps",      6);
        f.jitter       = optFloat(o, "jitter",       0.02f);
        if (o.has("sea_level") && o.get("sea_level").isJsonPrimitive()) {
            f.seaLevel = o.get("sea_level").getAsFloat();
        }
        f.width        = optFloat(o, "width",        0.08f);
        f.strength     = optFloat(o, "strength",     0.6f);
        f.directionDeg = optFloat(o, "direction_deg",270f);
        f.spacing      = optFloat(o, "spacing",      0.04f);
        f.amplitude    = optFloat(o, "amplitude",    0.04f);
        if (o.has("to") && o.get("to").isJsonArray()) {
            JsonArray a = o.getAsJsonArray("to");
            if (a.size() >= 2) {
                f.to = new float[]{ a.get(0).getAsFloat(), a.get(1).getAsFloat() };
            }
        }
        return f;
    }

    private static String optString(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }
    private static float optFloat(JsonObject o, String k, float def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsFloat() : def;
    }
    private static int optInt(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : def;
    }
    private static boolean optBool(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    public String toPrettyJson() { return PRETTY.toJson(this); }
}
