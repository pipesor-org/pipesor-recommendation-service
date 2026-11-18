package com.pipesor.recommendation.receipt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Validated historical context; current resource authority is checked separately. */
public record ReceiptSubmission(UUID operationId, UUID tenantId, String installationId, String repositoryId,
        UUID pipelineId, String scopeRevision, UUID incidentId, UUID executionId, List<UUID> evidenceIds,
        String summary, String correlationId, Instant contextCreatedAt, String signalsJson, String fingerprint) {
    public ReceiptSubmission { evidenceIds = List.copyOf(evidenceIds); }
    @Override public String toString() { return "ReceiptSubmission[redacted]"; }
}
