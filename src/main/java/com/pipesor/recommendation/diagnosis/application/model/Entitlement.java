package com.pipesor.recommendation.diagnosis.application.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pipeline's current answer for the session subject and one incident: VIEW access, current GitHub
 * access and the incident's single failed ORIGIN execution, with its resource scope.
 * {@code tenantId} may be absent when the resolver does not report it.
 */
public record Entitlement(UUID incidentId, UUID pipelineId, UUID executionId, UUID tenantId,
        String githubInstallationId, String githubRepositoryId, Set<String> capabilities) {
    public Entitlement {
        Objects.requireNonNull(incidentId);
        Objects.requireNonNull(pipelineId);
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(githubInstallationId);
        Objects.requireNonNull(githubRepositoryId);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public boolean canOperate() { return capabilities.contains("OPERATE"); }
}
