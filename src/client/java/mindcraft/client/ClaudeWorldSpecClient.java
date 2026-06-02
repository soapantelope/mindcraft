package mindcraft.client;

import mindcraft.procgen.WorldSpec;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/** Client-side Anthropic Messages API wrapper for prompt-to-WorldSpec generation. */
public final class ClaudeWorldSpecClient {

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeWorldSpecClient.class);

    public static final String CONFIG_FILE_NAME = "mindcraft-anthropic.properties";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-opus-4-7";
    private static final int MAX_TOKENS = 4096;
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private ClaudeWorldSpecClient() {}

    public static CompletableFuture<String> generateSpecJson(String userPrompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Config config = loadConfig();
                String envKey1 = System.getenv("MINDCRAFT_ANTHROPIC_API_KEY");
                String envKey2 = System.getenv("ANTHROPIC_API_KEY");
                LOG.info("API key sources: env MINDCRAFT_ANTHROPIC_API_KEY={}, env ANTHROPIC_API_KEY={}, config file={}",
                        envKey1 != null && !envKey1.isBlank() ? "set" : "not set",
                        envKey2 != null && !envKey2.isBlank() ? "set" : "not set",
                        !config.apiKey.isBlank() ? "set (" + config.apiKey.length() + " chars)" : "not set");
                String apiKey = firstNonBlank(envKey1, envKey2, config.apiKey);
                if (apiKey == null) {
                    throw new IllegalStateException("Missing Anthropic API key. Add it to "
                            + config.path + " as api_key=sk-ant-..., or set ANTHROPIC_API_KEY.");
                }

                LOG.info("Calling Claude model={} for prompt: {}", config.model, userPrompt);
                String responseText = callClaude(apiKey, config.model, userPrompt);
                String specJson = extractJsonObject(responseText);
                WorldSpec spec = WorldSpec.fromJson(specJson);
                List<String> errors = spec.validateForGeneration();
                if (!errors.isEmpty()) {
                    String msg = "Claude returned an invalid WorldSpec: " + String.join("; ", errors);
                    LOG.error("{}\nRaw JSON: {}", msg, specJson);
                    throw new IllegalStateException(msg);
                }
                LOG.info("WorldSpec '{}' generated successfully", spec.name);
                return specJson;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static String callClaude(String apiKey, String model, String userPrompt)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", firstNonBlank(model, DEFAULT_MODEL));
        body.addProperty("max_tokens", MAX_TOKENS);
        body.addProperty("system", "Return exactly one valid JSON object for the WorldSpec. "
                + "Do not include markdown fences, prose, comments, or keys beginning with __.");

        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", buildPrompt(userPrompt));
        messages.add(message);
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(90))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String err = "Anthropic API returned HTTP " + response.statusCode() + ": " + response.body();
            LOG.error(err);
            throw new IOException(err);
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray content = root.getAsJsonArray("content");
        if (content == null || content.isEmpty()) {
            throw new IOException("Anthropic API response did not include content.");
        }

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < content.size(); i++) {
            JsonObject block = content.get(i).getAsJsonObject();
            if (block.has("text")) {
                text.append(block.get("text").getAsString());
            }
        }
        if (text.isEmpty()) {
            throw new IOException("Anthropic API response did not include text content.");
        }
        return text.toString();
    }

    private static String buildPrompt(String userPrompt) throws IOException {
        return "You are going to design a detailed and beautiful procedural Minecraft world based on the user's prompt. "
                + "This is the user's prompt: \"" + userPrompt + "\". "
                + "Use the template's Minecraft Y-height guidance so terrain peaks do not flatten against the build limit. "
                + "Please use the following JSON template to design the world:\n\n"
                + loadTemplate();
    }

    private static String loadTemplate() throws IOException {
        String path = "/data/mindcraft/specs/_template.json";
        try (InputStream in = ClaudeWorldSpecClient.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing bundled WorldSpec template at " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String extractJsonObject(String raw) {
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Claude response did not contain a JSON object.");
        }
        return trimmed.substring(start, end + 1);
    }

    private static Config loadConfig() throws IOException {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path,
                    "# MindCraft Anthropic API settings\n"
                            + "# You can also set ANTHROPIC_API_KEY or MINDCRAFT_ANTHROPIC_API_KEY instead.\n"
                            + "api_key=\n"
                            + "# Available: claude-sonnet-4-6, claude-opus-4-7, claude-haiku-4-5-20251001\n"
                            + "model=" + DEFAULT_MODEL + "\n",
                    StandardCharsets.UTF_8);
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return new Config(
                path,
                properties.getProperty("api_key", "").trim(),
                properties.getProperty("model", DEFAULT_MODEL).trim()
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private record Config(Path path, String apiKey, String model) {}
}
