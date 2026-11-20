package de.sourcescout.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import de.sourcescout.config.ScoutProperties;
import de.sourcescout.util.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class PortalTopicsService {

    private static final Logger log = LoggerFactory.getLogger(PortalTopicsService.class);

    private final ScoutProperties properties;
    private final RestClient restClient;

    public PortalTopicsService(ScoutProperties properties) {
        this.properties = properties;
        this.restClient = HttpClientFactory.restClient(Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    public List<String> fetchTopics(DocsMapping mapping) {
        ScoutProperties.Portal config = properties.getPortal();
        RestClient.RequestHeadersSpec<?> request = restClient
                .method(org.springframework.http.HttpMethod.valueOf(methodOf(mapping)))
                .uri(absoluteUrl(mapping.topicsEndpoint()))
                .accept(MediaType.APPLICATION_JSON);
        if (config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
            request = request.header(config.getAuthHeaderName(), config.getAuthToken());
        }
        String body = request.retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return parseTopics(body);
    }

    private static String methodOf(DocsMapping mapping) {
        String method = mapping.topicsMethod();
        return method == null || method.isBlank() ? "GET" : method.toUpperCase();
    }

    private String absoluteUrl(String endpoint) {
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return endpoint;
        }
        ScoutProperties.Portal config = properties.getPortal();
        String base = config.getUrl() != null && !config.getUrl().isBlank()
                ? originOf(config.getUrl())
                : originOf(config.getDocsUrl());
        return base + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
    }

    static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = new URI(url);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (Exception ex) {
            return url;
        }
    }

    public static List<String> parseTopics(String body) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode root = mapper.readTree(body);
            JsonNode array = findArray(root);
            List<String> topics = new ArrayList<>();
            if (array.isMissingNode()) {
                String single = topicOf(root);
                if (single != null) {
                    topics.add(single);
                }
                return topics;
            }
            for (JsonNode item : array) {
                String topic = topicOf(item);
                if (topic != null && !topics.contains(topic)) {
                    topics.add(topic);
                }
            }
            return topics;
        } catch (Exception ex) {
            log.warn("Themen-Antwort konnte nicht ausgewertet werden: {}", ex.getMessage());
            return List.of();
        }
    }

    private static JsonNode findArray(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        for (String key : List.of("topics", "themen", "items", "data", "results")) {
            JsonNode candidate = root.path(key);
            if (candidate.isArray()) {
                return candidate;
            }
        }
        return MissingNode.getInstance();
    }

    private static String topicOf(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            return node.asText().trim();
        }
        if (!node.isObject()) {
            return null;
        }
        for (String field : List.of("name", "title", "label", "text", "topic", "thema", "question", "frage")) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }
}
