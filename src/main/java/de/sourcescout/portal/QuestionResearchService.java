package de.sourcescout.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import de.sourcescout.config.ScoutProperties;
import de.sourcescout.core.EvaluatedItem;
import de.sourcescout.scout.ResearchPipeline;
import de.sourcescout.store.ProcessedItem;
import de.sourcescout.store.ProcessedItemStore;
import de.sourcescout.util.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QuestionResearchService {

    private static final Logger log = LoggerFactory.getLogger(QuestionResearchService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScoutProperties properties;
    private final ResearchPipeline pipeline;
    private final ProcessedItemStore store;
    private final RestClient restClient;

    public QuestionResearchService(ScoutProperties properties, ResearchPipeline pipeline, ProcessedItemStore store) {
        this.properties = properties;
        this.pipeline = pipeline;
        this.store = store;
        this.restClient = HttpClientFactory.restClient(Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    public void researchPending(DocsMapping mapping) {
        int limit = Math.max(1, properties.getPortal().getMaxQuestionsPerRun());
        List<PortalQuestion> questions;
        try {
            questions = fetchQuestions(mapping);
        } catch (Exception ex) {
            log.warn("Fragen konnten nicht geladen werden ({}): {}", mapping.questionsEndpoint(), ex.getMessage());
            return;
        }
        if (questions.isEmpty()) {
            log.info("Keine offenen Fragen im Portal.");
            return;
        }
        log.info("{} offene Frage(n) im Portal, recherchiere maximal {} pro Lauf.", questions.size(), limit);
        for (PortalQuestion question : questions.subList(0, Math.min(limit, questions.size()))) {
            processQuestion(mapping, question);
        }
    }

    private void processQuestion(DocsMapping mapping, PortalQuestion question) {
        String storageKey = "question:" + (question.questionId() == null || question.questionId().isBlank()
                ? Integer.toHexString(question.question().hashCode())
                : question.questionId());
        if (store.isProcessed(storageKey)) {
            return;
        }
        try {
            List<EvaluatedItem> ranked = pipeline.researchAndRank(question.question(), new HashSet<>());
            if (ranked.isEmpty()) {
                log.info("Keine verwertbaren Treffer fuer Frage '{}'", question.question());
                store.markProcessed(new ProcessedItem(storageKey, question.question(), 0, "question", Instant.now()));
                return;
            }
            EvaluatedItem best = ranked.get(0);
            if (mapping.hasAnswerEndpoint()) {
                postAnswer(mapping, question, best);
                log.info("Antwort zur Frage '{}' veroeffentlicht (Score {}): {}",
                        question.question(), best.relevance(), best.hit().url());
            } else {
                log.warn("Kein Antwort-Endpunkt bekannt - beste Quelle fuer '{}': {} (Score {})",
                        question.question(), best.hit().url(), best.relevance());
            }
            store.markProcessed(new ProcessedItem(storageKey, question.question(), best.relevance(), "question",
                    Instant.now()));
        } catch (Exception ex) {
            log.error("Frage '{}' konnte nicht bearbeitet werden: {}", question.question(), ex.getMessage());
        }
    }

    private List<PortalQuestion> fetchQuestions(DocsMapping mapping) throws IOException {
        ScoutProperties.Portal config = properties.getPortal();
        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri(absoluteUrl(mapping.questionsEndpoint()))
                .accept(MediaType.APPLICATION_JSON);
        if (config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
            request = request.header(config.getAuthHeaderName(), config.getAuthToken());
        }
        String body = request.retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return parseQuestions(body);
    }

    private void postAnswer(DocsMapping mapping, PortalQuestion question, EvaluatedItem best) {
        ScoutProperties.Portal config = properties.getPortal();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questionId", question.questionId());
        payload.put("question", question.question());
        payload.put("title", best.hit().title());
        payload.put("url", best.hit().url());
        payload.put("snippet", best.hit().snippet());
        payload.put("summary", best.evaluation().summary());
        payload.put("relevance", best.relevance());
        payload.put("reason", best.evaluation().reason());
        payload.put("source", best.hit().provider());
        payload.put("publishedAt", best.hit().publishedAt());

        String method = mapping.answerMethod() == null || mapping.answerMethod().isBlank()
                ? "POST"
                : mapping.answerMethod();
        RestClient.RequestBodySpec request = restClient
                .method(HttpMethod.valueOf(method.toUpperCase()))
                .uri(absoluteUrl(mapping.answerEndpoint()));
        if (config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
            request.header(config.getAuthHeaderName(), config.getAuthToken());
        }
        request.contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private String absoluteUrl(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Endpunkt unbekannt");
        }
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

    public static List<PortalQuestion> parseQuestions(String body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode array = findArray(root);
        List<PortalQuestion> questions = new ArrayList<>();
        if (array.isMissingNode()) {
            PortalQuestion single = fromItem(root);
            if (single != null) {
                questions.add(single);
            }
            return questions;
        }
        for (JsonNode item : array) {
            PortalQuestion question = fromItem(item);
            if (question != null) {
                questions.add(question);
            }
        }
        return questions;
    }

    private static JsonNode findArray(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        for (String key : List.of("questions", "items", "data", "results", "fragen")) {
            JsonNode candidate = root.path(key);
            if (candidate.isArray()) {
                return candidate;
            }
        }
        return MissingNode.getInstance();
    }

    private static PortalQuestion fromItem(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String id = firstText(node, "id", "questionId", "question_id", "_id");
        String text = firstText(node, "question", "frage", "text", "title", "label", "content");
        if (text == null) {
            return null;
        }
        return new PortalQuestion(id, text);
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
