package mindcraft.procgen;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * String-name → Minecraft 1.21 Overworld biome key map.
 *
 * Source-of-truth list mirrors `__valid_biomes` in terrain_specs/_template.json.
 * Anything not listed here will fall back to {@link BiomeKeys#PLAINS} with a warning.
 */
public final class BiomeRegistry {

    private static final Map<String, RegistryKey<Biome>> NAME_TO_KEY = new HashMap<>();

    static {
        register("badlands",                    BiomeKeys.BADLANDS);
        register("bamboo_jungle",               BiomeKeys.BAMBOO_JUNGLE);
        register("beach",                       BiomeKeys.BEACH);
        register("birch_forest",                BiomeKeys.BIRCH_FOREST);
        register("cherry_grove",                BiomeKeys.CHERRY_GROVE);
        register("cold_ocean",                  BiomeKeys.COLD_OCEAN);
        register("dark_forest",                 BiomeKeys.DARK_FOREST);
        register("deep_cold_ocean",             BiomeKeys.DEEP_COLD_OCEAN);
        register("deep_frozen_ocean",           BiomeKeys.DEEP_FROZEN_OCEAN);
        register("deep_lukewarm_ocean",         BiomeKeys.DEEP_LUKEWARM_OCEAN);
        register("deep_ocean",                  BiomeKeys.DEEP_OCEAN);
        register("desert",                      BiomeKeys.DESERT);
        register("eroded_badlands",             BiomeKeys.ERODED_BADLANDS);
        register("flower_forest",               BiomeKeys.FLOWER_FOREST);
        register("forest",                      BiomeKeys.FOREST);
        register("frozen_ocean",                BiomeKeys.FROZEN_OCEAN);
        register("frozen_peaks",                BiomeKeys.FROZEN_PEAKS);
        register("frozen_river",                BiomeKeys.FROZEN_RIVER);
        register("grove",                       BiomeKeys.GROVE);
        register("ice_spikes",                  BiomeKeys.ICE_SPIKES);
        register("jagged_peaks",                BiomeKeys.JAGGED_PEAKS);
        register("jungle",                      BiomeKeys.JUNGLE);
        register("lukewarm_ocean",              BiomeKeys.LUKEWARM_OCEAN);
        register("mangrove_swamp",              BiomeKeys.MANGROVE_SWAMP);
        register("meadow",                      BiomeKeys.MEADOW);
        register("mushroom_fields",             BiomeKeys.MUSHROOM_FIELDS);
        register("ocean",                       BiomeKeys.OCEAN);
        register("old_growth_birch_forest",     BiomeKeys.OLD_GROWTH_BIRCH_FOREST);
        register("old_growth_pine_taiga",       BiomeKeys.OLD_GROWTH_PINE_TAIGA);
        register("old_growth_spruce_taiga",     BiomeKeys.OLD_GROWTH_SPRUCE_TAIGA);
        register("pale_garden",                 BiomeKeys.PALE_GARDEN);
        register("plains",                      BiomeKeys.PLAINS);
        register("river",                       BiomeKeys.RIVER);
        register("savanna",                     BiomeKeys.SAVANNA);
        register("savanna_plateau",             BiomeKeys.SAVANNA_PLATEAU);
        register("snowy_beach",                 BiomeKeys.SNOWY_BEACH);
        register("snowy_plains",                BiomeKeys.SNOWY_PLAINS);
        register("snowy_slopes",                BiomeKeys.SNOWY_SLOPES);
        register("snowy_taiga",                 BiomeKeys.SNOWY_TAIGA);
        register("sparse_jungle",               BiomeKeys.SPARSE_JUNGLE);
        register("stony_peaks",                 BiomeKeys.STONY_PEAKS);
        register("stony_shore",                 BiomeKeys.STONY_SHORE);
        register("sunflower_plains",            BiomeKeys.SUNFLOWER_PLAINS);
        register("swamp",                       BiomeKeys.SWAMP);
        register("taiga",                       BiomeKeys.TAIGA);
        register("warm_ocean",                  BiomeKeys.WARM_OCEAN);
        register("windswept_forest",            BiomeKeys.WINDSWEPT_FOREST);
        register("windswept_gravelly_hills",    BiomeKeys.WINDSWEPT_GRAVELLY_HILLS);
        register("windswept_hills",             BiomeKeys.WINDSWEPT_HILLS);
        register("windswept_savanna",           BiomeKeys.WINDSWEPT_SAVANNA);
        register("wooded_badlands",             BiomeKeys.WOODED_BADLANDS);
    }

    private BiomeRegistry() {}

    private static void register(String name, RegistryKey<Biome> key) {
        NAME_TO_KEY.put(name, key);
    }

    /** Returns the registry key for the given biome name, or {@link BiomeKeys#PLAINS} if unknown. */
    public static RegistryKey<Biome> resolve(String name) {
        RegistryKey<Biome> k = NAME_TO_KEY.get(name);
        return k != null ? k : BiomeKeys.PLAINS;
    }

    /** Whether the given name is a recognised Overworld biome. */
    public static boolean isKnown(String name) {
        return NAME_TO_KEY.containsKey(name);
    }

    /** All biome keys exposed to BiomeSource consumers. */
    public static Collection<RegistryKey<Biome>> allBiomeKeys() {
        return Collections.unmodifiableCollection(NAME_TO_KEY.values());
    }
}
