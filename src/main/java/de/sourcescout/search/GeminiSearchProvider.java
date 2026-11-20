package de.sourcescout.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.sourcescout.config.ScoutProperties;
import de.sourcescout.core.SearchHit;
import de.sourcescout.core.UrlNormalizer;
import de.sourcescout.util.HttpClientFactory;
import de.sourcescout.util.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeminiSearchProvider implements SearchProvider {

    public static final String ID = "gemini";
    private static final Logger log = LoggerFactory.getLogger(GeminiSearchProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScoutProperties properties;
    private final RestClient restClient;

    public GeminiSearchProvider(ScoutProperties properties) {
        this.properties = properties;
        this.restClient = HttpClientFactory.restClient(Duration.ofSeconds(15), Duration.ofSeconds(90));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isReady() {
        String apiKey = properties.getGemini().getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<SearchHit> search(String topic) {
        ScoutProperties.Gemini config = properties.getGemini();
        if (!isReady()) {
            log.warn("scout.gemini.api-key ist nicht gesetzt - Gemini-Suche wird uebersprungen.");
            return List.of();
        }
        ScoutProperties.Search search = properties.getSearch();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("parts", List.of(Map.of("text",
                buildPrompt(topic, search.getResultsPerProvider(), search.getLanguage()))))));
        body.put("tools", List.of(Map.of("google_search", Map.of())));

        String response;
        try {
            response = restClient.post()
                    .uri(config.getBaseUrl() + "/v1beta/models/" + config.getModel() + ":generateContent")
                    .header("x-goog-api-key", config.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            log.warn("Gemini-API-Fehler {}: {}", ex.getStatusCode(), excerpt(ex.getResponseBodyAsString(), 400));
            return List.of();
        }
        if (response == null || response.isBlank()) {
            log.warn("Gemini lieferte eine leere Antwort.");
            return List.of();
        }
        return parseResponse(response);
    }

    public static String buildPrompt(String topic, int maxResults, String language) {
        return "Du bist ein Recherche-Assistent. Suche im Web nach aktuellen, seriösen Online-Quellen zum Thema: \""
                + topic + "\". Finde maximal " + maxResults
                + " unterschiedliche Artikel und nenne die passenden URLs. Antworte AUSSCHLIESSLICH mit einem "
                + "JSON-Array aus maximal " + maxResults + " Objekten im Format "
                + "{\"title\": \"...\", \"url\": \"...\", \"snippet\": \"...\", \"published_at\": \"YYYY-MM-DD\"}. "
                + "Sprache der Quellen: " + language + ". Kein Text ausserhalb des JSON-Arrays.";
    }

    public static List<SearchHit> parseResponse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            LinkedHashMap<String, SearchHit> hits = new LinkedHashMap<>();
            for (SearchHit hit : hitsFromText(root)) {
                hits.putIfAbsent(UrlNormalizer.normalize(hit.url()), hit);
            }
            for (SearchHit hit : hitsFromGroundingChunks(root)) {
                hits.putIfAbsent(UrlNormalizer.normalize(hit.url()), hit);
            }
            List<SearchHit> result = new ArrayList<>(hits.values());
            if (result.isEmpty()) {
                log.warn("Gemini lieferte keine auswertbaren Treffer. Antwort-Auszug: {}", excerpt(body, 500));
            }
            return result;
        } catch (Exception ex) {
            log.warn("Gemini-Antwort konnte nicht ausgewertet werden ({}): {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    private static String excerpt(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String condensed = text.replaceAll("\\s+", " ").trim();
        return condensed.length() <= maxLength ? condensed : condensed.substring(0, maxLength) + "...";
    }

    private static List<SearchHit> hitsFromText(JsonNode root) throws JsonProcessingException {
        StringBuilder text = new StringBuilder();
        for (JsonNode candidate : root.path("candidates")) {
            for (JsonNode part : candidate.path("content").path("parts")) {
                JsonNode value = part.get("text");
                if (value != null && value.isTextual()) {
                    text.append(value.asText()).append('\n');
                }
            }
        }
        List<SearchHit> hits = new ArrayList<>();
        JsonNode array = JsonExtractor.extractJsonArray(text.toString()).orElseGet(MAPPER::createArrayNode);
        for (JsonNode item : array) {
            if (item.isArray()) {
                for (JsonNode entry : item) {
                    addIfValid(hits, entry);
                }
            } else {
                addIfValid(hits, item);
            }
        }
        return hits;
    }

    private static void addIfValid(List<SearchHit> hits, JsonNode node) {
        SearchHit hit = SearchHit.fromJson(node, ID);
        if (hit != null) {
            hits.add(hit);
        }
    }

    private static List<SearchHit> hitsFromGroundingChunks(JsonNode root) {
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode candidate : root.path("candidates")) {
            for (JsonNode chunk : candidate.path("groundingMetadata").path("groundingChunks")) {
                JsonNode web = chunk.get("web");
                if (web == null || !web.hasNonNull("uri")) {
                    continue;
                }
                String title = web.path("title").asText("");
                hits.add(new SearchHit(title, web.get("uri").asText(), "", "", ID));
            }
        }
        return hits;
    }
}
