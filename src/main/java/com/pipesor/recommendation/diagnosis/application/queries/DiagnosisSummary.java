package com.pipesor.recommendation.diagnosis.application.queries;

import com.pipesor.recommendation.diagnosis.application.model.AdmittedDiagnosis;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal summary of the Workspace diagnosis that Pipeline projects into its incident queue
 * (ADR-027, MVP HTTP API v1 "Internal: diagnosis summaries"). Status, review revision and times
 * come from the same {@link Projections#of projection} the Workspace serves; {@code pipelineId} and
 * {@code githubRepositoryId} come from the diagnosis authorization scope. {@code probableCause} is
 * inferred model output, bounded for display.
 */
public record DiagnosisSummary(UUID incidentId, UUID diagnosisId, int version, UUID executionId, UUID pipelineId,
        String githubRepositoryId, String status, int reviewRevision, String reviewedAt, String probableCause,
        String createdAt) {
    /** Contract bound, in Unicode code points. */
    public static final int MAX_PROBABLE_CAUSE_CODE_POINTS = 1000;
    private static final Set<String> STATUSES = Set.of("PENDING", "CONFIRMED", "CORRECTED", "REJECTED");

    public DiagnosisSummary {
        Objects.requireNonNull(incidentId);
        Objects.requireNonNull(diagnosisId);
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(pipelineId);
        Objects.requireNonNull(githubRepositoryId);
        Objects.requireNonNull(createdAt);
        if (version < 1 || reviewRevision < 0) throw new IllegalArgumentException("Invalid version or review revision");
        if (!STATUSES.contains(status)
                || "PENDING".equals(status) != (reviewRevision == 0) || (reviewRevision == 0) != (reviewedAt == null)) {
            throw new IllegalArgumentException("PENDING exactly when there is no review");
        }
        if (probableCause != null && probableCause.codePointCount(0, probableCause.length()) > MAX_PROBABLE_CAUSE_CODE_POINTS) {
            throw new IllegalArgumentException("probableCause exceeds the contract bound");
        }
    }

    /** @throws NullPointerException when the scope has no pipeline id (Pipeline could not bind the summary) */
    public static DiagnosisSummary of(AdmittedDiagnosis diagnosis) {
        var projection = Projections.of(diagnosis);
        var reviews = projection.reviews();
        var last = reviews.isEmpty() ? null : reviews.getLast();
        return new DiagnosisSummary(projection.incidentId(), projection.diagnosisId(), projection.version(),
                projection.executionId(), diagnosis.scope().pipelineId(), diagnosis.scope().githubRepositoryId(),
                projection.status(), last == null ? 0 : last.revision(), last == null ? null : last.timestamp(),
                boundedCause(projection.hypothesis().probableCause()), projection.createdAt());
    }

    /** At most {@value #MAX_PROBABLE_CAUSE_CODE_POINTS} code points, never splitting a surrogate pair; null when blank. */
    static String boundedCause(String cause) {
        if (cause == null || cause.isBlank()) return null;
        if (cause.codePointCount(0, cause.length()) <= MAX_PROBABLE_CAUSE_CODE_POINTS) return cause;
        return cause.substring(0, cause.offsetByCodePoints(0, MAX_PROBABLE_CAUSE_CODE_POINTS));
    }
}
