package com.pipesor.recommendation.retrieval.domain;

import java.util.Objects;
import java.util.UUID;

public record AuthorizationScope(
        UUID tenantId,
        String githubInstallationId,
        String githubRepositoryId,
        UUID pipelineId,
        String scopeRevision) {

    public AuthorizationScope {
        Objects.requireNonNull(tenantId, "tenantId is required");
        requirePositiveDecimal(githubInstallationId, "githubInstallationId");
        requirePositiveDecimal(githubRepositoryId, "githubRepositoryId");
        if (scopeRevision == null || !scopeRevision.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("scopeRevision is invalid");
        }
    }

    private static void requirePositiveDecimal(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,31}")) {
            throw new IllegalArgumentException(field + " must be a canonical positive decimal");
        }
    }
}
