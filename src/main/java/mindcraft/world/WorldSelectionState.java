package mindcraft.world;

import mindcraft.procgen.SpecRegistry;

import java.util.concurrent.atomic.AtomicReference;

/** In-memory handoff from the world-creation screen to the server-side initialiser. */
public final class WorldSelectionState {

    private static final AtomicReference<WorldSelection> PENDING = new AtomicReference<>();

    private WorldSelectionState() {}

    public static void setPending(String prompt, String specName) {
        setPending(prompt, specName, "");
    }

    public static void setPending(String prompt, String specName, String specJson) {
        PENDING.set(new WorldSelection(
                prompt == null ? "" : prompt,
                (specName == null || specName.isBlank()) ? SpecRegistry.defaultSpecName() : specName,
                specJson == null ? "" : specJson
        ));
    }

    public static WorldSelection consumePending() {
        return PENDING.getAndSet(null);
    }

    public static WorldSelection peekPendingOrDefault() {
        WorldSelection cur = PENDING.get();
        return cur != null ? cur : new WorldSelection("", SpecRegistry.defaultSpecName(), "");
    }
}
