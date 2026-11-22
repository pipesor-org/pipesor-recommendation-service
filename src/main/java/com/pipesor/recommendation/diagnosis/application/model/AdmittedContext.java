package com.pipesor.recommendation.diagnosis.application.model;

import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A correlated context exactly as admitted in Recommendation's own receipt (owned {@code diagnosis} schema). */
public record AdmittedContext(UUID contextId, UUID incidentId, UUID executionId, AuthorizationScope scope,
        String correlationId, List<Signal> signals) {
    public AdmittedContext {
        Objects.requireNonNull(contextId);
        Objects.requireNonNull(incidentId);
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(correlationId);
        signals = List.copyOf(signals);
    }

    /** One admitted signal in delivery order; {@code sourceLocator} is absent for protected evidence 2.0. */
    public record Signal(UUID evidenceId, UUID originContextId, String correlationId, String stage, String content,
            String sourceLocator) {
        public Signal {
            Objects.requireNonNull(evidenceId);
            Objects.requireNonNull(originContextId);
            Objects.requireNonNull(stage);
            Objects.requireNonNull(content);
        }
    }

    @Override public String toString() { return "AdmittedContext[" + contextId + "]"; }
}
