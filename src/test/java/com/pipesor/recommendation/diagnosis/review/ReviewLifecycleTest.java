package com.pipesor.recommendation.diagnosis.review;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Diagnosis;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Evidence;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Hypothesis;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import com.pipesor.recommendation.retrieval.domain.CorpusKind;
import com.pipesor.recommendation.retrieval.domain.KnowledgeMatch;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Admission and append-only review history on PostgreSQL (HTTP authorization is covered by the API tests). */
class ReviewLifecycleTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    private static final AuthorizationScope SCOPE = new AuthorizationScope(UUID.randomUUID(), "1", "2",
            UUID.randomUUID(), "fixture");
    private static PGSimpleDataSource source;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).defaultSchema("diagnosis").load().migrate();
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void admitsImmutableVersionsReplaysWithoutProvidersAndRejectsConflicts() throws Exception {
        var store = new ReviewStore(source);
        Diagnosis e05;
        try (var input = getClass().getResourceAsStream("/review/e05-diagnosis.json")) {
            e05 = JSON.readValue(input, Diagnosis.class);
        }
        var retrieved = e05.sources().getFirst();
        var kind = CorpusKind.AUTHORIZED_TECHNICAL_DOCUMENTATION;
        var query = new RetrievalQuery(e05.correlationId(), e05.evidence().getFirst().content(), SCOPE, List.of(kind), 1);
        var workflow = new PersistedDiagnosticWorkflow(new DiagnosticOrchestrator(
                request -> new RetrievalResult.Matches(request.correlationId(), List.of(new KnowledgeMatch(
                        retrieved.chunkId(), retrieved.documentId(), kind, "Replay guide", "Replay knowledge",
                        retrieved.provenance(), 1))),
                (signals, knowledge) -> e05.hypothesis()), store);
        var admitted = workflow.diagnose(e05.contextId(), e05.executionId(), e05.incidentId(), query, e05.evidence());
        assertThat(admitted.version()).isEqualTo(1);
        assertThat(admitted.output()).isEqualTo(e05);

        var replay = new PersistedDiagnosticWorkflow(new DiagnosticOrchestrator(
                request -> { throw new AssertionError("Replay called retrieval"); },
                (signals, knowledge) -> { throw new AssertionError("Replay called inference"); }), new ReviewStore(source));
        assertThat(replay.diagnose(e05.contextId(), e05.executionId(), e05.incidentId(), query, e05.evidence()))
                .isEqualTo(admitted);
        assertThatThrownBy(() -> workflow.diagnose(e05.contextId(), UUID.randomUUID(), e05.incidentId(), query,
                e05.evidence())).isInstanceOf(ReviewStore.Conflict.class);

        var invalid = new PersistedDiagnosticWorkflow(new DiagnosticOrchestrator(
                request -> new RetrievalResult.NoMatch(request.correlationId()),
                (signals, knowledge) -> e05.hypothesis()), store);
        assertThatThrownBy(() -> invalid.diagnose(UUID.randomUUID(), e05.executionId(), e05.incidentId(), query,
                e05.evidence())).isInstanceOf(DiagnosticOrchestrator.InvalidHypothesis.class);

        try (var workers = Executors.newFixedThreadPool(2)) {
            UUID incident = UUID.randomUUID();
            var first = workers.submit(() -> workflow.diagnose(UUID.randomUUID(), e05.executionId(), incident, query,
                    e05.evidence()));
            var second = workers.submit(() -> workflow.diagnose(UUID.randomUUID(), e05.executionId(), incident, query,
                    e05.evidence()));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS).version(), second.get(10, TimeUnit.SECONDS).version()))
                    .containsExactlyInAnyOrder(1, 2);
        }
        var next = workflow.diagnose(UUID.randomUUID(), e05.executionId(), e05.incidentId(), query, e05.evidence());
        assertThat(next.version()).isEqualTo(2);
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(
                "SELECT context_id, execution_id FROM diagnosis.diagnoses WHERE id = ?")) {
            statement.setObject(1, next.diagnosticId());
            try (var row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getObject(1, UUID.class)).isEqualTo(next.output().contextId());
                assertThat(row.getObject(2, UUID.class)).isEqualTo(e05.executionId());
            }
        }
    }

    @Test
    void appendsIdempotentReviewsWithOptimisticConcurrency() throws Exception {
        var store = new ReviewStore(source);
        UUID id = UUID.randomUUID();
        store.save(id, 1, SCOPE, synthetic());
        var snapshot = store.read(id);
        assertThat(snapshot.history()).isEmpty();

        var key = UUID.randomUUID();
        var confirmed = submission(ReviewStore.Decision.CONFIRMED, 0);
        var first = store.append(snapshot, key, "reviewer-1", confirmed);
        assertThat(first.history()).singleElement().satisfies(review -> {
            assertThat(review.reviewer()).isEqualTo("reviewer-1");
            assertThat(review.correlationId()).isEqualTo("p3-synthetic");
        });
        assertThat(store.append(snapshot, key, "reviewer-1", confirmed)).isEqualTo(first);
        assertThatThrownBy(() -> store.append(snapshot, key, "reviewer-1", submission(ReviewStore.Decision.REJECTED, 0)))
                .isInstanceOf(ReviewStore.Conflict.class);
        assertThatThrownBy(() -> store.append(snapshot, UUID.randomUUID(), "reviewer-1", confirmed))
                .isInstanceOf(ReviewStore.Conflict.class);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> attempt(store, snapshot));
            var b = workers.submit(() -> attempt(store, snapshot));
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        var corrected = new ReviewStore.Submission(1, 2, ReviewStore.Decision.CORRECTED, "Line does not establish cause.",
                "Header missing; upstream cause unknown.", null);
        assertThat(store.append(snapshot, UUID.randomUUID(), "reviewer-2", corrected).history()).hasSize(3);
        assertThat(store.read(id).original()).isEqualTo(synthetic());
    }

    private static boolean attempt(ReviewStore store, ReviewStore.Snapshot snapshot) throws Exception {
        try {
            store.append(snapshot, UUID.randomUUID(), "reviewer-1", submission(ReviewStore.Decision.CONFIRMED, 1));
            return true;
        } catch (ReviewStore.Conflict conflict) {
            return false;
        }
    }

    private static ReviewStore.Submission submission(ReviewStore.Decision decision, int revision) {
        return new ReviewStore.Submission(1, revision, decision, "Reviewed fixture evidence", null, null);
    }

    private static final UUID CONTEXT = UUID.randomUUID();
    private static final UUID EVIDENCE = UUID.randomUUID();

    private static Diagnosis synthetic() {
        var signal = new Evidence(EVIDENCE, CONTEXT, "BUILD", "Synthetic build step exited with 1.",
                "v2:job:701:member:0_verify.txt:lines:2-2:sha256:" + "a".repeat(64));
        return new Diagnosis(CONTEXT, CONTEXT, CONTEXT, "p3-synthetic",
                new Hypothesis("Cause", "Inspect the build log.", "Only fixture input.", List.of(EVIDENCE), List.of()),
                List.of(signal), List.of());
    }
}
