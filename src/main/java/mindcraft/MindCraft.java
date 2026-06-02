package mindcraft;

import mindcraft.world.MindCraftBiomeSource;
import mindcraft.world.MindCraftDensityFunction;
import mindcraft.world.WorldManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MindCraft implements ModInitializer {

    public static final String MOD_ID = "mindcraft";
    private static final Logger LOG = LoggerFactory.getLogger(MindCraft.class);

    @Override
    public void onInitialize() {
        LOG.info("Initialising MindCraft");
        Registry.register(Registries.BIOME_SOURCE, Identifier.of(MOD_ID, "world"),
                MindCraftBiomeSource.CODEC);
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, Identifier.of(MOD_ID, "world"),
                MindCraftDensityFunction.CODEC);

        ServerWorldEvents.LOAD.register((server, world) -> {
            if (world.getRegistryKey() == World.OVERWORLD) {
                WorldManager.initForWorld(world);
            }
        });
    }
}
