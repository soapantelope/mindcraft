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

    // Shared hosted proxy that holds a rate-limited API key server-side, so people can try
    // the mod without their own Anthropic key. 
    private static final String DEFAULT_PROXY_URL = "https://mindcraft-proxy.soapantelope.workers.dev";

    private static final String DEFAULT_MODEL = "claude-opus-4-8";
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
                boolean proxyConfigured = config.proxyUrl != null
                        && !config.proxyUrl.isBlank()
                        && !config.proxyUrl.contains("YOUR-WORKER");

                String targetUrl;
                String keyForCall;
                if (apiKey != null) {
                    // User supplied their own key -> call Anthropic directly (bypasses the shared proxy).
                    targetUrl = API_URL;
                    keyForCall = apiKey;
                    LOG.info("Using Anthropic API directly with a user-provided key.");
                } else if (proxyConfigured) {
                    // No personal key -> use the shared, rate-limited proxy (no key on the client).
                    targetUrl = config.proxyUrl;
                    keyForCall = null;
                    LOG.info("No personal API key set; using shared MindCraft proxy at {}", targetUrl);
                } else {
                    throw new IllegalStateException("No Anthropic API key and no proxy configured. "
                            + "Either set ANTHROPIC_API_KEY (or add api_key=sk-ant-... to " + config.path
                            + "), or set proxy_url to a deployed MindCraft proxy.");
                }

                LOG.info("Calling Claude model={} for prompt: {}", config.model, userPrompt);
                String responseText = callClaude(targetUrl, keyForCall, config.model, userPrompt);
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

    /**
     * Sends the Messages request to {@code url}. When {@code apiKey} is non-null the request goes
     * directly to Anthropic with auth headers; when it is null the request goes to the shared proxy,
     * which injects the key and version headers server-side.
     */
    private static String callClaude(String url, String apiKey, String model, String userPrompt)
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

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (apiKey != null) {
            builder.header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01");
        }
        HttpRequest request = builder.build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Try to extract a human-friendly message from the JSON body (proxy or Anthropic errors).
            String friendlyMessage = parseFriendlyError(response.body(), response.statusCode());
            LOG.error("API error HTTP {}: {}", response.statusCode(), response.body());
            throw new IOException(friendlyMessage);
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

    private static final String README_HINT =
            "To use your own API key with no limits, see the mod README.";

    private static String parseFriendlyError(String body, int status) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject err = root.has("error") ? root.getAsJsonObject("error") : null;
            if (err != null && err.has("message")) {
                String msg = err.get("message").getAsString();
                String type = err.has("type") ? err.get("type").getAsString() : "";
                // Proxy rate-limit errors — append the README hint.
                if (type.startsWith("rate_limit") || status == 429 || status == 503) {
                    return msg + "\n" + README_HINT;
                }
                return msg;
            }
        } catch (Exception ignored) {}
        return "API error (HTTP " + status + "). " + README_HINT;
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
                            + "#\n"
                            + "# Leave api_key BLANK to use the shared, rate-limited MindCraft proxy\n"
                            + "# (no key needed). To use your own Anthropic key instead, paste it below\n"
                            + "# or set ANTHROPIC_API_KEY / MINDCRAFT_ANTHROPIC_API_KEY in your environment.\n"
                            + "api_key=\n"
                            + "# Available: claude-sonnet-4-6, claude-opus-4-7, claude-haiku-4-5-20251001\n"
                            + "model=" + DEFAULT_MODEL + "\n"
                            + "# Advanced: override the shared proxy URL. Leave blank to use the built-in default.\n"
                            + "proxy_url=\n",
                    StandardCharsets.UTF_8);
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        String proxyUrl = properties.getProperty("proxy_url", "").trim();
        if (proxyUrl.isBlank()) {
            proxyUrl = DEFAULT_PROXY_URL;
        }
        return new Config(
                path,
                properties.getProperty("api_key", "").trim(),
                properties.getProperty("model", DEFAULT_MODEL).trim(),
                proxyUrl
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private record Config(Path path, String apiKey, String model, String proxyUrl) {}
}
