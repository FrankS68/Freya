package de.sourcescout.search;

import de.sourcescout.core.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MistralSearchProviderTest {

    @Test
    void harvestsUrlsFromToolExecutionWhenModelAnswersInProse() {
        String body = """
                {
                  "conversation": {"id": "conv_3"},
                  "outputs": [
                    {
                      "type": "tool.execution",
                      "name": "web_search",
                      "info": {"result": "{\"HmCBpYUH\": {\"url\": \"https://www.heise.de/t/p5\", \"title\": "
                              + "\"Raspberry Pi 5 Test\"}}"}
                    },
                    {"type": "message.output", "content": [{"type": "text", "text": "Ich habe keine relevanten Artikel gefunden."}]}
                  ]
                }
                """;

        List<SearchHit> hits = MistralSearchProvider.parseResponse(body);

        assertEquals(1, hits.size());
        assertEquals("https://www.heise.de/t/p5", hits.get(0).url());
        assertEquals("Raspberry Pi 5 Test", hits.get(0).title());
    }

    @Test
    void parsesModelJsonArrayFromConversationOutput() {
        String body = """
                {
                  "conversation": {"id": "conv_1", "object": "conversation"},
                  "outputs": [
                    {
                      "type": "message.output",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "text",
                          "text": "Hier sind die Quellen: ```json\\n[{\\"title\\": \\"Raspberry Pi 5 Test\\", \\"url\\": \\"https://heise.de/t/p5\\", \\"snippet\\": \\"Testbericht\\", \\"published_at\\": \\"2025-07-01\\"}]\\n```"
                        }
                      ]
                    }
                  ]
                }
                """;

        List<SearchHit> hits = MistralSearchProvider.parseResponse(body);

        assertEquals(1, hits.size());
        assertEquals("Raspberry Pi 5 Test", hits.get(0).title());
        assertEquals("https://heise.de/t/p5", hits.get(0).url());
    }

    @Test
    void mergesWebSearchReferences() {
        String body = """
                {
                  "conversation": {"id": "conv_2"},
                  "outputs": [
                    {"type": "message.output", "content": [{"type": "text", "text": "[]"}]},
                    {"type": "tool.output", "references": [
                      {"title": "heise online", "url": "https://www.heise.de/t/p5"},
                      {"title": "Golem", "url": "https://golem.de/news/pi"}
                    ]}
                  ]
                }
                """;

        List<SearchHit> hits = MistralSearchProvider.parseResponse(body);

        assertEquals(2, hits.size());
        assertTrue(hits.stream().anyMatch(hit -> hit.url().contains("golem.de")));
    }

    @Test
    void toleratesUnparseableResponses() {
        assertTrue(MistralSearchProvider.parseResponse("Kein JSON hier.").isEmpty());
    }
}
