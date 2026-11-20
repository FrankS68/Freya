package de.sourcescout.core;

public record EvaluatedItem(SearchHit hit, Evaluation evaluation) {

    public int relevance() {
        return evaluation.relevance();
    }
}
