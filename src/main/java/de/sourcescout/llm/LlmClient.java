package de.sourcescout.llm;

public interface LlmClient {

    String id();

    String completeJson(String systemPrompt, String userPrompt);
}
