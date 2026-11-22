package com.pipesor.recommendation.diagnosis.application.queries;

import java.util.List;
import java.util.UUID;

/**
 * Diagnosis view of MVP HTTP API v1: {@code hypothesis} is inferred model output, {@code evidence}
 * is observed sanitized evidence and {@code sources} is retrieved knowledge. {@code reviews} carries
 * only revision, decision and time (no reviewer identity or free text).
 */
public record DiagnosisProjection(UUID diagnosisId, int version, UUID incidentId, UUID executionId, UUID contextId,
        String correlationId, String createdAt, String status, Hypothesis hypothesis, List<Evidence> evidence,
        List<Source> sources, Model model,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        List<Review> reviews) {

    public record Hypothesis(String probableCause, String recommendation, String limitations, List<UUID> evidenceIds,
            List<UUID> chunkIds) { }

    public record Evidence(UUID evidenceId, String stage, String sourceLocator, String content) { }

    public record Source(UUID documentId, UUID chunkId, String provenance) { }

    public record Model(String deployment, String modelVersion) { }

    public record Review(int revision, String decision, String timestamp) { }

    /** The same projection without review history (GitHub Actions report). */
    public DiagnosisProjection withoutReviews() {
        return new DiagnosisProjection(diagnosisId, version, incidentId, executionId, contextId, correlationId, createdAt,
                status, hypothesis, evidence, sources, model, null);
    }
}
