package com.pipesor.recommendation.retrieval.domain;

import java.util.List;
import java.util.Objects;

public record RetrievalQuery(
        String correlationId,
        String query,
        AuthorizationScope authorizationScope,
        List<CorpusKind> allowedCorpusKinds,
        int topK) {

    public RetrievalQuery {
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("correlationId is invalid");
        }
        if (query == null || query.isBlank() || query.length() > 4000) {
            throw new IllegalArgumentException("query must contain between 1 and 4000 characters");
        }
        Objects.requireNonNull(authorizationScope, "authorizationScope is required");
        allowedCorpusKinds = List.copyOf(Objects.requireNonNull(allowedCorpusKinds, "allowedCorpusKinds is required"));
        if (allowedCorpusKinds.isEmpty()) {
            throw new IllegalArgumentException("at least one corpus kind is required");
        }
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
    }
}
