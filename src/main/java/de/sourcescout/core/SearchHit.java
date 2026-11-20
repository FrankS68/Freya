package de.sourcescout.core;

import com.fasterxml.jackson.databind.JsonNode;

public record SearchHit(String title, String url, String snippet, String publishedAt, String provider) {

    public static SearchHit fromJson(JsonNode node, String provider) {
        String url = field(node, "url");
        if (url == null || url.isBlank()) {
            return null;
        }
        String title = firstField(node, "title", "name");
        String snippet = firstField(node, "snippet", "description", "content");
        String publishedAt = firstField(node, "published_at", "publishedAt", "date");
        return new SearchHit(
                title == null ? "" : title,
                url.trim(),
                snippet == null ? "" : snippet,
                publishedAt == null ? "" : publishedAt,
                provider);
    }

    private static String field(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String firstField(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
