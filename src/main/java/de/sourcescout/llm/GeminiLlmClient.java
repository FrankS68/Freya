package de.sourcescout.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.sourcescout.config.ScoutProperties;
import de.sourcescout.util.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeminiLlmClient implements LlmClient {

    public static final String ID = "gemini";
    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScoutProperties properties;
    private final RestClient restClient;

    public GeminiLlmClient(ScoutProperties properties) {
        this.properties = properties;
        this.restClient = HttpClientFactory.restClient(Duration.ofSeconds(15), Duration.ofSeconds(60));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        ScoutProperties.Gemini config = properties.getGemini();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", userPrompt)))));
        body.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "temperature", 0.2));

        String response = restClient.post()
                .uri(config.getBaseUrl() + "/v1beta/models/" + config.getModel() + ":generateContent")
                .header("x-goog-api-key", config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        if (response == null || response.isBlank()) {
            return null;
        }
        return extractText(response);
    }

    private String extractText(String response) {
        try {
            StringBuilder text = new StringBuilder();
            JsonNode parts = MAPPER.readTree(response)
                    .path("candidates").path(0).path("content").path("parts");
            for (JsonNode part : parts) {
                JsonNode value = part.get("text");
                if (value != null && value.isTextual()) {
                    text.append(value.asText());
                }
            }
            return text.isEmpty() ? null : text.toString();
        } catch (Exception ex) {
            log.warn("Gemini-Antwort konnte nicht gelesen werden: {}", ex.getMessage());
            return null;
        }
    }
}
