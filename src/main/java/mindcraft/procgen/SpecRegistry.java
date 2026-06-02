package mindcraft.procgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads bundled WorldSpec JSON files from the mod's resources.
 *
 * Specs are stored at /data/mindcraft/specs/&lt;name&gt;.json
 * and discoverable by their bare name (without `.json` extension).
 */
public final class SpecRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SpecRegistry.class);
    private static final String DEFAULT_SPEC_NAME = "snowy_alps";
    private static final String RESOURCE_BASE = "/data/mindcraft/specs/";

    private static final Map<String, WorldSpec> CACHE = new HashMap<>();

    private SpecRegistry() {}

    /**
     * Demo keyword → spec mapping. Until the LLM prompt→spec flow is wired up,
     * typing one of these keywords in the terrain prompt box loads the matching
     * bundled spec. Insertion order = priority for substring matches.
     */
    private static final Map<String, String> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put("alps",        "snowy_alps");
        KEYWORDS.put("mountains",   "snowy_alps");
        KEYWORDS.put("mesa",        "mesa_canyons");
        KEYWORDS.put("canyon",      "mesa_canyons");
        KEYWORDS.put("tropical",    "tropical_archipelago");
        KEYWORDS.put("archipelago", "tropical_archipelago");
        KEYWORDS.put("volcanic",    "volcanic_jungle_islands");
        KEYWORDS.put("jungle",      "volcanic_jungle_islands");
        KEYWORDS.put("mangrove",    "mangrove_delta");
        KEYWORDS.put("delta",       "mangrove_delta");
        KEYWORDS.put("savanna",     "savanna_plateau");
        KEYWORDS.put("plains",      "rolling_plains_river");
        KEYWORDS.put("cherry",      "cherry_meadow_hills");
        KEYWORDS.put("taiga",       "frozen_boreal_taiga");
        KEYWORDS.put("boreal",      "frozen_boreal_taiga");
        KEYWORDS.put("antarctic",   "antarctic_tundra");
        KEYWORDS.put("tundra",      "antarctic_tundra");
        KEYWORDS.put("haunted",     "pale_haunted_forest");
        KEYWORDS.put("pale",        "pale_haunted_forest");
    }

    /** Returns the default spec to use when no explicit selection has been made. */
    public static String defaultSpecName() {
        return DEFAULT_SPEC_NAME;
    }

    /** Comma-separated keyword list, for help text in the UI. */
    public static String keywordHint() {
        return String.join(", ", KEYWORDS.keySet());
    }

    /**
     * Resolves a free-text terrain prompt to a bundled spec name.
     * Matching order: exact spec filename → first keyword that appears as a
     * whitespace-delimited token → first keyword contained as a substring →
     * the default spec.
     */
    public static String resolveSpecName(String prompt) {
        if (prompt == null) return DEFAULT_SPEC_NAME;
        String p = prompt.trim().toLowerCase();
        if (p.isEmpty()) return DEFAULT_SPEC_NAME;
        if (loadResource(p) != null) return p;            // exact spec name typed
        for (String token : p.split("\\s+")) {
            String hit = KEYWORDS.get(token);
            if (hit != null) return hit;
        }
        for (Map.Entry<String, String> e : KEYWORDS.entrySet()) {
            if (p.contains(e.getKey())) return e.getValue();
        }
        return DEFAULT_SPEC_NAME;
    }

    /** Loads (and caches) a bundled spec by name, falling back to the default if missing. */
    public static synchronized WorldSpec loadOrDefault(String name) {
        if (name == null || name.isBlank()) name = DEFAULT_SPEC_NAME;
        WorldSpec cached = CACHE.get(name);
        if (cached != null) return cached;

        WorldSpec spec = loadResource(name);
        if (spec == null && !DEFAULT_SPEC_NAME.equals(name)) {
            LOG.warn("Spec '{}' not found, falling back to '{}'", name, DEFAULT_SPEC_NAME);
            spec = loadResource(DEFAULT_SPEC_NAME);
        }
        if (spec == null) {
            // Last-ditch fallback: a flat-plains default so worldgen never hard-fails.
            LOG.error("Default spec '{}' is missing from resources; using a flat fallback", DEFAULT_SPEC_NAME);
            spec = new WorldSpec();
            spec.name = "Fallback Plains";
            spec.elevationProfile = new WorldSpec.NoiseLayer();
            spec.elevationRangeLo = 0.45f;
            spec.elevationRangeHi = 0.55f;
            spec.biomeZones.add(new WorldSpec.BiomeZone(1.0f, "plains"));
        }
        CACHE.put(name, spec);
        return spec;
    }

    private static WorldSpec loadResource(String name) {
        String path = RESOURCE_BASE + name + ".json";
        try (InputStream in = SpecRegistry.class.getResourceAsStream(path)) {
            if (in == null) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                WorldSpec spec = WorldSpec.fromJson(reader);
                LOG.info("Loaded WorldSpec '{}' ({} biomes, {} features)",
                        spec.name, spec.biomeZones.size(), spec.spatialFeatures.size());
                return spec;
            }
        } catch (Exception e) {
            LOG.error("Failed to load spec '{}'", name, e);
            return null;
        }
    }
}
