package de.sourcescout.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.sourcescout.config.ScoutProperties;
import de.sourcescout.util.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MistralLlmClient implements LlmClient {

    public static final String ID = "mistral";
    private static final Logger log = LoggerFactory.getLogger(MistralLlmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScoutProperties properties;
    private final RestClient restClient;

    public MistralLlmClient(ScoutProperties properties) {
        this.properties = properties;
        this.restClient = HttpClientFactory.restClient(Duration.ofSeconds(15), Duration.ofSeconds(60));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        ScoutProperties.Mistral config = properties.getMistral();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return null;
        }
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("response_format", Map.of("type", "json_object"));

        String response = restClient.post()
                .uri(config.getBaseUrl() + "/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        if (response == null || response.isBlank()) {
            return null;
        }
        return extractContent(response);
    }

    private String extractContent(String response) {
        try {
            JsonNode content = MAPPER.readTree(response).path("choices").path(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText();
            }
            if (content.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode part : content) {
                    JsonNode value = part.get("text");
                    if (value != null && value.isTextual()) {
                        text.append(value.asText());
                    }
                }
                return text.isEmpty() ? null : text.toString();
            }
            return null;
        } catch (Exception ex) {
            log.warn("Mistral-Antwort konnte nicht gelesen werden: {}", ex.getMessage());
            return null;
        }
    }
}
