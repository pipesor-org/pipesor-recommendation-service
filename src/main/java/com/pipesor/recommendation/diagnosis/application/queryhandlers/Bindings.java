package com.pipesor.recommendation.diagnosis.application.queryhandlers;

import com.pipesor.recommendation.diagnosis.application.model.AdmittedDiagnosis;
import com.pipesor.recommendation.diagnosis.application.model.Entitlement;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;

import java.util.UUID;

/** Binding rules between an admitted version, its receipt and the owner's current entitlement. */
final class Bindings {
    private Bindings() { }

    /** The version was admitted from a receipt of exactly this incident and execution, under the same scope. */
    static boolean admittedFor(AdmittedDiagnosis diagnosis, UUID incidentId, UUID executionId) {
        var receipt = diagnosis.receipt();
        var output = diagnosis.output();
        return receipt != null && receipt.contextId().equals(output.contextId())
                && incidentId.equals(receipt.incidentId()) && incidentId.equals(output.incidentId())
                && executionId.equals(receipt.executionId()) && executionId.equals(output.executionId())
                && receipt.scope().equals(diagnosis.scope());
    }

    /** The owner's current answer covers the historical scope (tenant only when the owner reports it). */
    static boolean covers(Entitlement entitlement, AuthorizationScope scope) {
        return scope.pipelineId() != null && scope.pipelineId().equals(entitlement.pipelineId())
                && scope.githubInstallationId().equals(entitlement.githubInstallationId())
                && scope.githubRepositoryId().equals(entitlement.githubRepositoryId())
                && (entitlement.tenantId() == null || entitlement.tenantId().equals(scope.tenantId()));
    }
}
