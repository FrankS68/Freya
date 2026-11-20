package de.sourcescout.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.sourcescout.config.ScoutProperties;
import de.sourcescout.core.SearchHit;
import de.sourcescout.core.UrlNormalizer;
import de.sourcescout.util.HttpClientFactory;
import de.sourcescout.util.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
public class OpenAiSearchProvider implements SearchProvider {

    public static final String ID = "openai";
    private static final Logger log = LoggerFactory.getLogger(OpenAiSearchProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScoutProperties properties;
    private final RestClient restClient;

    public OpenAiSearchProvider(ScoutProperties properties) {
        this.properties = properties;
        this.restClient = HttpClientFactory.restClient(Duration.ofSeconds(15), Duration.ofSeconds(120));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isReady() {
        String apiKey = properties.getOpenai().getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<SearchHit> search(String topic) {
        ScoutProperties.OpenAi config = properties.getOpenai();
        if (!isReady()) {
            log.warn("scout.openai.api-key ist nicht gesetzt - OpenAI-Suche wird uebersprungen.");
            return List.of();
        }
        ScoutProperties.Search search = properties.getSearch();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("tools", List.of(Map.of("type", "web_search")));
        body.put("include", List.of("web_search_call.action.sources"));
        body.put("input", buildPrompt(topic, search.getResultsPerProvider(), search.getLanguage()));

        String response;
        try {
            response = restClient.post()
                    .uri(config.getBaseUrl() + "/v1/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            log.warn("OpenAI-API-Fehler {}: {}", ex.getStatusCode(), excerpt(ex.getResponseBodyAsString(), 400));
            return List.of();
        }
        if (response == null || response.isBlank()) {
            log.warn("OpenAI lieferte eine leere Antwort.");
            return List.of();
        }
        return parseResponse(response);
    }

    public static String buildPrompt(String topic, int maxResults, String language) {
        return "Suche im Web nach aktuellen, serioesen Online-Artikeln zum Thema: \"" + topic + "\". "
                + "Gib maximal " + maxResults + " gefundene Artikel zurueck. Antworte AUSSCHLIESSLICH mit einem "
                + "JSON-Array aus maximal " + maxResults + " Objekten im Format "
                + "{\"title\": \"...\", \"url\": \"...\", \"snippet\": \"...\", \"published_at\": \"YYYY-MM-DD\"}, "
                + "wobei jede URL ein echter, von der Websuche gefundener Artikel sein muss. "
                + "Sprache der Quellen: " + language + ". Wenn die Suche wirklich nichts findet, antworte mit [].";
    }

    public static List<SearchHit> parseResponse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            LinkedHashMap<String, SearchHit> hits = new LinkedHashMap<>();
            for (SearchHit hit : hitsFromText(root)) {
                hits.putIfAbsent(UrlNormalizer.normalize(hit.url()), hit);
            }
            addCitedSources(root, hits);
            List<SearchHit> result = new ArrayList<>(hits.values());
            if (result.isEmpty()) {
                log.warn("OpenAI lieferte keine auswertbaren Treffer. Antwort-Auszug: {}", excerpt(body, 500));
            }
            return result;
        } catch (Exception ex) {
            log.warn("OpenAI-Antwort konnte nicht ausgewertet werden ({}): {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    private static List<SearchHit> hitsFromText(JsonNode root) {
        StringBuilder text = new StringBuilder();
        collectText(root, text);
        return JsonExtractor.extractJsonArray(text.toString())
                .map(node -> {
                    List<SearchHit> hits = new ArrayList<>();
                    if (node.isArray()) {
                        for (JsonNode item : node) {
                            SearchHit hit = SearchHit.fromJson(item, ID);
                            if (hit != null) {
                                hits.add(hit);
                            }
                        }
                    }
                    return hits;
                })
                .orElse(List.of());
    }

    private static void addCitedSources(JsonNode node, LinkedHashMap<String, SearchHit> hits) {
        if (node.isObject()) {
            for (String field : List.of("annotations", "sources")) {
                JsonNode list = node.get(field);
                if (list != null && list.isArray()) {
                    for (JsonNode item : list) {
                        SearchHit hit = SearchHit.fromJson(item, ID);
                        if (hit != null) {
                            hits.putIfAbsent(UrlNormalizer.normalize(hit.url()), hit);
                        }
                    }
                }
            }
            node.fields().forEachRemaining(entry -> addCitedSources(entry.getValue(), hits));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                addCitedSources(item, hits);
            }
        }
    }

    private static void collectText(JsonNode node, StringBuilder builder) {
        if (node.isTextual()) {
            builder.append(node.asText()).append('\n');
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectText(entry.getValue(), builder));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectText(item, builder);
            }
        }
    }

    private static String excerpt(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String condensed = text.replaceAll("\\s+", " ").trim();
        return condensed.length() <= maxLength ? condensed : condensed.substring(0, maxLength) + "...";
    }
}
