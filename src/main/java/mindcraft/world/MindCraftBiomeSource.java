package mindcraft.world;

import mindcraft.procgen.BiomeRegistry;
import mindcraft.procgen.TerrainProvider;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.WorldView;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * BiomeSource that delegates per-(x,z) lookups to {@link TerrainProvider}.
 *
 * Returns Minecraft 1.21 Overworld biomes resolved from the active WorldSpec.
 * Biome lookups are cached per name within this BiomeSource instance to avoid
 * map churn on every quart sample.
 */
public final class MindCraftBiomeSource extends BiomeSource {

    public static final MapCodec<MindCraftBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.getEntryLookupCodec(RegistryKeys.BIOME)
            ).apply(instance, instance.stable(MindCraftBiomeSource::new)));

    private final RegistryEntryLookup<Biome> biomeLookup;
    private final Map<String, RegistryEntry<Biome>> entryCache = new HashMap<>();

    public MindCraftBiomeSource(RegistryEntryLookup<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    public Stream<RegistryEntry<Biome>> biomeStream() {
        // Eagerly seed every known biome so vanilla logic that walks biomeStream finds them.
        // We don't actually know which subset will be used for the world's spec at codec time,
        // so we expose all valid Overworld biomes registered in BiomeRegistry.
        return BiomeRegistry.allBiomeKeys().stream()
                .map(this::lookupEntry);
    }

    @Override
    public RegistryEntry<Biome> getBiome(int x, int y, int z, MultiNoiseUtil.MultiNoiseSampler noise) {
        // x, y, z are quart coords — convert to block coords.
        int blockX = BiomeCoords.toBlock(x);
        int blockZ = BiomeCoords.toBlock(z);
        String name = TerrainProvider.getInstance().sampleBiomeName(blockX, blockZ);
        return lookupName(name);
    }

    private RegistryEntry<Biome> lookupName(String name) {
        RegistryEntry<Biome> cached = entryCache.get(name);
        if (cached != null) return cached;
        RegistryKey<Biome> key = BiomeRegistry.resolve(name);
        RegistryEntry<Biome> entry = biomeLookup.getOrThrow(key);
        entryCache.put(name, entry);
        return entry;
    }

    private RegistryEntry<Biome> lookupEntry(RegistryKey<Biome> key) {
        return biomeLookup.getOrThrow(key);
    }

    @Override
    public Pair<BlockPos, RegistryEntry<Biome>> locateBiome(BlockPos origin, int radius,
                                                             int horizontalBlockCheckInterval, int verticalBlockCheckInterval,
                                                             Predicate<RegistryEntry<Biome>> predicate,
                                                             MultiNoiseUtil.MultiNoiseSampler noiseSampler, WorldView world) {
        return null;
    }

    @Override
    public Pair<BlockPos, RegistryEntry<Biome>> locateBiome(int x, int y, int z, int radius, int blockCheckInterval,
                                                             Predicate<RegistryEntry<Biome>> predicate, Random random,
                                                             boolean bl, MultiNoiseUtil.MultiNoiseSampler noiseSampler) {
        return null;
    }
}
