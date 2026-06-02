package mindcraft.world;

/** World-creation settings chosen on the client before world init. */
public final class WorldSelection {
    public final String prompt;
    public final String specName;
    public final String specJson;

    public WorldSelection(String prompt, String specName, String specJson) {
        this.prompt = prompt;
        this.specName = specName;
        this.specJson = specJson;
    }
}
