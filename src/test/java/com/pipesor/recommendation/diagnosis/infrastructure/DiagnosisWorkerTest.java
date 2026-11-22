package com.pipesor.recommendation.diagnosis.infrastructure;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Hypothesis;
import com.pipesor.recommendation.diagnosis.application.commandhandlers.DiagnoseAdmittedContextsHandler;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisJobs;
import com.pipesor.recommendation.diagnosis.infrastructure.persistence.JdbcAdmittedContexts;
import com.pipesor.recommendation.diagnosis.infrastructure.persistence.JdbcDiagnosisJobs;
import com.pipesor.recommendation.diagnosis.review.PersistedDiagnosticWorkflow;
import com.pipesor.recommendation.diagnosis.review.ReviewStore;
import com.pipesor.recommendation.receipt.ReceiptStore;
import com.pipesor.recommendation.receipt.ReceiptSubmission;
import com.pipesor.recommendation.retrieval.application.ports.RagKnowledgeRetriever;
import com.pipesor.recommendation.retrieval.application.ports.RagUnavailableException;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Durable worker on PostgreSQL: claim, replay guarantee, backoff, terminal failure and replica safety. */
class DiagnosisWorkerTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    private static PGSimpleDataSource source;
    private static JdbcTemplate jdbc;
    private final AtomicInteger retrievals = new AtomicInteger();
    private final AtomicInteger inferences = new AtomicInteger();
    private final AtomicReference<RetrievalQuery> lastQuery = new AtomicReference<>();
    private final AtomicReference<List<UUID>> inferredEvidence = new AtomicReference<>();

    @BeforeAll
    static void start() {
        POSTGRES.start();
        source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).defaultSchema("diagnosis").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @BeforeEach
    void settleEarlierJobs() {
        jdbc.update("UPDATE diagnosis.diagnosis_jobs SET state = 'DONE', lease_until = NULL WHERE state = 'PENDING'");
    }

    @Test
    void diagnosesAdmittedReceiptOnceAndReplaysWithoutProviders() {
        var receipt = receipt(1);
        new ReceiptStore(source).accept(receipt);
        assertThat(handler(working(), policy(4, 8)).runOnce()).isEqualTo(1);
        assertThat(retrievals).hasValue(1);
        assertThat(inferences).hasValue(1);
        assertThat(job(receipt.operationId())).containsEntry("state", "DONE").containsEntry("attempts", 1);
        var diagnosis = jdbc.queryForMap("""
                SELECT version, execution_id, admission_fingerprint IS NOT NULL AS admitted
                FROM diagnosis.diagnoses WHERE context_id = ?""", receipt.operationId());
        assertThat(diagnosis).containsEntry("version", 1).containsEntry("admitted", true)
                .containsEntry("execution_id", receipt.executionId());
        assertThat(lastQuery.get().query()).isEqualTo("content 0");

        // A job processed again (expired lease, crash before settlement) replays the admission only.
        jdbc.update("UPDATE diagnosis.diagnosis_jobs SET state = 'PENDING' WHERE context_id = ?", receipt.operationId());
        var replayOnly = new DiagnosticOrchestrator(query -> { throw new AssertionError("replay called RAG"); },
                (signals, knowledge) -> { throw new AssertionError("replay called inference"); });
        assertThat(handler(replayOnly, policy(4, 8)).runOnce()).isEqualTo(1);
        assertThat(job(receipt.operationId())).containsEntry("state", "DONE").containsEntry("attempts", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis.diagnoses WHERE context_id = ?", Integer.class,
                receipt.operationId())).isEqualTo(1);
    }

    @Test
    void diagnosesMultiSignalReceiptWithEveryEvidence() {
        var receipt = receipt(3);
        new ReceiptStore(source).accept(receipt);
        assertThat(handler(working(), policy(4, 8)).runOnce()).isEqualTo(1);
        assertThat(lastQuery.get().topK()).isEqualTo(3);
        assertThat(lastQuery.get().query()).isEqualTo("content 0\n\ncontent 1\n\ncontent 2");
        assertThat(inferredEvidence.get()).containsExactlyElementsOf(receipt.evidenceIds());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis.diagnoses WHERE context_id = ? "
                + "AND admission_fingerprint IS NOT NULL", Integer.class, receipt.operationId())).isEqualTo(1);
    }

    @Test
    void failedAttemptBacksOffAndTerminalFailureStops() {
        var receipt = receipt(1);
        new ReceiptStore(source).accept(receipt);
        var failing = new DiagnosticOrchestrator(query -> { throw new RagUnavailableException("fixture", null); },
                (signals, knowledge) -> { throw new AssertionError("inference after failed retrieval"); });
        assertThat(handler(failing, policy(4, 2)).runOnce()).isEqualTo(1);
        var job = job(receipt.operationId());
        assertThat(job).containsEntry("state", "PENDING").containsEntry("attempts", 1)
                .containsEntry("last_failure", "rag_unavailable");
        assertThat(job.get("lease_until")).isNull();
        assertThat((Boolean) jdbc.queryForObject("SELECT next_attempt_at > clock_timestamp() + interval '20 seconds' "
                + "FROM diagnosis.diagnosis_jobs WHERE context_id = ?", Boolean.class, receipt.operationId())).isTrue();
        assertThat(handler(failing, policy(4, 2)).runOnce()).isZero();

        jdbc.update("UPDATE diagnosis.diagnosis_jobs SET next_attempt_at = clock_timestamp() WHERE context_id = ?",
                receipt.operationId());
        assertThat(handler(failing, policy(4, 2)).runOnce()).isEqualTo(1);
        assertThat(job(receipt.operationId())).containsEntry("state", "FAILED").containsEntry("attempts", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis.diagnoses WHERE context_id = ?", Integer.class,
                receipt.operationId())).isZero();
    }

    @Test
    void concurrentReplicasClaimDisjointJobsAndExpiredLeasesAreFenced() throws Exception {
        var ids = new HashSet<UUID>();
        for (int i = 0; i < 6; i++) {
            var receipt = receipt(1);
            new ReceiptStore(source).accept(receipt);
            ids.add(receipt.operationId());
        }
        var start = new CountDownLatch(1);
        List<DiagnosisJobs.Job> claimed = new ArrayList<>();
        try (var replicas = Executors.newFixedThreadPool(3)) {
            var futures = new ArrayList<java.util.concurrent.Future<List<DiagnosisJobs.Job>>>();
            for (int i = 0; i < 3; i++) futures.add(replicas.submit(() -> {
                start.await();
                return new JdbcDiagnosisJobs(jdbc).claim(4, Duration.ofMinutes(5));
            }));
            start.countDown();
            for (var future : futures) claimed.addAll(future.get(10, TimeUnit.SECONDS));
        }
        assertThat(claimed).extracting(DiagnosisJobs.Job::contextId).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(ids);
        assertThat(new JdbcDiagnosisJobs(jdbc).claim(10, Duration.ofMinutes(5))).isEmpty();

        // Simulate a crashed worker: its lease expires and another replica claims the job again.
        var stale = claimed.getFirst();
        jdbc.update("UPDATE diagnosis.diagnosis_jobs SET lease_until = clock_timestamp() - interval '1 second' "
                + "WHERE context_id = ?", stale.contextId());
        var reclaimed = new JdbcDiagnosisJobs(jdbc).claim(10, Duration.ofMinutes(5));
        assertThat(reclaimed).containsExactly(new DiagnosisJobs.Job(stale.contextId(), 2));
        new JdbcDiagnosisJobs(jdbc).complete(stale);
        assertThat(job(stale.contextId())).containsEntry("state", "PENDING");
        new JdbcDiagnosisJobs(jdbc).complete(reclaimed.getFirst());
        assertThat(job(stale.contextId())).containsEntry("state", "DONE");
    }

    private DiagnoseAdmittedContextsHandler handler(DiagnosticOrchestrator orchestrator,
            DiagnoseAdmittedContextsHandler.Policy policy) {
        return new DiagnoseAdmittedContextsHandler(new JdbcDiagnosisJobs(jdbc), new JdbcAdmittedContexts(jdbc),
                new PersistedDiagnosticWorkflow(orchestrator, new ReviewStore(source)), policy);
    }

    private static DiagnoseAdmittedContextsHandler.Policy policy(int batch, int maxAttempts) {
        return new DiagnoseAdmittedContextsHandler.Policy(batch, Duration.ofMinutes(5), Duration.ofSeconds(30),
                Duration.ofMinutes(30), maxAttempts);
    }

    private DiagnosticOrchestrator working() {
        RagKnowledgeRetriever rag = query -> {
            retrievals.incrementAndGet();
            lastQuery.set(query);
            return new RetrievalResult.NoMatch(query.correlationId());
        };
        return new DiagnosticOrchestrator(rag, (signals, knowledge) -> {
            inferences.incrementAndGet();
            inferredEvidence.set(signals.stream().map(DiagnosticOrchestrator.Evidence::evidenceId).toList());
            return new Hypothesis("Probable cause", "Manual check", "Synthetic only",
                    List.of(signals.getLast().evidenceId()), List.of());
        });
    }

    private static Map<String, Object> job(UUID context) {
        return jdbc.queryForMap("SELECT state, attempts, last_failure, lease_until FROM diagnosis.diagnosis_jobs "
                + "WHERE context_id = ?", context);
    }

    static ReceiptSubmission receipt(int signals) {
        var context = UUID.randomUUID();
        var evidence = new ArrayList<UUID>();
        var json = new StringBuilder("[");
        for (int i = 0; i < signals; i++) {
            var id = UUID.randomUUID();
            evidence.add(id);
            var origin = i == signals - 1 ? context : UUID.randomUUID();
            if (i > 0) json.append(',');
            json.append("{\"evidence_id\":\"").append(id).append("\",\"origin_context_id\":\"").append(origin)
                    .append("\",\"correlation_id\":\"worker-corr\",\"stage\":\"BUILD\",\"summary\":\"summary ")
                    .append(i).append("\",\"content\":\"content ").append(i).append("\"}");
        }
        json.append(']');
        return new ReceiptSubmission(context, UUID.randomUUID(), "123", "456", UUID.randomUUID(), "scope-1",
                UUID.randomUUID(), UUID.randomUUID(), evidence, "summary", "worker-corr",
                Instant.parse("2026-11-20T10:00:00.000001Z"), json.toString(), "v1:sha256:" + "d".repeat(64));
    }
}
