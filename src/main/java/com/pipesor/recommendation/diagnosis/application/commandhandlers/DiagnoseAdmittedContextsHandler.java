package com.pipesor.recommendation.diagnosis.application.commandhandlers;

import com.pipesor.recommendation.diagnosis.application.ContextReconstruction;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator;
import com.pipesor.recommendation.diagnosis.application.ports.AdmittedContexts;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisJobs;
import com.pipesor.recommendation.diagnosis.review.PersistedDiagnosticWorkflow;
import com.pipesor.recommendation.diagnosis.review.ReviewStore;
import com.pipesor.recommendation.retrieval.application.ports.RagContractException;
import com.pipesor.recommendation.retrieval.application.ports.RagUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/**
 * Claims a bounded batch of admitted contexts without an admitted diagnosis and runs the persisted
 * workflow for each. The workflow's replay check returns an existing admission without calling RAG
 * or inference, so a job processed twice (expired lease, crash before completion) is harmless.
 */
public final class DiagnoseAdmittedContextsHandler {
    private static final Logger LOG = LoggerFactory.getLogger(DiagnoseAdmittedContextsHandler.class);

    public record Policy(int batchSize, Duration lease, Duration initialBackoff, Duration maxBackoff, int maxAttempts) {
        public Policy {
            if (batchSize < 1 || batchSize > 50) throw new IllegalArgumentException("batchSize must be 1..50");
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
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
    }

    private final DiagnosisJobs jobs;
    private final AdmittedContexts contexts;
    private final PersistedDiagnosticWorkflow workflow;
    private final Policy policy;

    public DiagnoseAdmittedContextsHandler(DiagnosisJobs jobs, AdmittedContexts contexts,
            PersistedDiagnosticWorkflow workflow, Policy policy) {
        this.jobs = Objects.requireNonNull(jobs);
        this.contexts = Objects.requireNonNull(contexts);
        this.workflow = Objects.requireNonNull(workflow);
        this.policy = Objects.requireNonNull(policy);
    }

    /** Processes at most one batch; returns the number of jobs claimed. */
    public int runOnce() {
        var claimed = jobs.claim(policy.batchSize(), policy.lease());
        for (var job : claimed) process(job);
        return claimed.size();
    }

    private void process(DiagnosisJobs.Job job) {
        String correlation = null;
        try {
            var context = contexts.find(job.contextId()).orElseThrow(
                    () -> new ContextReconstruction.InvalidContext("admitted context is missing"));
            correlation = context.correlationId();
            var input = ContextReconstruction.reconstruct(context);
            var admission = workflow.diagnose(input.contextId(), input.executionId(), input.incidentId(),
                    input.query(), input.evidence());
            jobs.complete(job);
            LOG.info("diagnosis_admitted contextId={} diagnosisId={} version={} attempt={} correlationId={}",
                    job.contextId(), admission.diagnosticId(), admission.version(), job.attempt(), correlation);
        } catch (ContextReconstruction.InvalidContext failure) {
            fail(job, "invalid_context", failure, correlation);
        } catch (ReviewStore.Conflict failure) {
            fail(job, "admission_conflict", failure, correlation);
        } catch (DiagnosticOrchestrator.InvalidHypothesis failure) {
            retry(job, "invalid_hypothesis", failure, correlation);
        } catch (RagContractException failure) {
            retry(job, "rag_rejected", failure, correlation);
        } catch (RagUnavailableException failure) {
            retry(job, "rag_unavailable", failure, correlation);
        } catch (SQLException failure) {
            retry(job, "storage_unavailable", failure, correlation);
        } catch (IllegalStateException failure) {
            retry(job, "inference_failed", failure, correlation);
        } catch (RuntimeException failure) {
            retry(job, "unexpected_failure", failure, correlation);
        }
    }

    private void retry(DiagnosisJobs.Job job, String reason, Exception failure, String correlation) {
        if (job.attempt() >= policy.maxAttempts()) {
            fail(job, reason, failure, correlation);
            return;
        }
        var delay = policy.backoff(job.attempt());
        LOG.warn("diagnosis_retry contextId={} attempt={} reason={} exception={} delaySeconds={} correlationId={}",
                job.contextId(), job.attempt(), reason, failure.getClass().getName(), delay.toSeconds(), correlation);
        settle(() -> jobs.retry(job, delay, reason), job);
    }

    private void fail(DiagnosisJobs.Job job, String reason, Exception failure, String correlation) {
        LOG.error("diagnosis_failed contextId={} attempt={} reason={} exception={} correlationId={}",
                job.contextId(), job.attempt(), reason, failure.getClass().getName(), correlation);
        settle(() -> jobs.fail(job, reason), job);
    }

    private static void settle(Runnable update, DiagnosisJobs.Job job) {
        try {
            update.run();
        } catch (RuntimeException failure) {
            // The lease expires and the job becomes claimable again.
            LOG.warn("diagnosis_job_update_failed contextId={} exception={}", job.contextId(), failure.getClass().getName());
        }
    }
}
