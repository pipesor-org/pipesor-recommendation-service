package com.pipesor.recommendation.diagnosis.review;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Diagnosis;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Evidence;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Hypothesis;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import com.pipesor.recommendation.retrieval.domain.CorpusKind;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-027 outbox writes: same transaction as the admission or review, never on replays, backfilled once. */
class IncidentSummaryOutboxTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    private static final AuthorizationScope SCOPE = new AuthorizationScope(UUID.randomUUID(), "1", "2",
            UUID.randomUUID(), "fixture");
    private static PGSimpleDataSource source;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        source = dataSource(POSTGRES.getJdbcUrl());
        Flyway.configure().dataSource(source).defaultSchema("diagnosis").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void admissionEnqueuesInItsTransactionAndReplaysDoNot() throws Exception {
        UUID incident = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        var workflow = workflow();
        UUID firstContext = UUID.randomUUID();
        workflow.diagnose(firstContext, execution, incident, query(), evidence(firstContext));
        assertThat(row(incident, execution)).containsEntry("generation", 1L).containsEntry("attempts", 0)
                .containsEntry("last_failure", null).containsEntry("lease_until", null);

        // The existing admission is returned without a new row, both before and inside the transaction.
        workflow.diagnose(firstContext, execution, incident, query(), evidence(firstContext));
        assertThat(row(incident, execution)).containsEntry("generation", 1L);
        var store = new ReviewStore(source);
        UUID id = jdbc.queryForObject("SELECT id FROM diagnosis.diagnoses WHERE context_id = ?", UUID.class, firstContext);
        String fingerprint = jdbc.queryForObject("SELECT admission_fingerprint FROM diagnosis.diagnoses WHERE id = ?",
                String.class, id);
        store.admit(id, fingerprint, SCOPE, store.read(id).original());
        assertThat(row(incident, execution)).containsEntry("generation", 1L);

        // A newer version of the same incident and execution bumps the pending row: due now, attempts and
        // failure reset, the in-flight lease kept.
        jdbc.update("""
                UPDATE diagnosis.incident_summary_outbox SET attempts = 3, last_failure = 'pipeline_status_503',
                    next_attempt_at = clock_timestamp() + interval '1 hour', lease_until = clock_timestamp() + interval '1 minute'
                WHERE incident_id = ? AND execution_id = ?""", incident, execution);
        UUID secondContext = UUID.randomUUID();
        assertThat(workflow.diagnose(secondContext, execution, incident, query(), evidence(secondContext)).version())
                .isEqualTo(2);
        assertThat(row(incident, execution)).containsEntry("generation", 2L).containsEntry("attempts", 0)
                .containsEntry("last_failure", null).containsEntry("due", true).containsEntry("leased", true);
    }

    @Test
    void newReviewsEnqueueAndReplaysOrConflictsDoNot() throws Exception {
        var store = new ReviewStore(source);
        UUID id = UUID.randomUUID();
        var original = synthetic();
        store.save(id, 1, SCOPE, original);
        var snapshot = store.read(id);
        assertThat(row(original.incidentId(), original.executionId())).isNull();

        UUID key = UUID.randomUUID();
        var confirmed = new ReviewStore.Submission(1, 0, ReviewStore.Decision.CONFIRMED, null, null, null);
        store.append(snapshot, key, "reviewer-1", confirmed);
        assertThat(row(original.incidentId(), original.executionId())).containsEntry("generation", 1L);

        jdbc.update("DELETE FROM diagnosis.incident_summary_outbox WHERE incident_id = ?", original.incidentId());
        store.append(snapshot, key, "reviewer-1", confirmed);
        assertThatThrownBy(() -> store.append(snapshot, UUID.randomUUID(), "reviewer-1", confirmed))
                .isInstanceOf(ReviewStore.Conflict.class);
        assertThat(row(original.incidentId(), original.executionId())).isNull();

        store.append(snapshot, UUID.randomUUID(), "reviewer-2",
                new ReviewStore.Submission(1, 1, ReviewStore.Decision.REJECTED, "Not supported", null, null));
        assertThat(row(original.incidentId(), original.executionId())).containsEntry("generation", 1L);
        store.append(snapshot, UUID.randomUUID(), "reviewer-2",
                new ReviewStore.Submission(1, 2, ReviewStore.Decision.CONFIRMED, null, null, null));
        assertThat(row(original.incidentId(), original.executionId())).containsEntry("generation", 2L);
    }

    @Test
    void failedCommitLeavesNeitherTheChangeNorItsOutboxRow() throws Exception {
        UUID incident = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        failCommitsFor(incident);
        try {
            UUID context = UUID.randomUUID();
            assertThatThrownBy(() -> workflow().diagnose(context, execution, incident, query(), evidence(context)))
                    .isInstanceOf(SQLException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis.diagnoses WHERE incident_id = ?",
                    Integer.class, incident)).isZero();
            assertThat(row(incident, execution)).isNull();

            var store = new ReviewStore(source);
            UUID id = UUID.randomUUID();
            var original = new Diagnosis(context, execution, incident, "p3-synthetic", synthetic().hypothesis(),
                    synthetic().evidence(), List.of());
            store.save(id, 1, SCOPE, original);
            assertThatThrownBy(() -> store.append(store.read(id), UUID.randomUUID(), "reviewer-1",
                    new ReviewStore.Submission(1, 0, ReviewStore.Decision.CONFIRMED, null, null, null)))
                    .isInstanceOf(SQLException.class);
            assertThat(store.read(id).history()).isEmpty();
            assertThat(row(incident, execution)).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER fail_commit ON diagnosis.incident_summary_outbox");
            jdbc.execute("DROP FUNCTION diagnosis.fail_commit()");
        }
    }

    @Test
    void migrationBackfillsEachAdmittedIncidentExecutionOnce() {
        jdbc.execute("CREATE DATABASE backfill");
        var backfill = dataSource(POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/backfill$1"));
        Flyway.configure().dataSource(backfill).defaultSchema("diagnosis").target("8").load().migrate();
        var before = new JdbcTemplate(backfill);
        UUID incident = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID unadmitted = UUID.randomUUID();
        insertDiagnosis(before, incident, first, 1, true);
        insertDiagnosis(before, incident, first, 2, true);
        insertDiagnosis(before, incident, second, 3, true);
        insertDiagnosis(before, unadmitted, UUID.randomUUID(), 1, false);

        Flyway.configure().dataSource(backfill).defaultSchema("diagnosis").load().migrate();
        assertThat(before.queryForList("""
                SELECT incident_id, execution_id, generation, attempts, lease_until IS NULL AS free,
                       next_attempt_at <= clock_timestamp() AS due
                FROM diagnosis.incident_summary_outbox ORDER BY execution_id""")).containsExactlyInAnyOrder(
                Map.of("incident_id", incident, "execution_id", first, "generation", 1L, "attempts", 0, "free", true,
                        "due", true),
                Map.of("incident_id", incident, "execution_id", second, "generation", 1L, "attempts", 0, "free", true,
                        "due", true));
    }

    private static void insertDiagnosis(JdbcTemplate target, UUID incident, UUID execution, int version, boolean admitted) {
        UUID context = UUID.randomUUID();
        target.update("""
                INSERT INTO diagnosis.diagnoses (id, incident_id, version, correlation_id, authorization_scope, output,
                    admission_fingerprint, context_id, execution_id)
                VALUES (?, ?, ?, 'backfill', '{}'::jsonb, ?::jsonb, ?, ?, ?)""", UUID.randomUUID(), incident, version,
                "{\"contextId\":\"" + context + "\",\"executionId\":\"" + execution + "\"}",
                admitted ? "f".repeat(64) : null, context, execution);
    }

    /** A deferred trigger fails the commit after the outbox write for one incident. */
    private static void failCommitsFor(UUID incident) {
        jdbc.execute("""
                CREATE FUNCTION diagnosis.fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected commit failure'; END; $$""");
        jdbc.execute("CREATE CONSTRAINT TRIGGER fail_commit AFTER INSERT OR UPDATE ON diagnosis.incident_summary_outbox "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.incident_id = '" + incident + "'::uuid) "
                + "EXECUTE FUNCTION diagnosis.fail_commit()");
    }

    private static Map<String, Object> row(UUID incident, UUID execution) {
        var rows = jdbc.queryForList("""
                SELECT generation, attempts, last_failure, lease_until, lease_until IS NOT NULL AS leased,
                       next_attempt_at <= clock_timestamp() AS due
                FROM diagnosis.incident_summary_outbox WHERE incident_id = ? AND execution_id = ?""", incident, execution);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static PersistedDiagnosticWorkflow workflow() {
        return new PersistedDiagnosticWorkflow(new DiagnosticOrchestrator(
                request -> new RetrievalResult.NoMatch(request.correlationId()),
                (signals, knowledge) -> new Hypothesis("Cause", "Inspect the build log.", "Only fixture input.",
                        List.of(signals.getFirst().evidenceId()), List.of())), new ReviewStore(source));
    }

    private static RetrievalQuery query() {
        return new RetrievalQuery("outbox-corr", "content", SCOPE, List.of(CorpusKind.AUTHORIZED_TECHNICAL_DOCUMENTATION), 3);
    }

    private static List<Evidence> evidence(UUID context) {
        return List.of(new Evidence(UUID.nameUUIDFromBytes(context.toString().getBytes()), context, "BUILD",
                "Synthetic build step exited with 1."));
    }

    private static Diagnosis synthetic() {
        UUID context = UUID.randomUUID();
        var signal = evidence(context).getFirst();
        return new Diagnosis(context, UUID.randomUUID(), UUID.randomUUID(), "p3-synthetic",
                new Hypothesis("Cause", "Inspect the build log.", "Only fixture input.", List.of(signal.evidenceId()),
                        List.of()), List.of(signal), List.of());
    }

    private static PGSimpleDataSource dataSource(String url) {
        var created = new PGSimpleDataSource();
        created.setURL(url);
        created.setUser(POSTGRES.getUsername());
        created.setPassword(POSTGRES.getPassword());
        return created;
    }
}
