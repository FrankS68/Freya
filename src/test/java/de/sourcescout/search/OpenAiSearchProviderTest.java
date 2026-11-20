package de.sourcescout.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.sourcescout.core.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiSearchProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String body(String messageText, String annotationsJson, String sourcesJson) {
        return "{"
                + "\"output\": ["
                + "{\"type\": \"web_search_call\", \"action\": {\"type\": \"search\", " + sourcesJson + "}},"
                + "{\"type\": \"message\", \"content\": [{\"type\": \"output_text\", \"text\": "
                + quote(messageText) + ", " + annotationsJson + "}]}"
                + "]}";
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void parsesJsonArrayFromMessageText() throws Exception {
        String text = "[{\"title\": \"Artikel A\", \"url\": \"https://example.org/a\", "
                + "\"snippet\": \"Inhalt\", \"published_at\": \"2026-11-20\"}]";
        String body = body(text, "\"annotations\": []", "\"sources\": []");
        List<SearchHit> hits = OpenAiSearchProvider.parseResponse(body);

        assertEquals("https://example.org/a", hits.get(0).url());
        assertEquals("openai", hits.get(0).provider());
        assertTrue(hits.get(0).title().startsWith("Artikel"));
    }

    @Test
    void mergesUrlCitationAnnotationsAndSearchSources() throws Exception {
        String text = "Kurzer Bericht mit Quellen.";
        String body = body(text,
                "\"annotations\": [{\"type\": \"url_citation\", \"url\": \"https://example.org/cite1\", "
                        + "\"title\": \"Zitat 1\"}]",
                "\"sources\": [{\"type\": \"url\", \"url\": \"https://example.org/source2\", \"title\": \"Quelle 2\"}]");
        List<SearchHit> hits = OpenAiSearchProvider.parseResponse(body);

        assertEquals(2, hits.size());
        assertTrue(hits.stream().anyMatch(h -> h.url().equals("https://example.org/cite1")));
        assertTrue(hits.stream().anyMatch(h -> h.url().equals("https://example.org/source2")));
    }

    @Test
    void unparseableResponseYieldsEmptyList() {
        assertTrue(OpenAiSearchProvider.parseResponse("{\"output\": []}").isEmpty());
    }
}
