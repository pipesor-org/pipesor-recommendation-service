package com.pipesor.recommendation.diagnosis.application.ports;

import com.pipesor.recommendation.diagnosis.application.model.Entitlement;

import java.util.UUID;

/** Resolves the session subject's current entitlement for an incident with its owner (Pipeline). */
public interface DiagnosisEntitlements {
    /**
     * @throws Denied when the incident does not exist or the subject has no access (indistinguishable)
     * @throws Unavailable when the authority cannot give a definite answer
     */
    Entitlement resolve(UUID incidentId, String subjectToken, String correlationId);

    final class Denied extends RuntimeException {
        public Denied() { super("Entitlement denied", null, false, false); }
    }

    final class Unavailable extends RuntimeException {
        public Unavailable() { super("Entitlement authority unavailable", null, false, false); }
    }
}
