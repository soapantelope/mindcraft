package mindcraft.client;

import mindcraft.procgen.SpecRegistry;
import mindcraft.world.WorldSelectionState;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.MultilineTextWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.dimension.DimensionType;

import java.util.HashMap;
import java.util.Map;

/** MindCraft world-creation settings screen. */
public final class MindCraftSettingsScreen extends Screen {

    private static final String MOD_ID = "mindcraft";
    private static final int FIELD_WIDTH = 240;
    private static final int FIELD_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 90;
    private static final int BUTTON_HEIGHT = 20;

    private static final Text TITLE = Text.literal("MindCraft Settings");
    private static final Text PROMPT_LABEL = Text.literal("Terrain Prompt");
    private static final Text PROMPT_HELP = Text.literal("Describe the world you would like to create. Example: \"Snowy alpine mountains with frozen rivers winding through the valleys.\"");

    private final Screen parentScreen;
    private TextFieldWidget promptField;
    private ButtonWidget doneButton;
    private TextWidget validationWidget;
    private boolean generating;

    public MindCraftSettingsScreen(Screen parentScreen) {
        super(TITLE);
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = Math.max(40, this.height / 2 - 80);

        addCenteredText(TITLE.copy().formatted(Formatting.BOLD), centerX, top, 0xFFFFFF);

        int yPromptHelp = top + 30;
        MultilineTextWidget helpWidget = new MultilineTextWidget(
                centerX - FIELD_WIDTH / 2, yPromptHelp,
                PROMPT_HELP.copy().styled(s -> s.withColor(0xAAAAAA)),
                this.textRenderer)
                .setMaxWidth(FIELD_WIDTH)
                .setCentered(true);
        this.addDrawableChild(helpWidget);

        int yPromptLabel = yPromptHelp + helpWidget.getHeight() + 6;
        int yPromptField = yPromptLabel + 12;
        addCenteredText(PROMPT_LABEL, centerX, yPromptLabel, 0xFFFFFF);
        promptField = new TextFieldWidget(this.textRenderer,
                centerX - FIELD_WIDTH / 2, yPromptField,
                FIELD_WIDTH, FIELD_HEIGHT, PROMPT_LABEL);
        promptField.setMaxLength(512);
        String savedPrompt = WorldSelectionState.peekPendingOrDefault().prompt;
        promptField.setText(savedPrompt == null ? "" : savedPrompt);
        promptField.setCursorToStart(false);
        this.addDrawableChild(promptField);
        this.setInitialFocus(promptField);

        // Buttons + validation
        int yButtons = yPromptField + 36;
        doneButton = ButtonWidget.builder(Text.translatable("gui.done"), b -> onDone())
                .dimensions(centerX - BUTTON_WIDTH - 5, yButtons, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addDrawableChild(doneButton);
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> close())
                .dimensions(centerX + 5, yButtons, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        validationWidget = new TextWidget(0, yButtons + 26, this.width, 9, Text.empty(), this.textRenderer);
        this.addDrawableChild(validationWidget);
    }

    private void addCenteredText(Text text, int centerX, int y, int color) {
        int w = this.textRenderer.getWidth(text);
        MutableText colored = text.copy().styled(s -> s.withColor(color));
        TextWidget widget = new TextWidget(centerX - w / 2, y, w, 9, colored, this.textRenderer);
        this.addDrawableChild(widget);
    }

    @Override
    public void close() {
        if (generating) return;
        if (this.client != null) this.client.setScreen(parentScreen);
    }

    private void onDone() {
        if (generating) return;

        String prompt = promptField.getText();
        if (prompt == null || prompt.isBlank()) {
            applyDimensionType();
            WorldSelectionState.setPending(prompt == null ? "" : prompt, SpecRegistry.defaultSpecName());
            close();
            return;
        }

        setGenerating(true);
        validationWidget.setMessage(Text.literal("Updating world settings... please wait a couple seconds").formatted(Formatting.GRAY));
        ClaudeWorldSpecClient.generateSpecJson(prompt).whenComplete((specJson, throwable) -> {
            if (this.client == null) return;
            this.client.execute(() -> {
                if (throwable != null) {
                    setGenerating(false);
                    validationWidget.setMessage(Text.literal(rootMessage(throwable)).formatted(Formatting.RED));
                    return;
                }
                applyDimensionType();
                WorldSelectionState.setPending(prompt, "generated_prompt_world", specJson);
                setGenerating(false);
                close();
            });
        });
    }

    private void setGenerating(boolean generating) {
        this.generating = generating;
        if (promptField != null) promptField.setEditable(!generating);
        if (doneButton != null) {
            doneButton.active = !generating;
            doneButton.setMessage(generating ? Text.literal("Generating...") : Text.translatable("gui.done"));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cur = throwable;
        while (cur.getCause() != null) cur = cur.getCause();
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }

    /** Applies the MindCraft dimension type to the overworld. */
    private void applyDimensionType() {
        if (!(parentScreen instanceof CreateWorldScreen createWorldScreen)) return;
        createWorldScreen.getWorldCreator().applyModifier((registryManager, dims) -> {
            Registry<DimensionType> reg = registryManager.getOrThrow(RegistryKeys.DIMENSION_TYPE);
            DimensionOptions overworld = dims.getOrEmpty(DimensionOptions.OVERWORLD).orElse(null);
            if (overworld == null) return dims;
            Identifier id = Identifier.of(MOD_ID, "world");
            RegistryEntry.Reference<DimensionType> entry = reg.getEntry(id).orElse(null);
            if (entry == null) return dims;
            DimensionOptions updated = new DimensionOptions(entry, overworld.chunkGenerator());
            Map<net.minecraft.registry.RegistryKey<DimensionOptions>, DimensionOptions> next = new HashMap<>(dims.dimensions());
            next.put(DimensionOptions.OVERWORLD, updated);
            return new DimensionOptionsRegistryHolder(next);
        });
    }
}
