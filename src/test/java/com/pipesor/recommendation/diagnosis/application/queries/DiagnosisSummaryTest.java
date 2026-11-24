package com.pipesor.recommendation.diagnosis.application.queries;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Diagnosis;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Hypothesis;
import com.pipesor.recommendation.diagnosis.application.model.AdmittedDiagnosis;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Summary fields follow the Workspace projection; the inferred cause is bounded in code points. */
class DiagnosisSummaryTest {
    private static final String EMOJI = "😀";

    @Test
    void boundsTheCauseInCodePointsWithoutSplittingSurrogatePairs() {
        assertThat(DiagnosisSummary.boundedCause(null)).isNull();
        assertThat(DiagnosisSummary.boundedCause(" \n\t")).isNull();
        assertThat(DiagnosisSummary.boundedCause("a".repeat(1000))).hasSize(1000);
        assertThat(DiagnosisSummary.boundedCause("a".repeat(1001))).isEqualTo("a".repeat(1000));
        assertThat(DiagnosisSummary.boundedCause(EMOJI.repeat(1000))).isEqualTo(EMOJI.repeat(1000));
        assertThat(DiagnosisSummary.boundedCause("a" + EMOJI.repeat(1000))).isEqualTo("a" + EMOJI.repeat(999));
    }

    @Test
    void statusRevisionAndTimeComeFromTheLastReview() {
        var scope = new AuthorizationScope(UUID.randomUUID(), "123", "825001", UUID.randomUUID(), "scope-1");
        var created = Instant.parse("2026-11-23T10:00:00Z");
        var pending = DiagnosisSummary.of(diagnosis(scope, created, List.of()));
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.reviewRevision()).isZero();
        assertThat(pending.reviewedAt()).isNull();
        assertThat(pending.createdAt()).isEqualTo("2026-11-23T10:00:00Z");
        assertThat(pending.pipelineId()).isEqualTo(scope.pipelineId());
        assertThat(pending.githubRepositoryId()).isEqualTo("825001");

        var reviewed = DiagnosisSummary.of(diagnosis(scope, created, List.of(
                new AdmittedDiagnosis.ReviewEntry(2, "CORRECTED", Instant.parse("2026-11-23T11:00:00.123456Z")),
                new AdmittedDiagnosis.ReviewEntry(1, "CONFIRMED", Instant.parse("2026-11-23T10:30:00Z")))));
        assertThat(reviewed.status()).isEqualTo("CORRECTED");
        assertThat(reviewed.reviewRevision()).isEqualTo(2);
        assertThat(reviewed.reviewedAt()).isEqualTo("2026-11-23T11:00:00.123456Z");
    }

    private static AdmittedDiagnosis diagnosis(AuthorizationScope scope, Instant created,
            List<AdmittedDiagnosis.ReviewEntry> reviews) {
        UUID context = UUID.randomUUID();
        UUID evidence = UUID.randomUUID();
        var output = new Diagnosis(context, UUID.randomUUID(), UUID.randomUUID(), "corr-1",
                new Hypothesis("Cause", "Check", "Synthetic", List.of(evidence), List.of()), List.of(), List.of());
        return new AdmittedDiagnosis(UUID.randomUUID(), 3, created, scope, output, null, reviews);
    }
}
