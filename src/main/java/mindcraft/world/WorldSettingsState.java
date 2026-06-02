package mindcraft.world;

import mindcraft.procgen.SpecRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

/** Persisted per-world settings: the user's prompt and the active WorldSpec. */
public final class WorldSettingsState extends PersistentState {

    private static final Codec<WorldSettingsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("explicit", false).forGetter(WorldSettingsState::isInitialised),
            Codec.STRING.optionalFieldOf("prompt", "").forGetter(WorldSettingsState::getPrompt),
            Codec.STRING.optionalFieldOf("spec_name", SpecRegistry.defaultSpecName()).forGetter(WorldSettingsState::getSpecName),
            Codec.STRING.optionalFieldOf("spec_json", "").forGetter(WorldSettingsState::getSpecJson)
    ).apply(instance, WorldSettingsState::new));

    private boolean initialised;
    private String prompt;
    private String specName;
    private String specJson;

    private WorldSettingsState(boolean initialised, String prompt, String specName, String specJson) {
        this.initialised = initialised;
        this.prompt = prompt == null ? "" : prompt;
        this.specName = (specName == null || specName.isBlank()) ? SpecRegistry.defaultSpecName() : specName;
        this.specJson = specJson == null ? "" : specJson;
    }

    public static WorldSettingsState createDefault() {
        return new WorldSettingsState(false, "", SpecRegistry.defaultSpecName(), "");
    }

    public static final PersistentStateType<WorldSettingsState> TYPE =
            new PersistentStateType<>("mindcraft_world_settings", WorldSettingsState::createDefault, CODEC, null);

    public boolean isInitialised() { return initialised; }
    public String getPrompt()      { return prompt; }
    public String getSpecName()    { return specName; }
    public String getSpecJson()    { return specJson; }

    public void apply(String prompt, String specName) {
        apply(prompt, specName, "");
    }

    public void apply(String prompt, String specName, String specJson) {
        this.initialised = true;
        this.prompt   = prompt == null ? "" : prompt;
        this.specName = (specName == null || specName.isBlank()) ? SpecRegistry.defaultSpecName() : specName;
        this.specJson = specJson == null ? "" : specJson;
        markDirty();
    }
}
