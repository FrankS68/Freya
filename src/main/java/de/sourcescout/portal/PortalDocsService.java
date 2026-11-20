package de.sourcescout.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.sourcescout.config.ScoutProperties;
import de.sourcescout.llm.LlmClient;
import de.sourcescout.util.HttpClientFactory;
import de.sourcescout.util.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PortalDocsService {

    private static final Logger log = LoggerFactory.getLogger(PortalDocsService.class);
    private static final Pattern API_PATH = Pattern.compile("/api/[A-Za-z0-9_/{}\\.\\-]*");

    private final ScoutProperties properties;
    private final LlmClient llmClient;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path stateFile;
    private final Path docFile;

    private String docsHash;
    private DocsMapping mapping;

    public PortalDocsService(ScoutProperties properties, List<LlmClient> llmClients) {
        this.properties = properties;
        this.llmClient = llmClients.stream()
                .filter(client -> client.id().equals(properties.getEvaluation().getProvider()))
                .findFirst()
                .orElseGet(() -> llmClients.stream().findFirst().orElse(null));
        this.restClient = HttpClientFactory.restClient(Duration.ofSeconds(10), Duration.ofSeconds(30));
        Path store = Path.of(properties.getStore().getPath());
        Path parent = store.getParent() != null ? store.getParent() : Path.of(".");
        this.stateFile = parent.resolve("portal-docs-state.json");
        this.docFile = parent.resolve("portal-docs-latest.txt");
        loadState();
    }

    @Scheduled(cron = "${scout.portal.docs-cron:0 17 * * * *}")
    public void scheduledCheck() {
        checkForChanges();
    }

    public synchronized Optional<DocsMapping> checkForChanges() {
        ScoutProperties.Portal config = properties.getPortal();
        if (config.getDocsUrl() == null || config.getDocsUrl().isBlank()) {
            return Optional.empty();
        }
        String raw;
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get()
                    .uri(config.getDocsUrl())
                    .accept(MediaType.APPLICATION_JSON);
            if (config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
                request = request.header(config.getAuthHeaderName(), config.getAuthToken());
            }
            raw = request.retrieve().body(String.class);
        } catch (Exception ex) {
            log.warn("Schnittstellen-Doku {} nicht erreichbar: {}", config.getDocsUrl(), ex.getMessage());
            return Optional.ofNullable(mapping);
        }
        if (raw == null || raw.isBlank()) {
            log.warn("Schnittstellen-Doku {} lieferte keinen Inhalt.", config.getDocsUrl());
            return Optional.ofNullable(mapping);
        }
        String hash = sha256(raw);
        if (docsHash != null && docsHash.equals(hash)) {
            return Optional.ofNullable(mapping);
        }
        boolean firstLoad = docsHash == null;
        docsHash = hash;
        saveDoc(raw);
        log.warn("Schnittstellen-Doku {}: {} Bytes, Kopie unter {}", firstLoad ? "erstmalig geladen" : "GEAENDERT",
                raw.length(), docFile);
        mapping = interpret(raw);
        persistState();
        return Optional.ofNullable(mapping);
    }

    private DocsMapping interpret(String raw) {
        DocsMapping interpreted = interpretWithLlm(raw);
        if (interpreted != null && (interpreted.hasQuestionsEndpoint() || interpreted.hasAnswerEndpoint()
                || interpreted.hasTopicsEndpoint())) {
            log.info("Schnittstellen-Mapping (LLM): Fragen={}, Methode={}, Antwort={}, Methode={}, Themen={}, Methode={}",
                    interpreted.questionsEndpoint(), interpreted.questionsMethod(),
                    interpreted.answerEndpoint(), interpreted.answerMethod(),
                    interpreted.topicsEndpoint(), interpreted.topicsMethod());
            return interpreted;
        }
        DocsMapping heuristic = heuristic(raw);
        log.info("Schnittstellen-Mapping (Heuristik): Fragen={}, Antwort={}, Themen={}",
                heuristic.questionsEndpoint(), heuristic.answerEndpoint(), heuristic.topicsEndpoint());
        return heuristic;
    }

    private DocsMapping interpretWithLlm(String raw) {
        if (llmClient == null) {
            return null;
        }
        try {
            String system = "Analysiere eine REST-API-Doku (HTML oder JSON). Identifiziere (1) den Endpunkt zum "
                    + "Abrufen offener Recherche-Fragen als Liste, (2) den Endpunkt zum Einreichen einer Antwort "
                    + "zu einer Frage und (3) den Endpunkt, der Recherche-Themen/Themen (Topics) als Liste liefert. "
                    + "Antworte ausschliesslich mit einem JSON-Objekt: "
                    + "{\"questionsEndpoint\": \"/pfad/oder-null\", \"questionsMethod\": \"GET\", "
                    + "\"answerEndpoint\": \"/pfad/oder-null\", \"answerMethod\": \"POST\", "
                    + "\"topicsEndpoint\": \"/pfad/oder-null\", \"topicsMethod\": \"GET\"}. "
                    + "Verwende null fuer Endpunkte, die in der Doku nicht vorkommen.";
            String response = llmClient.completeJson(system, "Doku: " + excerpt(raw, 6000));
            JsonNode node = JsonExtractor.extractJsonObject(response).orElse(null);
            if (node == null) {
                return null;
            }
            return new DocsMapping(
                    textOrNull(node, "questionsEndpoint"),
                    textOrNull(node, "questionsMethod"),
                    textOrNull(node, "answerEndpoint"),
                    textOrNull(node, "answerMethod"),
                    textOrNull(node, "topicsEndpoint"),
                    textOrNull(node, "topicsMethod"));
        } catch (Exception ex) {
            log.warn("LLM-Analyse der Schnittstellen-Doku fehlgeschlagen: {}", ex.getMessage());
            return null;
        }
    }

    static DocsMapping heuristic(String raw) {
        String questions = null;
        String answers = null;
        String topics = null;
        Matcher matcher = API_PATH.matcher(raw);
        while (matcher.find()) {
            String path = matcher.group();
            String lower = path.toLowerCase();
            if (questions == null && (lower.contains("question") || lower.contains("frage"))) {
                questions = path;
            }
            if (answers == null && !path.equals(questions)
                    && (lower.contains("answer") || lower.contains("antwort") || lower.contains("result"))) {
                answers = path;
            }
            if (topics == null && !path.equals(questions) && !path.equals(answers)
                    && (lower.contains("topic") || lower.contains("thema"))) {
                topics = path;
            }
        }
        return new DocsMapping(questions, "GET", answers, "POST", topics, "GET");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String excerpt(String text, int maxLength) {
        String condensed = text.replaceAll("\\s+", " ").trim();
        return condensed.length() <= maxLength ? condensed : condensed.substring(0, maxLength) + "...";
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return String.valueOf(text.hashCode());
        }
    }

    private void saveDoc(String raw) {
        try {
            Files.createDirectories(docFile.getParent());
            Files.writeString(docFile, raw, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("Doku-Kopie konnte nicht gespeichert werden: {}", ex.getMessage());
        }
    }

    private void persistState() {
        try {
            Files.createDirectories(stateFile.getParent());
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("hash", docsHash);
            state.put("checkedAt", Instant.now().toString());
            if (mapping != null) {
                state.put("questionsEndpoint", mapping.questionsEndpoint());
                state.put("questionsMethod", mapping.questionsMethod());
                state.put("answerEndpoint", mapping.answerEndpoint());
                state.put("answerMethod", mapping.answerMethod());
                state.put("topicsEndpoint", mapping.topicsEndpoint());
                state.put("topicsMethod", mapping.topicsMethod());
            }
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), state);
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            log.warn("Doku-Status konnte nicht gespeichert werden: {}", ex.getMessage());
        }
    }

    private void loadState() {
        if (!Files.exists(stateFile)) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(stateFile.toFile());
            JsonNode hash = node.get("hash");
            docsHash = hash != null && hash.isTextual() ? hash.asText() : null;
            mapping = new DocsMapping(
                    textOrNull(node, "questionsEndpoint"),
                    textOrNull(node, "questionsMethod"),
                    textOrNull(node, "answerEndpoint"),
                    textOrNull(node, "answerMethod"),
                    textOrNull(node, "topicsEndpoint"),
                    textOrNull(node, "topicsMethod"));
        } catch (Exception ex) {
            log.warn("Doku-Status konnte nicht geladen werden: {}", ex.getMessage());
        }
    }
}
