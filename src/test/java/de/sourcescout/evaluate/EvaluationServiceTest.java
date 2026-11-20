package de.sourcescout.evaluate;

import de.sourcescout.config.ScoutProperties;
import de.sourcescout.core.Evaluation;
import de.sourcescout.core.SearchHit;
import de.sourcescout.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationServiceTest {

    static class FakeLlmClient implements LlmClient {
        private final String id;
        private final String response;

        FakeLlmClient(String id, String response) {
            this.id = id;
            this.response = response;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String completeJson(String systemPrompt, String userPrompt) {
            return response;
        }
    }

    @Test
    void parsesRelevanceSummaryAndReason() {
        ScoutProperties properties = new ScoutProperties();
        properties.getEvaluation().setProvider("mistral");
        FakeLlmClient client = new FakeLlmClient("mistral",
                "{\"relevance\": 85, \"summary\": \"relevant\", \"reason\": \"serioes\"}");
        EvaluationService service = new EvaluationService(properties, List.of(client));

        Optional<Evaluation> evaluation = service.evaluate(
                new SearchHit("T", "https://example.com", "s", "", "mistral"));

        assertTrue(evaluation.isPresent());
        assertEquals(85, evaluation.get().relevance());
        assertEquals("relevant", evaluation.get().summary());
    }

    @Test
    void clampsOutOfRangeScores() {
        ScoutProperties properties = new ScoutProperties();
        properties.getEvaluation().setProvider("gemini");
        FakeLlmClient client = new FakeLlmClient("gemini", "{\"relevance\": 250}");
        EvaluationService service = new EvaluationService(properties, List.of(client));

        Optional<Evaluation> evaluation = service.evaluate(
                new SearchHit("T", "https://example.com", "s", "", "gemini"));

        assertTrue(evaluation.isPresent());
        assertEquals(100, evaluation.get().relevance());
    }

    @Test
    void fallsBackToFirstAvailableClient() {
        ScoutProperties properties = new ScoutProperties();
        properties.getEvaluation().setProvider("nicht-vorhanden");
        FakeLlmClient client = new FakeLlmClient("gemini", "{\"relevance\": 12}");
        EvaluationService service = new EvaluationService(properties, List.of(client));

        Optional<Evaluation> evaluation = service.evaluate(
                new SearchHit("T", "https://example.com", "s", "", "gemini"));

        assertTrue(evaluation.isPresent());
        assertEquals(12, evaluation.get().relevance());
    }

    @Test
    void returnsEmptyWithoutAnyClient() {
        ScoutProperties properties = new ScoutProperties();
        EvaluationService service = new EvaluationService(properties, List.of());

        assertTrue(service.evaluate(new SearchHit("T", "https://example.com", "s", "", "x")).isEmpty());
    }
}
