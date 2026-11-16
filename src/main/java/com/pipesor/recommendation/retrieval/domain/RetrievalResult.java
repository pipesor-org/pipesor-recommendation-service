package com.pipesor.recommendation.retrieval.domain;

import java.util.List;

public sealed interface RetrievalResult permits RetrievalResult.Matches, RetrievalResult.NoMatch {
    String correlationId();

    record Matches(String correlationId, List<KnowledgeMatch> matches) implements RetrievalResult {
        public Matches {
            matches = List.copyOf(matches);
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("matches result cannot be empty");
            }
        }
    }

    record NoMatch(String correlationId) implements RetrievalResult {
    }
}
