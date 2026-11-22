package com.pipesor.recommendation.diagnosis.application.queryhandlers;

import com.pipesor.recommendation.diagnosis.application.model.Entitlement;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisEntitlements;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisReadModel;
import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisAccessException;
import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisAccessException.Outcome;
import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisProjection;
import com.pipesor.recommendation.diagnosis.application.queries.Projections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pipesor.recommendation.diagnosis.application.model.AdmittedDiagnosis;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code GET /api/v1/incidents/{incidentId}/diagnosis}: resolves the current entitlement with
 * Pipeline on every request, then selects the highest admitted version for the incident and its
 * failed ORIGIN execution. A newer version that does not bind fails the request; there is no
 * fallback to an older version.
 */
public final class IncidentDiagnosisQueryHandler {
    private static final Logger LOG = LoggerFactory.getLogger(IncidentDiagnosisQueryHandler.class);
    private final DiagnosisEntitlements entitlements;
    private final DiagnosisReadModel diagnoses;

    public IncidentDiagnosisQueryHandler(DiagnosisEntitlements entitlements, DiagnosisReadModel diagnoses) {
        this.entitlements = Objects.requireNonNull(entitlements);
        this.diagnoses = Objects.requireNonNull(diagnoses);
    }

    public DiagnosisProjection handle(UUID incidentId, String subjectToken, String correlationId) {
        Entitlement entitlement;
        try {
            entitlement = entitlements.resolve(incidentId, subjectToken, correlationId);
        } catch (DiagnosisEntitlements.Denied denied) {
            throw new DiagnosisAccessException(Outcome.INCIDENT_NOT_FOUND);
        } catch (DiagnosisEntitlements.Unavailable unavailable) {
            throw new DiagnosisAccessException(Outcome.AUTHORITY_UNAVAILABLE);
        }
        if (!incidentId.equals(entitlement.incidentId())) {
            LOG.warn("entitlement_incident_mismatch correlationId={}", correlationId);
            throw new DiagnosisAccessException(Outcome.AUTHORITY_UNAVAILABLE);
        }
        final Optional<AdmittedDiagnosis> selected;
        try {
            selected = diagnoses.highestAdmitted(incidentId, entitlement.executionId());
        } catch (RuntimeException failure) {
            LOG.warn("diagnosis_read_failed exception={} correlationId={}", failure.getClass().getName(), correlationId);
            throw new DiagnosisAccessException(Outcome.DIAGNOSIS_UNAVAILABLE);
        }
        var diagnosis = selected.orElseThrow(() -> new DiagnosisAccessException(Outcome.DIAGNOSIS_ABSENT));
        if (!Bindings.admittedFor(diagnosis, incidentId, entitlement.executionId())
                || !Bindings.covers(entitlement, diagnosis.scope())) {
            LOG.warn("diagnosis_stale diagnosisId={} version={} correlationId={}", diagnosis.diagnosisId(),
                    diagnosis.version(), correlationId);
            throw new DiagnosisAccessException(Outcome.DIAGNOSIS_STALE);
        }
        return Projections.of(diagnosis);
    }
}
