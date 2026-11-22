package com.pipesor.recommendation.diagnosis.application.model;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Diagnosis;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An admitted diagnosis version read consistently with its review history and the receipt of
 * the context it was admitted from ({@code receipt} is absent when no receipt exists).
 */
public record AdmittedDiagnosis(UUID diagnosisId, int version, Instant createdAt, AuthorizationScope scope,
        Diagnosis output, Receipt receipt, List<ReviewEntry> reviews) {
    public AdmittedDiagnosis {
        reviews = List.copyOf(reviews);
    }

    public record Receipt(UUID contextId, UUID incidentId, UUID executionId, AuthorizationScope scope) { }

    public record ReviewEntry(int revision, String decision, Instant timestamp) { }
}
