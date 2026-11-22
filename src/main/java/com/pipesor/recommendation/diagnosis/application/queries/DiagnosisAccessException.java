package com.pipesor.recommendation.diagnosis.application.queries;

/** Public outcomes of diagnosis and review access (MVP HTTP API v1); carries no internal detail. */
public final class DiagnosisAccessException extends RuntimeException {
    public enum Outcome {
        /** Incident unknown or not accessible to the subject (indistinguishable). */
        INCIDENT_NOT_FOUND,
        /** Diagnosis unknown or not accessible to the subject (indistinguishable). */
        DIAGNOSIS_NOT_FOUND,
        /** Accessible, but the operation needs a capability the subject lacks. */
        FORBIDDEN,
        DIAGNOSIS_ABSENT,
        DIAGNOSIS_STALE,
        AUTHORITY_UNAVAILABLE,
        DIAGNOSIS_UNAVAILABLE,
        REPOSITORY_MISMATCH
    }

    private final Outcome outcome;

    public DiagnosisAccessException(Outcome outcome) {
        super(outcome.name(), null, false, false);
        this.outcome = outcome;
    }

    public Outcome outcome() { return outcome; }
}
