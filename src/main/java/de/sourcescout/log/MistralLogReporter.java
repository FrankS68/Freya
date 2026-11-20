package de.sourcescout.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.sourcescout.config.ScoutProperties;
import de.sourcescout.util.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class MistralLogReporter {

    private static final Logger log = LoggerFactory.getLogger(MistralLogReporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    private final ScoutProperties properties;
    private final RestClient restClient;
    private final Path stateFile;

    public MistralLogReporter(ScoutProperties properties) {
        this.properties = properties;
        this.restClient = HttpClientFactory.restClient(Duration.ofSeconds(10), Duration.ofSeconds(60));
        Path store = Path.of(properties.getStore().getPath());
        Path parent = store.getParent() != null ? store.getParent() : Path.of(".");
        this.stateFile = parent.resolve("log-library.json");
    }

    public void reportRun(List<String> logLines) {
        ScoutProperties.LogReport config = properties.getLogReport();
        if (!config.isEnabled()) {
            return;
        }
        String apiKey = properties.getMistral().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Kein Mistral-API-Key - Lauf-Log wird nicht abgelegt.");
            return;
        }
        try {
            String libraryId = resolveLibraryId(apiKey);
            if (libraryId == null || libraryId.isBlank()) {
                return;
            }
            String filename = "freya-lauf-" + RUN_STAMP.format(Instant.now()) + ".md";
            upload(libraryId, apiKey, filename, buildMarkdown(logLines));
            log.info("Lauf-Log in Mistral-Bibliothek abgelegt: {} ({} Zeilen)", filename, logLines.size());
        } catch (Exception ex) {
            log.warn("Lauf-Log konnte nicht in die Mistral-Bibliothek gelegt werden: {}", ex.getMessage());
        }
    }

    private String resolveLibraryId(String apiKey) {
        ScoutProperties.LogReport config = properties.getLogReport();
        if (config.getLibraryId() != null && !config.getLibraryId().isBlank()) {
            return config.getLibraryId();
        }
        if (Files.exists(stateFile)) {
            try {
                JsonNode node = MAPPER.readTree(stateFile.toFile());
                String id = node.path("libraryId").asText("");
                if (!id.isBlank()) {
                    return id;
                }
            } catch (Exception ex) {
                log.warn("Bibliotheks-Status nicht lesbar: {}", ex.getMessage());
            }
        }
        try {
            String response = restClient.post()
                    .uri(properties.getMistral().getBaseUrl() + "/v1/libraries")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", "Freya-Logs",
                            "description", "Laufzusammenfassungen der Quellen-Recherche (automatisch von Freya abgelegt)"))
                    .retrieve()
                    .body(String.class);
            JsonNode node = MAPPER.readTree(response == null ? "{}" : response);
            String id = node.path("id").asText("");
            if (id.isBlank()) {
                log.warn("Mistral-Bibliothek konnte nicht erstellt werden.");
                return null;
            }
            Files.createDirectories(stateFile.getParent());
            MAPPER.writeValue(stateFile.toFile(), Map.of("libraryId", id, "name", "Freya-Logs"));
            log.info("Mistral-Bibliothek 'Freya-Logs' erstellt ({}).", id);
            return id;
        } catch (Exception ex) {
            log.warn("Mistral-Bibliothek konnte nicht angelegt werden: {}", ex.getMessage());
            return null;
        }
    }

    private void upload(String libraryId, String apiKey, String filename, String markdown) {
        String finalName = filename;
        ByteArrayResource file = new ByteArrayResource(markdown.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return finalName;
            }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", file);
        restClient.post()
                .uri(properties.getMistral().getBaseUrl() + "/v1/libraries/" + libraryId + "/documents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toBodilessEntity();
    }

    static String buildMarkdown(List<String> logLines) {
        StringBuilder text = new StringBuilder("# Freya-Lauf\n\n");
        text.append("- Zeit: ").append(Instant.now()).append('\n');
        text.append("- Version: ").append(runningVersion()).append('\n');
        text.append("\n## Log\n\n```text\n");
        for (String line : logLines) {
            text.append(line).append('\n');
        }
        text.append("```\n");
        return text.toString();
    }

    private static String runningVersion() {
        java.lang.Package pkg = MistralLogReporter.class.getPackage();
        return pkg == null || pkg.getImplementationVersion() == null ? "unbekannt" : pkg.getImplementationVersion();
    }
}
