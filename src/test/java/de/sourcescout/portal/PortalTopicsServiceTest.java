package de.sourcescout.portal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalTopicsServiceTest {

    @Test
    void parsesBareArrayOfStrings() {
        List<String> topics = PortalTopicsService.parseTopics(
                "[\"Einheitliches Risikomanagement\", \"Hybride Veranstaltungen\"]");

        assertEquals(2, topics.size());
        assertEquals("Einheitliches Risikomanagement", topics.get(0));
    }

    @Test
    void parsesWrapperArrayWithObjects() {
        String body = """
                {"topics": [
                  {"id": "t-1", "title": "Digitale Verwaltung"},
                  {"id": "t-2", "text": "Offene Daten"}
                ]}
                """;

        List<String> topics = PortalTopicsService.parseTopics(body);

        assertEquals(List.of("Digitale Verwaltung", "Offene Daten"), topics);
    }

    @Test
    void deduplicatesAndSkipsEmptyEntries() {
        List<String> topics = PortalTopicsService.parseTopics(
                "[\"Thema A\", \"Thema A\", {\"meta\": 1}, \"\"]");

        assertEquals(List.of("Thema A"), topics);
    }

    @Test
    void unparseableBodyYieldsEmptyList() {
        assertTrue(PortalTopicsService.parseTopics("<html>kein json</html>").isEmpty());
    }

    @Test
    void heuristicFindsTopicPaths() {
        DocsMapping mapping = PortalDocsService.heuristic(
                "GET /api/questions ... GET /api/topics Liste der Recherche-Themen");

        assertEquals("/api/topics", mapping.topicsEndpoint());
        assertTrue(mapping.hasTopicsEndpoint());
    }
}
