package mindcraft.world;

import mindcraft.procgen.SpecRegistry;
import mindcraft.procgen.TerrainProvider;
import mindcraft.procgen.WorldSpec;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Initialises the procedural terrain pipeline when an Overworld loads. */
public final class WorldManager {

    private static final Logger LOG = LoggerFactory.getLogger(WorldManager.class);

    private WorldManager() {}

    public static void initForWorld(ServerWorld world) {
        WorldSettingsState state = world.getPersistentStateManager()
                .getOrCreate(WorldSettingsState.TYPE);

        if (!state.isInitialised()) {
            WorldSelection pending = WorldSelectionState.consumePending();
            if (pending != null) {
                state.apply(pending.prompt, pending.specName, pending.specJson);
            } else {
                state.apply("", SpecRegistry.defaultSpecName());
            }
        }

        TerrainProvider.init(world.getSeed(), loadSpec(state));
    }

    private static WorldSpec loadSpec(WorldSettingsState state) {
        String json = state.getSpecJson();
        if (json != null && !json.isBlank()) {
            try {
                WorldSpec spec = WorldSpec.fromJson(json);
                LOG.info("Loaded generated WorldSpec '{}'", spec.name);
                return spec;
            } catch (Exception e) {
                LOG.error("Generated WorldSpec invalid, falling back to '{}'", state.getSpecName(), e);
            }
        }
        return SpecRegistry.loadOrDefault(state.getSpecName());
    }
}
