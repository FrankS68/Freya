package de.sourcescout.evaluate;

import com.fasterxml.jackson.databind.JsonNode;
import de.sourcescout.config.ScoutProperties;
import de.sourcescout.core.Evaluation;
import de.sourcescout.core.EvaluatedItem;
import de.sourcescout.core.SearchHit;
import de.sourcescout.llm.LlmClient;
import de.sourcescout.util.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private static final String SYSTEM_PROMPT = "Du bewertest Internet-Quellen fuer ein Redaktions-Portal. "
            + "Bewerte die Relevanz und Qualitaet der Quelle auf einer Skala von 0 (irrelevant) bis 100 (sehr relevant). "
            + "Achte auf Serioesitaet, Aktualitaet und informativen Gehalt. Antworte AUSSCHLIESSLICH mit einem JSON-Objekt: "
            + "{\"relevance\": <0-100>, \"summary\": \"<1-2 Saetze Zusammenfassung>\", \"reason\": \"<kurze Begruendung>\"}";

    private final ScoutProperties properties;
    private final LlmClient client;

    public EvaluationService(ScoutProperties properties, List<LlmClient> clients) {
        this.properties = properties;
        String wanted = properties.getEvaluation().getProvider();
        this.client = clients.stream()
                .filter(c -> c.id().equals(wanted))
                .findFirst()
                .orElseGet(() -> clients.stream().findFirst().orElse(null));
        if (this.client == null) {
            log.warn("Kein LLM-Client verfuegbar - Quellen werden nicht bewertet.");
        }
    }

    public Optional<Evaluation> evaluate(SearchHit hit) {
        if (client == null) {
            return Optional.empty();
        }
        String user = "Titel: " + hit.title() + "\n"
                + "URL: " + hit.url() + "\n"
                + "Auszug: " + truncate(hit.snippet(), 600);
        try {
            String response = client.completeJson(SYSTEM_PROMPT, user);
            if (response == null || response.isBlank()) {
                return Optional.empty();
            }
            JsonNode node = JsonExtractor.extractJsonObject(response).orElse(null);
            if (node == null || !node.has("relevance")) {
                return Optional.empty();
            }
            return Optional.of(new Evaluation(
                    clamp(node.path("relevance").asInt(-1)),
                    textOf(node, "summary"),
                    textOf(node, "reason")));
        } catch (Exception ex) {
            log.warn("Bewertung fehlgeschlagen fuer {}: {}", hit.url(), ex.getMessage());
            return Optional.empty();
        }
    }

    public List<EvaluatedItem> evaluateAll(List<SearchHit> hits) {
        List<EvaluatedItem> result = new ArrayList<>();
        for (SearchHit hit : hits) {
            evaluate(hit).ifPresent(evaluation -> result.add(new EvaluatedItem(hit, evaluation)));
        }
        return result;
    }

    private static int clamp(int score) {
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 100);
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
