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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MistralSearchProvider implements SearchProvider {

    public static final String ID = "mistral";
    private static final Logger log = LoggerFactory.getLogger(MistralSearchProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScoutProperties properties;
    private final RestClient restClient;

    public MistralSearchProvider(ScoutProperties properties) {
        this.properties = properties;
        this.restClient = HttpClientFactory.restClient(Duration.ofSeconds(15), Duration.ofSeconds(90));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<SearchHit> search(String topic) {
        ScoutProperties.Mistral config = properties.getMistral();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.warn("scout.mistral.api-key ist nicht gesetzt - Mistral-Suche wird uebersprungen.");
            return List.of();
        }
        ScoutProperties.Search search = properties.getSearch();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("tools", List.of(Map.of("type", "web_search")));
        body.put("inputs", buildPrompt(topic, search.getResultsPerProvider(), search.getLanguage()));

        String response = restClient.post()
                .uri(config.getBaseUrl() + "/v1/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        if (response == null || response.isBlank()) {
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
            StringBuilder text = new StringBuilder();
            collectText(root, text);
            LinkedHashMap<String, SearchHit> hits = new LinkedHashMap<>();
            for (SearchHit hit : hitsFromJsonArray(text.toString())) {
                hits.putIfAbsent(UrlNormalizer.normalize(hit.url()), hit);
            }
            addReferences(root, hits);
            return new ArrayList<>(hits.values());
        } catch (Exception ex) {
            log.warn("Mistral-Antwort konnte nicht ausgewertet werden: {}", ex.getMessage());
            return List.of();
        }
    }

    private static List<SearchHit> hitsFromJsonArray(String text) {
        return JsonExtractor.extractJsonArray(text)
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

    private static void addReferences(JsonNode node, LinkedHashMap<String, SearchHit> hits) {
        if (node.isObject()) {
            JsonNode references = node.get("references");
            if (references != null && references.isArray()) {
                for (JsonNode reference : references) {
                    SearchHit hit = SearchHit.fromJson(reference, ID);
                    if (hit != null) {
                        hits.putIfAbsent(UrlNormalizer.normalize(hit.url()), hit);
                    }
                }
            }
            node.fields().forEachRemaining(entry -> addReferences(entry.getValue(), hits));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                addReferences(item, hits);
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
}
