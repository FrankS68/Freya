package de.sourcescout.portal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionResearchServiceTest {

    @Test
    void parsesQuestionListInKnownWrapper() throws Exception {
        String body = """
                {"questions": [
                  {"id": "q-1", "question": "Was ist neu bei Docker?"},
                  {"id": "q-2", "frage": "Welche Themen gibt es?"}
                ]}
                """;

        List<PortalQuestion> questions = QuestionResearchService.parseQuestions(body);

        assertEquals(2, questions.size());
        assertEquals("q-1", questions.get(0).questionId());
        assertEquals("Was ist neu bei Docker?", questions.get(0).question());
        assertEquals("Welche Themen gibt es?", questions.get(1).question());
    }

    @Test
    void parsesBareArrayAndSkipsEntriesWithoutText() throws Exception {
        String body = """
                [
                  {"id": 7, "title": "Frage ohne id-String"},
                  {"nur": "Metadaten"},
                  {"questionId": "q-9", "text": "Direkte Frage"}
                ]
                """;

        List<PortalQuestion> questions = QuestionResearchService.parseQuestions(body);

        assertEquals(2, questions.size());
        assertEquals("Direkte Frage", questions.get(1).question());
    }

    @Test
    void itemsWithoutAnyTextFieldAreIgnored() throws Exception {
        assertTrue(QuestionResearchService.parseQuestions("{\"data\": [{\"meta\": 1}]}").isEmpty());
    }

    @Test
    void heuristicFindsQuestionAndAnswerPaths() {
        DocsMapping mapping = PortalDocsService.heuristic(
                "GET /api/questions Liste offener Fragen. POST /api/questions/{id}/answer Antwort einreichen.");

        assertEquals("/api/questions", mapping.questionsEndpoint());
        assertEquals("/api/questions/{id}/answer", mapping.answerEndpoint());
        assertTrue(mapping.hasQuestionsEndpoint());
        assertTrue(mapping.hasAnswerEndpoint());
    }

    @Test
    void heuristicReturnsNullPathsWhenNothingMatches() {
        DocsMapping mapping = PortalDocsService.heuristic("GET /api/status");

        assertNull(mapping.questionsEndpoint());
        assertNull(mapping.answerEndpoint());
    }

    @Test
    void originExtractsSchemeAndHost() {
        assertEquals("http://spiekeroog.fritz.box:8082",
                QuestionResearchService.originOf("http://spiekeroog.fritz.box:8082/api/docs"));
    }
}
