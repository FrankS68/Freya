package de.sourcescout.portal;

import de.sourcescout.config.ScoutProperties;
import de.sourcescout.core.EvaluatedItem;
import de.sourcescout.util.HttpClientFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PortalPublisher {

    private final ScoutProperties properties;
    private final RestClient restClient;

    public PortalPublisher(ScoutProperties properties) {
        this.properties = properties;
        ScoutProperties.Portal config = properties.getPortal();
        this.restClient = HttpClientFactory.restClient(config.getConnectTimeout(), config.getReadTimeout());
    }

    public boolean isConfigured() {
        String url = properties.getPortal().getUrl();
        return url != null && !url.isBlank();
    }

    public void publish(EvaluatedItem item) {
        ScoutProperties.Portal config = properties.getPortal();
        if (!isConfigured()) {
            throw new IllegalStateException("Portal-API ist nicht konfiguriert (scout.portal.url).");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", item.hit().title());
        payload.put("url", item.hit().url());
        payload.put("snippet", item.hit().snippet());
        payload.put("summary", item.evaluation().summary());
        payload.put("relevance", item.relevance());
        payload.put("reason", item.evaluation().reason());
        payload.put("source", item.hit().provider());
        payload.put("publishedAt", item.hit().publishedAt());

        RestClient.RequestBodySpec request = restClient
                .method(HttpMethod.valueOf(config.getMethod().toUpperCase()))
                .uri(config.getUrl());
        if (config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
            request.header(config.getAuthHeaderName(), config.getAuthToken());
        }
        if (config.getHeaders() != null) {
            config.getHeaders().forEach(request::header);
        }
        request.contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}
