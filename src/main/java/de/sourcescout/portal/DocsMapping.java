package de.sourcescout.portal;

public record DocsMapping(String questionsEndpoint, String questionsMethod, String answerEndpoint, String answerMethod) {

    public boolean hasQuestionsEndpoint() {
        return questionsEndpoint != null && !questionsEndpoint.isBlank();
    }

    public boolean hasAnswerEndpoint() {
        return answerEndpoint != null && !answerEndpoint.isBlank();
    }
}
