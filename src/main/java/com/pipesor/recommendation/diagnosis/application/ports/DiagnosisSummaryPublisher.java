package com.pipesor.recommendation.diagnosis.application.ports;

import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisSummary;

/** Delivers a diagnosis summary to Pipeline's incident read model (idempotent and monotonic on the receiver). */
public interface DiagnosisSummaryPublisher {
    Result publish(DiagnosisSummary summary, String correlationId);

    sealed interface Result { }

    /** Pipeline stored this summary or already holds the same or a later one ({@code applied} may be unknown). */
    record Acknowledged(Boolean applied) implements Result { }

    /** Pipeline refused the summary permanently ({@code 400} or {@code 409}); retrying cannot succeed. */
    record Rejected(int status) implements Result { }

    /** Any other outcome; retried with backoff. {@code reason} is a short code, {@code exception} a class name or null. */
    record Failed(String reason, String exception) implements Result { }
}
