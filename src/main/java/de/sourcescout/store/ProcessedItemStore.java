package de.sourcescout.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.sourcescout.config.ScoutProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProcessedItemStore {

    private static final Logger log = LoggerFactory.getLogger(ProcessedItemStore.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ProcessedItem> itemsByNormalizedUrl = new ConcurrentHashMap<>();

    @Autowired
    public ProcessedItemStore(ScoutProperties properties) {
        this(Path.of(properties.getStore().getPath()));
    }

    public ProcessedItemStore(Path file) {
        this.file = file;
        this.mapper.findAndRegisterModules();
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        load();
    }

    public boolean isProcessed(String normalizedUrl) {
        String normalized = normalizedUrl == null ? "" : normalizedUrl;
        return !normalized.isEmpty() && itemsByNormalizedUrl.containsKey(normalized);
    }

    public synchronized void markProcessed(ProcessedItem item) {
        itemsByNormalizedUrl.put(item.url(), item);
        persist();
    }

    public int size() {
        return itemsByNormalizedUrl.size();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<ProcessedItem> items = mapper.readValue(file.toFile(), new TypeReference<>() {
            });
            for (ProcessedItem item : items) {
                itemsByNormalizedUrl.put(item.url(), item);
            }
        } catch (IOException ex) {
            log.warn("Verarbeitete Eintraege konnten nicht geladen werden ({}): {}", file, ex.getMessage());
        }
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tmp.toFile(), List.copyOf(itemsByNormalizedUrl.values()));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.error("Verarbeitete Eintraege konnten nicht gespeichert werden ({}): {}", file, ex.getMessage());
        }
    }
}
