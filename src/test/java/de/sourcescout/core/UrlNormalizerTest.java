package de.sourcescout.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlNormalizerTest {

    @Test
    void removesTrackingParametersAndWww() {
        assertEquals("https://example.com/artikel?id=7",
                UrlNormalizer.normalize(
                        "https://www.Example.com/artikel/?utm_source=newsletter&utm_medium=mail&id=7#top"));
    }

    @Test
    void keepsRelevantQueryParameters() {
        assertEquals("https://example.com/suche?q=raspi",
                UrlNormalizer.normalize("https://example.com/suche?q=raspi&gclid=abc"));
    }

    @Test
    void emptyInputYieldsEmptyKey() {
        assertEquals("", UrlNormalizer.normalize("  "));
    }

    @Test
    void fallsBackToRawUrlOnInvalidUri() {
        String result = UrlNormalizer.normalize("http://beispiel{" + '\u007d');
        assertTrue(result.contains("beispiel"));
    }
}
