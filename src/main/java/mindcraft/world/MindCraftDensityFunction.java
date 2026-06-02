package mindcraft.world;

import mindcraft.procgen.TerrainProvider;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * Density function that reports `surfaceY - y` so blocks at or below the
 * spec-derived surface are solid (positive density) and blocks above are air.
 *
 * The surface Y is sampled via {@link TerrainProvider}, which delegates to the
 * tile cache + procedural pipeline.
 */
public final class MindCraftDensityFunction implements DensityFunction {

    public static final MapCodec<MindCraftDensityFunction> CODEC =
            MapCodec.unit(MindCraftDensityFunction::new);

    public static final CodecHolder<MindCraftDensityFunction> CODEC_HOLDER = CodecHolder.of(CODEC);

    @Override
    public double sample(NoisePos pos) {
        int x = pos.blockX();
        int y = pos.blockY();
        int z = pos.blockZ();
        int surfaceY = TerrainProvider.getInstance().surfaceY(x, z);
        return (double) (surfaceY - y);
    }

    @Override
    public void fill(double[] densities, EachApplier applier) {
        applier.fill(densities, this);
    }

    @Override
    public DensityFunction apply(DensityFunctionVisitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public double minValue() {
        return TerrainProvider.MIN_Y - TerrainProvider.MAX_Y;
    }

    @Override
    public double maxValue() {
        return TerrainProvider.MAX_Y - TerrainProvider.MIN_Y;
    }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() {
        return CODEC_HOLDER;
    }
}
