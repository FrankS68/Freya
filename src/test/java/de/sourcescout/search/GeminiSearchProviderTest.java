package de.sourcescout.search;

import de.sourcescout.core.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiSearchProviderTest {

    @Test
    void parsesJsonTextAndGroundingChunks() {
        String body = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "```json\\n[{\\"title\\": \\"Artikel A\\", \\"url\\": \\"https://example.com/a\\"}]\\n```"
                          }
                        ],
                        "role": "model"
                      },
                      "groundingMetadata": {
                        "webSearchQueries": ["topic"],
                        "groundingChunks": [
                          {"web": {"uri": "https://example.com/a", "title": "Artikel A"}},
                          {"web": {"uri": "https://example.org/b", "title": "Artikel B"}}
                        ]
                      }
                    }
                  ]
                }
                """;

        List<SearchHit> hits = GeminiSearchProvider.parseResponse(body);

        assertEquals(2, hits.size());
        assertTrue(hits.stream().anyMatch(hit -> hit.url().equals("https://example.org/b")));
        assertTrue(hits.stream().anyMatch(hit -> hit.title().equals("Artikel A")));
    }

    @Test
    void fallsBackToGroundingChunksWithoutJson() {
        String body = """
                {
                  "candidates": [
                    {
                      "content": {"parts": [{"text": "Freitext ohne JSON."}], "role": "model"},
                      "groundingMetadata": {
                        "groundingChunks": [
                          {"web": {"uri": "https://example.net/c", "title": "Artikel C"}}
                        ]
                      }
                    }
                  ]
                }
                """;

        List<SearchHit> hits = GeminiSearchProvider.parseResponse(body);

        assertEquals(1, hits.size());
        assertEquals("https://example.net/c", hits.get(0).url());
    }
}
