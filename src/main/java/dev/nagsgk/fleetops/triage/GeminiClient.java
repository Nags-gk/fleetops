package dev.nagsgk.fleetops.triage;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nagsgk.fleetops.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Google Gemini via REST (free tier). Short timeout: triage must never hang the API. */
public final class GeminiClient implements LlmClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String apiKey;
    private final String model;

    public GeminiClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public Optional<String> complete(String prompt) {
        try {
            var body = Json.write(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of("temperature", 0.2, "maxOutputTokens", 400)));
            var req = HttpRequest.newBuilder(URI.create(
                            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return Optional.empty();
            JsonNode n = Json.M.readTree(res.body()).at("/candidates/0/content/parts/0/text");
            return n.isMissingNode() ? Optional.empty() : Optional.of(n.asText().strip());
        } catch (Exception e) {
            return Optional.empty();
        } 
    }

    @Override
    public String name() {
        return "gemini:" + model;
    }
}
