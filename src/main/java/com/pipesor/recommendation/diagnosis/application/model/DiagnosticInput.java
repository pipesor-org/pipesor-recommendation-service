package com.pipesor.recommendation.diagnosis.application.model;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Evidence;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;

import java.util.List;
import java.util.UUID;

/** Inputs of {@code PersistedDiagnosticWorkflow.diagnose}, reconstructed from an admitted context. */
public record DiagnosticInput(UUID contextId, UUID executionId, UUID incidentId, RetrievalQuery query,
        List<Evidence> evidence) {
    public DiagnosticInput {
        evidence = List.copyOf(evidence);
    }

    @Override public String toString() { return "DiagnosticInput[" + contextId + "]"; }
}
