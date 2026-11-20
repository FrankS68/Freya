package de.sourcescout.store;

import java.time.Instant;

public record ProcessedItem(String url, String title, int relevance, String provider, Instant processedAt) {
}
