package com.pipesor.recommendation.retrieval.infrastructure;

import com.pipesor.recommendation.retrieval.application.ports.RagAccessTokenProvider;
import com.pipesor.recommendation.shared.security.WorkloadTokens;

import java.util.Objects;

/** Platform workload token for {@code recommendation → rag}, role {@code Rag.Query} (identity contract v1, §2). */
public final class WorkloadRagAccessTokenProvider implements RagAccessTokenProvider {
    public static final String TARGET = "rag";
    public static final String ROLE = "Rag.Query";
    private final WorkloadTokens tokens;

    public WorkloadRagAccessTokenProvider(WorkloadTokens tokens) {
        this.tokens = Objects.requireNonNull(tokens);
    }

    @Override
    public String accessToken() {
        return tokens.issue(TARGET, ROLE).value();
    }
}
