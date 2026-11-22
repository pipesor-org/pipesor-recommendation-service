package com.pipesor.recommendation.diagnosis.application.queryhandlers;

import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisReadModel;
import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisAccessException;
import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisAccessException.Outcome;
import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisProjection;
import com.pipesor.recommendation.diagnosis.application.queries.Projections;
import com.pipesor.recommendation.diagnosis.domain.ExecutionIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pipesor.recommendation.diagnosis.application.model.AdmittedDiagnosis;
import java.util.Objects;
import java.util.Optional;

/**
 * GitHub Actions report: the OIDC token's repository must equal the path repository; the run and
 * attempt map to Pipeline's deterministic execution id, whose highest admitted version is returned.
 */
public final class GitHubActionsReportQueryHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubActionsReportQueryHandler.class);
    private final DiagnosisReadModel diagnoses;

    public record Report(DiagnosisProjection report, String markdown) { }

    public GitHubActionsReportQueryHandler(DiagnosisReadModel diagnoses) {
        this.diagnoses = Objects.requireNonNull(diagnoses);
    }

    /** @throws IllegalArgumentException when the path identifiers are not canonical */
    public Report handle(String tokenRepositoryId, String repositoryId, String runId, String attempt) {
        var executionId = ExecutionIds.githubExecution(repositoryId, runId, attempt);
        if (!repositoryId.equals(tokenRepositoryId)) throw new DiagnosisAccessException(Outcome.REPOSITORY_MISMATCH);
        final Optional<AdmittedDiagnosis> selected;
        try {
            selected = diagnoses.highestAdmittedForExecution(executionId);
        } catch (RuntimeException failure) {
            LOG.warn("report_read_failed exception={}", failure.getClass().getName());
            throw new DiagnosisAccessException(Outcome.DIAGNOSIS_UNAVAILABLE);
        }
        var diagnosis = selected.orElseThrow(() -> new DiagnosisAccessException(Outcome.DIAGNOSIS_ABSENT));
        if (!Bindings.admittedFor(diagnosis, diagnosis.output().incidentId(), executionId)
                || !repositoryId.equals(diagnosis.scope().githubRepositoryId())) {
            LOG.warn("report_stale diagnosisId={} version={}", diagnosis.diagnosisId(), diagnosis.version());
            throw new DiagnosisAccessException(Outcome.DIAGNOSIS_STALE);
        }
        var projection = Projections.of(diagnosis).withoutReviews();
        return new Report(projection, ReportMarkdown.render(projection));
    }
}
