package de.sourcescout.scout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.sourcescout.SourceScoutApplication;
import de.sourcescout.config.ScoutProperties;
import de.sourcescout.util.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class SelfUpdateService {

    private static final Logger log = LoggerFactory.getLogger(SelfUpdateService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MIN_JAR_BYTES = 1_000_000;

    private final ScoutProperties properties;
    private final RestClient restClient;

    public SelfUpdateService(ScoutProperties properties) {
        this.properties = properties;
        this.restClient = HttpClientFactory.restClient(Duration.ofSeconds(10), Duration.ofSeconds(60));
    }

    public boolean checkAndApply() {
        ScoutProperties.Update config = properties.getUpdate();
        if (!config.isEnabled()) {
            return false;
        }
        String runningVersion = runningVersion();
        if (runningVersion == null) {
            log.info("Laufende Version nicht feststellbar - Update-Pruefung uebersprungen.");
            return false;
        }
        try {
            String body = restClient.get()
                    .uri("https://api.github.com/repos/" + config.getRepo() + "/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return false;
            }
            JsonNode release = MAPPER.readTree(body);
            String tag = release.path("tag_name").asText("");
            if (tag.isBlank()) {
                log.warn("Kein Release-Tag ermittelbar - Update-Pruefung uebersprungen.");
                return false;
            }
            if (compareVersions(tag, runningVersion) <= 0) {
                log.debug("Kein Update: {} ist aktuell.", runningVersion);
                return false;
            }
            String jarUrl = null;
            for (JsonNode asset : release.path("assets")) {
                if ("freya.jar".equals(asset.path("name").asText())) {
                    jarUrl = asset.path("browser_download_url").asText(null);
                }
            }
            if (jarUrl == null) {
                log.warn("Release {} enthaelt keine freya.jar - Update uebersprungen.", tag);
                return false;
            }
            log.info("Update verfuegbar: {} (laufend {}) - lade herunter...", tag, runningVersion);
            applyUpdate(jarUrl, tag);
        } catch (Exception ex) {
            log.warn("Update-Pruefung fehlgeschlagen: {}", ex.getMessage());
            return false;
        }
        restart();
        return true;
    }

    private void applyUpdate(String jarUrl, String tag) throws Exception {
        byte[] jarBytes = restClient.get().uri(jarUrl).retrieve().body(byte[].class);
        if (jarBytes == null || jarBytes.length < MIN_JAR_BYTES) {
            log.warn("Heruntergeladene JAR unvollstaendig ({} Bytes) - Update abgebrochen.",
                    jarBytes == null ? 0 : jarBytes.length);
            return;
        }
        Path target = locateJar();
        Path tmp = target.resolveSibling(target.getFileName() + ".new");
        Files.write(tmp, jarBytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicFailure) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Update auf {} installiert ({} Bytes) unter {}", tag, jarBytes.length, target);
    }

    private void restart() {
        log.info("Beende Prozess - systemd startet den Dienst danach mit der neuen Version.");
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        System.exit(0);
    }

    private String runningVersion() {
        try {
            Path jar = locateJar();
            String version;
            try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
                version = jarFile.getManifest().getMainAttributes().getValue("Implementation-Version");
            }
            if (version == null || version.isBlank()) {
                java.lang.Package pkg = SourceScoutApplication.class.getPackage();
                version = pkg == null ? null : pkg.getImplementationVersion();
            }
            return version == null || version.isBlank() ? null : version;
        } catch (Exception ex) {
            return null;
        }
    }

    private Path locateJar() throws Exception {
        java.security.CodeSource source = SourceScoutApplication.class.getProtectionDomain().getCodeSource();
        if (source != null && source.getLocation() != null) {
            Path candidate = Path.of(source.getLocation().toURI());
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Path.of("freya.jar");
    }

    static int compareVersions(String left, String right) {
        int[] leftParts = parse(left);
        int[] rightParts = parse(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int l = i < leftParts.length ? leftParts[i] : 0;
            int r = i < rightParts.length ? rightParts[i] : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int[] parse(String version) {
        String cleaned = version.trim().replaceFirst("^v", "").replaceFirst("[^0-9.].*$", "");
        List<Integer> numbers = new ArrayList<>();
        for (String part : cleaned.split("\\.")) {
            try {
                numbers.add(Integer.parseInt(part));
            } catch (NumberFormatException ex) {
                return numbers.stream().mapToInt(Integer::intValue).toArray();
            }
        }
        return numbers.stream().mapToInt(Integer::intValue).toArray();
    }
}
