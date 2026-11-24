package com.pipesor.recommendation.diagnosis.application.commandhandlers;

import com.pipesor.recommendation.diagnosis.application.model.AdmittedDiagnosis;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisReadModel;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisSummaryOutbox;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisSummaryOutbox.Claim;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisSummaryPublisher;
import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisSummary;
import com.pipesor.recommendation.diagnosis.application.queryhandlers.Bindings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Drains the incident summary outbox (ADR-027): for each claimed (incident, execution, generation)
 * it reads the current state with the Workspace selection (highest admitted version, status from
 * the last review) and pushes it to Pipeline. A generation is removed when Pipeline acknowledged it
 * or refused it permanently, or when there is nothing bindable to project; every other outcome is
 * retried with capped exponential backoff, indefinitely, so the projection converges after outages.
 */
public final class PublishDiagnosisSummariesHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PublishDiagnosisSummariesHandler.class);

    public record Policy(int batchSize, Duration lease, Duration initialBackoff, Duration maxBackoff) {
        public Policy {
            if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("batchSize must be 1..100");
            Objects.requireNonNull(lease);
            Objects.requireNonNull(initialBackoff);
            Objects.requireNonNull(maxBackoff);
            if (lease.isNegative() || lease.isZero() || initialBackoff.isNegative() || initialBackoff.isZero()
                    || maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("Lease and backoff must be positive and ordered");
            }
        }

        Duration backoff(int attempt) {
            int doublings = Math.min(Math.max(attempt - 1, 0), 30);
            var delay = initialBackoff.multipliedBy(1L << doublings);
            return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
        }

        /** Pushes start only in the first half of the lease, so each one ends before its claim expires. */
        Duration pushWindow() {
            return lease.dividedBy(2);
        }
    }

    private final DiagnosisSummaryOutbox outbox;
    private final DiagnosisReadModel diagnoses;
    private final DiagnosisSummaryPublisher publisher;
    private final Policy policy;
    private final Clock clock;

    public PublishDiagnosisSummariesHandler(DiagnosisSummaryOutbox outbox, DiagnosisReadModel diagnoses,
            DiagnosisSummaryPublisher publisher, Policy policy, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox);
        this.diagnoses = Objects.requireNonNull(diagnoses);
        this.publisher = Objects.requireNonNull(publisher);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Processes at most one batch; returns the number of rows claimed. */
    public int runOnce() {
        var stopAt = clock.instant().plus(policy.pushWindow());
        List<Claim> claimed = outbox.claim(policy.batchSize(), policy.lease());
        for (int index = 0; index < claimed.size(); index++) {
            if (clock.instant().isAfter(stopAt) || Thread.currentThread().isInterrupted()) {
                // Hand the rest back untouched instead of pushing under an expiring lease.
                LOG.info("diagnosis_summary_batch_released remaining={}", claimed.size() - index);
                for (var rest : claimed.subList(index, claimed.size())) settle(() -> outbox.release(rest), rest);
                break;
            }
            process(claimed.get(index));
        }
        return claimed.size();
    }

    private void process(Claim claim) {
        final Optional<AdmittedDiagnosis> current;
        try {
            current = diagnoses.highestAdmitted(claim.incidentId(), claim.executionId());
        } catch (RuntimeException failure) {
            retry(claim, "storage_unavailable", failure.getClass().getName(), null);
            return;
        }
        if (current.isEmpty()) {
            skip(claim, "diagnosis_absent");
            return;
        }
        var diagnosis = current.get();
        if (!Bindings.admittedFor(diagnosis, claim.incidentId(), claim.executionId())) {
            skip(claim, "binding_mismatch");
            return;
        }
        if (diagnosis.scope().pipelineId() == null) {
            skip(claim, "pipeline_scope_missing");
            return;
        }
        String correlation = diagnosis.output().correlationId();
        final DiagnosisSummary summary;
        final DiagnosisSummaryPublisher.Result result;
        try {
            summary = DiagnosisSummary.of(diagnosis);
            result = publisher.publish(summary, correlation);
        } catch (RuntimeException failure) {
            retry(claim, "unexpected_failure", failure.getClass().getName(), correlation);
            return;
        }
        switch (result) {
            case DiagnosisSummaryPublisher.Acknowledged acknowledged -> {
                LOG.info("diagnosis_summary_published incidentId={} executionId={} diagnosisId={} version={} "
                        + "reviewRevision={} status={} applied={} generation={} correlationId={}", claim.incidentId(),
                        claim.executionId(), summary.diagnosisId(), summary.version(), summary.reviewRevision(),
                        summary.status(), acknowledged.applied(), claim.generation(), correlation);
                settle(() -> outbox.complete(claim), claim);
            }
            case DiagnosisSummaryPublisher.Rejected rejected -> {
                LOG.warn("diagnosis_summary_rejected status={} incidentId={} executionId={} diagnosisId={} version={} "
                        + "generation={} correlationId={}", rejected.status(), claim.incidentId(), claim.executionId(),
                        summary.diagnosisId(), summary.version(), claim.generation(), correlation);
                settle(() -> outbox.complete(claim), claim);
            }
            case DiagnosisSummaryPublisher.Failed failed -> retry(claim, failed.reason(), failed.exception(), correlation);
        }
    }

    private void skip(Claim claim, String reason) {
        LOG.warn("diagnosis_summary_skipped reason={} incidentId={} executionId={} generation={}", reason,
                claim.incidentId(), claim.executionId(), claim.generation());
        settle(() -> outbox.complete(claim), claim);
    }

    private void retry(Claim claim, String reason, String exception, String correlation) {
        int attempt = claim.attempts() + 1;
        var delay = policy.backoff(attempt);
        // Never given up: once the backoff is capped every further attempt is logged at ERROR.
        String message = "diagnosis_summary_retry incidentId={} executionId={} attempt={} reason={} exception={} "
                + "delaySeconds={} correlationId={}";
        Object[] fields = {claim.incidentId(), claim.executionId(), attempt, reason, exception, delay.toSeconds(),
                correlation};
        if (delay.compareTo(policy.maxBackoff()) >= 0) LOG.error(message, fields);
        else LOG.warn(message, fields);
        settle(() -> outbox.retry(claim, delay, reason), claim);
    }

    private static void settle(Runnable update, Claim claim) {
        try {
            update.run();
        } catch (RuntimeException failure) {
            // The lease expires and the row becomes claimable again.
            LOG.warn("diagnosis_summary_outbox_update_failed incidentId={} executionId={} exception={}",
                    claim.incidentId(), claim.executionId(), failure.getClass().getName());
        }
    }
}
