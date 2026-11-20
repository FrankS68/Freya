package de.sourcescout.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessedItemStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAcrossInstances() {
        Path file = tempDir.resolve("store.json");
        ProcessedItemStore first = new ProcessedItemStore(file);

        assertFalse(first.isProcessed("https://example.com/a"));
        first.markProcessed(new ProcessedItem("https://example.com/a", "Titel", 88, "mistral", Instant.now()));

        ProcessedItemStore second = new ProcessedItemStore(file);
        assertTrue(second.isProcessed("https://example.com/a"));
        assertFalse(second.isProcessed("https://example.com/b"));
        assertEquals(1, second.size());
    }
}
