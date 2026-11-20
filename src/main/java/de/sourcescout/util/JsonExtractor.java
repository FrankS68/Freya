package de.sourcescout.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

public final class JsonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonExtractor() {
    }

    public static Optional<JsonNode> extractJsonObject(String text) {
        return extract(text, false);
    }

    public static Optional<JsonNode> extractJsonArray(String text) {
        return extract(text, true);
    }

    private static Optional<JsonNode> extract(String text, boolean array) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String cleaned = stripCodeFences(text);
        char open = array ? '[' : '{';
        char close = array ? ']' : '}';
        int start = cleaned.indexOf(open);
        int end = cleaned.lastIndexOf(close);
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readTree(cleaned.substring(start, end + 1)));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static String stripCodeFences(String text) {
        return text.replace("```json", "")
                .replace("```JSON", "")
                .replace("```", "");
    }
}
