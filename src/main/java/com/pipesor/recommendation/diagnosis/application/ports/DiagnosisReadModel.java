package com.pipesor.recommendation.diagnosis.application.ports;

import com.pipesor.recommendation.diagnosis.application.model.AdmittedDiagnosis;

import java.util.Optional;
import java.util.UUID;

/** Admitted diagnosis versions; never returns diagnoses saved without an admission fingerprint. */
public interface DiagnosisReadModel {
    /** Highest admitted version whose context belongs to this incident and execution. */
    Optional<AdmittedDiagnosis> highestAdmitted(UUID incidentId, UUID executionId);

    /** Highest admitted version for an execution, whatever incident it was admitted under. */
    Optional<AdmittedDiagnosis> highestAdmittedForExecution(UUID executionId);
}
